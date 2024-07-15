package org.kadampa.festivalstreaming;

import java.io.Serial;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class Settings implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private final Map<String, String> audioSources = new HashMap<>();
    private final Map<String, String> audioSourcesChannel = new HashMap<>();
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

    public Map<String, String> getAudioSources() {
        return audioSources;
    }

    public Map<String, String> getAudioSourcesChannel() {
        return audioSourcesChannel;
    }
    public Map<String, String> getNoiseReductionLevel() {
        return noiseReductionLevel;
    }

    public String getVideoSource() {
        return videoSource;
    }

    public void setVideoSource(String videoSource) {
        this.videoSource = videoSource;
    }

    public String getDelay() {
        return delay;
    }
    public String getEnMixDelay() {
        return enMixDelay;
    }

    public void setEnMixDelay(String delay) {
        enMixDelay = delay;
    }

    public void setDelay(String delay) {
        this.delay = delay;
    }

    public String getPixFormat() {
        return pixFormat;
    }

    public void setPixFormat(String pixFormat) {
        this.pixFormat = pixFormat;
    }

    public String getOutputType() {
        return outputType;
    }

    public void setOutputType(String outputType) {
        this.outputType = outputType;
    }

    public String getSrtDef() {
        return srtDef;
    }

    public void setSrtDef(String srtDef) {
        this.srtDef = srtDef;
    }

    public String getFileDef() {
        return fileDef;
    }

    public void setFileDef(String fileDef) {
        this.fileDef = fileDef;
    }

    public String getEncoder() {
        return encoder;
    }

    public void setEncoder(String encoder) {
        this.encoder = encoder;
    }

    public String getSrtURL() {
        return srtURL;
    }

    public void setSrtURL(String srtURL) {
        this.srtURL = srtURL;
    }

    public String getOutputDirectory() {
        return outputDirectory;
    }

    public void setOutputDirectory(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public String getAudioBitrate() {
        return audioBitrate;
    }

    public void setAudioBitrate(String audioBitrate) {
        this.audioBitrate = audioBitrate;
    }

    public String getFps() {
        return fps;
    }

    public void setFps(String fps) {
        this.fps = fps;
    }

    public String getVideoPID() {
        return videoPID;
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
        return videoBitrate;
    }

    public String getVideoBuffer() {
        return videoBuffer;
    }

    public String getAudioBuffer() {
        return audioBuffer;
    }

    public String getTimeNeededToOpenADevice() {
        return timeNeededToOpenADevice;
    }

    public void setTimeNeededToOpenADevice(String timeNeededToOpenADevice) {
        this.timeNeededToOpenADevice = timeNeededToOpenADevice;
    }
}
