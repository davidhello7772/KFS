package org.kadampa.festivalstreaming;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Settings implements Serializable {

    private static final Logger logger = LoggerFactory.getLogger(Settings.class);

    public static final boolean DEVELOPMENT_MODE = false;

    public record Language(String name, String nativeName, String code) {
        public Language(String name) {
            this(name, null, null);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public static final String PRAYERS_LANGUAGE = "Prayers";
    public static final String ENGLISH_FOR_MIX_LANGUAGE = "English (for mix)";
    public static final String ENGLISH_LANGUAGE = "English"; // The reference language

    // The first three languages are built in and cannot be redefined in the settings file
    private static final Language[] FIXED_LANGUAGES = {
            new Language(PRAYERS_LANGUAGE),
            new Language(ENGLISH_FOR_MIX_LANGUAGE),
            new Language(ENGLISH_LANGUAGE, ENGLISH_LANGUAGE, "eng")
    };

    // Used when the settings file does not define any language
    public static final Language[] DEFAULT_CONFIGURABLE_LANGUAGES = {
            new Language("Spanish", "Español", "spa"),
            new Language("Swedish", "Svenska", "swe"),
            new Language("French", "Français", "fra"),
            new Language("Portuguese", "Português", "por"),
            new Language("German", "Deutsch", "deu"),
            new Language("Cantonese", "廣東話", "chi"),
            new Language("Mandarin", "普通话", "chi"),
            new Language("Vietnamese", "Tiếng_Việt", "vie"),
            new Language("Italian", "Italiano", "ita"),
            new Language("Greek", "Ελληνικά", "grc"),
            new Language("Finnish", "Suomi", "fin"),
            new Language("Dutch", "Nederlands", "nld")
    };

    // Replaced once at startup by initLanguages(), before any Settings instance is created
    public static Language[] LANGUAGES = concat(FIXED_LANGUAGES, DEFAULT_CONFIGURABLE_LANGUAGES);

    public static void initLanguages(List<Language> configurableLanguages) {
        List<Language> result = new ArrayList<>(Arrays.asList(FIXED_LANGUAGES));
        Set<String> usedNames = new HashSet<>();
        for (Language fixed : FIXED_LANGUAGES) {
            usedNames.add(fixed.name());
        }
        for (Language language : configurableLanguages) {
            if (language.name() == null || language.name().isBlank()
                    || language.code() == null || language.code().isBlank()) {
                logger.warn("Ignoring language with missing name or code: {}", language);
                continue;
            }
            if (!usedNames.add(language.name())) {
                logger.warn("Ignoring duplicated language name: {}", language.name());
                continue;
            }
            String nativeName = (language.nativeName() == null || language.nativeName().isBlank())
                    ? language.name() : language.nativeName();
            result.add(new Language(language.name(), nativeName, language.code()));
        }
        LANGUAGES = result.toArray(new Language[0]);
    }

    /** The three built-in languages, in the order they always occupy. */
    public static List<Language> fixedLanguages() {
        return List.of(FIXED_LANGUAGES);
    }

    private static Language[] concat(Language[] first, Language[] second) {
        Language[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** The list the settings file should be written from: the edited one if there is one. */
    public List<Language> effectiveLanguages() {
        return pendingLanguages != null ? pendingLanguages : List.of(LANGUAGES);
    }

    public void setPendingLanguages(List<Language> languages) {
        pendingLanguages = languages == null ? null : List.copyOf(languages);
    }

    /**
     * Moves everything a language owns onto its new name. The four per-language maps are keyed by
     * name, so a rename would otherwise leave the audio source, channel, colour and noise level
     * behind under a name nothing refers to any more, and the language would come back after the
     * restart with none of them - the settings that take longest to get right. Every read is taken
     * before any write, so two languages swapping names each keep their own.
     */
    public void renameLanguages(Map<String, String> oldToNewNames) {
        for (Map<String, String> perLanguage :
                List.of(audioSources, audioSourcesChannel, noiseReductionLevel, languageColors)) {
            Map<String, String> moved = new HashMap<>();
            for (Map.Entry<String, String> rename : oldToNewNames.entrySet()) {
                String value = perLanguage.remove(rename.getKey());
                if (value != null) {
                    moved.put(rename.getValue(), value);
                }
            }
            perLanguage.putAll(moved);
        }
    }

    /**
     * Drops what a language owned once it has left the list. Nothing reads a dead key - the file is
     * written from the language list, not from these maps - so this is housekeeping: it stops a
     * session's worth of removals accumulating under names nothing refers to.
     */
    public void forgetLanguagesNotIn(Set<String> keptNames) {
        for (Map<String, String> perLanguage :
                List.of(audioSources, audioSourcesChannel, noiseReductionLevel, languageColors)) {
            perLanguage.keySet().retainAll(keptNames);
        }
    }

    public Settings() {
        // Initialize default colors for languages
        for (Language language : LANGUAGES) {
            languageColors.put(language.name(), "#4E342E");
        }
    }
    @Serial
    private static final long serialVersionUID = 1L;
    /**
     * The language list to write to the settings file. Normally the one the application is running
     * with, but the Languages tab edits a list that only takes effect at the next start: until then
     * LANGUAGES has to stay exactly as it is, because every per-language control in the window - the
     * audio source combos, the colour pickers, the level meters - was built from it and is indexed
     * in lock-step with it. Having both Save buttons write through this is what stops the settings
     * tab and the languages tab disagreeing about the list.
     */
    private transient List<Language> pendingLanguages;
    private final Map<String, String> audioSources = new HashMap<>();
    private final Map<String, String> audioSourcesChannel = new HashMap<>();
    private final Map<String, String> languageColors = new HashMap<>();
    private final Map<String,String> noiseReductionLevel = new HashMap<>();
    private String videoSource;
    /** The AVFoundation capture mode, as "1920x1080@30". Only meaningful on macOS. */
    private String videoInputMode;
    /** An explicit ffmpeg binary, for when it is not on the PATH. Empty means "find it". */
    private String ffmpegPath;
    private String videoBitrate;
    private String videoBuffer;
    private String audioBuffer;
    private String delay;
    private String pixFormat;
    private String timeNeededToOpenADevice;
    private String srtDef;
    /** The extra file for the communication team: its own quality and destination. */
    private boolean commRecording = false;
    private String commResolution;
    private String commVideoBitrate;
    private String commAudioBitrate;
    private String commDirectory;
    private String encoder;
    private String outputType;
    private String srtURL;
    /** Whether to replace the latency inside the URL, and with what. Kept apart from srtURL so
     *  what the operator pasted is what the file keeps, whatever the command ends up saying. */
    private boolean srtLatencyOverride = false;
    private String srtLatencyMs;
    private String outputDirectory;
    private String audioBitrate; // Add audio bitrate field
    /** The rate the capture devices run at and the recording is encoded at. */
    private String audioSampleRate = "48000";
    private String fps; // Add FPS field
    private boolean developmentMode = false;
    private double levelMeterWidthScale = 1.0;
    private double levelMeterHeightScale = 1.0;
    // Level meter zone thresholds (dB): grey below green, then green, yellow and red zones
    private double meterGreenThresholdDb = -9.0;
    private double meterYellowThresholdDb = 6.0;
    private double meterRedThresholdDb = 9.0;
    // "English (for mix)" monitors a deliberately low signal, so it has its own thresholds
    private double enMixMeterGreenThresholdDb = -28.0;
    private double enMixMeterYellowThresholdDb = -20.0;
    private double enMixMeterRedThresholdDb = 9.0;

    public Map<String, String> getAudioSources() {
        return audioSources;
    }

    public Map<String, String> getAudioSourcesChannel() {
        return audioSourcesChannel;
    }
    public Map<String, String> getNoiseReductionLevel() {
        return noiseReductionLevel;
    }

    public Map<String, String> getLanguageColors() {
        return languageColors;
    }

    public String getVideoSource() {
        return Objects.requireNonNullElse(videoSource, "");
    }

    public void setVideoSource(String videoSource) {
        this.videoSource = videoSource;
    }

    public String getVideoInputMode() {
        return Objects.requireNonNullElse(videoInputMode, "");
    }

    public void setVideoInputMode(String videoInputMode) {
        this.videoInputMode = videoInputMode;
    }

    public String getFfmpegPath() {
        return Objects.requireNonNullElse(ffmpegPath, "");
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public String getDelay() {
        return Objects.requireNonNullElse(delay, "0");
    }

    public void setDelay(String delay) {
        this.delay = delay;
    }

    public String getPixFormat() {
        return Objects.requireNonNullElse(pixFormat, "");
    }

    public void setPixFormat(String pixFormat) {
        this.pixFormat = pixFormat;
    }

    public String getOutputType() {
        return Objects.requireNonNullElse(outputType, "");
    }

    public void setOutputType(String outputType) {
        this.outputType = outputType;
    }

    public String getSrtDef() {
        return Objects.requireNonNullElse(srtDef, "");
    }

    public void setSrtDef(String srtDef) {
        this.srtDef = srtDef;
    }


    public boolean isCommRecording() {
        return commRecording;
    }

    public void setCommRecording(boolean commRecording) {
        this.commRecording = commRecording;
    }

    public String getCommResolution() {
        return Objects.requireNonNullElse(commResolution, "");
    }

    public void setCommResolution(String commResolution) {
        this.commResolution = commResolution;
    }

    public String getCommVideoBitrate() {
        return Objects.requireNonNullElse(commVideoBitrate, "");
    }

    public void setCommVideoBitrate(String commVideoBitrate) {
        this.commVideoBitrate = commVideoBitrate;
    }

    public String getCommAudioBitrate() {
        return Objects.requireNonNullElse(commAudioBitrate, "");
    }

    public void setCommAudioBitrate(String commAudioBitrate) {
        this.commAudioBitrate = commAudioBitrate;
    }

    public String getCommDirectory() {
        return Objects.requireNonNullElse(commDirectory, "");
    }

    public void setCommDirectory(String commDirectory) {
        this.commDirectory = commDirectory;
    }

    public String getEncoder() {
        return Objects.requireNonNullElse(encoder, "");
    }

    public void setEncoder(String encoder) {
        this.encoder = encoder;
    }

    public String getSrtURL() {
        return Objects.requireNonNullElse(srtURL, "");
    }

    public void setSrtURL(String srtURL) {
        this.srtURL = srtURL;
    }

    public boolean isSrtLatencyOverride() {
        return srtLatencyOverride;
    }

    public void setSrtLatencyOverride(boolean srtLatencyOverride) {
        this.srtLatencyOverride = srtLatencyOverride;
    }

    /** In milliseconds, unlike the latency= inside the URL, which libsrt reads as microseconds. */
    public String getSrtLatencyMs() {
        return Objects.requireNonNullElse(srtLatencyMs, "2000");
    }

    public void setSrtLatencyMs(String srtLatencyMs) {
        this.srtLatencyMs = srtLatencyMs;
    }

    public String getOutputDirectory() {
        return Objects.requireNonNullElse(outputDirectory, "");
    }

    public void setOutputDirectory(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public String getAudioBitrate() {
        return Objects.requireNonNullElse(audioBitrate, "");
    }

    public void setAudioBitrate(String audioBitrate) {
        this.audioBitrate = audioBitrate;
    }

    public String getAudioSampleRate() {
        return Objects.requireNonNullElse(audioSampleRate, "48000");
    }

    public void setAudioSampleRate(String audioSampleRate) {
        this.audioSampleRate = audioSampleRate;
    }

    public String getFps() {
        return Objects.requireNonNullElse(fps, "");
    }

    public void setFps(String fps) {
        this.fps = fps;
    }


    public void setVideoBitrate(String videoBitrate) {
        this.videoBitrate = videoBitrate;
    }

    public void setVideoBuffer(String videoBuffer) {
        this.videoBuffer = videoBuffer;
    }

    public void setAudioBuffer(String audioBuffer) {
        this.audioBuffer = audioBuffer;
    }

    public String getVideoBitrate() {
        return Objects.requireNonNullElse(videoBitrate, "");
    }

    public String getVideoBuffer() {
        return Objects.requireNonNullElse(videoBuffer, "");
    }

    public String getAudioBuffer() {
        return Objects.requireNonNullElse(audioBuffer, "");
    }

    public String getTimeNeededToOpenADevice() {
        return Objects.requireNonNullElse(timeNeededToOpenADevice, "0");
    }

    public void setTimeNeededToOpenADevice(String timeNeededToOpenADevice) {
        this.timeNeededToOpenADevice = timeNeededToOpenADevice;
    }

    public boolean isDevelopmentMode() {
        return developmentMode;
    }

    public void setDevelopmentMode(boolean developmentMode) {
        this.developmentMode = developmentMode;
    }

    public double getLevelMeterWidthScale() {
        return levelMeterWidthScale;
    }

    public void setLevelMeterWidthScale(double levelMeterWidthScale) {
        this.levelMeterWidthScale = levelMeterWidthScale;
    }

    public double getLevelMeterHeightScale() {
        return levelMeterHeightScale;
    }

    public void setLevelMeterHeightScale(double levelMeterHeightScale) {
        this.levelMeterHeightScale = levelMeterHeightScale;
    }

    public double getMeterGreenThresholdDb() {
        return meterGreenThresholdDb;
    }

    public void setMeterGreenThresholdDb(double meterGreenThresholdDb) {
        this.meterGreenThresholdDb = meterGreenThresholdDb;
    }

    public double getMeterYellowThresholdDb() {
        return meterYellowThresholdDb;
    }

    public void setMeterYellowThresholdDb(double meterYellowThresholdDb) {
        this.meterYellowThresholdDb = meterYellowThresholdDb;
    }

    public double getMeterRedThresholdDb() {
        return meterRedThresholdDb;
    }

    public void setMeterRedThresholdDb(double meterRedThresholdDb) {
        this.meterRedThresholdDb = meterRedThresholdDb;
    }

    public double getEnMixMeterGreenThresholdDb() {
        return enMixMeterGreenThresholdDb;
    }

    public void setEnMixMeterGreenThresholdDb(double enMixMeterGreenThresholdDb) {
        this.enMixMeterGreenThresholdDb = enMixMeterGreenThresholdDb;
    }

    public double getEnMixMeterYellowThresholdDb() {
        return enMixMeterYellowThresholdDb;
    }

    public void setEnMixMeterYellowThresholdDb(double enMixMeterYellowThresholdDb) {
        this.enMixMeterYellowThresholdDb = enMixMeterYellowThresholdDb;
    }

    public double getEnMixMeterRedThresholdDb() {
        return enMixMeterRedThresholdDb;
    }

    public void setEnMixMeterRedThresholdDb(double enMixMeterRedThresholdDb) {
        this.enMixMeterRedThresholdDb = enMixMeterRedThresholdDb;
    }
}
