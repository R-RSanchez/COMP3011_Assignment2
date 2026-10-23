/*
 * COMP3011 Assignment 2 automated performance regression test.
 *
 * Author:
 *   Raúl Ruiz Sánchez, a3229619
 *
 * Developed with assistance from ChatGPT.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Compares the original single-threaded processing strategy with the
 * concurrent pipeline under the same synthetic CPU-bound effect load.
 *
 * <p>
 * The sequential baseline intentionally reproduces the original
 * VideoPlayerModel processing strategy:
 *
 * <pre>
 * for each frame:
 *     for each processor:
 *         processor.process(frame, info)
 * </pre>
 *
 * The concurrent measurement sends the same number of frames through the
 * same number of equally expensive processor stages using
 * FrameProcessingPipeline.
 * </p>
 */
class FrameProcessingLoadTest {

    private static final int PROCESSOR_COUNT = 4;
    private static final int FRAME_COUNT = 40;

    /*
     * Each synthetic effect consumes roughly two milliseconds of CPU time.
     * The test is intentionally short enough for mvn verify while still
     * producing a clear throughput difference on a multicore machine.
     */
    private static final long WORK_NANOS =
            TimeUnit.MILLISECONDS.toNanos(2);

    @Test
    void concurrentPipelineOutperformsOriginalSequentialStrategy()
            throws Exception {

        /*
         * A multicore comparison is meaningless on a single-core machine.
         */
        assumeTrue(
                Runtime.getRuntime().availableProcessors() >= 2,
                "Performance comparison requires at least two processors");

        /*
         * Small warm-up runs reduce JIT/startup noise in the timed samples.
         */
        runSequential(8);
        runConcurrent(8);

        long sequentialNanos =
                runSequential(FRAME_COUNT);

        long concurrentNanos =
                runConcurrent(FRAME_COUNT);

        double sequentialMs =
                sequentialNanos / 1_000_000.0;

        double concurrentMs =
                concurrentNanos / 1_000_000.0;

        double speedup =
                (double) sequentialNanos
                        / concurrentNanos;

        double sequentialFps =
                FRAME_COUNT
                        / (sequentialNanos / 1_000_000_000.0);

        double concurrentFps =
                FRAME_COUNT
                        / (concurrentNanos / 1_000_000_000.0);

        System.out.println();
        System.out.println("=== Frame processing load comparison ===");

        System.out.printf(
                "Sequential baseline: %.1f ms, %.1f frames/s%n",
                sequentialMs,
                sequentialFps);

        System.out.printf(
                "Concurrent pipeline: %.1f ms, %.1f frames/s%n",
                concurrentMs,
                concurrentFps);

        System.out.printf(
                "Measured speed-up: %.2fx%n",
                speedup);

        System.out.println(
                "========================================");

        assertTrue(
                concurrentNanos < sequentialNanos,
                () -> String.format(
                        "Concurrent processing should outperform "
                                + "the original sequential strategy "
                                + "(sequential %.1f ms, concurrent %.1f ms)",
                        sequentialMs,
                        concurrentMs));
    }

    /**
     * Reproduces the supplied player's original processing architecture.
     */
    private long runSequential(int frameCount)
            throws Exception {

        List<FrameProcessor> processors =
                createLoadProcessors();

        long start =
                System.nanoTime();

        for (int frameNumber = 0;
                frameNumber < frameCount;
                frameNumber++) {

            Frame frame =
                    new Frame();

            InfoFrame info =
                    new InfoFrame(
                            frameNumber,
                            frameNumber * 33_333L);

            for (FrameProcessor processor
                    : processors) {

                processor.process(
                        frame,
                        info);
            }
        }

        return System.nanoTime() - start;
    }

    /**
     * Runs the same workload using the submitted concurrent architecture.
     */
    private long runConcurrent(int frameCount)
            throws Exception {

        List<Integer> completedFrames =
                new ArrayList<>();

        try (FrameProcessingPipeline pipeline =
                new FrameProcessingPipeline(
                        createLoadProcessors())) {

            pipeline.start();

            long start =
                    System.nanoTime();

            int submitted = 0;

            long timeout =
                    start
                            + TimeUnit.SECONDS.toNanos(10);

            while (completedFrames.size() < frameCount
                    && System.nanoTime() < timeout) {

                while (submitted < frameCount
                        && pipeline.hasCapacity()) {

                    boolean accepted =
                            pipeline.trySubmit(
                                    new Frame(),
                                    new InfoFrame(
                                            submitted,
                                            submitted * 33_333L));

                    if (accepted) {
                        submitted++;
                    }
                }

                FrameProcessingPipeline.ProcessedFrame completed =
                        pipeline.pollCompleted();

                if (completed != null) {

                    completedFrames.add(
                            completed.info().frameNumber());

                } else {

                    Thread.onSpinWait();
                }
            }

            long elapsed =
                    System.nanoTime() - start;

            assertEquals(
                    frameCount,
                    completedFrames.size(),
                    "Every submitted frame must complete");

            assertEquals(
                    IntStream.range(0, frameCount)
                            .boxed()
                            .toList(),
                    completedFrames,
                    "Concurrent processing must preserve frame order");

            return elapsed;
        }
    }

    private List<FrameProcessor> createLoadProcessors() {

        List<FrameProcessor> processors =
                new ArrayList<>();

        for (int i = 0;
                i < PROCESSOR_COUNT;
                i++) {

            processors.add(
                    new CpuLoadProcessor());
        }

        return processors;
    }

    /**
     * Synthetic deterministic CPU-bound frame effect.
     *
     * The arithmetic prevents the loop becoming an empty timing wait while
     * keeping the benchmark independent of video resolution and codecs.
     */
    private static final class CpuLoadProcessor
            extends FrameProcessor {

        private volatile long resultSink;

        @Override
        public void process(
                Frame frame,
                InfoFrame info) {

            long deadline =
                    System.nanoTime()
                            + WORK_NANOS;

            long value =
                    info.frameNumber() + 1L;

            while (System.nanoTime() < deadline) {

                value =
                        value * 1_664_525L
                                + 1_013_904_223L;

                value ^=
                        value >>> 13;
            }

            resultSink = value;
        }
    }
}