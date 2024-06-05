package org.kadampa.festivalstreaming

import java.util.HashMap;
import java.util.Map;

public class Settings {
    private Map<String, String> audioInputs = new HashMap<>();
    private Map<String, String> pids = new HashMap<>();
    private String videoInput;
    private String delay;
    private String pixFormat;
    private String streamId;
    private String srtDef;
    private String fileDef;
    private String encoder;
    private String srtDest;
    private String outputFile;

    public Map<String, String> getAudioInputs() {
        return audioInputs;
    }

    public Map<String, String> getPids() {
        return pids;
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

    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
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
}
