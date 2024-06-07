package org.kadampa.festivalstreaming;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.control.ComboBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;

public class StreamRecorderRunnable implements Runnable {

    private Settings settings;
    private String srtUrl;
    private String videoDevice;
    private final List<String> audioDevicesList = new ArrayList<>();;
    private final List<String> audioInputsChannel = new ArrayList<>();

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
            if(monitor!=null) monitor.stopMonitoring();
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
        streamIdCommand.add("-streamid");
        streamIdCommand.add("0:"+ videoPid);
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
            if(audioInputsChannel.get(i-1).equals("Join")) {
                filterCommand.append("[").append(i).append(":a]adelay=").append(delay).append("|").append(delay).append("[out").append(i).append("];");
                mapCommand.add("-map");
                mapCommand.add("\"[out"+i+"]\"");
                streamIdCommand.add("-streamid");
                int audioPid = videoPid +i;
                streamIdCommand.add(i+":"+ audioPid);
            }
            else {
                filterCommand.append("[").append(i).append(":a]channelsplit[left").append(i).append("][right").append(i).append("];");
                if(audioInputsChannel.get(i-1).equals("Left")) {
                    filterCommand.append("[left").append(i).append("]adelay=").append(delay).append("|").append(delay).append("[outleft").append(i).append("];");
                    mapCommand.add("-map");
                    mapCommand.add("\"[outleft"+i+"]\"");
                    streamIdCommand.add("-streamid");
                    int audioPid = videoPid +i;
                    streamIdCommand.add(i+":"+ audioPid);
                }
                if(audioInputsChannel.get(i-1).equals("Right")) {
                    filterCommand.append("[right").append(i).append("]adelay=").append(delay).append("|").append(delay).append("[outright").append(i).append("];");
                    mapCommand.add("-map");
                    mapCommand.add("\"[outright"+i+"]\"");
                    streamIdCommand.add("-streamid");
                    int audioPid = videoPid +i;
                    streamIdCommand.add(i+":"+ audioPid);
                }
            }

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

        //Not needed:  Relationship Between -mpegts_start_pid and -streamid:
        //Default Behavior: If you only use -mpegts_start_pid, streams will be assigned PIDs starting from this value in a sequential manner (37, 38, 39, ...).
        //Custom Mapping: By using -streamid, you can override this default sequential assignment and specify exact PIDs for each stream. This allows you more control over the PID allocation.
        //finalCommand.addAll(streamIdCommand);

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
    public void initialiseAudioDevicesChannel(String[] channelInfos) {
        audioInputsChannel.clear();
        audioInputsChannel.addAll(Arrays.asList(channelInfos));
    }

    public void initialiseVideoDevice(String deviceName) {
        videoDevice = deviceName;
    }
    private void destroyProcessAndChildren(Process process) {
        ProcessHandle processHandle = process.toHandle();
        processHandle.descendants().forEach(ph -> {
                    ph.destroy();
                    //outputLineProperty.setValue("Stopping child process");
                });
        process.destroy();
        //outputLineProperty.setValue("Stopping parent process");
        try {
            Thread.sleep(3000); // Wait for 5 seconds
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