package org.kadampa.festivalstreaming;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the macOS capture devices straight from ffmpeg.
 * <p>
 * The Windows build lists cameras through the webcam-capture library, but that has no usable
 * macOS driver and its names do not always match what ffmpeg wants. Asking ffmpeg itself gives
 * names that can be handed back to it verbatim.
 * <p>
 * AVFoundation also refuses any capture mode it did not advertise, so unlike DirectShow the
 * video input has to be opened with an explicit {@code -framerate} and {@code -video_size}.
 * {@link #supportedModes} reports what a device offers.
 */
public final class AvFoundationDevices {

    private static final Pattern DEVICE_LINE = Pattern.compile("]\\s*\\[(\\d+)]\\s(.*)$");
    private static final Pattern MODE_LINE = Pattern.compile("(\\d+)x(\\d+)@\\[([\\d.]+)\\s+([\\d.]+)]fps");
    private static final String VIDEO_HEADER = "AVFoundation video devices:";
    private static final String AUDIO_HEADER = "AVFoundation audio devices:";

    /** A framerate no real device offers, so ffmpeg prints the mode list instead of capturing. */
    private static final String IMPOSSIBLE_FRAMERATE = "1";
    /**
     * ffmpeg's own default input pixel format. Probing with it means a device that accepts it
     * needs nothing said about it, and one that does not answers with the formats it does offer.
     * A format AVFoundation itself does not know is rejected before the device is ever asked.
     */
    private static final String DEFAULT_PIXEL_FORMAT = "yuv420p";
    private static final Pattern PIXEL_FORMAT_LINE = Pattern.compile("]\\s{2,}([0-9a-z]+)\\s*$");

    /**
     * Input pixel formats in the order we want them, best first. NV12 is what a virtual camera
     * usually produces and is already the chroma layout the encoder wants, so it needs no
     * resampling on the way to yuv420p; the 4:2:2 formats do, and RGB needs a full conversion.
     */
    private static final List<String> PREFERRED_PIXEL_FORMATS = List.of("nv12", "uyvy422", "yuyv422", "0rgb");

    private AvFoundationDevices() {
    }

    /** One capture mode as AVFoundation reports it, e.g. {@code 1920x1080@30}. */
    public record CaptureMode(int width, int height, int frameRate) {

        public String videoSize() {
            return width + "x" + height;
        }

        @Override
        public String toString() {
            return videoSize() + "@" + frameRate;
        }

        public static CaptureMode parse(String text) {
            if (text == null) {
                return null;
            }
            Matcher matcher = Pattern.compile("(\\d+)x(\\d+)@(\\d+)").matcher(text.trim());
            if (!matcher.matches()) {
                return null;
            }
            return new CaptureMode(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        }
    }

    public static List<String> videoDeviceNames(String ffmpegPath) {
        return deviceNames(ffmpegPath, VIDEO_HEADER, AUDIO_HEADER);
    }

    public static List<String> audioDeviceNames(String ffmpegPath) {
        return deviceNames(ffmpegPath, AUDIO_HEADER, null);
    }

    /**
     * The modes a video device advertises, largest and fastest first. Empty when the device
     * cannot be queried, which is what a virtual camera does while its host application is not
     * running.
     */
    public static List<CaptureMode> supportedModes(String ffmpegPath, String deviceName) {
        if (deviceName == null || deviceName.isBlank()) {
            return List.of();
        }
        List<String> output = Host.runFfmpeg(ffmpegPath,
                "-f", "avfoundation",
                "-framerate", IMPOSSIBLE_FRAMERATE,
                "-i", deviceName + ":",
                "-t", "0.1",
                "-f", "null", "-");
        Set<CaptureMode> modes = new LinkedHashSet<>();
        for (String line : output) {
            Matcher matcher = MODE_LINE.matcher(line);
            while (matcher.find()) {
                int width = Integer.parseInt(matcher.group(1));
                int height = Integer.parseInt(matcher.group(2));
                // The two numbers are the range ends; devices report a single rate twice.
                modes.add(new CaptureMode(width, height, (int) Math.round(Double.parseDouble(matcher.group(4)))));
            }
        }
        List<CaptureMode> sorted = new ArrayList<>(modes);
        sorted.sort(Comparator.comparingInt(CaptureMode::width)
                .thenComparingInt(CaptureMode::height)
                .thenComparingInt(CaptureMode::frameRate)
                .reversed());
        return sorted;
    }

    /**
     * Picks the mode to capture with: the requested frame rate at the smallest resolution that
     * still covers the output, falling back to the largest mode at that frame rate and finally
     * to the device's best mode.
     */
    public static CaptureMode bestMode(List<CaptureMode> modes, int frameRate, int outputWidth, int outputHeight) {
        if (modes.isEmpty()) {
            return null;
        }
        List<CaptureMode> atRate = modes.stream().filter(m -> m.frameRate() == frameRate).toList();
        List<CaptureMode> candidates = atRate.isEmpty() ? modes : atRate;
        return candidates.stream()
                .filter(m -> m.width() >= outputWidth && m.height() >= outputHeight)
                .min(Comparator.comparingLong(m -> (long) m.width() * m.height()))
                .orElse(candidates.get(0));
    }

    /**
     * The input pixel format to open a video device with.
     * <p>
     * AVFoundation accepts only the formats the device actually produces, and ffmpeg's default
     * for this input is yuv420p, which cameras rarely offer: OBS Virtual Camera, for one, refuses
     * to open with it. The format chosen here is only how the frames arrive; what the recording
     * is encoded in is a separate setting and is unaffected.
     *
     * @param mode a mode the device supports, needed because the frame rate is checked first
     * @return the format to pass, or null to let ffmpeg try its own default
     */
    public static String bestPixelFormat(String ffmpegPath, String deviceName, CaptureMode mode) {
        if (deviceName == null || deviceName.isBlank() || mode == null) {
            return null;
        }
        List<String> output = Host.runFfmpeg(ffmpegPath,
                "-f", "avfoundation",
                "-pixel_format", DEFAULT_PIXEL_FORMAT,
                "-framerate", String.valueOf(mode.frameRate()),
                "-video_size", mode.videoSize(),
                "-i", deviceName + ":",
                "-t", "0.1",
                "-f", "null", "-");
        Set<String> supported = new LinkedHashSet<>();
        boolean inSection = false;
        for (String line : output) {
            if (line.contains("Supported pixel formats:")) {
                inSection = true;
                continue;
            }
            if (!inSection) {
                continue;
            }
            Matcher matcher = PIXEL_FORMAT_LINE.matcher(line);
            if (matcher.find()) {
                supported.add(matcher.group(1));
            } else {
                break;
            }
        }
        if (supported.isEmpty()) {
            return null;
        }
        for (String preferred : PREFERRED_PIXEL_FORMATS) {
            if (supported.contains(preferred)) {
                return preferred;
            }
        }
        return supported.iterator().next();
    }

    private static List<String> deviceNames(String ffmpegPath, String header, String stopHeader) {
        List<String> output = Host.runFfmpeg(ffmpegPath,
                "-f", "avfoundation", "-list_devices", "true", "-i", "");
        List<String> names = new ArrayList<>();
        boolean inSection = false;
        for (String line : output) {
            if (line.contains(header)) {
                inSection = true;
                continue;
            }
            if (inSection && stopHeader != null && line.contains(stopHeader)) {
                break;
            }
            if (!inSection) {
                continue;
            }
            Matcher matcher = DEVICE_LINE.matcher(line);
            if (matcher.find()) {
                names.add(matcher.group(2));
            } else if (line.contains("Error") || line.contains("error")) {
                break;
            }
        }
        return names;
    }
}
