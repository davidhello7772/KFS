package org.kadampa.festivalstreaming.linux;

import org.kadampa.festivalstreaming.Host;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the Linux audio capture devices from ffmpeg's pulse input, which PipeWire serves through
 * its PulseAudio interface on any current distribution.
 * <p>
 * Capture deliberately goes through the sound server rather than straight to ALSA. A device held
 * directly with {@code hw:}/{@code plughw:} does not merely refuse a second client: a concurrent
 * server capture of the same device connects and then delivers nothing, which in this application
 * would freeze the whole audio mix with no error anywhere. Behind the server every client shares
 * every device, and the server resamples each stream on its own, so USB interfaces running at
 * different native rates all arrive at whatever rate the stream asks for.
 * <p>
 * The user sees, and the settings store, a device's description ("Qu-5 Multicanal"); ffmpeg wants
 * the source name ({@code alsa_input.usb-...}). This class enumerates and translates. Channel
 * counts come from pw-dump, because the numbers Java Sound reports through the server's ALSA
 * compatibility device are ranges of what the server could convert, not what the hardware has.
 */
public final class PulseAudioDevices {

    private static final Logger logger = LoggerFactory.getLogger(PulseAudioDevices.class);

    /** A line of {@code ffmpeg -sources pulse}: {@code * name [description] (media types)}. */
    private static final Pattern SOURCE_LINE = Pattern.compile("^([*\\s])\\s+(\\S+)\\s+\\[(.+)]");

    /** One capture source: the name ffmpeg wants, the description the user sees. */
    public record Source(String name, String description, boolean isDefault, int channels,
                         String channelMap) {
    }

    /** What pw-dump says about one node: how many channels, and what it calls each of them. */
    private record NodeAudio(int channels, String positions) {
    }

    private static volatile List<Source> cached = List.of();

    private PulseAudioDevices() {
    }

    /**
     * The capture sources currently present, re-read from ffmpeg. Monitors of playback devices
     * are left out: they are the sound the machine itself is playing, not a microphone, and every
     * output would otherwise appear in the list twice.
     */
    public static List<Source> sources(String ffmpegPath) {
        List<String> output = Host.runFfmpeg(ffmpegPath, "-sources", "pulse");
        List<Source> sources = new ArrayList<>();
        Map<String, NodeAudio> nodesByName = nodesFromPipeWire();
        Map<String, Integer> seenDescriptions = new HashMap<>();
        for (String line : output) {
            Matcher matcher = SOURCE_LINE.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            String name = matcher.group(2);
            if (name.endsWith(".monitor")) {
                continue;
            }
            // Two identical interfaces carry the same description; number the later ones so
            // the picker, the settings file and the resolution here all mean the same device
            String description = matcher.group(3);
            int occurrence = seenDescriptions.merge(description, 1, Integer::sum);
            if (occurrence > 1) {
                description = description + " (" + occurrence + ")";
            }
            NodeAudio node = nodesByName.get(name);
            sources.add(new Source(name, description, "*".equals(matcher.group(1)),
                    node != null ? node.channels() : 0,
                    node != null ? node.positions() : null));
        }
        cached = List.copyOf(sources);
        return cached;
    }

    /** The descriptions to offer in a device picker, in the order ffmpeg reports them. */
    public static List<String> descriptions(String ffmpegPath) {
        return sources(ffmpegPath).stream().map(Source::description).toList();
    }

    /**
     * The source name to hand ffmpeg for a picked description, or null when no such device is
     * present any more.
     */
    public static String resolveName(String ffmpegPath, String description) {
        Source source = find(ffmpegPath, description);
        return source != null ? source.name() : null;
    }

    /** Whether the description still belongs to a present device. */
    public static boolean exists(String ffmpegPath, String description) {
        return find(ffmpegPath, description) != null;
    }

    /** The device's real channel count, or 0 when pw-dump could not say. */
    public static int channelCount(String ffmpegPath, String description) {
        Source source = find(ffmpegPath, description);
        return source != null ? source.channels() : 0;
    }

    /**
     * The device's own channel labels, comma separated for pw-record's {@code --channel-map},
     * or null when pw-dump could not say.
     * <p>
     * The server routes a stream's channels to the device's by label, not by position. Left to
     * its default map a 32-channel stream leads with the surround labels (FL, FR, FC...) while
     * a mixer labels its channels AUX0 onward, so every channel lands a dozen places away from
     * where the hardware has it. Asking with the device's own labels makes the routing the
     * identity (verified live against the Qu-5: without this, its channel 1 arrived as 13).
     */
    public static String channelMap(String ffmpegPath, String description) {
        Source source = find(ffmpegPath, description);
        return source != null ? source.channelMap() : null;
    }

    private static Source find(String ffmpegPath, String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        Source source = findInCache(description);
        if (source == null) {
            // The device may have been plugged in since the list was last read
            sources(ffmpegPath);
            source = findInCache(description);
        }
        return source;
    }

    private static Source findInCache(String description) {
        for (Source source : cached) {
            if (source.description().equals(description)) {
                return source;
            }
        }
        return null;
    }

    /**
     * Each source's channel count and channel labels by source name, from pw-dump. PipeWire
     * names its nodes exactly as its PulseAudio interface names its sources, so the two listings
     * join on the name. An empty map when pw-dump is missing or its output cannot be read; the
     * callers fall back.
     */
    private static Map<String, NodeAudio> nodesFromPipeWire() {
        Map<String, NodeAudio> nodes = new HashMap<>();
        List<String> output = Host.runCommand(List.of("pw-dump"));
        if (output.isEmpty()) {
            return nodes;
        }
        try {
            JsonElement root = JsonParser.parseString(String.join("\n", output));
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject info = element.getAsJsonObject().getAsJsonObject("info");
                JsonObject props = info != null ? info.getAsJsonObject("props") : null;
                if (props == null || !props.has("node.name") || !props.has("audio.channels")) {
                    continue;
                }
                nodes.put(props.get("node.name").getAsString(),
                        new NodeAudio(props.get("audio.channels").getAsInt(),
                                channelPositions(props.get("audio.position"))));
            }
        } catch (RuntimeException e) {
            // Gson signals malformed or truncated output with unchecked exceptions
            logger.warn("Could not read the channel counts from pw-dump", e);
        }
        return nodes;
    }

    /** The channel labels as one comma-separated string; pw-dump writes either form. */
    private static String channelPositions(JsonElement position) {
        if (position == null) {
            return null;
        }
        if (position.isJsonArray()) {
            List<String> labels = new ArrayList<>();
            for (JsonElement label : position.getAsJsonArray()) {
                labels.add(label.getAsString());
            }
            return String.join(",", labels);
        }
        return position.getAsString();
    }
}
