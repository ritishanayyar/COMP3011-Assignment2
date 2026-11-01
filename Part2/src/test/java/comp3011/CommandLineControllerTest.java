package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

public class CommandLineControllerTest {

    @Test
    public void testHelp() {
        CommandLineController controller =
                new CommandLineController(new String[]{"-h"});
        assertEquals(0, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
    }
    @Test
    public void testHelpWithVideoFile() throws Exception {
        File video = File.createTempFile("movie", ".mp4");

        CommandLineController controller = new CommandLineController(
                new String[]{"--help", video.getPath()});
        assertEquals(0, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());

        video.delete();
    }
    @Test
    public void testHelpAfterBadOption() {
        CommandLineController controller = new CommandLineController(
                new String[]{"--illegal-argument", "movie.mp4", "--help"});
        assertEquals(0, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    public void testNoArguments() {
        CommandLineController controller =
                new CommandLineController(new String[]{});
        assertEquals(1, controller.getExitCode());
    }

    @Test
    public void testUnknownLongOption() {
        CommandLineController controller =
                new CommandLineController(new String[]{"--wrong-option"});
        assertEquals(1, controller.getExitCode());
    }

    @Test
    public void testUnknownShortOption() throws Exception {
        File video = File.createTempFile("movie", ".mp4");

        CommandLineController controller = new CommandLineController(
                new String[]{"-sfbnz", video.getPath()});

        assertEquals(1, controller.getExitCode());
        video.delete();
    }
    @Test
    public void testFileNotFound() {
        CommandLineController controller =
                new CommandLineController(new String[]{"missing.mp4"});
        assertEquals(1, controller.getExitCode());
    }

    @Test
    public void testValidVideoFile() throws Exception {
        File video = File.createTempFile("movie", ".mp4");
        CommandLineController controller = new CommandLineController(
                new String[]{video.getPath()});

        assertEquals(0, controller.getExitCode());
        assertTrue(controller.shouldLaunchApplication());

        video.delete();
    }

    @Test
    public void testFrameEffects() throws Exception {
        File video = File.createTempFile("movie", ".mp4");

        CommandLineController controller = new CommandLineController(
                new String[]{"-ns", video.getPath()});

        assertEquals(List.of("n", "s"), controller.getFrameEffects());

        video.delete();
    }

    @Test
    public void testEffectOrderAndRepeats() throws Exception {
        // Scratches twice and numbers twice, as in the help text.
        File video = File.createTempFile("movie", ".mp4");

        CommandLineController controller = new CommandLineController(
                new String[]{"-nssnfwyvdjmbp", video.getPath()});

        assertEquals(
                List.of("n", "s", "s", "n", "f", "w", "y",
                        "v", "d", "j", "m", "b", "p"),
                controller.getFrameEffects());

        video.delete();
    }

    @Test
    public void testLongAndShortOptionsAreTheSame() throws Exception {
        File video = File.createTempFile("movie", ".mp4");
        String path = video.getPath();

        List<String> longForm = new CommandLineController(new String[]{
                "--jitter-frames", "--flicker-frames",
                "--dust-frames", "--dust-frames",
                "--number-frames", path
        }).getFrameEffects();

        List<String> shortForm = new CommandLineController(new String[]{
                "-j", "-f", "-d", "-d", "-n", path
        }).getFrameEffects();

        List<String> stacked = new CommandLineController(new String[]{
                "-jfddn", path
        }).getFrameEffects();

        assertEquals(longForm, shortForm);
        assertEquals(longForm, stacked);
        video.delete();
    }
    @Test
    public void testLaunchOptions() throws Exception {
        File video = File.createTempFile("movie", ".mp4");

        CommandLineController controller = new CommandLineController(
                new String[]{"-a", "-x", "--monitor-2", video.getPath()});
        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
        assertEquals(2, controller.getDisplayId());
        video.delete();
    }
}