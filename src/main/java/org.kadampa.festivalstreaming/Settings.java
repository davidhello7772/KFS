package org.kadampa.festivalstreaming;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Settings implements Serializable {

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

    public static final Language[] LANGUAGES = {
            new Language("Prayers"),
            new Language("English (for mix)"),
            new Language("English", "English", "eng"),
            new Language("Spanish", "Español", "spa"),
            new Language("French", "Français", "fra"),
            new Language("Portuguese", "Português", "por"),
            new Language("German", "Deutsch", "deu"),
            new Language("Cantonese", "廣東話", "chi"),
            new Language("Mandarin", "普通话", "chi"),
            new Language("Vietnamese", "Tiếng_Việt", "vie"),
            new Language("Italian", "Italiano", "ita"),
            new Language("Greek", "Ελληνικά", "grc")
    };

    public Settings() {
        // Initialize default colors for languages
        for (Language language : LANGUAGES) {
            languageColors.put(language.name(), "#4E342E");
        }
    }
    @Serial
    private static final long serialVersionUID = 1L;
    private final Map<String, String> audioSources = new HashMap<>();
    private final Map<String, String> audioSourcesChannel = new HashMap<>();
    private final Map<String, String> languageColors = new HashMap<>();
    private final Map<String,String> noiseReductionLevel = new HashMap<>();
    private String videoSource;
    private String videoBitrate;
    private String videoBuffer;
    private String audioBuffer;
    private String videoPID;
    private String delay;
    private String pixFormat;
    private String timeNeededToOpenADevice;
    private String srtDef;
    private String fileDef;
    private String encoder;
    private String outputType;
    private String srtURL;
    private String outputDirectory;
    private String audioBitrate; // Add audio bitrate field
    private String fps; // Add FPS field
    private String enMixDelay;
    private boolean developmentMode = false;

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

    public String getDelay() {
        return Objects.requireNonNullElse(delay, "0");
    }
    public String getEnMixDelay() {
        return Objects.requireNonNullElse(enMixDelay, "0");
    }

    public void setEnMixDelay(String delay) {
        enMixDelay = delay;
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

    public String getFileDef() {
        return Objects.requireNonNullElse(fileDef, "");
    }

    public void setFileDef(String fileDef) {
        this.fileDef = fileDef;
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

    public String getFps() {
        return Objects.requireNonNullElse(fps, "");
    }

    public void setFps(String fps) {
        this.fps = fps;
    }

    public String getVideoPID() {
        return Objects.requireNonNullElse(videoPID, "");
    }

    public void setVideoPID(String videoPID) {
        this.videoPID = videoPID;
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
}
