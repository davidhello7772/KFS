package org.kadampa.festivalstreaming.linux;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.kadampa.festivalstreaming.Host;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The GNOME counterpart of the Windows taskbar blink. The dock draws the icon of the
 * matched .desktop entry and ignores whatever the stage icons do, so the only way to wave
 * from the taskbar is the window manager's demands-attention state, which the shell shows
 * as a highlighted dock icon until the operator focuses the window. JavaFX has no urgency
 * API, so the state is toggled through wmctrl - or xdotool as a stand-in - addressing the
 * window by its title. Both reach the window fine under Wayland, because JavaFX always
 * runs on XWayland.
 */
public final class WindowAttention {

    private static final Logger logger = LoggerFactory.getLogger(WindowAttention.class);

    // A single worker so the FX thread never waits on a spawned process, and so a request
    // and a clear issued in quick succession cannot land out of order
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "window-attention");
        thread.setDaemon(true);
        return thread;
    });

    /** The tool found on this machine; null until probed, empty when there is none. */
    private static String tool;

    private WindowAttention() {
    }

    /** Marks the window whose title contains the given text as demanding attention. */
    public static void request(String titleSubstring) {
        toggle(titleSubstring, true);
    }

    /** Takes the demand back, as when the operator stopped the stream on purpose. */
    public static void clear(String titleSubstring) {
        toggle(titleSubstring, false);
    }

    private static void toggle(String titleSubstring, boolean on) {
        if (!Host.isLinux()) {
            return;
        }
        worker.submit(() -> {
            switch (resolveTool()) {
                case "wmctrl" -> Host.runCommand(List.of("wmctrl", "-r", titleSubstring,
                        "-b", (on ? "add" : "remove") + ",demands_attention"));
                case "xdotool" -> Host.runCommand(List.of("xdotool", "search", "--name",
                        "^" + titleSubstring, "set_window", "--urgency", on ? "1" : "0"));
                default -> { /* neither tool is installed; already warned once */ }
            }
        });
    }

    /**
     * Probed on first use, on the worker thread, so a slow probe cannot stall the GUI.
     *
     * <p>Whether the tool exists is settled by looking for it, and only a tool that exists is then
     * asked whether it works here - a window manager that answers wmctrl is not a given. Probing by
     * running it regardless put two stack traces in the log of every machine that has neither,
     * which is every machine that has never needed the dock to wave.
     */
    private static String resolveTool() {
        if (tool == null) {
            if (Host.isCommandAvailable("wmctrl") && !Host.runCommand(List.of("wmctrl", "-m")).isEmpty()) {
                tool = "wmctrl";
            } else if (Host.isCommandAvailable("xdotool")
                    && !Host.runCommand(List.of("xdotool", "version")).isEmpty()) {
                tool = "xdotool";
            } else {
                tool = "";
                logger.warn("Neither wmctrl nor xdotool is installed, so the dock icon "
                        + "cannot signal attention; install one with: sudo apt install wmctrl");
            }
        }
        return tool;
    }
}
