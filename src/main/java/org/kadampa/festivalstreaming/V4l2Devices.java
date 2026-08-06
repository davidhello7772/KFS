package org.kadampa.festivalstreaming;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.kadampa.festivalstreaming.AvFoundationDevices.CaptureMode;

/**
 * Reads the Linux cameras straight from ffmpeg's Video4Linux2 input, the same way
 * {@link AvFoundationDevices} does on macOS.
 * <p>
 * A camera appears as several {@code /dev/video} nodes, and only one of them captures: the
 * others carry metadata and answer every format query with an error, which is how they are told
 * apart here. Device descriptions are not unique either — the capture and metadata nodes of one
 * camera share theirs — so the picker shows "Description [/dev/videoN]" and the path is what is
 * handed back to ffmpeg.
 * <p>
 * OBS Virtual Camera is such a device too (a v4l2loopback node), with the same behaviour as its
 * macOS counterpart: while OBS is not emitting, the node lists no formats, and the interface asks
 * the user to start the virtual camera first.
 * <p>
 * A note for future slideshow debugging: v4l2loopback 0.15.3 and earlier had an attach-time race
 * that could hand one capture session a poisoned start — OBS pushed frames normally, yet the
 * reader received one only every few seconds, for the whole life of that session. ffmpeg padded
 * the gaps ({@code dup=} climbing in its status line) and the stream played as a slideshow while
 * every diagnostic elsewhere looked healthy. Seen live on 2026-08-05, typically on the first
 * capture after a reboot, and cured by upgrading the module to 0.15.4 (buffer-mapping ordering
 * and locking fixes). If it ever returns: check {@code modinfo v4l2loopback}, and know that
 * capturing and discarding a few frames right before the real capture absorbs a poisoned
 * session — a warm-up doing exactly that was used here while the fix was verified, then removed.
 */
public final class V4l2Devices {

    /** A line of {@code ffmpeg -sources v4l2}: {@code   /dev/videoN [description] (media types)}. */
    private static final Pattern DEVICE_LINE = Pattern.compile("^[*\\s]\\s+(/dev/video\\d+)\\s+\\[(.+)]");
    /** The path put at the end of a picker entry by {@link #videoDeviceNames}. */
    private static final Pattern DISPLAYED_PATH = Pattern.compile("\\[(/dev/video\\d+)]\\s*$");
    /** A line of {@code -list_formats all}: {@code Raw : yuyv422 : YUYV 4:2:2 : 1280x720 ...}. */
    private static final Pattern FORMAT_LINE = Pattern.compile("(Raw|Compressed)\\s*:\\s*(\\S+)\\s*:");
    /** The frame sizes at the end of a format line. The descriptions never contain WxH. */
    private static final Pattern SIZE = Pattern.compile("(\\d+)x(\\d+)");

    /**
     * Input formats in the order we want them, best first. NV12 and yuv420p arrive already in the
     * chroma layout the encoder wants — v4l2loopback relays whatever OBS produces, usually one of
     * these. MJPEG is what real webcams need to deliver their full frame rate over USB2, and it
     * decodes cheaply at these sizes; the 4:2:2 formats need a chroma resample on the way in.
     */
    private static final List<String> PREFERRED_FORMATS = List.of("nv12", "yuv420p", "mjpeg", "yuyv422", "uyvy422");

    /**
     * Offered when a device reports formats but no discrete sizes, which is what drivers
     * advertising continuous size ranges do. Any size within the range is accepted, so the
     * usual output sizes are as good a list as any.
     */
    private static final List<String> FALLBACK_SIZES = List.of("1920x1080", "1280x720", "640x480");

    private V4l2Devices() {
    }

    /**
     * The cameras able to capture right now, smallest device number first, as
     * {@code "Description [/dev/videoN]"} picker entries.
     */
    public static List<String> videoDeviceNames(String ffmpegPath) {
        List<String> output = Host.runFfmpeg(ffmpegPath, "-sources", "v4l2");
        Map<Integer, String> names = new TreeMap<>();
        for (String line : output) {
            Matcher matcher = DEVICE_LINE.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String path = matcher.group(1);
            if (listFormats(ffmpegPath, path).isEmpty()) {
                continue;  // a metadata node, or a device gone since the listing
            }
            names.put(Integer.parseInt(path.substring("/dev/video".length())),
                    matcher.group(2) + " [" + path + "]");
        }
        return new ArrayList<>(names.values());
    }

    /** The {@code /dev/videoN} path inside a picker entry, or null when there is none. */
    public static String devicePath(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        Matcher matcher = DISPLAYED_PATH.matcher(displayName);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // A settings file written by hand may name the device by its path alone
        return displayName.trim().startsWith("/dev/") ? displayName.trim() : null;
    }

    /**
     * The modes to offer for a video device, largest first. The driver reports sizes but not
     * rates, and clamps a requested rate to the nearest one it supports rather than refusing,
     * so every size is offered at the configured frame rate. Empty when the device cannot be
     * queried, which is what OBS Virtual Camera does while OBS is not emitting.
     */
    public static List<CaptureMode> supportedModes(String ffmpegPath, String displayName, int frameRate) {
        Set<CaptureMode> modes = new LinkedHashSet<>();
        List<String> formatLines = listFormats(ffmpegPath, devicePath(displayName));
        for (String line : formatLines) {
            // The log prefix "[in#0 @ 0x5e3b...]" would otherwise read as a 0x5 frame size
            Matcher size = SIZE.matcher(line.replaceFirst("^\\[[^]]*]\\s*", ""));
            while (size.find()) {
                modes.add(new CaptureMode(Integer.parseInt(size.group(1)),
                        Integer.parseInt(size.group(2)), frameRate));
            }
        }
        if (modes.isEmpty() && !formatLines.isEmpty()) {
            for (String fallback : FALLBACK_SIZES) {
                modes.add(new CaptureMode(Integer.parseInt(fallback.split("x")[0]),
                        Integer.parseInt(fallback.split("x")[1]), frameRate));
            }
        }
        List<CaptureMode> sorted = new ArrayList<>(modes);
        sorted.sort(Comparator.comparingInt(CaptureMode::width)
                .thenComparingInt(CaptureMode::height)
                .reversed());
        return sorted;
    }

    /**
     * The input format to open a video device with, handed to ffmpeg as {@code -input_format}.
     * Null when the device reports nothing, in which case ffmpeg negotiates its own default.
     */
    public static String bestPixelFormat(String ffmpegPath, String displayName) {
        Set<String> supported = new LinkedHashSet<>();
        for (String line : listFormats(ffmpegPath, devicePath(displayName))) {
            Matcher matcher = FORMAT_LINE.matcher(line);
            if (matcher.find()) {
                supported.add(matcher.group(2));
            }
        }
        if (supported.isEmpty()) {
            return null;
        }
        for (String preferred : PREFERRED_FORMATS) {
            if (supported.contains(preferred)) {
                return preferred;
            }
        }
        return supported.iterator().next();
    }

    /** The format lines a device answers, empty for metadata nodes and absent or idle devices. */
    private static List<String> listFormats(String ffmpegPath, String path) {
        if (path == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : Host.runFfmpeg(ffmpegPath, "-f", "v4l2", "-list_formats", "all", "-i", path)) {
            if (FORMAT_LINE.matcher(line).find()) {
                lines.add(line);
            }
        }
        return lines;
    }
}
