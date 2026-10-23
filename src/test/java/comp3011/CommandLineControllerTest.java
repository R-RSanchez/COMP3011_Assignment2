/*
 * COMP3011 Assignment 2 regression tests.
 *
 * Author:
 *   Raúl Ruiz Sánchez, a3229619
 *
 * Developed with assistance from ChatGPT.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesStackedShortFrameProcessorOptionsInOrder() throws IOException {
        Path video = createVideoFile("video.mp4");

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-nssnf",
                        video.toString()
                });

        assertEquals(
                List.of(
                        FrameProcessorOption.NUMBER,
                        FrameProcessorOption.SCRATCH,
                        FrameProcessorOption.SCRATCH,
                        FrameProcessorOption.NUMBER,
                        FrameProcessorOption.FLICKER),
                controller.getFrameProcessorOptions());

        assertEquals(0, controller.getExitCode());
        assertTrue(controller.shouldLaunchApplication());
    }

    @Test
    void parsesMixedShortAndLongOptionsInCommandLineOrder()
            throws IOException {

        Path video = createVideoFile("video.mp4");

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-jf",
                        "--dust-frames",
                        "-d",
                        "--number-frames",
                        video.toString()
                });

        assertEquals(
                List.of(
                        FrameProcessorOption.JITTER,
                        FrameProcessorOption.FLICKER,
                        FrameProcessorOption.DUST,
                        FrameProcessorOption.DUST,
                        FrameProcessorOption.NUMBER),
                controller.getFrameProcessorOptions());

        assertEquals(0, controller.getExitCode());
    }

    @Test
    void preservesRepeatedFrameProcessorOptions() throws IOException {
        Path video = createVideoFile("video.mp4");

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-ddd",
                        "--dust-frames",
                        video.toString()
                });

        assertEquals(
                List.of(
                        FrameProcessorOption.DUST,
                        FrameProcessorOption.DUST,
                        FrameProcessorOption.DUST,
                        FrameProcessorOption.DUST),
                controller.getFrameProcessorOptions());
    }

    @Test
    void preservesOriginalLaunchOptions() throws IOException {
        Path video = createVideoFile("video.mp4");

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-a",
                        "-x",
                        "-2",
                        video.toString()
                });

        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(2, controller.getDisplayId());
        assertEquals(0, controller.getExitCode());
        assertTrue(controller.shouldLaunchApplication());
    }

    @Test
    void rejectsUnknownShortOption() throws IOException {
        Path video = createVideoFile("video.mp4");

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-q",
                        video.toString()
                });

        assertEquals(
                "Unknown option: -q",
                controller.getErrorMessage());

        assertEquals(1, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void rejectsUnknownLongOption() throws IOException {
        Path video = createVideoFile("video.mp4");

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "--unknown-effect",
                        video.toString()
                });

        assertEquals(
                "Unknown option: --unknown-effect",
                controller.getErrorMessage());

        assertEquals(1, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void rejectsMissingVideoFile() {
        Path missingVideo =
                tempDir.resolve("missing.mp4");

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-n",
                        missingVideo.toString()
                });

        assertEquals(
                "File not found: " + missingVideo,
                controller.getErrorMessage());

        assertNull(controller.getVideoFile());
        assertEquals(1, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void rejectsMultipleVideoFiles() throws IOException {
        Path firstVideo =
                createVideoFile("first.mp4");

        Path secondVideo =
                createVideoFile("second.mp4");

        CommandLineController controller =
                new CommandLineController(new String[] {
                        firstVideo.toString(),
                        secondVideo.toString()
                });

        assertEquals(
                "Usage: VideoPlayer [options] [video-file]",
                controller.getErrorMessage());

        assertEquals(1, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void helpOverridesInvalidArguments() {
        CommandLineController controller =
                new CommandLineController(new String[] {
                        "--illegal-argument",
                        "movie.mp4",
                        "--help"
                });

        assertEquals(0, controller.getExitCode());
        assertNull(controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    private Path createVideoFile(String fileName)
            throws IOException {

        return Files.createFile(
                tempDir.resolve(fileName));
    }
}