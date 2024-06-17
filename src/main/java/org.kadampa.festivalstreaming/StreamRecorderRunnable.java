package org.kadampa.festivalstreaming;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;

public class StreamRecorderRunnable implements Runnable {

    private String srtUrl;
    private String outputDirectory;

    private String videoDevice;
    private final List<String> audioDevicesList = new ArrayList<>();
    private final List<String> audioInputsChannel = new ArrayList<>();

    private String outputResolution;
    private String pixelFormat;
    private String encoder;
    private String audioBitrate;
    private String videoBitrate;
    private String videoBufferSize;
    private String audioBufferSize;
    private boolean isTheOutputAFile;

    private int fps;
    private int delay;
    private Process process = null;
    private final StringProperty outputLineProperty = new SimpleStringProperty();
    private ProcessMonitor monitor;
    private final BooleanProperty isAliveProperty = new SimpleBooleanProperty(); // Create the property
    private int videoPid;

    public StreamRecorderRunnable() {

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
            process.waitFor();
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
        int videoDelay=0;
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
                //command to sync all the audio output

                filterCommand.append("[").append(i).append(":a]adelay=").append(delay).append("|").append(delay).append(",pan=mono|c0=c0[out").append(i).append("];");

                mapCommand.add("-map");
                mapCommand.add("\"[out"+i+"]\"");
            }
            else {
                filterCommand.append("[").append(i).append(":a]channelsplit=channel_layout=stereo[left").append(i).append("][right").append(i).append("];");
                if(audioInputsChannel.get(i-1).equals("Left")) {
                    filterCommand.append("[right").append(i).append("]anullsink;[left").append(i).append("]adelay=").append(delay).append("|").append(delay).append(",pan=mono|c0=c0[outleft").append(i).append("];");

                    mapCommand.add("-map");
                    mapCommand.add("\"[outleft"+i+"]\"");
                }
                if(audioInputsChannel.get(i-1).equals("Right")) {
                    filterCommand.append("[left").append(i).append("]anullsink;[right").append(i).append("]adelay=").append(delay).append("|").append(delay).append(",pan=mono|c0=c0[outright").append(i).append("];");
                    mapCommand.add("-map");
                    mapCommand.add("\"[outright"+i+"]\"");
                }
            }
            //On ffmpeg, all the inout devices are opened in sequence, resulting in a delay between each one, so each ffmpeg input is async of 620ms compare to the previous one
            //input. so we adjust the delay here.
            //TODO: Check if the delay is the same for on all computer.
            delay = delay+620;
            videoDelay+=620;
        }
        double videoDelayInS = videoDelay/1000.0;
        /*
         * settb=AVTB
         * settb stands for "set time base." The AVTB parameter is short for "audio-video time base." Here's what it does:
         * Time Base (tb): In FFmpeg, the time base represents the fundamental unit of time (in seconds) in terms of which frame timestamps are represented. A lower time base can represent smaller time intervals.
         * AVTB: This sets the time base to the standard audio-video time base used by FFmpeg. Essentially, it aligns the time base of the stream to the default time base used for audio and video.
         * setpts=PTS-STARTPTS
         * setpts stands for "set presentation timestamp." PTS and STARTPTS are key components here:
         * PTS (Presentation Timestamp): The PTS represents the exact time at which a particular frame should be presented to the viewer. Each frame in a video stream has a unique PTS.
         * STARTPTS: This is the PTS of the first frame in the stream.
         * setpts=PTS-STARTPTS adjusts the PTS of each frame so that the stream starts from zero. Here's what happens:
         */

        //Because of the audio async on each input, we have to delay the video
        filterCommand.append("[0:v]settb=AVTB,setpts=PTS+").append(videoDelayInS).append("/TB[vid]");
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
        /*
         * MPEG Transport Stream (MPEG-TS) is a standard digital container format used for transmission and storage of audio, video, and data.
         * It is defined by the MPEG-2 Part 1 specification (ISO/IEC standard 13818-1).
         * MPEG-TS is designed to address issues such as error correction and synchronization for streaming media.
         */
        parameterCommand.add("-f");
        parameterCommand.add("mpegts");
        /*
         * -mpegts_flags +initial_discontinuity
         * This flag is used to handle initial discontinuities in the stream.
         * A discontinuity occurs when there is a gap or an unexpected jump in the timestamps of the packets within the stream.
         * This can happen, for instance, when starting a live stream or switching between different sources.
         */
        parameterCommand.add("-mpegts_flags");
        parameterCommand.add("+initial_discontinuity");

        parameterCommand.add("-mpegts_start_pid");
        parameterCommand.add(String.valueOf(videoPid));

        outputCommand.add("-r");
        outputCommand.add(String.valueOf(fps));

        if(isTheOutputAFile) {
            LocalDateTime now = LocalDateTime.now();
            // Create a formatter to define the output format
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd ¦ HH mm-ss");
            String formattedDateTime = now.format(formatter);
            String fileName = "recorded-video-"+formattedDateTime+".mp4";
            outputCommand.add("\"" + outputDirectory + File.separatorChar + fileName + "\"");
        }
        else {
            outputCommand.add("\"" + srtUrl + "\"");
        }
        List<String> finalCommand = new ArrayList<>();
        finalCommand.add("ffmpeg");
        finalCommand.addAll(devicesListCommand);
        finalCommand.addAll(filterComplexCommand);
        finalCommand.addAll(mapCommand);
        finalCommand.addAll(parameterCommand);

        finalCommand.addAll(outputCommand);
        return finalCommand;
    }


    public void stop() {
        if (process != null) {
            destroyProcessAndChildren(process);
        }
    }

    public void initialiseAudioDevices(String[] deviceNames,String[] channelInfos) {
        audioDevicesList.clear();
        audioInputsChannel.clear();
        for (int i = 0; i< deviceNames.length; i++) {
            String deviceName = deviceNames[i];
            String deviceInputChannel = channelInfos[i];
            if (!Objects.equals(deviceName, "Not Used")) {
                audioDevicesList.add(deviceName);
                audioInputsChannel.add(deviceInputChannel);
            }
        }
    }

    public void initialiseVideoDevice(String deviceName) {
        videoDevice = deviceName;
    }
    private void destroyProcessAndChildren(Process process) {
        ProcessHandle processHandle = process.toHandle();
        processHandle.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            Thread.sleep(3000); // Wait for 5 seconds
        } catch (InterruptedException e) {
            e.printStackTrace();
            outputLineProperty.setValue(e.toString());
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

    public void setIsTheOutputAFile(boolean theOutputAFile) {
        isTheOutputAFile = theOutputAFile;
    }

    public void setOutputDirectory(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }
}