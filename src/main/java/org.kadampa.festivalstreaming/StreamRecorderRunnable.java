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
    private String srtUrl;
    private String videoDevice;
    private final List<String> audioDevicesList = new ArrayList<>();;
    public BooleanProperty running = new SimpleBooleanProperty(false);
    private Process process = null;
    private final StringProperty outputLineProperty = new SimpleStringProperty();
    private ProcessMonitor monitor;
    private final BooleanProperty isAliveProperty = new SimpleBooleanProperty(); // Create the property


    public StreamRecorderRunnable() {

    }

    @Override
    public void run() {
       List<String> command = initialiseFFMpegCommand();
       System.out.println(String.join(" ", command));
       outputLineProperty.setValue(String.join(" ", command));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        try {
            running.setValue(true);
            process = processBuilder.start();


            monitor = new ProcessMonitor(process, isAliveProperty);

            StreamGobbler inputStreamGobbler = new StreamGobbler(process.getInputStream(), outputLineProperty::setValue);
            StreamGobbler errorStreamGobbler = new StreamGobbler(process.getErrorStream(), outputLineProperty::setValue);
            Executors.newSingleThreadExecutor().submit(inputStreamGobbler);
            Executors.newSingleThreadExecutor().submit(errorStreamGobbler);
            StringBuilder strBuild = new StringBuilder();
            int exitCode = process.waitFor();
        } catch (IOException | InterruptedException e) {
            running.setValue(false);
            monitor.stopMonitoring();
            stop();
        }
    }

    public Process getProcess() {
        return process;
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
        devicesListCommand.add("1024M");
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
            devicesListCommand.add("256M");
            devicesListCommand.add("-f");
            devicesListCommand.add("dshow");
            devicesListCommand.add("-i");
            devicesListCommand.add("\"audio=" + audioDevice+"\"");
            int audioDelay = 800;
            filterCommand.append("[").append(i).append(":a]adelay=").append(audioDelay).append("|").append(audioDelay).append("[out").append(i).append("];");
            mapCommand.add("-map");
            mapCommand.add("\"[out"+i+"]\"");
        }

        filterCommand.append("[0:v]settb=AVTB,setpts=PTS-STARTPTS[vid]");
        filterComplexCommand.add("-filter_complex");
        filterComplexCommand.add("\""+ filterCommand +"\"");

        parameterCommand.add("-s");
        parameterCommand.add("hd720");
        parameterCommand.add("-c:v");
        parameterCommand.add("libx264");
        parameterCommand.add("-b:v");
        parameterCommand.add("2M");
        parameterCommand.add("-pix_fmt");
        parameterCommand.add("yuv420p");
        parameterCommand.add("-c:a");
        parameterCommand.add("aac");
        parameterCommand.add("-b:a");
        parameterCommand.add("128");
        parameterCommand.add("-f");
        parameterCommand.add("mpegts");
        parameterCommand.add("-mpegts_flags");
        parameterCommand.add("+initial_discontinuity");
        parameterCommand.add("-mpegts_start_pid");
        parameterCommand.add("0x20");

        outputCommand.add("-r");
        outputCommand.add("30");
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
        running.setValue(false);
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
}