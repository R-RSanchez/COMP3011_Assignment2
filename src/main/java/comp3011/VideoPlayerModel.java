/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Raúl Ruiz Sánchez, a3229619
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns media decoding, playback timing, seeking, audio scheduling and the
 * concurrent frame-processing pipeline.
 * </p>
 *
 * <p>
 * FFmpeg decoding and JavaFX interaction remain on the JavaFX application
 * thread. CPU-heavy FrameProcessor effects are moved into an ordered
 * multi-threaded pipeline. Each processor instance is owned by one worker,
 * so stateful effects are never entered concurrently. Different video frames
 * may nevertheless be processed by different pipeline stages simultaneously.
 * </p>
 *
 * <p>
 * FIFO stage queues preserve both frame order and the exact command-line
 * ordering of effects. Seeking destroys and drains the old pipeline before a
 * replacement pipeline can reuse the processors, preventing old frames from
 * racing with frames from the new playback position.
 * </p>
 */
public class VideoPlayerModel {

    private static final long NO_SEEK_REQUEST = -1;
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    private final List<FrameProcessor> frameProcessors;

    private final Queue<PendingAudio> pendingAudio =
            new ArrayDeque<>();

    private final BiConsumer<Integer, Integer>
            videoSizeChangedHandler;

    private final Consumer<Image>
            frameReadyHandler;

    private final Consumer<String>
            statusChangedHandler;

    private final BiConsumer<Boolean, Boolean>
            playbackStateChangedHandler;

    private final Consumer<Boolean>
            audioOutputStateChangedHandler;

    private final AnimationTimer playbackTimer =
            new AnimationTimer() {

                @Override
                public void handle(long now) {
                    pumpPlayback(now);
                }
            };

    private File videoFile;

    private FFmpegFrameGrabber grabber;

    private JavaFXFrameConverter converter;

    private AudioPlayer audioPlayer;

    private FrameProcessingPipeline frameProcessingPipeline;

    private PreparedFrame preparedFrame;

    private boolean playbackOpen;

    private boolean pauseRequested;

    private boolean audioOutputEnabled;

    private boolean audioAvailable;

    private boolean frameProcessorsInitialised;

    private boolean endOfInput;

    /*
     * These two flags preserve the starter player's behaviour where seeking
     * while paused still updates the image displayed on screen.
     */
    private boolean pausedFrameRequestPending;

    private boolean waitingForPausedFrame;

    private long currentTimestampUs;

    private long videoDurationUs =
            NO_SEEK_REQUEST;

    private int videoFrameDurationUs;

    private double frameRate;

    private int intFrameRate;

    private int totalVideoFrames;

    private long firstTimestampUs =
            NO_SEEK_REQUEST;

    private long logicalPlaybackBaseUs;

    private long playbackStartNs;

    private long pauseStartedNs;

    private long relativeSeekBaseUs =
            NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessorOption> frameProcessorOptions,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {

        audioOutputEnabled = audioEnabled;

        this.videoSizeChangedHandler =
                videoSizeChangedHandler;

        this.frameReadyHandler =
                frameReadyHandler;

        this.statusChangedHandler =
                statusChangedHandler;

        this.playbackStateChangedHandler =
                playbackStateChangedHandler;

        this.audioOutputStateChangedHandler =
                audioOutputStateChangedHandler;

        this.frameProcessors =
                new ArrayList<>(
                        frameProcessorOptions.size());

        for (FrameProcessorOption option
                : frameProcessorOptions) {

            this.frameProcessors.add(
                    option.createProcessor());
        }
    }

    public void play(File file) {

        videoFile = file;

        videoDurationUs =
                NO_SEEK_REQUEST;

        videoFrameDurationUs = 0;

        startPlayback(
                0,
                false,
                NO_SEEK_REQUEST);
    }

    public void startOver() {

        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {

        seekRelative(
                -FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {

        seekRelative(
                FIVE_SECONDS_US);
    }

    public void togglePause() {

        if (!playbackOpen) {

            if (videoFile != null) {

                startPlayback(
                        displayableSeekTimestamp(
                                currentTimestampUs),
                        false,
                        currentTimestampUs);
            }

            return;
        }

        pauseRequested =
                !pauseRequested;

        relativeSeekBaseUs =
                NO_SEEK_REQUEST;

        if (pauseRequested) {

            pauseStartedNs =
                    System.nanoTime();

            flushAudioOutput();

        } else {

            resumePlaybackClock(
                    System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {

        audioOutputEnabled =
                !audioOutputEnabled;

        pendingAudio.clear();

        flushAudioOutput();

        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {

        return audioOutputEnabled;
    }

    public void stopPlayback() {

        closePlaybackResources();

        currentTimestampUs = 0;

        pauseRequested = false;

        notifyStatusChanged(
                "Stopped");

        notifyFrameReady(null);

        notifyPlaybackStateChanged();
    }

    public void shutdown() {

        closePlaybackResources();
    }

    private void seekRelative(
            long offsetUs) {

        if (videoFile == null) {
            return;
        }

        long baseTimestampUs =
                relativeSeekBaseUs
                        != NO_SEEK_REQUEST
                        ? relativeSeekBaseUs
                        : currentTimestampUs;

        seekTo(
                baseTimestampUs
                        + offsetUs);
    }

    private void seekTo(
            long timestampUs) {

        long logicalTimestampUs =
                clampSeekTimestamp(
                        timestampUs);

        long grabTimestampUs =
                displayableSeekTimestamp(
                        logicalTimestampUs);

        if (!playbackOpen) {

            startPlayback(
                    grabTimestampUs,
                    pauseRequested,
                    logicalTimestampUs);

            return;
        }

        try {

            /*
             * Discard every frame belonging to the old playback position
             * before moving FFmpeg to the requested timestamp.
             */
            resetFrameProcessingPipeline();

            grabber.setTimestamp(
                    grabTimestampUs);

            resetPlaybackClock(
                    logicalTimestampUs);

            pausedFrameRequestPending =
                    pauseRequested;

            waitingForPausedFrame =
                    false;

            flushAudioOutput();

            notifyPlaybackStateChanged();

        } catch (Exception e) {

            handlePlaybackError(e);
        }
    }

    private void startPlayback(
            long startTimestampUs,
            boolean initiallyPaused,
            long initialRelativeSeekBaseUs) {

        closePlaybackResources();

        pauseRequested =
                initiallyPaused;

        currentTimestampUs =
                initialRelativeSeekBaseUs
                        != NO_SEEK_REQUEST
                        ? initialRelativeSeekBaseUs
                        : startTimestampUs;

        relativeSeekBaseUs =
                initialRelativeSeekBaseUs;

        notifyStatusChanged(
                videoFile.getName());

        try {

            openPlaybackResources(
                    startTimestampUs);

            resetPlaybackClock(
                    currentTimestampUs);

            pausedFrameRequestPending =
                    initiallyPaused;

            playbackOpen = true;

            playbackTimer.start();

            notifyPlaybackStateChanged();

        } catch (Exception e) {

            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(
            long startTimestampUs)
            throws Exception {

        converter =
                new JavaFXFrameConverter();

        grabber =
                new FFmpegFrameGrabber(
                        videoFile);

        grabber.setImageMode(
                FrameGrabber.ImageMode.COLOR);

        grabber.setSampleMode(
                FrameGrabber.SampleMode.SHORT);

        grabber.start();

        notifyVideoSizeChanged(
                grabber.getImageWidth(),
                grabber.getImageHeight());

        frameRate =
                grabber.getFrameRate();

        intFrameRate =
                (int) Math.round(
                        frameRate);

        videoFrameDurationUs =
                frameRate > 0
                        ? (int) Math.round(
                                1_000_000.0
                                        / frameRate)
                        : 0;

        totalVideoFrames =
                grabber.getLengthInVideoFrames();

        long durationUs =
                grabber.getLengthInTime();

        videoDurationUs =
                durationUs > 0
                        ? durationUs
                        : NO_SEEK_REQUEST;

        audioAvailable =
                grabber.hasAudio();

        audioPlayer =
                new AudioPlayer();

        if (audioAvailable) {

            audioPlayer.open(
                    grabber.getSampleRate(),
                    grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {

            grabber.setTimestamp(
                    startTimestampUs);
        }
    }

    private void resetPlaybackClock(
            long logicalTimestampUs) {

        pendingAudio.clear();

        preparedFrame = null;

        endOfInput = false;

        pausedFrameRequestPending = false;

        waitingForPausedFrame = false;

        currentTimestampUs =
                logicalTimestampUs;

        relativeSeekBaseUs =
                logicalTimestampUs;

        firstTimestampUs =
                NO_SEEK_REQUEST;

        logicalPlaybackBaseUs =
                logicalTimestampUs;

        playbackStartNs = 0;

        pauseStartedNs =
                pauseRequested
                        ? System.nanoTime()
                        : 0;
    }

    private void resumePlaybackClock(
            long now) {

        if (pauseStartedNs > 0) {

            long pauseDurationNs =
                    now - pauseStartedNs;

            if (playbackStartNs > 0) {

                playbackStartNs +=
                        pauseDurationNs;
            }

            /*
             * A frame may already have been converted before the pause.
             * Move its deadline by the same duration as the playback clock.
             */
            if (preparedFrame != null) {

                preparedFrame =
                        new PreparedFrame(
                                preparedFrame.image(),
                                preparedFrame.frameNumber(),
                                preparedFrame.mediaTimestampUs(),
                                preparedFrame.logicalTimestampUs(),
                                preparedFrame.targetTimeNs()
                                        + pauseDurationNs,
                                preparedFrame.preparedAtNs());
            }
        }

        pauseStartedNs = 0;
    }

    /**
     * Reads and submits at most one video frame.
     *
     * The JavaFX thread never waits for an effect to finish.
     */
    private boolean submitNextFrame() {

        if (!playbackOpen
                || endOfInput) {

            return false;
        }

        if (frameProcessingPipeline != null
                && !frameProcessingPipeline.hasCapacity()) {

            return false;
        }

        try {

            while (playbackOpen
                    && !endOfInput) {

                Frame frame =
                        grabFrame();

                if (frame == null) {

                    endOfInput = true;
                    return false;
                }

                long timestampUs =
                        grabber.getTimestamp();

                if (audioAvailable
                        && frame.samples != null) {

                    queueAudio(
                            timestampUs,
                            frame);
                }

                /*
                 * grab() may return audio-only frames.
                 */
                if (frame.image == null) {
                    continue;
                }

                if (!frameProcessorsInitialised) {

                    initialiseFrameProcessingPipeline(
                            new InfoVideo(
                                    mediaName(videoFile),
                                    totalVideoFrames,
                                    frame.imageWidth,
                                    frame.imageHeight,
                                    frame.imageDepth,
                                    frame.imageChannels,
                                    frame.imageStride,
                                    frameRate,
                                    intFrameRate,
                                    videoFrameDurationUs,
                                    grabber.getPixelFormat()));
                }

                if (firstTimestampUs
                        == NO_SEEK_REQUEST) {

                    firstTimestampUs =
                            timestampUs;
                }

                int frameNumber =
                        grabber.getFrameNumber();

                InfoFrame info =
                        new InfoFrame(
                                frameNumber,
                                timestampUs);

                return frameProcessingPipeline
                        .trySubmit(
                                frame,
                                info);
            }

        } catch (Exception e) {

            handlePlaybackError(e);
        }

        return false;
    }

    /**
     * Moves one completed frame from the worker pipeline into JavaFX-ready
     * display state.
     */
    private void prepareCompletedFrame() {

        if (preparedFrame != null
                || frameProcessingPipeline == null) {

            return;
        }

        try {

            FrameProcessingPipeline.ProcessedFrame
                    processed =
                    frameProcessingPipeline
                            .pollCompleted();

            if (processed == null) {
                return;
            }

            InfoFrame info =
                    processed.info();

            /*
             * Start the real-time playback clock when the first processed
             * frame is actually ready. Initial processing latency therefore
             * does not make the whole video begin already behind schedule.
             */
            if (playbackStartNs == 0) {

                playbackStartNs =
                        System.nanoTime();

                if (pauseRequested) {

                    pauseStartedNs =
                            playbackStartNs;
                }
            }

            long relativeTimestampUs =
                    Math.max(
                            0,
                            info.timestampUs()
                                    - firstTimestampUs);

            long logicalTimestampUs =
                    logicalPlaybackBaseUs
                            + relativeTimestampUs;

            long targetTimeNs =
                    playbackStartNs
                            + relativeTimestampUs
                                    * 1_000L;

            Image image =
                    converter.convert(
                            processed.frame());

            preparedFrame =
                    new PreparedFrame(
                            image,
                            info.frameNumber(),
                            info.timestampUs(),
                            logicalTimestampUs,
                            targetTimeNs,
                            System.nanoTime());

        } catch (Exception e) {

            handlePlaybackError(e);
        }
    }

    /**
     * Called from JavaFX's heartbeat.
     *
     * No call here waits for effect processing. The GUI thread only moves
     * already-completed work, handles audio, displays due frames and feeds
     * additional input into the bounded pipeline.
     */
    private void pumpPlayback(
            long now) {

        if (!playbackOpen) {
            return;
        }

        try {

            /*
             * Normal playback polls completed frames continuously.
             *
             * When paused, it only polls if a seek explicitly requested a
             * replacement still image.
             */
            if (!pauseRequested
                    || waitingForPausedFrame) {

                prepareCompletedFrame();
            }

            writeDueAudio(now);

            if (preparedFrame != null) {

                if (!pauseRequested
                        && preparedFrame.targetTimeNs()
                                <= now) {

                    displayPreparedFrame(now);

                } else if (pauseRequested
                        && waitingForPausedFrame) {

                    /*
                     * Seek while paused: show the requested frame but remain
                     * paused afterwards.
                     */
                    displayPreparedFrame(now);

                    waitingForPausedFrame =
                            false;
                }
            }

            if (!playbackOpen) {
                return;
            }

            if (pauseRequested) {

                /*
                 * Only one frame is allowed into the pipeline for a paused
                 * seek operation.
                 */
                if (pausedFrameRequestPending
                        && !waitingForPausedFrame) {

                    boolean submitted =
                            submitNextFrame();

                    if (submitted) {

                        pausedFrameRequestPending =
                                false;

                        waitingForPausedFrame =
                                true;

                    } else if (endOfInput) {

                        pausedFrameRequestPending =
                                false;
                    }
                }

            } else {

                if (!endOfInput
                        && (frameProcessingPipeline == null
                                || frameProcessingPipeline
                                        .hasCapacity())) {

                    submitNextFrame();
                }
            }

            /*
             * Playback finishes only after the decoder has reached EOF and
             * every submitted frame has travelled through the complete
             * pipeline.
             */
            if (endOfInput
                    && preparedFrame == null
                    && frameProcessingPipeline != null
                    && frameProcessingPipeline.isIdle()
                    && !waitingForPausedFrame) {

                finishPlayback();
            }

        } catch (Exception e) {

            handlePlaybackError(e);
        }
    }

    private void displayPreparedFrame(
            long now) {

        PreparedFrame frame =
                preparedFrame;

        preparedFrame = null;

        currentTimestampUs =
                frame.logicalTimestampUs();

        relativeSeekBaseUs =
                NO_SEEK_REQUEST;

        if (intFrameRate > 0
                && frame.frameNumber()
                        % intFrameRate == 0) {

            long remainingUs =
                    (frame.targetTimeNs()
                            - frame.preparedAtNs())
                            / 1_000L;

            if (remainingUs < 0) {

                System.out.printf(
                        "Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);

            } else {

                System.out.printf(
                        "Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(
                frame.image());
    }

    private void queueAudio(
            long timestampUs,
            Frame frame) {

        if (!audioOutputEnabled
                || audioPlayer == null) {

            return;
        }

        byte[] samples =
                audioPlayer.copySamples(
                        frame);

        if (samples.length > 0) {

            pendingAudio.add(
                    new PendingAudio(
                            timestampUs,
                            samples));
        }
    }

    private void writeDueAudio(
            long now) {

        if (!audioOutputEnabled
                || audioPlayer == null) {

            pendingAudio.clear();
            return;
        }

        if (pauseRequested
                || firstTimestampUs
                        == NO_SEEK_REQUEST
                || playbackStartNs <= 0) {

            return;
        }

        long dueTimestampUs =
                firstTimestampUs
                        + (now
                        + AUDIO_LEAD_NS
                        - playbackStartNs)
                        / 1_000L;

        while (!pendingAudio.isEmpty()) {

            PendingAudio audio =
                    pendingAudio.peek();

            if (audio.timestampUs()
                    > dueTimestampUs) {

                return;
            }

            int written =
                    audioPlayer.write(
                            audio.samples(),
                            audio.offset(),
                            audio.remaining());

            if (written <= 0) {
                return;
            }

            audio.advance(
                    written);

            if (!audio.finished()) {
                return;
            }

            pendingAudio.remove();
        }
    }

    private void finishPlayback() {

        long endTimestampUs =
                videoDurationUs
                        != NO_SEEK_REQUEST
                        ? videoDurationUs
                        : currentTimestampUs;

        closePlaybackResources();

        pauseRequested = true;

        currentTimestampUs =
                endTimestampUs;

        relativeSeekBaseUs =
                endTimestampUs;

        notifyStatusChanged(
                "Playback finished");

        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(
            Exception e) {

        e.printStackTrace();

        closePlaybackResources();

        pauseRequested = false;

        notifyStatusChanged(
                "Error: "
                        + e.getMessage());

        notifyPlaybackStateChanged();
    }

    /**
     * Replaces the processing pipeline during seeking.
     *
     * Processor objects themselves are retained because this is the same
     * media file and they have already been initialised. close() waits for
     * old workers before the replacement can use those processor objects.
     */
    private void resetFrameProcessingPipeline() {

        closeFrameProcessingPipeline();

        if (!frameProcessorsInitialised) {
            return;
        }

        frameProcessingPipeline =
                new FrameProcessingPipeline(
                        frameProcessors);

        frameProcessingPipeline.start();
    }

    /**
     * Initialises processor state exactly once after the first image frame
     * reveals the media's actual pixel layout.
     */
    private void initialiseFrameProcessingPipeline(
            InfoVideo videoInfo)
            throws Exception {

        closeFrameProcessingPipeline();

        frameProcessingPipeline =
                new FrameProcessingPipeline(
                        frameProcessors);

        frameProcessingPipeline.initialise(
                videoInfo);

        frameProcessingPipeline.start();

        frameProcessorsInitialised =
                true;
    }

    private void closeFrameProcessingPipeline() {

        if (frameProcessingPipeline != null) {

            frameProcessingPipeline.close();

            frameProcessingPipeline =
                    null;
        }
    }

    private void closePlaybackResources() {

        playbackTimer.stop();

        /*
         * Stop effect workers before releasing media/converter resources.
         */
        closeFrameProcessingPipeline();

        playbackOpen = false;

        preparedFrame = null;

        pendingAudio.clear();

        endOfInput = false;

        pausedFrameRequestPending = false;

        waitingForPausedFrame = false;

        firstTimestampUs =
                NO_SEEK_REQUEST;

        playbackStartNs = 0;

        pauseStartedNs = 0;

        audioAvailable = false;

        frameProcessorsInitialised = false;

        if (audioPlayer != null) {

            audioPlayer.close();

            audioPlayer = null;
        }

        if (grabber != null) {

            try {

                grabber.stop();

            } catch (Exception e) {

                // Resource is being closed; no useful recovery action.
            }

            try {

                grabber.close();

            } catch (Exception e) {

                // Resource is being closed; no useful recovery action.
            }

            grabber = null;
        }

        if (converter != null) {

            converter.close();

            converter = null;
        }
    }

    private String mediaName(
            File file) {

        String fileName =
                file.getName();

        int extensionStart =
                fileName.lastIndexOf('.');

        if (extensionStart <= 0) {
            return fileName;
        }

        return fileName.substring(
                0,
                extensionStart);
    }

    private long clampSeekTimestamp(
            long timestampUs) {

        long clampedTimestampUs =
                Math.max(
                        0,
                        timestampUs);

        long durationUs =
                videoDurationUs;

        if (durationUs
                != NO_SEEK_REQUEST) {

            clampedTimestampUs =
                    Math.min(
                            clampedTimestampUs,
                            durationUs);
        }

        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(
            long logicalTimestampUs) {

        long durationUs =
                videoDurationUs;

        if (durationUs
                == NO_SEEK_REQUEST
                || logicalTimestampUs
                        < durationUs) {

            return logicalTimestampUs;
        }

        int frameDurationUs =
                videoFrameDurationUs;

        if (frameDurationUs <= 0) {

            return Math.max(
                    0,
                    durationUs - 1);
        }

        return Math.max(
                0,
                durationUs
                        - frameDurationUs);
    }

    private Frame grabFrame()
            throws Exception {

        if (audioAvailable) {

            return grabber.grab();
        }

        return grabber.grabImage();
    }

    private void flushAudioOutput() {

        if (audioPlayer != null) {

            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(
            int width,
            int height) {

        videoSizeChangedHandler.accept(
                width,
                height);
    }

    private void notifyFrameReady(
            Image image) {

        frameReadyHandler.accept(
                image);
    }

    private void notifyStatusChanged(
            String status) {

        statusChangedHandler.accept(
                status);
    }

    private void notifyPlaybackStateChanged() {

        playbackStateChangedHandler.accept(
                playbackOpen,
                pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {

        audioOutputStateChangedHandler.accept(
                audioOutputEnabled);
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    private static class PendingAudio {

        private final long timestampUs;

        private final byte[] samples;

        private int offset;

        PendingAudio(
                long timestampUs,
                byte[] samples) {

            this.timestampUs =
                    timestampUs;

            this.samples =
                    samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {

            return samples.length
                    - offset;
        }

        void advance(
                int byteCount) {

            offset += byteCount;
        }

        boolean finished() {

            return offset
                    >= samples.length;
        }
    }
}