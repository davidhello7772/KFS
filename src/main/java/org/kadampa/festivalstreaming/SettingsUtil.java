package org.kadampa.festivalstreaming;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

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
    private static final String SETTINGS_FILE_EXTENSION = ".ini";

    private static String sanitizeKey(String key) {
        // Replace any character that is not a letter, a digit, or a hyphen with an underscore
        return key.replaceAll("[^a-zA-Z0-9-]", "_");
    }

    private static String findOriginalLanguageName(String sanitizedKey, Iterable<Settings.Language> languages) {
        for (Settings.Language language : languages) {
            if (sanitizeKey(language.name()).equals(sanitizedKey)) {
                return language.name();
            }
        }
        return null;
    }

    public static void saveSettings(Settings settings, String key) {
        String sanitizedFileName = sanitizeKey(key) + SETTINGS_FILE_EXTENSION;
        // Use LinkedHashMap to maintain insertion order for grouping
        Map<String, String> sortedProps = new LinkedHashMap<>();

        // Group 1: Basic video/audio settings
        sortedProps.put("videoSource", settings.getVideoSource());
        sortedProps.put("videoBitrate", settings.getVideoBitrate());
        sortedProps.put("videoBuffer", settings.getVideoBuffer());
        sortedProps.put("audioBitrate", settings.getAudioBitrate());
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
        sortedProps.put("developmentMode", String.valueOf(settings.isDevelopmentMode()));

        // Group 5: Language-based settings (sorted by language order in Settings.LANGUAGES)
        // Audio Sources
        for (Settings.Language language : Settings.LANGUAGES) {
                String languageName = language.name();
                String audioSource = settings.getAudioSources().get(languageName);
                if (audioSource != null) {
                    sortedProps.put("audioSource." + sanitizeKey(languageName), audioSource);
                }
            }

            // Audio Source Channels
            for (Settings.Language language : Settings.LANGUAGES) {
                String languageName = language.name();
                String audioChannel = settings.getAudioSourcesChannel().get(languageName);
                if (audioChannel != null) {
                    sortedProps.put("audioSourceChannel." + sanitizeKey(languageName), audioChannel);
                }
            }

            // Noise Reduction Levels
            for (Settings.Language language : Settings.LANGUAGES) {
                String languageName = language.name();
                String noiseReduction = settings.getNoiseReductionLevel().get(languageName);
                if (noiseReduction != null) {
                    sortedProps.put("noiseReduction." + sanitizeKey(languageName), noiseReduction);
                }
            }

            // Language Colors
            for (Settings.Language language : Settings.LANGUAGES) {
                String languageName = language.name();
                String color = settings.getLanguageColors().get(languageName);
                if (color != null) {
                    sortedProps.put("languageColor." + sanitizeKey(languageName), color);
                }
            }

        try (FileOutputStream out = new FileOutputStream(sanitizedFileName);
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {

            // Custom store method to maintain order and add comments
            writer.write("#Festival Streaming Settings\n");
            writer.write("#Generated on: " + new java.util.Date() + "\n\n");

            // Write basic settings with section header
            writer.write("# === VIDEO AND AUDIO SETTINGS ===\n");
            writePropertiesSection(writer, sortedProps,
                new String[]{"videoSource", "videoBitrate", "videoBuffer", "audioBitrate", "audioBuffer", "fps", "videoPID", "pixFormat", "encoder"});

            writer.write("\n# === TIMING SETTINGS ===\n");
            writePropertiesSection(writer, sortedProps,
                new String[]{"delay", "enMixDelay", "timeNeededToOpenADevice"});

            writer.write("\n# === OUTPUT SETTINGS ===\n");
            writePropertiesSection(writer, sortedProps,
                new String[]{"outputType", "srtURL", "outputDirectory", "srtDef", "fileDef"});

            writer.write("\n# === SYSTEM SETTINGS ===\n");
            writePropertiesSection(writer, sortedProps,
                new String[]{"developmentMode"});

            writer.write("\n# === STREAMABLE LANGUAGES ===\n");
            writer.write("# language.N.name / .nativeName / .code (ISO 639-2). N starts at 1 and must be contiguous.\n");
            writer.write("# Prayers, \"English (for mix)\" and English are built in and cannot be changed here.\n");
            writer.write("# Renaming a language resets its audio source, channel, noise reduction and color below.\n");
            for (int i = 3; i < Settings.LANGUAGES.length; i++) {
                Settings.Language language = Settings.LANGUAGES[i];
                int n = i - 2;
                writer.write(escapeKey("language." + n + ".name") + "=" + escapeValue(language.name()) + "\n");
                writer.write(escapeKey("language." + n + ".nativeName") + "=" + escapeValue(language.nativeName()) + "\n");
                writer.write(escapeKey("language." + n + ".code") + "=" + escapeValue(language.code()) + "\n");
            }

            writer.write("\n# === LANGUAGE AUDIO SOURCES ===\n");
            for (Settings.Language language : Settings.LANGUAGES) {
                String propKey = "audioSource." + sanitizeKey(language.name());
                if (sortedProps.containsKey(propKey)) {
                    writer.write(escapeKey(propKey) + "=" + escapeValue(sortedProps.get(propKey)) + "\n");
                }
            }

            writer.write("\n# === LANGUAGE AUDIO CHANNELS ===\n");
            for (Settings.Language language : Settings.LANGUAGES) {
                String propKey = "audioSourceChannel." + sanitizeKey(language.name());
                if (sortedProps.containsKey(propKey)) {
                    writer.write(escapeKey(propKey) + "=" + escapeValue(sortedProps.get(propKey)) + "\n");
                }
            }

            writer.write("\n# === LANGUAGE NOISE REDUCTION ===\n");
            for (Settings.Language language : Settings.LANGUAGES) {
                String propKey = "noiseReduction." + sanitizeKey(language.name());
                if (sortedProps.containsKey(propKey)) {
                    writer.write(escapeKey(propKey) + "=" + escapeValue(sortedProps.get(propKey)) + "\n");
                }
            }

            writer.write("\n# === LANGUAGE COLORS ===\n");
            for (Settings.Language language : Settings.LANGUAGES) {
                String propKey = "languageColor." + sanitizeKey(language.name());
                if (sortedProps.containsKey(propKey)) {
                    writer.write(escapeKey(propKey) + "=" + escapeValue(sortedProps.get(propKey)) + "\n");
                }
            }

        } catch (IOException e) {
            logger.error("An error occurred", e);
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

    private static List<Settings.Language> parseLanguages(Properties props) {
        List<Settings.Language> languages = new ArrayList<>();
        for (int i = 1; props.getProperty("language." + i + ".name") != null; i++) {
            languages.add(new Settings.Language(
                    props.getProperty("language." + i + ".name").trim(),
                    props.getProperty("language." + i + ".nativeName", "").trim(),
                    props.getProperty("language." + i + ".code", "").trim()));
        }
        return languages;
    }

    public static Settings loadSettings(String key) {
        String sanitizedFileName = sanitizeKey(key) + SETTINGS_FILE_EXTENSION;
        File settingsFile = new File(sanitizedFileName);

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

            // The language list must be installed before creating the Settings instance
            // and before resolving the per-language keys below
            List<Settings.Language> configurableLanguages = parseLanguages(props);
            if (configurableLanguages.isEmpty()) {
                configurableLanguages = Arrays.asList(Settings.DEFAULT_CONFIGURABLE_LANGUAGES);
            }
            Settings.initLanguages(configurableLanguages);
            settings = new Settings();

            // Load basic settings
            settings.setVideoSource(props.getProperty("videoSource", ""));
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
            settings.setFps(props.getProperty("fps", ""));
            settings.setDevelopmentMode(Boolean.parseBoolean(props.getProperty("developmentMode", "false")));

            // Load audio sources using language names
            for (String propKey : props.stringPropertyNames()) {
                if (propKey.startsWith("audioSource.")) {
                    String sanitizedLanguageName = propKey.substring("audioSource.".length());
                    String originalLanguageName = findOriginalLanguageName(sanitizedLanguageName, Arrays.asList(Settings.LANGUAGES));
                    if (originalLanguageName != null) {
                        settings.getAudioSources().put(originalLanguageName, props.getProperty(propKey));
                    }
                }
            }

            // Load audio source channels using language names
            for (String propKey : props.stringPropertyNames()) {
                if (propKey.startsWith("audioSourceChannel.")) {
                    String sanitizedLanguageName = propKey.substring("audioSourceChannel.".length());
                    String originalLanguageName = findOriginalLanguageName(sanitizedLanguageName, Arrays.asList(Settings.LANGUAGES));
                    if (originalLanguageName != null) {
                        settings.getAudioSourcesChannel().put(originalLanguageName, props.getProperty(propKey));
                    }
                }
            }

            // Load noise reduction levels using language names
            for (String propKey : props.stringPropertyNames()) {
                if (propKey.startsWith("noiseReduction.")) {
                    String sanitizedLanguageName = propKey.substring("noiseReduction.".length());
                    String originalLanguageName = findOriginalLanguageName(sanitizedLanguageName, Arrays.asList(Settings.LANGUAGES));
                    if (originalLanguageName != null) {
                        settings.getNoiseReductionLevel().put(originalLanguageName, props.getProperty(propKey));
                    }
                }
            }

            // Load language colors using language names
            for (String propKey : props.stringPropertyNames()) {
                if (propKey.startsWith("languageColor.")) {
                    String sanitizedLanguageName = propKey.substring("languageColor.".length());
                    String originalLanguageName = findOriginalLanguageName(sanitizedLanguageName, Arrays.asList(Settings.LANGUAGES));
                    if (originalLanguageName != null) {
                        settings.getLanguageColors().put(originalLanguageName, props.getProperty(propKey));
                    }
                }
            }

        } catch (IOException e) {
            logger.error("An error occurred", e);
            Settings.initLanguages(Arrays.asList(Settings.DEFAULT_CONFIGURABLE_LANGUAGES));
            settings = new Settings();
        }

        return settings;
    }
}