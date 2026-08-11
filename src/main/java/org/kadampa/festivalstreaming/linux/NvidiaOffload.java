package org.kadampa.festivalstreaming.linux;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.kadampa.festivalstreaming.Host;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes the windows onto the NVIDIA card of a hybrid-graphics machine. JavaFX has no
 * Wayland backend, so it runs on XWayland and its ES2 pipeline reaches a GPU through GLX,
 * where NVIDIA's PRIME render offload is chosen by two environment variables that must
 * exist before the JVM loads any GL. A Java process cannot change its own environment,
 * so the only way to plant them is to start this very command again with them added,
 * wait, and pass the child's exit code through - the first process is only a supervisor,
 * and an offloaded session is therefore two java processes for one window.
 *
 * <p>The variables are only ever set on a genuine hybrid: on an NVIDIA-only box the
 * default GPU already is the NVIDIA, and asking for offload where no offload source
 * exists is the one arrangement that can fail to produce a context at all. Detection is
 * pure file reads - glxinfo is not installed on the streaming machine, and XWayland
 * answers {@code xrandr --listproviders} with nothing (measured), so neither is asked.
 */
public final class NvidiaOffload {

    private static final Logger logger = LoggerFactory.getLogger(NvidiaOffload.class);

    private static final String NVIDIA_PCI_VENDOR = "0x10de";
    private static final String ENV_OFFLOAD = "__NV_PRIME_RENDER_OFFLOAD";
    private static final String ENV_GLX_VENDOR = "__GLX_VENDOR_LIBRARY_NAME";
    /** Present in the child so it never relaunches itself in turn. */
    public static final String ENV_RELAUNCHED = "KFS_RENDER_RELAUNCHED";
    /** Present in the retry so the window can warn that offload failed this run. */
    public static final String ENV_FALLBACK = "KFS_RENDER_FALLBACK";

    /** A child that dies this quickly never survived toolkit init; a working session an
     *  operator ends on purpose does not end inside twenty seconds. */
    private static final long EARLY_FAILURE_WINDOW_MS = 20_000;

    private NvidiaOffload() {
    }

    /** Whether the NVIDIA kernel driver is loaded - the card alone is not enough, since
     *  offload without a driver leaves GLX nothing to hand the work to. */
    public static boolean isDriverLoaded() {
        return Host.isLinux() && Files.exists(Path.of("/proc/driver/nvidia/version"));
    }

    /**
     * Whether a non-NVIDIA GPU sits beside an NVIDIA one. Connector entries such as
     * card1-eDP-1 share the card prefix in /sys/class/drm and carry no vendor of their
     * own, so only names that are exactly cardN are read.
     */
    public static boolean isHybridGraphics() {
        boolean nvidia = false;
        boolean other = false;
        try (DirectoryStream<Path> cards = Files.newDirectoryStream(Path.of("/sys/class/drm"), "card*")) {
            for (Path card : cards) {
                if (!card.getFileName().toString().matches("card\\d+")) {
                    continue;
                }
                Path vendorFile = card.resolve("device/vendor");
                if (!Files.isReadable(vendorFile)) {
                    continue;
                }
                if (NVIDIA_PCI_VENDOR.equalsIgnoreCase(Files.readString(vendorFile).trim())) {
                    nvidia = true;
                } else {
                    other = true;
                }
            }
        } catch (IOException e) {
            logger.warn("Could not read /sys/class/drm, treating this machine as not hybrid: {}", e.toString());
            return false;
        }
        return nvidia && other;
    }

    /**
     * Starts this command again with the offload variables and supervises it, when the
     * choice wants the NVIDIA card and this process can still be redirected.
     *
     * @return the exit code to end this process with, or -1 to carry on and open the
     *         window in this process - which is also every failure's answer, because a
     *         render preference must never stop the application from starting
     */
    public static int relaunchIfNeeded(boolean nvidiaWanted) {
        if (!Host.isLinux() || !nvidiaWanted) {
            return -1;
        }
        if (System.getenv(ENV_RELAUNCHED) != null || System.getenv(ENV_FALLBACK) != null) {
            return -1; // this IS the relaunched process
        }
        if (System.getenv(ENV_OFFLOAD) != null) {
            return -1; // set by hand outside the app: already offloaded, honour it
        }
        if (!isDriverLoaded() || !isHybridGraphics()) {
            return -1; // nothing to gain, or the offload-without-a-source corner we avoid
        }
        List<String> command = currentCommand();
        if (command.isEmpty()) {
            logger.warn("Cannot read this process's own command line; the windows stay on the default GPU");
            return -1;
        }
        return superviseOffloadedChild(command);
    }

    /**
     * The exact argv this JVM was started with, from /proc via ProcessHandle. Built from
     * command() and arguments() rather than commandLine(), which flattens argv into one
     * space-joined string and would tear any argument carrying a space.
     */
    private static List<String> currentCommand() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        if (info.command().isEmpty() || info.arguments().isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>();
        command.add(info.command().get());
        command.addAll(List.of(info.arguments().get()));
        return command;
    }

    /**
     * Runs the offloaded child and, when it dies almost at once with an error, runs it
     * one more time without the offload - a driver that cannot create a context fails
     * inside seconds, and the festival must get a window either way. Exits 130 and 143
     * are Ctrl-C and SIGTERM: somebody ended the child on purpose, so they pass straight
     * through. 137 (SIGKILL) deliberately stays eligible for the retry - the cost of a
     * misread is one benign extra start, and kill -9 on the child is also how this very
     * path is tested by hand.
     */
    private static int superviseOffloadedChild(List<String> command) {
        long started = System.nanoTime();
        Process child = start(command, Map.of(ENV_OFFLOAD, "1", ENV_GLX_VENDOR, "nvidia", ENV_RELAUNCHED, "1"));
        if (child == null) {
            return -1; // could not even start a child: open the window here instead
        }
        int code = await(child);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        boolean deliberate = code == 0 || code == 130 || code == 143;
        if (deliberate || elapsedMs >= EARLY_FAILURE_WINDOW_MS) {
            return code;
        }
        logger.warn("NVIDIA-offloaded window exited with {} after {} ms; starting again on the default GPU",
                code, elapsedMs);
        Process retry = start(command, Map.of(ENV_RELAUNCHED, "1", ENV_FALLBACK, "1"));
        return retry == null ? code : await(retry);
    }

    private static Process start(List<String> command, Map<String, String> extraEnvironment) {
        // The working directory and the rest of the environment inherit, so the desktop
        // wrapper's cd into the data dir - which SettingsUtil's ./settings.ini fallback
        // relies on - carries into the child untouched
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        builder.environment().putAll(extraEnvironment);
        try {
            Process child = builder.start();
            // A SIGTERM to this supervisor must take the window with it, not orphan it
            Runtime.getRuntime().addShutdownHook(new Thread(child::destroy));
            return child;
        } catch (IOException e) {
            logger.error("Could not restart the application for NVIDIA rendering", e);
            return null;
        }
    }

    private static int await(Process child) {
        try {
            return child.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            child.destroy();
            return 130;
        }
    }

    /** What the process that ends up opening the windows should tell the operator about
     *  how they are drawn; empty when there is nothing worth a console line. */
    public static String statusForThisProcess(boolean nvidiaExplicit) {
        if (!Host.isLinux()) {
            return "";
        }
        if (System.getenv(ENV_FALLBACK) != null) {
            return "fallback";
        }
        if (System.getenv(ENV_OFFLOAD) != null) {
            return "offload"; // the relaunched child, or an operator who exported it by hand
        }
        if (nvidiaExplicit && !isDriverLoaded()) {
            return "nvidia-missing";
        }
        if (nvidiaExplicit && !isHybridGraphics()) {
            return "nvidia-native"; // the default GPU already is the NVIDIA
        }
        return "";
    }
}
