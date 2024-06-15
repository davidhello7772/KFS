package org.kadampa.festivalstreaming;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class Settings implements Serializable {
    private static final long serialVersionUID = 1L;
    private final Map<String, String> audioInputs = new HashMap<>();
    private final Map<String, String> audioInputsChannel = new HashMap<>();
    private final Map<String, String> pids = new HashMap<>();
    private String videoInput;
    private String videoBitrateInput;
    private String videoBufferInput;
    private String audioBufferInput;
    private String videoPID;
    private String delay;
    private String pixFormat;
    private String srtDef;
    private String fileDef;
    private String encoder;
    private String outputType;
    private String srtDest;
    private String outputFile;
    private String audioBitrate; // Add audio bitrate field
    private String fps; // Add FPS field

    public Map<String, String> getAudioInputs() {
        return audioInputs;
    }

    public Map<String, String> getAudioInputsChannel() {
        return audioInputsChannel;
    }
    public String getVideoInput() {
        return videoInput;
    }

    public void setVideoInput(String videoInput) {
        this.videoInput = videoInput;
    }

    public String getDelay() {
        return delay;
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

    public String getSrtDest() {
        return srtDest;
    }

    public void setSrtDest(String srtDest) {
        this.srtDest = srtDest;
    }

    public String getOutputFile() {
        return outputFile;
    }

    public void setOutputFile(String outputFile) {
        this.outputFile = outputFile;
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

    public void setVideoBitrateInput(String videoBitrateInput) {
        this.videoBitrateInput = videoBitrateInput;
    }

    public void setVideoBufferInput(String videoBufferInput) {
        this.videoBufferInput = videoBufferInput;
    }

    public void setAudioBufferInput(String audioBufferInput) {
        this.audioBufferInput = audioBufferInput;
    }

    public String getVideoBitrateInput() {
        return videoBitrateInput;
    }

    public String getVideoBufferInput() {
        return videoBufferInput;
    }

    public String getAudioBufferInput() {
        return audioBufferInput;
    }
}
