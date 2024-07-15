package org.kadampa.festivalstreaming;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Task;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;

public class StreamRecorderRunnable implements Runnable {

    private String srtUrl;
    private String outputDirectory;

    private String videoDevice;
    private final List<String> audioDevicesList = new ArrayList<>();
    private final List<String> audioInputsChannel = new ArrayList<>();
    private final List<Integer> noiseReductionValues = new ArrayList<>();
    private String outputResolution;
    private String pixelFormat;
    private String encoder;
    private String audioBitrate;
    private String videoBitrate;
    private String videoBufferSize;
    private String audioBufferSize;
    private boolean isTheOutputAFile;
    private int enMixDelay;
    private int fps;
    private int delay;
    private int timeNeededToOpenADevice;
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
        //The device 0 is the video input
        mapCommand.add("0:v");

        StringBuilder filterCommand = new StringBuilder();
        int i = 0;
        int j = 0;
        int videoDelay=0;
        int audioDelay = delay;

        //The audio devices
        Map<String,Integer> alreadyOpenedAudioDevices = new LinkedHashMap<>();
        int numberOfChannel= audioInputsChannel.size();
        for(String audioDevice:audioDevicesList) {
            i++;
            //Here we open the devices just one time for each device
            //we do this because it takes time to open each device and we have to sync all the audios and videos
            //together.
            if(!alreadyOpenedAudioDevices.containsKey(audioDevice)) {
                j++;
                devicesListCommand.add("-rtbufsize");
                devicesListCommand.add(audioBufferSize);
                devicesListCommand.add("-f");
                devicesListCommand.add("dshow");
                devicesListCommand.add("-i");
                devicesListCommand.add("\"audio=" + audioDevice + "\"");
                alreadyOpenedAudioDevices.put(audioDevice,j);
                if(j>1) {
                    audioDelay = audioDelay + timeNeededToOpenADevice;
                }
            }

            int deviceNumber = alreadyOpenedAudioDevices.get(audioDevice);

            //input 0 is Prayer to be mixed with other languages (english included)
            //input 1 is English voices to be mixed with other languages except english (at low level)
            //input 2 is English (not to be mixed with other language, but need the  prayer to be added)
            if(i==1) {
                //Here it's the prayer
                if(audioInputsChannel.get(i-1).equals("Join"))
                    filterCommand.append("[").append(deviceNumber).append(":a]adelay=").append(audioDelay).append("|").append(audioDelay).append(",pan=mono|c0=c0[prayers];");
                else
                    filterCommand.append("[").append(deviceNumber).append(":a]channelsplit=channel_layout=stereo[left").append(i).append("][right").append(i).append("];");
                if(audioInputsChannel.get(i-1).equals("Left"))
                    filterCommand.append("[right").append(i).append("]anullsink;[left").append(i).append("]adelay=").append(audioDelay).append("|").append(audioDelay).append(",pan=mono|c0=c0[prayers];");
                if(audioInputsChannel.get(i-1).equals("Right"))
                    filterCommand.append("[left").append(i).append("]anullsink;[right").append(i).append("]adelay=").append(audioDelay).append("|").append(audioDelay).append(",pan=mono|c0=c0[prayers];");
                //We new duplicate prayers to use it in the different mixes
                //The first 2 channel don't have the mix, because they are the prayers itself and the english to be mixed with the translation
                filterCommand.append("[prayers]asplit=").append(numberOfChannel-2);
                for (int  k=0;k<numberOfChannel-2;k++) {
                    //We start at 3 because the first need for the miw in english which is at index 3
                    filterCommand.append("[prayers").append(k+3).append("]");
                }
                filterCommand.append(";");
            } else if(i==2) {
                //TODO: we add a delay to remove the echo

                int audioDelayToRemoveEcho = audioDelay + enMixDelay;
                //Here it's the english low level to mix with other languages than english
                if(audioInputsChannel.get(i-1).equals("Join"))
                    filterCommand.append("[").append(deviceNumber).append(":a]adelay=").append(audioDelayToRemoveEcho).append("|").append(audioDelayToRemoveEcho).append(",pan=mono|c0=c0[englishToBeMixed];");
                else
                    filterCommand.append("[").append(deviceNumber).append(":a]channelsplit=channel_layout=stereo[left").append(i).append("][right").append(i).append("];");
                if(audioInputsChannel.get(i-1).equals("Left"))
                    filterCommand.append("[right").append(i).append("]anullsink;[left").append(i).append("]adelay=").append(audioDelayToRemoveEcho).append("|").append(audioDelayToRemoveEcho).append(",pan=mono|c0=c0[englishToBeMixed];");
                if(audioInputsChannel.get(i-1).equals("Right"))
                    filterCommand.append("[left").append(i).append("]anullsink;[right").append(i).append("]adelay=").append(audioDelayToRemoveEcho).append("|").append(audioDelayToRemoveEcho).append(",pan=mono|c0=c0[englishToBeMixed];");
                //We new duplicate to use it in the different mixes
                //The first 3 channel don't have the mix, because they are the prayers, the english to be mixed with the translation itself and the english
                filterCommand.append("[englishToBeMixed]asplit=").append(numberOfChannel-3);
                for (int  k=0;k<numberOfChannel-3;k++) {
                    //We start at 4 because the first need for the miw in the language after english which is at index 4
                    filterCommand.append("[englishToBeMixed").append(k+4).append("]");
                }
                filterCommand.append(";");

            } else if(i==3) {
                //Here it's the english that need the mix of the prayer but not the english low level
                if(audioInputsChannel.get(i-1).equals("Join"))
                    filterCommand.append("[").append(deviceNumber).append(":a]adelay=").append(audioDelay).append("|").append(audioDelay).append(",pan=mono|c0=c0");
                else
                    filterCommand.append("[").append(deviceNumber).append(":a]channelsplit=channel_layout=stereo[left").append(i).append("][right").append(i).append("];");
                if(audioInputsChannel.get(i-1).equals("Right")) {
                    filterCommand.append("[left").append(i).append("]anullsink;[right").append(i).append("]adelay=").append(audioDelay).append("|").append(audioDelay).append(",pan=mono|c0=c0");
                }
                if(audioInputsChannel.get(i-1).equals("Left")) {
                    filterCommand.append("[right").append(i).append("]anullsink;[left").append(i).append("]adelay=").append(audioDelay).append("|").append(audioDelay).append(",pan=mono|c0=c0");
                }
                //For the english language, the noiseReduction value has a different meaning that the other languages
                //Here, 3 means we use 100% of the filter, 2 means 75% and 1 means 50%
                //We also use the bd model that is trained to remove general noise but not human noises
                //We had 8dB because thefilter decrease the sound
                if(noiseReductionValues.get(i - 1)==1) {
                    filterCommand.append(",arnndn=model='/rnmodel/bd.rnnn:mix=0.3'");
                }
                if(noiseReductionValues.get(i - 1)==2) {
                    filterCommand.append(",arnndn=model='/rnmodel/bd.rnnn:mix=0.6'");
                }
                if(noiseReductionValues.get(i - 1)==3) {
                    filterCommand.append(",arnndn=model='/rnmodel/bd.rnnn'");
                }


                filterCommand.append("[englishfiltered").append(i).append("];");
                filterCommand.append("[prayers").append(i).append("][englishfiltered").append(i).append("]amix=inputs=2,volume=6dB").append("[outmixed").append(i).append("];");
                mapCommand.add("-map");
                mapCommand.add("\"[outmixed" + i + "]\";");
            } else {
                if(audioInputsChannel.get(i-1).equals("Join"))
                    filterCommand.append("[").append(deviceNumber).append(":a]adelay=").append(audioDelay).append("|").append(audioDelay).append(",pan=mono|c0=c0");
                else
                    filterCommand.append("[").append(deviceNumber).append(":a]channelsplit=channel_layout=stereo[left").append(i).append("][right").append(i).append("];");
                if(audioInputsChannel.get(i-1).equals("Right")) {
                    filterCommand.append("[left").append(i).append("]anullsink;[right").append(i).append("]adelay=").append(audioDelay).append("|").append(audioDelay).append(",pan=mono|c0=c0");
                }
                if(audioInputsChannel.get(i-1).equals("Left")) {
                    filterCommand.append("[right").append(i).append("]anullsink;[left").append(i).append("]adelay=").append(audioDelay).append("|").append(audioDelay).append(",pan=mono|c0=c0");
                }
                //For the other language, the noiseReduction value is the number of time we apply the filter
                //We use the sh model that is quite a strong filter
                for(int l=0;l<noiseReductionValues.get(i - 1);l++) {
                    filterCommand.append(",arnndn=model='/rnmodel/sh.rnnn'");
                }
                filterCommand.append("[outfiltered").append(i).append("];");
                filterCommand.append("[prayers").append(i).append("][englishToBeMixed").append(i).append("][outfiltered").append(i).append("]amix=inputs=3,volume=8dB").append("[outmixed").append(i).append("];");
                mapCommand.add("-map");
                mapCommand.add("\"[outmixed" + i + "]\";");
            }
        }

        filterComplexCommand.add("-filter_complex");
        filterComplexCommand.add("\""+ filterCommand +"\"");
        parameterCommand.add("-s");
        parameterCommand.add(outputResolution);
        parameterCommand.add("-c:v");
        parameterCommand.add(encoder);
        parameterCommand.add("-b:v");
        parameterCommand.add(videoBitrate);
        parameterCommand.add("-minrate:v");
        parameterCommand.add(videoBitrate);
        parameterCommand.add("-maxrate:v");
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

    public void initialiseAudioDevices(String[] deviceNames,String[] channelInfos,String[] noiseReductionVals) {
        audioDevicesList.clear();
        audioInputsChannel.clear();
        noiseReductionValues.clear();
        for (int i = 0; i< deviceNames.length; i++) {
            String deviceName = deviceNames[i];
            String deviceInputChannel = channelInfos[i];
            int noiseReductionIteration = Integer.parseInt(noiseReductionVals[i]);
            if (!Objects.equals(deviceName, "Not Used")) {
                audioDevicesList.add(deviceName);
                audioInputsChannel.add(deviceInputChannel);
                noiseReductionValues.add(noiseReductionIteration);
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

    public void setEnMixDelay(int delay) {
        enMixDelay = delay;
    }
    public void setIsTheOutputAFile(boolean theOutputAFile) {
        isTheOutputAFile = theOutputAFile;
    }

    public void setOutputDirectory(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public int getTimeNeededToOpenADevice() {
        return timeNeededToOpenADevice;
    }

    public void setTimeNeededToOpenADevice(int timeNeededToOpenADevice) {
        this.timeNeededToOpenADevice = timeNeededToOpenADevice;
    }
}