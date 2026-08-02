package org.kadampa.festivalstreaming;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public class SettingsUtil {
    private static final Logger logger = LoggerFactory.getLogger(SettingsUtil.class);
    public static final String AUDIO_CHANNEL_LEFT = "Left";
    public static final String AUDIO_CHANNEL_RIGHT = "Right";
    public static final String AUDIO_CHANNEL_JOIN = "Join";
    public static final String AUDIO_SOURCE_NOT_USED = "Not Used";
    public static final String AUDIO_SOURCE_NOT_SELECTED = "Not Selected";
    public static final String AUDIO_CHANNEL_STEREO = "Stereo";
    /** Prefix for the channels of a device with more than two inputs, e.g. "Ch 7" on a mixer. */
    public static final String AUDIO_CHANNEL_PREFIX = "Ch ";
    private static final String SETTINGS_FILE_EXTENSION = ".ini";

    private static final String KEY_FORMAT = "settingsFormat";
    private static final int FORMAT_V1 = 1; // languages numbered from 1, settings keyed by language name
    private static final int FORMAT_V2 = 2; // one block per language, everything inside the block
    private static final int FIRST_CONFIGURABLE_BLOCK = 4; // blocks 1 to 3 are the built-in languages
    private static final int MAX_LANGUAGES = 64;

    /** Everything the file says about one language. The block number is only cosmetic: the
     *  settings are matched to a language by name, never by position. */
    private record LanguageBlock(int n, String name, String nativeName, String code,
                                 String audioSource, String channel,
                                 String noiseReduction, String color) {}

    private static String sanitizeKey(String key) {
        // Replace any character that is not a letter, a digit, or a hyphen with an underscore
        return key.replaceAll("[^a-zA-Z0-9-]", "_");
    }

    /** The label stored for the n-th input of a multi-channel device, counting from one. */
    public static String audioChannelName(int oneBasedChannel) {
        return AUDIO_CHANNEL_PREFIX + oneBasedChannel;
    }

    /**
     * The input channel a language reads from, counting from zero. Left, Join and anything unset
     * take the first channel, which is what the stereo-only version of the application did.
     */
    public static int audioChannelIndex(String channel) {
        if (AUDIO_CHANNEL_RIGHT.equals(channel)) {
            return 1;
        }
        if (channel != null && channel.startsWith(AUDIO_CHANNEL_PREFIX)) {
            try {
                int oneBased = Integer.parseInt(channel.substring(AUDIO_CHANNEL_PREFIX.length()).trim());
                return Math.max(0, oneBased - 1);
            } catch (NumberFormatException e) {
                logger.warn("Unreadable audio channel '{}', using the first channel", channel);
            }
        }
        return 0;
    }

    /**
     * Where the settings live: the per-user data directory, so the same file is found wherever
     * the application is started from. Older versions wrote to the working directory, which a
     * desktop launcher makes somewhere else entirely; a file still there is read until the next
     * save rewrites it in its new home, and then stays behind as an inert backup.
     */
    private static File settingsFile(String fileName) {
        File preferred = new File(Host.userDataDir(), fileName);
        if (!preferred.exists()) {
            File legacy = new File(fileName);
            if (legacy.exists()) {
                return legacy;
            }
        }
        return preferred;
    }

    public static void saveSettings(Settings settings, String key) {
        String sanitizedFileName = sanitizeKey(key) + SETTINGS_FILE_EXTENSION;
        File settingsFile = new File(Host.userDataDir(), sanitizedFileName);
        //noinspection ResultOfMethodCallIgnored
        settingsFile.getParentFile().mkdirs();
        backupLegacyFile(settingsFile);
        // Use LinkedHashMap to maintain insertion order for grouping
        Map<String, String> sortedProps = new LinkedHashMap<>();

        // Group 1: Basic video/audio settings
        sortedProps.put("videoSource", settings.getVideoSource());
        sortedProps.put("videoInputMode", settings.getVideoInputMode());
        sortedProps.put("videoBitrate", settings.getVideoBitrate());
        sortedProps.put("videoBuffer", settings.getVideoBuffer());
        sortedProps.put("audioBitrate", settings.getAudioBitrate());
        sortedProps.put("audioSampleRate", settings.getAudioSampleRate());
        sortedProps.put("audioBuffer", settings.getAudioBuffer());
        sortedProps.put("fps", settings.getFps());
        sortedProps.put("videoPID", settings.getVideoPID());
        sortedProps.put("pixFormat", settings.getPixFormat());
        sortedProps.put("encoder", settings.getEncoder());

        // Group 2: Timing settings
        sortedProps.put("delay", settings.getDelay());
        sortedProps.put("enMixDelay", settings.getEnMixDelay());
        sortedProps.put("timeNeededToOpenADevice", settings.getTimeNeededToOpenADevice());

        // Group 3: Output settings
        sortedProps.put("outputType", settings.getOutputType());
        sortedProps.put("srtURL", settings.getSrtURL());
        sortedProps.put("outputDirectory", settings.getOutputDirectory());
        sortedProps.put("srtDef", settings.getSrtDef());
        sortedProps.put("fileDef", settings.getFileDef());

        // Group 4: System settings
        sortedProps.put("ffmpegPath", settings.getFfmpegPath());
        sortedProps.put("developmentMode", String.valueOf(settings.isDevelopmentMode()));
        sortedProps.put("levelMeterWidthScale", String.valueOf(settings.getLevelMeterWidthScale()));
        sortedProps.put("levelMeterHeightScale", String.valueOf(settings.getLevelMeterHeightScale()));

        // Group 5: Level meter zone thresholds (dB)
        sortedProps.put("meterThreshold.green", String.valueOf(settings.getMeterGreenThresholdDb()));
        sortedProps.put("meterThreshold.yellow", String.valueOf(settings.getMeterYellowThresholdDb()));
        sortedProps.put("meterThreshold.red", String.valueOf(settings.getMeterRedThresholdDb()));
        sortedProps.put("meterThreshold.enMix.green", String.valueOf(settings.getEnMixMeterGreenThresholdDb()));
        sortedProps.put("meterThreshold.enMix.yellow", String.valueOf(settings.getEnMixMeterYellowThresholdDb()));
        sortedProps.put("meterThreshold.enMix.red", String.valueOf(settings.getEnMixMeterRedThresholdDb()));

        try (FileOutputStream out = new FileOutputStream(settingsFile);
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {

            // Custom store method to maintain order and add comments
            writer.write("#Festival Streaming Settings\n");
            writer.write("#Generated on: " + new java.util.Date() + "\n\n");

            writer.write("# Settings file format version - do not edit.\n");
            writer.write(KEY_FORMAT + "=" + FORMAT_V2 + "\n");

            // Write basic settings with section header
            writer.write("\n# === VIDEO AND AUDIO SETTINGS ===\n");
            writePropertiesSection(writer, sortedProps,
                new String[]{"videoSource", "videoInputMode", "videoBitrate", "videoBuffer", "audioBitrate", "audioSampleRate", "audioBuffer", "fps", "videoPID", "pixFormat", "encoder"});

            writer.write("\n# === TIMING SETTINGS ===\n");
            writePropertiesSection(writer, sortedProps,
                new String[]{"delay", "enMixDelay", "timeNeededToOpenADevice"});

            writer.write("\n# === OUTPUT SETTINGS ===\n");
            writePropertiesSection(writer, sortedProps,
                new String[]{"outputType", "srtURL", "outputDirectory", "srtDef", "fileDef"});

            writer.write("\n# === SYSTEM SETTINGS ===\n");
            writePropertiesSection(writer, sortedProps,
                new String[]{"ffmpegPath", "developmentMode", "levelMeterWidthScale", "levelMeterHeightScale"});

            writer.write("\n# === LEVEL METER ZONE THRESHOLDS (dB) ===\n");
            writer.write("# Below green = grey zone, then green, yellow and red zones.\n");
            writer.write("# The enMix.* thresholds apply to the \"English (for mix)\" meter only.\n");
            writePropertiesSection(writer, sortedProps,
                new String[]{"meterThreshold.green", "meterThreshold.yellow", "meterThreshold.red",
                             "meterThreshold.enMix.green", "meterThreshold.enMix.yellow", "meterThreshold.enMix.red"});

            writer.write("\n# === LANGUAGES ===\n");
            writer.write("# One block per language. Blocks 1 to " + (FIRST_CONFIGURABLE_BLOCK - 1)
                    + " are built in: their name and code cannot be changed here.\n");
            writer.write("# From block " + FIRST_CONFIGURABLE_BLOCK
                    + " on, language.N.name / .nativeName / .code (ISO 639-2) define the streamable languages.\n");
            writer.write("# Deleting a block truncates the list at that point - renumber the blocks after it.\n");
            writer.write("# Everything about a language lives in its own block, so renaming one keeps its settings.\n");
            for (int i = 0; i < Settings.LANGUAGES.length; i++) {
                writeLanguageBlock(writer, i + 1, Settings.LANGUAGES[i], settings);
            }

        } catch (IOException e) {
            logger.error("An error occurred", e);
        }
    }

    /** Keeps a copy of the previous file the first time it is rewritten in the block format, so a
     *  rollback to an older version of the application can be repaired by hand. */
    private static void backupLegacyFile(File settingsFile) {
        if (!settingsFile.exists()) {
            return;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(settingsFile);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            props.load(reader);
        } catch (IOException e) {
            logger.warn("Could not read {} before rewriting it: {}", settingsFile, e.getMessage());
            return;
        }
        if (readFormat(props) >= FORMAT_V2) {
            return;
        }
        File backup = new File(settingsFile.getPath() + ".v" + FORMAT_V1 + ".bak");
        try {
            Files.copy(settingsFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Migrating {} from format {} to {}, previous file kept as {}",
                    settingsFile, FORMAT_V1, FORMAT_V2, backup.getName());
        } catch (IOException e) {
            logger.warn("Could not back up {} before migrating it: {}", settingsFile, e.getMessage());
        }
    }

    private static void writeLanguageBlock(OutputStreamWriter writer, int n, Settings.Language language, Settings settings) throws IOException {
        String name = language.name();
        boolean builtIn = n < FIRST_CONFIGURABLE_BLOCK;
        writer.write("\n# --- " + n + ": " + name + (builtIn ? " (built in)" : "") + " ---\n");
        String prefix = "language." + n + ".";
        if (!builtIn) {
            writeKey(writer, prefix + "name", name);
            writeKey(writer, prefix + "nativeName", language.nativeName());
            writeKey(writer, prefix + "code", language.code());
        }
        writeKeyIfPresent(writer, prefix + "audioSource", settings.getAudioSources().get(name));
        writeKeyIfPresent(writer, prefix + "channel", settings.getAudioSourcesChannel().get(name));
        // Prayers and the English mix have no noise reduction control in the settings tab
        if (n > 2) {
            writeKeyIfPresent(writer, prefix + "noiseReduction", settings.getNoiseReductionLevel().get(name));
        }
        writeKeyIfPresent(writer, prefix + "color", settings.getLanguageColors().get(name));
    }

    private static void writeKey(OutputStreamWriter writer, String key, String value) throws IOException {
        writer.write(escapeKey(key) + "=" + escapeValue(value) + "\n");
    }

    private static void writeKeyIfPresent(OutputStreamWriter writer, String key, String value) throws IOException {
        if (value != null && !value.isBlank()) {
            writeKey(writer, key, value);
        }
    }

    private static void writePropertiesSection(OutputStreamWriter writer, Map<String, String> props, String[] keys) throws IOException {
        for (String key : keys) {
            if (props.containsKey(key)) {
                writer.write(escapeKey(key) + "=" + escapeValue(props.get(key)) + "\n");
            }
        }
    }

    private static String escapeKey(String key) {
        return key.replaceAll("([\\s:=\\\\])", "\\\\$1");
    }

    private static String escapeValue(String value) {
        if (value == null) return "";
        return value.replaceAll("(\\\\)", "\\\\$1").replaceAll("\n", "\\\\n").replaceAll("\r", "\\\\r");
    }

    private static double parseDouble(Properties props, String key, double defaultValue) {
        try {
            return Double.parseDouble(props.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            logger.warn("Invalid value for {}, falling back to {}", key, defaultValue);
            return defaultValue;
        }
    }

    private static int readFormat(Properties props) {
        String rawFormat = props.getProperty(KEY_FORMAT);
        if (rawFormat == null) {
            return FORMAT_V1; // every file written before the language blocks
        }
        try {
            return Integer.parseInt(rawFormat.trim());
        } catch (NumberFormatException e) {
            // The key only exists because a format 2 or later writer put it there
            logger.warn("Invalid {}='{}', reading the file as format {}", KEY_FORMAT, rawFormat, FORMAT_V2);
            return FORMAT_V2;
        }
    }

    private static List<LanguageBlock> parseV2Blocks(Properties props) {
        List<LanguageBlock> blocks = new ArrayList<>();
        String[] builtInNames = {Settings.PRAYERS_LANGUAGE, Settings.ENGLISH_FOR_MIX_LANGUAGE, Settings.ENGLISH_LANGUAGE};
        for (int n = 1; n < FIRST_CONFIGURABLE_BLOCK; n++) {
            warnIfBuiltInIdentityEdited(props, n);
            blocks.add(readBlock(props, n, builtInNames[n - 1], null, null));
        }
        for (int n = FIRST_CONFIGURABLE_BLOCK; n < FIRST_CONFIGURABLE_BLOCK + MAX_LANGUAGES; n++) {
            String name = props.getProperty("language." + n + ".name");
            if (name == null) {
                warnIfBlocksAfterTheGap(props, n);
                return blocks;
            }
            blocks.add(readBlock(props, n, name.trim(),
                    props.getProperty("language." + n + ".nativeName", "").trim(),
                    props.getProperty("language." + n + ".code", "").trim()));
        }
        logger.warn("Only the first {} languages are read", MAX_LANGUAGES);
        return blocks;
    }

    private static LanguageBlock readBlock(Properties props, int n, String name, String nativeName, String code) {
        String prefix = "language." + n + ".";
        return new LanguageBlock(n, name, nativeName, code,
                props.getProperty(prefix + "audioSource"),
                props.getProperty(prefix + "channel"),
                props.getProperty(prefix + "noiseReduction"),
                props.getProperty(prefix + "color"));
    }

    private static void warnIfBuiltInIdentityEdited(Properties props, int n) {
        for (String field : new String[]{"name", "nativeName", "code"}) {
            if (props.getProperty("language." + n + "." + field) != null) {
                logger.warn("language.{}.{} is ignored: the first {} languages are built in and cannot be renamed",
                        n, field, FIRST_CONFIGURABLE_BLOCK - 1);
            }
        }
    }

    /** The block scan stops at the first missing name, so a gap silently drops everything after it. */
    private static void warnIfBlocksAfterTheGap(Properties props, int missingBlock) {
        List<Integer> ignored = new ArrayList<>();
        for (String propKey : props.stringPropertyNames()) {
            if (propKey.startsWith("language.") && propKey.endsWith(".name")) {
                try {
                    int n = Integer.parseInt(propKey.substring("language.".length(), propKey.length() - ".name".length()));
                    if (n > missingBlock) {
                        ignored.add(n);
                    }
                } catch (NumberFormatException e) {
                    // not a numbered language block, nothing to report
                }
            }
        }
        if (!ignored.isEmpty()) {
            Collections.sort(ignored);
            logger.warn("language.{}.name is missing, so the language blocks {} are ignored", missingBlock, ignored);
        }
    }

    private static List<Settings.Language> toLanguages(List<LanguageBlock> blocks) {
        List<Settings.Language> languages = new ArrayList<>();
        for (LanguageBlock block : blocks) {
            if (block.n() >= FIRST_CONFIGURABLE_BLOCK) {
                languages.add(new Settings.Language(block.name(), block.nativeName(), block.code()));
            }
        }
        return languages;
    }

    private static void applyBlocks(List<LanguageBlock> blocks, Settings settings) {
        Set<String> knownNames = new HashSet<>();
        for (Settings.Language language : Settings.LANGUAGES) {
            knownNames.add(language.name());
        }
        Set<String> alreadyApplied = new HashSet<>();
        for (LanguageBlock block : blocks) {
            // A language that initLanguages dropped, and a duplicated one, keep no settings
            if (!knownNames.contains(block.name()) || !alreadyApplied.add(block.name())) {
                continue;
            }
            putIfPresent(settings.getAudioSources(), block.name(), block.audioSource());
            putIfPresent(settings.getAudioSourcesChannel(), block.name(), block.channel());
            putIfPresent(settings.getNoiseReductionLevel(), block.name(), block.noiseReduction());
            putIfPresent(settings.getLanguageColors(), block.name(), block.color());
        }
    }

    private static void putIfPresent(Map<String, String> target, String name, String value) {
        if (value != null) {
            target.put(name, value);
        }
    }

    private static void loadGeneralSettings(Properties props, Settings settings) {
        settings.setVideoSource(props.getProperty("videoSource", ""));
        settings.setVideoInputMode(props.getProperty("videoInputMode", ""));
        settings.setFfmpegPath(props.getProperty("ffmpegPath", ""));
        settings.setVideoBitrate(props.getProperty("videoBitrate", ""));
        settings.setVideoBuffer(props.getProperty("videoBuffer", ""));
        settings.setAudioBuffer(props.getProperty("audioBuffer", ""));
        settings.setVideoPID(props.getProperty("videoPID", ""));
        settings.setDelay(props.getProperty("delay", "0"));
        settings.setEnMixDelay(props.getProperty("enMixDelay", "0"));
        settings.setPixFormat(props.getProperty("pixFormat", ""));
        settings.setTimeNeededToOpenADevice(props.getProperty("timeNeededToOpenADevice", "0"));
        settings.setSrtDef(props.getProperty("srtDef", ""));
        settings.setFileDef(props.getProperty("fileDef", ""));
        settings.setEncoder(props.getProperty("encoder", ""));
        settings.setOutputType(props.getProperty("outputType", ""));
        settings.setSrtURL(props.getProperty("srtURL", ""));
        settings.setOutputDirectory(props.getProperty("outputDirectory", ""));
        settings.setAudioBitrate(props.getProperty("audioBitrate", ""));
        settings.setAudioSampleRate(props.getProperty("audioSampleRate", "48000"));
        settings.setFps(props.getProperty("fps", ""));
        settings.setDevelopmentMode(Boolean.parseBoolean(props.getProperty("developmentMode", "false")));
        settings.setLevelMeterWidthScale(parseDouble(props, "levelMeterWidthScale", settings.getLevelMeterWidthScale()));
        settings.setLevelMeterHeightScale(parseDouble(props, "levelMeterHeightScale", settings.getLevelMeterHeightScale()));
        settings.setMeterGreenThresholdDb(parseDouble(props, "meterThreshold.green", settings.getMeterGreenThresholdDb()));
        settings.setMeterYellowThresholdDb(parseDouble(props, "meterThreshold.yellow", settings.getMeterYellowThresholdDb()));
        settings.setMeterRedThresholdDb(parseDouble(props, "meterThreshold.red", settings.getMeterRedThresholdDb()));
        settings.setEnMixMeterGreenThresholdDb(parseDouble(props, "meterThreshold.enMix.green", settings.getEnMixMeterGreenThresholdDb()));
        settings.setEnMixMeterYellowThresholdDb(parseDouble(props, "meterThreshold.enMix.yellow", settings.getEnMixMeterYellowThresholdDb()));
        settings.setEnMixMeterRedThresholdDb(parseDouble(props, "meterThreshold.enMix.red", settings.getEnMixMeterRedThresholdDb()));
    }

    public static Settings loadSettings(String key) {
        String sanitizedFileName = sanitizeKey(key) + SETTINGS_FILE_EXTENSION;
        File settingsFile = settingsFile(sanitizedFileName);

        if (!settingsFile.exists()) {
            Settings.initLanguages(Arrays.asList(Settings.DEFAULT_CONFIGURABLE_LANGUAGES));
            return new Settings();
        }

        Properties props = new Properties();
        Settings settings;
        try (FileInputStream in = new FileInputStream(settingsFile);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            // The file is written in UTF-8, so it must be read with a UTF-8 reader
            // (Properties.load(InputStream) would decode it as ISO-8859-1)
            props.load(reader);

            int format = readFormat(props);
            List<LanguageBlock> blocks = format >= FORMAT_V2 ? parseV2Blocks(props) : List.of();
            List<Settings.Language> configurableLanguages = format >= FORMAT_V2
                    ? toLanguages(blocks)
                    : V1.parseLanguages(props);
            // Only a file predating the language blocks can be silent about its languages. In the
            // block format an empty list means the user removed them, and must not be overruled.
            if (configurableLanguages.isEmpty() && format < FORMAT_V2) {
                configurableLanguages = Arrays.asList(Settings.DEFAULT_CONFIGURABLE_LANGUAGES);
            }
            // The language list must be installed before creating the Settings instance
            // and before resolving the per-language settings below
            Settings.initLanguages(configurableLanguages);
            settings = new Settings();

            loadGeneralSettings(props, settings);
            // The two key spaces are disjoint, so each reader is a no-op on the other's format
            V1.applyNameKeyedSettings(props, settings);
            applyBlocks(blocks, settings);

        } catch (IOException e) {
            logger.error("An error occurred", e);
            Settings.initLanguages(Arrays.asList(Settings.DEFAULT_CONFIGURABLE_LANGUAGES));
            settings = new Settings();
        }

        return settings;
    }

    /** Reads the format that numbered the languages from 1 and kept their settings in separate
     *  sections keyed by language name. Once every file in use has been rewritten by a Save, this
     *  class, its two calls in loadSettings and the format branch above can all be deleted. */
    private static final class V1 {

        static List<Settings.Language> parseLanguages(Properties props) {
            List<Settings.Language> languages = new ArrayList<>();
            for (int i = 1; props.getProperty("language." + i + ".name") != null; i++) {
                languages.add(new Settings.Language(
                        props.getProperty("language." + i + ".name").trim(),
                        props.getProperty("language." + i + ".nativeName", "").trim(),
                        props.getProperty("language." + i + ".code", "").trim()));
            }
            return languages;
        }

        static void applyNameKeyedSettings(Properties props, Settings settings) {
            apply(props, "audioSource.", settings.getAudioSources());
            apply(props, "audioSourceChannel.", settings.getAudioSourcesChannel());
            apply(props, "noiseReduction.", settings.getNoiseReductionLevel());
            apply(props, "languageColor.", settings.getLanguageColors());
        }

        private static void apply(Properties props, String prefix, Map<String, String> target) {
            for (String propKey : props.stringPropertyNames()) {
                if (propKey.startsWith(prefix)) {
                    String languageName = findOriginalLanguageName(propKey.substring(prefix.length()));
                    if (languageName != null) {
                        target.put(languageName, props.getProperty(propKey));
                    }
                }
            }
        }

        /** The name was sanitized to build the key, which is not reversible: the only way back is
         *  to sanitize every known name and compare. A renamed language therefore loses its
         *  settings, which is what the block format fixes. */
        private static String findOriginalLanguageName(String sanitizedKey) {
            for (Settings.Language language : Settings.LANGUAGES) {
                if (sanitizeKey(language.name()).equals(sanitizedKey)) {
                    return language.name();
                }
            }
            return null;
        }
    }
}
