# COMP3011 Assignment 2 - Java Local Project

**Student:** Raúl Ruiz Sánchez  
**Student ID:** a3229619

This project extends the supplied COMP3011 single-threaded JavaFX video player with configurable frame-processing effects and a concurrent frame-processing architecture designed to improve playback performance on multicore systems.

## Building and Running

The project uses Maven.

```bash
mvn validate
mvn compile
mvn package
mvn verify
```

Run the player with:

```bash
mvn javafx:run '-Djavafx.args=[options] video.mp4'
```

Show command-line help with:

```bash
mvn javafx:run '-Djavafx.args=--help'
```

## Command-Line Frame Processing

Frame-processing effects can be selected using short or long command-line options.

Options may be:

- combined
- repeated
- mixed between short and long forms
- applied in command-line order

For example:

```bash
mvn javafx:run '-Djavafx.args=-nss video.mp4'
```

applies:

```text
FrameNumberer
FrameScratcher
FrameScratcher
```

Similarly:

```text
-nssnf
```

produces:

```text
FrameNumberer
FrameScratcher
FrameScratcher
FrameNumberer
FrameFlickerer
```

The command-line parser stores the selected effects as `FrameProcessorOption` values.

`FrameProcessorOption` is responsible for mapping each command-line option to its corresponding `FrameProcessor`. This keeps processor creation separate from command-line parsing and makes future processors straightforward to add.

## Concurrent Architecture

The supplied implementation processes all effects for one frame sequentially before moving to the next frame:

```text
Frame 1: A -> B -> C
Frame 2: A -> B -> C
Frame 3: A -> B -> C
```

This limits processing throughput because all CPU-heavy effects execute sequentially.

The modified implementation introduces `FrameProcessingPipeline`.

Each selected `FrameProcessor` becomes one stage in an ordered pipeline with one dedicated worker thread:

```text
             Stage A       Stage B       Stage C

Frame 1 --->   A   ------>   B   ------>   C   ---> display
Frame 2 --------------->   A   ------>   B   ------> C
Frame 3 --------------------------->   A   ------>   B
```

A single frame still passes through every effect sequentially and in exactly the order requested on the command line.

However, different frames may occupy different pipeline stages simultaneously. This allows frame processing to use multiple CPU cores while preserving effect ordering.

Platform threads are used because the frame effects are CPU-bound operations.

## Concurrency Correctness

The design avoids race conditions by establishing clear ownership of mutable objects.

### FrameProcessor ownership

Each `FrameProcessor` instance is owned by exactly one pipeline worker thread.

Therefore, two threads never execute `process()` concurrently on the same processor instance.

This is particularly important because some supplied processors contain mutable state and are not designed for concurrent access.

### Frame ordering

Frames are transferred between processing stages using thread-safe FIFO queues.

This preserves:

- temporal frame order
- command-line effect order
- repeated effects

A frame cannot enter stage B until stage A has completed processing that frame.

### Back-pressure

A `Semaphore` limits the number of frames allowed inside the pipeline simultaneously.

This prevents the decoder from running arbitrarily far ahead of playback, limiting memory usage and avoiding excessive pause or seek latency.

### Shared concurrent state

The pipeline uses:

- `BlockingQueue` for safe frame transfer between threads
- `Semaphore` for bounded in-flight work
- `AtomicInteger` for the number of frames currently being processed
- `AtomicReference` for safely propagating worker failures
- `volatile` state for worker shutdown visibility

### Seek and shutdown correctness

When playback is stopped or a seek changes the active processing position, the existing pipeline is closed.

Workers are interrupted and joined before processor instances may be reused.

This ensures that frames belonging to an old playback position cannot continue processing concurrently with frames belonging to the new position.

### JavaFX responsiveness

The JavaFX application thread does not wait for CPU-heavy frame effects to finish during normal playback.

Instead, it:

1. checks for already-completed frames
2. updates audio state
3. displays frames when due
4. submits additional frames only when pipeline capacity is available

This keeps GUI operations such as resizing, pause/play and seeking responsive under processing load.

## Testing

All automated tests are executed using:

```bash
mvn verify
```

The current suite contains **14 tests**.

### CommandLineControllerTest

Regression tests cover:

- short frame-processing options
- long frame-processing options
- combined short options
- repeated processors
- processor ordering
- original audio/maximise/monitor options
- unknown options
- missing video files
- multiple video files

### FrameProcessingPipelineTest

Concurrency tests verify that:

- completed frames remain in input order
- effects execute in the required order
- each processor is owned by one worker
- a processor is never entered concurrently
- processors are initialised correctly
- worker shutdown completes without deadlock

### FrameProcessingLoadTest

An automated CPU-bound load test compares the concurrent architecture against the original sequential processing strategy.

A representative development-machine result was:

```text
Sequential baseline: 320.9 ms, 124.7 frames/s
Concurrent pipeline: 94.7 ms, 422.4 frames/s
Measured speed-up: 3.39x
```

These values depend on the machine and workload and do not mean that normal video playback is always exactly 3.39 times faster.

The purpose of the test is to provide a repeatable performance regression check showing that the concurrent pipeline can achieve greater CPU-bound frame-processing throughput on a multicore system.

## Manual Performance Testing

The original single-threaded implementation was compared with the concurrent implementation using the same video and repeated effect chains.

A heavy example was:

```text
-nssnfwyvdjmbpnssnfwyvdjmbp
```

The original single-threaded implementation showed significant slowdown under this load.

The concurrent implementation remained smooth during manual testing, including with two complete repeated effect chains.

The console `Frame headroom` output was also used to observe whether processed frames were remaining within their real-time rendering deadlines.

## Functional Regression Testing

The following original functionality was manually checked after introducing concurrency:

- normal playback
- pause and resume
- forward five seconds
- backward five seconds
- restart
- stop
- audio toggle
- keyboard controls
- window resizing
- effect composition
- repeated effects
- effect ordering

The intention of the concurrent architecture is to improve frame-processing throughput without changing the externally visible behaviour of the supplied player.

## Generative AI Attribution

ChatGPT was used as a development assistant during this assignment for discussion of architecture, concurrency, debugging, testing and documentation.

AI-generated suggestions were reviewed, adapted and tested during development.

Modified and newly created source files include student attribution and acknowledge AI assistance where appropriate.

For more information on this code and how to get started, see https://learn.adelaide.edu.au/courses/30747/assignments/94505
