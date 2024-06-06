package org.kadampa.festivalstreaming;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;

public class StreamRecorderRunnable implements Runnable {

    private Settings settings;
    private String srtUrl;
    private String videoDevice;
    private final List<String> audioDevicesList = new ArrayList<>();;
    private String outputResolution;
    private String pixelFormat;
    private String encoder;
    private String audioBitrate;
    private String videoBitrate;
    private String videoBufferSize;
    private String audioBufferSize;
    private int fps;
    private int delay;
    private Process process = null;
    private final StringProperty outputLineProperty = new SimpleStringProperty();
    private ProcessMonitor monitor;
    private final BooleanProperty isAliveProperty = new SimpleBooleanProperty(); // Create the property
    private int videoPid;

    public StreamRecorderRunnable() {

    }
    public void setSettings(Settings settings) {
        this.settings = settings;
    }
    @Override
    public void run() {
       List<String> command = initialiseFFMpegCommand();
       outputLineProperty.setValue(String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        try {
            process = processBuilder.start();
            monitor = new ProcessMonitor(process, isAliveProperty);
            StreamGobbler inputStreamGobbler = new StreamGobbler(process.getInputStream(), outputLineProperty::setValue);
            StreamGobbler errorStreamGobbler = new StreamGobbler(process.getErrorStream(), outputLineProperty::setValue);
            Executors.newSingleThreadExecutor().submit(inputStreamGobbler);
            Executors.newSingleThreadExecutor().submit(errorStreamGobbler);
            int exitCode = process.waitFor();
        } catch (IOException | InterruptedException e) {
            monitor.stopMonitoring();
            stop();
        }
    }

    public Process getProcess() {
        return process;
    }

    public String getFFMpegCommand() {
        return String.join(" ",initialiseFFMpegCommand());
    }

    private List<String> initialiseFFMpegCommand() {
        List<String> devicesListCommand = new ArrayList<>();
        List<String> filterComplexCommand = new ArrayList<>();
        List<String> mapCommand = new ArrayList<>();
        List<String> parameterCommand = new ArrayList<>();
        List<String> streamIdCommand = new ArrayList<>();
        List<String> outputCommand = new ArrayList<>();
        //The video device
        devicesListCommand.add("-rtbufsize");
        devicesListCommand.add(videoBufferSize);
        devicesListCommand.add("-f");
        devicesListCommand.add("dshow");
        devicesListCommand.add("-i");
        devicesListCommand.add("\"video=" + videoDevice+"\"");
        mapCommand.add("-map");
        mapCommand.add("\"[vid]\"");

        StringBuilder filterCommand = new StringBuilder();
        int i = 0;
        //The audio devices
        for(String audioDevice:audioDevicesList) {
            i++;
            devicesListCommand.add("-rtbufsize");
            devicesListCommand.add(audioBufferSize);
            devicesListCommand.add("-f");
            devicesListCommand.add("dshow");
            devicesListCommand.add("-i");
            devicesListCommand.add("\"audio=" + audioDevice+"\"");
            filterCommand.append("[").append(i).append(":a]adelay=").append(delay).append("|").append(delay).append("[out").append(i).append("];");
            mapCommand.add("-map");
            mapCommand.add("\"[out"+i+"]\"");
            //TODO : if we split the input into left and right
            //ffmpeg -f alsa -ac 2 -i hw:0 -filter_complex "[0:a]channelsplit=channel_layout=stereo[left][right]" -map "[left]" left_channel.wav
        }

        filterCommand.append("[0:v]settb=AVTB,setpts=PTS-STARTPTS[vid]");
        filterComplexCommand.add("-filter_complex");
        filterComplexCommand.add("\""+ filterCommand +"\"");
        parameterCommand.add("-s");
        parameterCommand.add(outputResolution);
        parameterCommand.add("-c:v");
        parameterCommand.add(encoder);
        parameterCommand.add("-b:v");
        parameterCommand.add(videoBitrate);
        parameterCommand.add("-pix_fmt");
        parameterCommand.add(pixelFormat);
        parameterCommand.add("-c:a");
        parameterCommand.add("aac");
        parameterCommand.add("-b:a");
        parameterCommand.add(audioBitrate);
        parameterCommand.add("-f");
        parameterCommand.add("mpegts");
        parameterCommand.add("-mpegts_flags");
        parameterCommand.add("+initial_discontinuity");
        parameterCommand.add("-mpegts_start_pid");
        parameterCommand.add(String.valueOf(videoPid));

        outputCommand.add("-r");
        outputCommand.add(String.valueOf(fps));
        outputCommand.add("\""+srtUrl+"\"");

        List<String> finalCommand = new ArrayList<>();
        finalCommand.add("ffmpeg");
        finalCommand.addAll(devicesListCommand);
        finalCommand.addAll(filterComplexCommand);
        finalCommand.addAll(mapCommand);
        finalCommand.addAll(parameterCommand);
        finalCommand.addAll(streamIdCommand);
        finalCommand.addAll(outputCommand);
        return finalCommand;
    }


    public void stop() {
        if (process != null) {
            destroyProcessAndChildren(process);
        }
    }

    public void initialiseAudioDevices(String[] deviceNames) {
        audioDevicesList.clear();
        for (String deviceName : deviceNames) {
            if (!Objects.equals(deviceName, "Not Used")) {
                audioDevicesList.add(deviceName);
            }
        }
    }
    public void initialiseVideoDevice(String deviceName) {
        videoDevice = deviceName;
    }
    private void destroyProcessAndChildren(Process process) {
        ProcessHandle processHandle = process.toHandle();
        processHandle.descendants().forEach(ph -> {
                    ph.destroy();
                    outputLineProperty.setValue("Stopping child process");
                });
        process.destroy();
        outputLineProperty.setValue("Stopping parent process");
        try {
            Thread.sleep(5000); // Wait for 5 seconds
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        processHandle.descendants().forEach(ph -> {
            if (ph.isAlive()) {
                ph.destroyForcibly();
                outputLineProperty.setValue("Stopping child process forcefully");
            }
        });
        if (process.isAlive()) {
            process.destroyForcibly();
            outputLineProperty.setValue("Stopping parent process forcefully");
        }
        monitor.stopMonitoring();
    }
    public void setSrtUrl(String url) {
        this.srtUrl = url;
    }

    public StringProperty getOutputLineProperty() {
        return outputLineProperty;
    }

    public BooleanProperty isAliveProperty() {
        return isAliveProperty;
    }

    public void setOutputResolution(String outputResolution) {
        this.outputResolution = outputResolution;
    }

    public void setPixelFormat(String pixelFormat) {
        this.pixelFormat = pixelFormat;
    }

    public void setEncoder(String encoder) {
        this.encoder = encoder;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }

    public void setAudioBitrate(String audioBitrate) {
        this.audioBitrate = audioBitrate;
    }
    public void setFps(int fps) {
        this.fps = fps;
    }

    public void setVideoPid(int videoPid) {
        this.videoPid = videoPid;
    }

    public void setVideoBitrate(String videoBitrate) {
        this.videoBitrate = videoBitrate;
    }

    public void setVideoBufferSize(String videoBufferSize) {
        this.videoBufferSize = videoBufferSize;
    }

    public void setAudioBufferSize(String audioBufferSize) {
        this.audioBufferSize = audioBufferSize;
    }
}