/*
 * COMP3011 Assignment 2.
 *
 * Author:
 *   Raúl Ruiz Sánchez, a3229619
 *
 * Developed with assistance from ChatGPT.
 */
package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bytedeco.javacv.Frame;

/**
 * Concurrent ordered pipeline for frame processing.
 *
 * <p>
 * Each FrameProcessor belongs to exactly one worker thread. A frame therefore
 * always passes through processors sequentially and in command-line order,
 * while different frames can occupy different stages at the same time.
 * </p>
 *
 * <p>
 * This gives multicore parallelism without requiring individual
 * FrameProcessor implementations to be thread-safe.
 * </p>
 *
 * <p>
 * The number of frames allowed inside the pipeline is bounded. This prevents
 * excessive read-ahead, keeps memory use under control and keeps pause/seek
 * latency low.
 * </p>
 */
public final class FrameProcessingPipeline implements AutoCloseable {

    private final List<Stage> stages = new ArrayList<>();

    private final BlockingQueue<FrameJob> firstInputQueue =
            new LinkedBlockingQueue<>();

    private final BlockingQueue<ProcessedFrame> completedFrames =
            new LinkedBlockingQueue<>();

    private final Semaphore inFlightSlots;

    private final AtomicInteger inFlightCount =
            new AtomicInteger();

    private final AtomicReference<Throwable> processingFailure =
            new AtomicReference<>();

    private volatile boolean running;

    public FrameProcessingPipeline(List<FrameProcessor> processors) {

        int usefulParallelism =
                Math.max(
                        1,
                        Math.min(
                                Runtime.getRuntime().availableProcessors(),
                                Math.max(1, processors.size())));

        inFlightSlots = new Semaphore(usefulParallelism);

        BlockingQueue<FrameJob> inputQueue =
                firstInputQueue;

        for (FrameProcessor processor : processors) {

            BlockingQueue<FrameJob> outputQueue =
                    new LinkedBlockingQueue<>();

            stages.add(
                    new Stage(
                            processor,
                            inputQueue,
                            outputQueue));

            inputQueue = outputQueue;
        }
    }

    /**
     * Initialises all processors before worker threads are started.
     */
    public void initialise(InfoVideo videoInfo) throws Exception {

        for (Stage stage : stages) {
            stage.processor.initialise(videoInfo);
        }
    }

    /**
     * Starts one platform worker thread for each effect stage.
     *
     * CPU-bound effect processing benefits from platform threads rather
     * than virtual threads.
     */
    public void start() {

        if (running) {
            return;
        }

        running = true;

        for (int i = 0; i < stages.size(); i++) {

            Stage stage = stages.get(i);

            boolean finalStage =
                    i == stages.size() - 1;

            stage.worker =
                    Thread.ofPlatform()
                            .name("frame-effect-" + i)
                            .daemon(true)
                            .start(
                                    () -> runStage(
                                            stage,
                                            finalStage));
        }
    }

    /**
     * Returns true if adding another frame will not block the caller.
     */
    public boolean hasCapacity() {

        throwIfFailed();

        return running
                && inFlightSlots.availablePermits() > 0;
    }

    /**
     * Attempts to submit a frame without blocking the JavaFX thread.
     */
    public boolean trySubmit(
            Frame frame,
            InfoFrame info) {

        throwIfFailed();

        if (!running || !inFlightSlots.tryAcquire()) {
            return false;
        }

        boolean counted = false;

        try {
            /*
             * FFmpeg may reuse the native buffers associated with frames
             * returned by the grabber. Once processing is asynchronous,
             * the pipeline must own an independent copy.
             */
            FrameJob job =
                    new FrameJob(
                            frame.clone(),
                            info);

            inFlightCount.incrementAndGet();
            counted = true;

            /*
             * An empty processor list still uses the same pipeline API,
             * but the frame can immediately be considered complete.
             */
            if (stages.isEmpty()) {

                completedFrames.add(
                        new ProcessedFrame(
                                job.frame(),
                                job.info()));

            } else {

                firstInputQueue.add(job);
            }

            return true;

        } catch (RuntimeException | Error e) {

            if (counted) {
                inFlightCount.decrementAndGet();
            }

            inFlightSlots.release();

            throw e;
        }
    }

    /**
     * Retrieves the oldest completed frame without blocking.
     */
    public ProcessedFrame pollCompleted() {

        throwIfFailed();

        ProcessedFrame result =
                completedFrames.poll();

        if (result != null) {

            inFlightCount.decrementAndGet();
            inFlightSlots.release();
        }

        return result;
    }

    /**
     * Returns true once no submitted frame remains in the pipeline.
     */
    public boolean isIdle() {

        throwIfFailed();

        return inFlightCount.get() == 0;
    }

    private void runStage(
            Stage stage,
            boolean finalStage) {

        try {

            while (running
                    && !Thread.currentThread().isInterrupted()) {

                FrameJob job =
                        stage.inputQueue.take();

                /*
                 * Only this worker ever invokes this processor instance.
                 * This is the critical ownership rule that prevents races
                 * in stateful FrameProcessor implementations.
                 */
                stage.processor.process(
                        job.frame(),
                        job.info());

                if (finalStage) {

                    completedFrames.add(
                            new ProcessedFrame(
                                    job.frame(),
                                    job.info()));

                } else {

                    stage.outputQueue.add(job);
                }
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

        } catch (Throwable t) {

            processingFailure.compareAndSet(
                    null,
                    t);

            running = false;

            interruptOtherWorkers();
        }
    }

    private void interruptOtherWorkers() {

        Thread current =
                Thread.currentThread();

        for (Stage stage : stages) {

            if (stage.worker != null
                    && stage.worker != current) {

                stage.worker.interrupt();
            }
        }
    }

    private void throwIfFailed() {

        Throwable failure =
                processingFailure.get();

        if (failure != null) {

            throw new IllegalStateException(
                    "Frame processing pipeline failed",
                    failure);
        }
    }

    /**
     * Stops every worker and waits for ownership of processors to be released.
     *
     * Waiting for workers here is important when seeking: the replacement
     * pipeline must not start using a processor while an old worker is still
     * modifying a frame with the same processor instance.
     */
    @Override
    public void close() {

        running = false;

        for (Stage stage : stages) {

            if (stage.worker != null) {
                stage.worker.interrupt();
            }
        }

        for (Stage stage : stages) {

            if (stage.worker == null) {
                continue;
            }

            try {

                stage.worker.join();

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();
                break;
            }
        }

        firstInputQueue.clear();
        completedFrames.clear();

        for (Stage stage : stages) {
            stage.inputQueue.clear();
            stage.outputQueue.clear();
        }
    }

    public record ProcessedFrame(
            Frame frame,
            InfoFrame info) {
    }

    private record FrameJob(
            Frame frame,
            InfoFrame info) {
    }

    private static final class Stage {

        private final FrameProcessor processor;

        private final BlockingQueue<FrameJob> inputQueue;

        private final BlockingQueue<FrameJob> outputQueue;

        private Thread worker;

        private Stage(
                FrameProcessor processor,
                BlockingQueue<FrameJob> inputQueue,
                BlockingQueue<FrameJob> outputQueue) {

            this.processor = processor;
            this.inputQueue = inputQueue;
            this.outputQueue = outputQueue;
        }
    }
}