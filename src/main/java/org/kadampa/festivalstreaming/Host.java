package org.kadampa.festivalstreaming;

import org.kadampa.festivalstreaming.linux.PulseAudioDevices;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The handful of things that genuinely differ between the Windows streaming machine, a Mac and a
 * Linux box: which ffmpeg capture device is used, where ffmpeg lives, and where we may write
 * files. Everything else in the application stays platform neutral.
 */
public final class Host {

    private static final Logger logger = LoggerFactory.getLogger(Host.class);

    private static final String OS_NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private static final Pattern ENCODER_LINE = Pattern.compile("^\\s*V[.A-Z]{5}\\s+(\\S+)");

    /**
     * A capture device can sit there without ever answering, and these queries run while the user
     * waits on the interface, so they are given a short deadline. Everything they need is printed
     * as the device opens, long before this, and whatever arrived by then is used.
     */
    private static final int QUERY_TIMEOUT_SECONDS = 4;

    /**
     * The H.264 encoders offered in the settings, best first, per platform. An x264 entry may
     * carry a preset after a space: at a fixed bitrate a slower preset trades processor time
     * directly for picture quality. The ladder stops at slow, which still encoded twice real
     * time at 720p on the streaming laptop; on Windows the entries stay bare because that
     * machine's command is deliberately left untouched and a preset would silently not apply.
     * <p>
     * x264 leads on the Mac: measured on real festival footage at 2500kbps it encodes about
     * 1.2dB better than VideoToolbox, which on this hardware needs roughly half as much bitrate
     * again to match it. VideoToolbox stays available for a machine short of processor time.
     */
    private static final List<String> X264_PRESET_OPTIONS = List.of(
            "libx264 veryfast", "libx264 faster", "libx264 fast", "libx264 medium", "libx264 slow");
    private static final List<String> MAC_VIDEO_ENCODERS = concat(X264_PRESET_OPTIONS, "h264_videotoolbox");
    private static final List<String> WINDOWS_VIDEO_ENCODERS = List.of("libx264", "h264_nvenc");
    /**
     * QSV takes ordinary frames and uploads them itself, so it works with the existing filter
     * graph wherever the Intel driver stack is complete; like NVENC on Windows it is offered and
     * fails at start when the hardware or drivers are missing. VAAPI is deliberately not offered:
     * it only accepts frames already on the GPU, which the filter graph does not produce.
     */
    private static final List<String> LINUX_VIDEO_ENCODERS = concat(X264_PRESET_OPTIONS, "h264_qsv", "h264_nvenc");

    private static List<String> concat(List<String> head, String... tail) {
        List<String> all = new ArrayList<>(head);
        all.addAll(List.of(tail));
        return List.copyOf(all);
    }

    /** The codec inside an encoder option: {@code "libx264 fast"} names libx264. */
    public static String encoderCodec(String option) {
        if (option == null) {
            return null;
        }
        String trimmed = option.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    /** The preset inside an encoder option, or null when it carries none. */
    public static String encoderPreset(String option) {
        if (option == null) {
            return null;
        }
        String trimmed = option.trim();
        int space = trimmed.indexOf(' ');
        return space < 0 ? null : trimmed.substring(space + 1).trim();
    }

    /** ffmpeg is expected on the PATH, but a GUI launch on macOS does not inherit the shell PATH. */
    private static final List<String> MAC_FFMPEG_FALLBACKS = List.of(
            "/opt/homebrew/bin/ffmpeg",  // Apple Silicon Homebrew
            "/usr/local/bin/ffmpeg",     // Intel Homebrew
            "/opt/local/bin/ffmpeg");    // MacPorts

    /**
     * The same for a Linux desktop launch, whose PATH is not the shell's either. Newest-first:
     * a hand-installed build outranks the distribution package, which the bare PATH fallback
     * finds on its own and which would otherwise shadow it.
     */
    private static final List<String> LINUX_FFMPEG_FALLBACKS = List.of(
            System.getProperty("user.home", "") + "/.local/bin/ffmpeg",  // hand-installed build
            "/usr/local/bin/ffmpeg",     // built from source
            "/snap/bin/ffmpeg");         // snap package

    private Host() {
    }

    public static boolean isMac() {
        return OS_NAME.contains("mac");
    }

    public static boolean isWindows() {
        return OS_NAME.contains("win");
    }

    public static boolean isLinux() {
        return OS_NAME.contains("linux");
    }

    /**
     * The ffmpeg video capture device: DirectShow on Windows, AVFoundation on macOS, Video4Linux2
     * on Linux. All are compiled into the standard ffmpeg builds, so no probing is needed. On
     * Windows the same DirectShow input also captures the audio; Linux audio goes through the
     * sound server instead: see {@link PulseAudioDevices}.
     */
    public static String captureFormat() {
        if (isMac()) {
            return "avfoundation";
        }
        return isLinux() ? "v4l2" : "dshow";
    }

    /**
     * A writable per-user directory for the files the application unpacks for itself.
     * Deliberately hidden and space free so it is safe to inline into an ffmpeg filter string.
     * <p>
     * An explicit folder wins over the default, which is how one machine runs several
     * independent configurations: a launcher passes {@code -Dkfs.dataDir=<folder>} and that
     * copy keeps its settings, and unpacks its noise models, in there alone. A path with a
     * space in it would break the filter strings the models are inlined into, so a
     * configured folder must not contain one.
     */
    public static File userDataDir() {
        String configured = System.getProperty("kfs.dataDir");
        if (configured != null && !configured.isBlank()) {
            return new File(configured);
        }
        String home = System.getProperty("user.home", ".");
        if (isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            File base = (localAppData == null || localAppData.isBlank()) ? new File(home) : new File(localAppData);
            return new File(base, "KadampaFestivalStreaming");
        }
        return new File(home, ".kfs");
    }

    /** Where recordings go when the configured directory does not exist on this machine. */
    public static String defaultOutputDirectory() {
        String home = System.getProperty("user.home", ".");
        return isMac() ? new File(home, "Movies").getPath() : new File(home, "Videos").getPath();
    }

    /**
     * Resolves the ffmpeg binary. An explicit setting wins; otherwise we rely on the PATH and
     * fall back to the usual install locations, which is what makes the app work when it is
     * started from Finder rather than from a terminal.
     *
     * @return the command to hand to ProcessBuilder, never null
     */
    public static String ffmpegExecutable(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return configuredPath.trim();
        }
        List<String> fallbacks = isMac() ? MAC_FFMPEG_FALLBACKS
                : isLinux() ? LINUX_FFMPEG_FALLBACKS
                : List.of();
        for (String candidate : fallbacks) {
            if (new File(candidate).canExecute()) {
                return candidate;
            }
        }
        return "ffmpeg";
    }

    /**
     * Whether the resolved ffmpeg can actually be started, so the GUI can say so before the
     * user presses Start instead of failing with a bare IOException.
     */
    public static boolean isFfmpegAvailable(String configuredPath) {
        return !runFfmpeg(configuredPath, "-version").isEmpty();
    }

    /**
     * The video encoders to offer, in preference order, keeping only those this ffmpeg build
     * actually has. Homebrew and distribution builds vary, and NVENC is impossible on a Mac.
     */
    public static List<String> videoEncoders(String configuredPath) {
        List<String> preferred = isMac() ? MAC_VIDEO_ENCODERS
                : isLinux() ? LINUX_VIDEO_ENCODERS
                : WINDOWS_VIDEO_ENCODERS;
        Set<String> available = availableVideoEncoders(configuredPath);
        if (available.isEmpty()) {
            return preferred;  // ffmpeg could not be queried; offer the list and let it fail loudly
        }
        List<String> result = new ArrayList<>(preferred.stream()
                .filter(option -> available.contains(encoderCodec(option))).toList());
        return result.isEmpty() ? preferred : result;
    }

    private static Set<String> availableVideoEncoders(String configuredPath) {
        Set<String> encoders = new LinkedHashSet<>();
        for (String line : runFfmpeg(configuredPath, "-encoders")) {
            Matcher matcher = ENCODER_LINE.matcher(line);
            if (matcher.find()) {
                encoders.add(matcher.group(1));
            }
        }
        return encoders;
    }

    /**
     * Runs ffmpeg and returns its merged output. Used for the queries whose answer ffmpeg writes
     * to stderr and which end in an expected non-zero exit, so the exit code is not checked.
     */
    public static List<String> runFfmpeg(String configuredPath, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegExecutable(configuredPath));
        command.add("-hide_banner");
        command.addAll(List.of(arguments));
        return runCommand(command);
    }

    /**
     * Runs any short query command with the same deadline and drain safety as the ffmpeg
     * queries, and an empty answer instead of an exception when the program is not there.
     */
    /**
     * Whether a program can be run, worked out without running it.
     * <p>
     * "Not installed" is a perfectly ordinary answer here - the dock-attention tools are optional,
     * pw-record only exists on a PipeWire machine, and ffmpeg may be somewhere the settings do not
     * say. Finding that out by spawning the program turns the answer into an IOException and a
     * stack trace in the log, which buries the failures that do deserve one. A bare name is looked
     * for along the PATH; anything carrying a separator is taken as the path it already is.
     */
    public static boolean isCommandAvailable(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        try {
            if (command.contains("/") || command.contains(File.separator)) {
                return Files.isExecutable(Path.of(command));
            }
            String searchPath = System.getenv("PATH");
            if (searchPath == null) {
                return false;
            }
            for (String entry : searchPath.split(File.pathSeparator)) {
                if (entry.isBlank()) {
                    continue;
                }
                if (Files.isExecutable(Path.of(entry, command))) {
                    return true;
                }
                // The PATH carries no extension on Windows, where the executable has one
                if (isWindows()) {
                    for (String extension : List.of(".exe", ".bat", ".cmd")) {
                        if (Files.isExecutable(Path.of(entry, command + extension))) {
                            return true;
                        }
                    }
                }
            }
        } catch (InvalidPathException e) {
            // A PATH entry this platform cannot even name is not where the program is
            return false;
        }
        return false;
    }

    public static List<String> runCommand(List<String> command) {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // The output is drained on its own thread so a device that never answers cannot
            // block the caller: these queries run while the user is waiting on the interface
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (IOException ignored) {
                    // the process was killed; whatever was read already is what we use
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("No answer within {}s, giving up on: {}",
                        QUERY_TIMEOUT_SECONDS, String.join(" ", command));
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
            reader.join(1000);
        } catch (IOException e) {
            // A missing program is an answer, not a failure: it is how the optional tools and a
            // mis-set ffmpeg path both arrive, and every one of them used to land in the log as a
            // stack trace. Anything else that stops a program starting still gets one.
            if (isCommandAvailable(command.get(0))) {
                logger.error("Could not run {}", command.get(0), e);
            } else {
                logger.info("{} is not installed on this machine", command.get(0));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new ArrayList<>(lines);
    }
}
