/*
 * COMP3011 Assignment 2 concurrency regression tests.
 *
 * Author:
 *   Raúl Ruiz Sánchez, a3229619
 *
 * Developed with assistance from ChatGPT.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class FrameProcessingPipelineTest {

    @Test
    void preservesFrameOrderAndProcessorOrder() throws Exception {

        List<String> events =
                new CopyOnWriteArrayList<>();

        RecordingProcessor first =
                new RecordingProcessor("A", events);

        RecordingProcessor second =
                new RecordingProcessor("B", events);

        RecordingProcessor third =
                new RecordingProcessor("C", events);

        List<Integer> completedFrames =
                new ArrayList<>();

        int totalFrames = 20;

        try (FrameProcessingPipeline pipeline =
                new FrameProcessingPipeline(
                        List.of(first, second, third))) {

            pipeline.start();

            int nextFrame = 0;

            long deadline =
                    System.nanoTime()
                            + TimeUnit.SECONDS.toNanos(5);

            while (completedFrames.size() < totalFrames
                    && System.nanoTime() < deadline) {

                while (nextFrame < totalFrames
                        && pipeline.hasCapacity()) {

                    boolean submitted =
                            pipeline.trySubmit(
                                    new Frame(),
                                    new InfoFrame(
                                            nextFrame,
                                            nextFrame * 1_000L));

                    assertTrue(submitted);

                    nextFrame++;
                }

                FrameProcessingPipeline.ProcessedFrame completed =
                        pipeline.pollCompleted();

                if (completed != null) {

                    completedFrames.add(
                            completed.info().frameNumber());

                } else {

                    Thread.sleep(1);
                }
            }
        }

        List<Integer> expected =
                IntStream.range(0, totalFrames)
                        .boxed()
                        .toList();

        /*
         * FIFO queues must prevent frame reordering.
         */
        assertEquals(
                expected,
                completedFrames);

        /*
         * Every individual frame must pass A -> B -> C.
         */
        for (int frameNumber = 0;
                frameNumber < totalFrames;
                frameNumber++) {

            int a =
                    events.indexOf(
                            "A:" + frameNumber);

            int b =
                    events.indexOf(
                            "B:" + frameNumber);

            int c =
                    events.indexOf(
                            "C:" + frameNumber);

            assertTrue(a >= 0);
            assertTrue(b > a);
            assertTrue(c > b);
        }
    }

    @Test
    void eachProcessorIsOwnedByOneWorkerThread()
            throws Exception {

        ThreadRecordingProcessor first =
                new ThreadRecordingProcessor();

        ThreadRecordingProcessor second =
                new ThreadRecordingProcessor();

        ThreadRecordingProcessor third =
                new ThreadRecordingProcessor();

        try (FrameProcessingPipeline pipeline =
                new FrameProcessingPipeline(
                        List.of(first, second, third))) {

            pipeline.start();

            processFrames(
                    pipeline,
                    15);
        }

        assertEquals(
                1,
                first.threadNames.size());

        assertEquals(
                1,
                second.threadNames.size());

        assertEquals(
                1,
                third.threadNames.size());

        /*
         * Each stage should have its own worker.
         */
        assertFalse(
                first.threadNames.equals(
                        second.threadNames));

        assertFalse(
                second.threadNames.equals(
                        third.threadNames));
    }

    @Test
    void processorIsNeverEnteredConcurrently()
            throws Exception {

        ConcurrentAccessDetector detector =
                new ConcurrentAccessDetector();

        try (FrameProcessingPipeline pipeline =
                new FrameProcessingPipeline(
                        List.of(detector))) {

            pipeline.start();

            processFrames(
                    pipeline,
                    25);
        }

        assertFalse(
                detector.concurrentAccessDetected.get());
    }

    @Test
    void initialisesEachProcessorExactlyOnce()
            throws Exception {

        InitialisationCounter first =
                new InitialisationCounter();

        InitialisationCounter second =
                new InitialisationCounter();

        InfoVideo videoInfo =
                new InfoVideo(
                        "test-video",
                        100,
                        640,
                        480,
                        8,
                        3,
                        1_920,
                        30.0,
                        30,
                        33_333,
                        0);

        try (FrameProcessingPipeline pipeline =
                new FrameProcessingPipeline(
                        List.of(first, second))) {

            pipeline.initialise(
                    videoInfo);
        }

        assertEquals(
                1,
                first.initialisationCount.get());

        assertEquals(
                1,
                second.initialisationCount.get());
    }

    @Test
    void closingPipelineInterruptsWorkers()
            throws Exception {

        BlockingProcessor processor =
                new BlockingProcessor();

        FrameProcessingPipeline pipeline =
                new FrameProcessingPipeline(
                        List.of(processor));

        pipeline.start();

        assertTrue(
                pipeline.trySubmit(
                        new Frame(),
                        new InfoFrame(1, 1_000)));

        assertTrue(
                processor.entered.await(
                        2,
                        TimeUnit.SECONDS));

        /*
         * close() must not deadlock while a worker is processing.
         */
        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                pipeline::close);

        assertTrue(
                processor.interrupted.get());
    }

    private void processFrames(
            FrameProcessingPipeline pipeline,
            int frameCount)
            throws Exception {

        int submitted = 0;
        int completed = 0;

        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS.toNanos(5);

        while (completed < frameCount
                && System.nanoTime() < deadline) {

            while (submitted < frameCount
                    && pipeline.hasCapacity()) {

                if (pipeline.trySubmit(
                        new Frame(),
                        new InfoFrame(
                                submitted,
                                submitted * 1_000L))) {

                    submitted++;
                }
            }

            FrameProcessingPipeline.ProcessedFrame result =
                    pipeline.pollCompleted();

            if (result != null) {

                completed++;

            } else {

                Thread.sleep(1);
            }
        }

        assertEquals(
                frameCount,
                submitted);

        assertEquals(
                frameCount,
                completed);
    }

    private static final class RecordingProcessor
            extends FrameProcessor {

        private final String name;

        private final List<String> events;

        private RecordingProcessor(
                String name,
                List<String> events) {

            this.name = name;
            this.events = events;
        }

        @Override
        public void process(
                Frame frame,
                InfoFrame info) {

            events.add(
                    name + ":"
                            + info.frameNumber());
        }
    }

    private static final class ThreadRecordingProcessor
            extends FrameProcessor {

        private final Set<String> threadNames =
                ConcurrentHashMap.newKeySet();

        @Override
        public void process(
                Frame frame,
                InfoFrame info)
                throws Exception {

            threadNames.add(
                    Thread.currentThread()
                            .getName());

            Thread.sleep(2);
        }
    }

    private static final class ConcurrentAccessDetector
            extends FrameProcessor {

        private final AtomicInteger activeCalls =
                new AtomicInteger();

        private final AtomicBoolean concurrentAccessDetected =
                new AtomicBoolean();

        @Override
        public void process(
                Frame frame,
                InfoFrame info)
                throws Exception {

            int active =
                    activeCalls.incrementAndGet();

            if (active > 1) {

                concurrentAccessDetected.set(
                        true);
            }

            try {

                Thread.sleep(2);

            } finally {

                activeCalls.decrementAndGet();
            }
        }
    }

    private static final class InitialisationCounter
            extends FrameProcessor {

        private final AtomicInteger initialisationCount =
                new AtomicInteger();

        @Override
        public void initialise(
                InfoVideo videoInfo)
                throws Exception {

            super.initialise(
                    videoInfo);

            initialisationCount.incrementAndGet();
        }

        @Override
        public void process(
                Frame frame,
                InfoFrame info) {
        }
    }

    private static final class BlockingProcessor
            extends FrameProcessor {

        private final CountDownLatch entered =
                new CountDownLatch(1);

        private final AtomicBoolean interrupted =
                new AtomicBoolean();

        @Override
        public void process(
                Frame frame,
                InfoFrame info)
                throws Exception {

            entered.countDown();

            try {

                Thread.sleep(30_000);

            } catch (InterruptedException e) {

                interrupted.set(true);

                throw e;
            }
        }
    }
}