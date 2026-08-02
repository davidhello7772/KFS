package org.kadampa.festivalstreaming;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;

public class StreamRecorderRunnable implements Runnable {

    /** Seconds between keyframes; two is the usual choice for a live stream. */
    private static final int KEYFRAME_INTERVAL_SECONDS = 2;
    private static final String X264_ENCODER = "libx264";
    private static final String X264_PRESET = "veryfast";

    public final static int URL=1;
    public final static int FILE=2;
    public final static int FILE_AND_URL=3;

    private String srtUrl;
    private String outputDirectory;
    private final StreamingGUI appContext;
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
    private int outputType;
    private int enMixDelay;
    private int fps;
    private int delay;
    private int timeNeededToOpenADevice;
    private String ffmpegPath;
    private String videoInputMode;
    private String videoInputPixelFormat;
    private String audioSampleRate = "48000";
    /** On macOS the application reads the audio device and feeds ffmpeg through this. */
    private AudioPipe audioPipe;
    private String pipedDeviceName;
    /**
     * On Linux a multi-channel device is read by pw-record and piped into ffmpeg, because the
     * sound server's PulseAudio interface scrambles channel maps above eight channels: asked for
     * the Qu-5's 32, it delivers silence where the native reader delivers every channel in order
     * (verified live on the machine). Simple devices keep the pulse input.
     */
    private static final int PULSE_CHANNEL_LIMIT = 8;
    private List<String> pwRecordCommand;
    private Process pwRecordProcess;
    // Built on the FX thread when the Information tab is filled, consumed by the encoding thread
    private volatile List<String> preparedCommand;
    private Process process = null;
    private final StringProperty outputLineProperty = new SimpleStringProperty();
    private ProcessMonitor monitor;
    private final BooleanProperty isAliveProperty = new SimpleBooleanProperty(); // Create the property
    private int videoPid;
    private static final Logger logger = LoggerFactory.getLogger(StreamRecorderRunnable.class);

    public StreamRecorderRunnable(StreamingGUI streamingGUI) {
        appContext = streamingGUI;
    }

    @Override
    public void run() {
        List<String> command = takeCommand();
        try {
            if (pwRecordCommand != null) {
                // pw-record's output becomes ffmpeg's standard input
                ProcessBuilder recorder = new ProcessBuilder(pwRecordCommand)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                List<Process> pipeline = ProcessBuilder.startPipeline(List.of(recorder, new ProcessBuilder(command)));
                pwRecordProcess = pipeline.get(0);
                process = pipeline.get(1);
            } else {
                process = new ProcessBuilder(command).start();
            }
            if (audioPipe != null) {
                audioPipe.start(process.getOutputStream());
            }
            monitor = new ProcessMonitor(process, isAliveProperty);
            StreamGobbler inputStreamGobbler = new StreamGobbler(process.getInputStream(), outputLineProperty::setValue);
            StreamGobbler errorStreamGobbler = new StreamGobbler(process.getErrorStream(), outputLineProperty::setValue);
            Executors.newSingleThreadExecutor().submit(inputStreamGobbler);
            Executors.newSingleThreadExecutor().submit(errorStreamGobbler);
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            if (appContext.getSettings().isDevelopmentMode()) {
                throw new RuntimeException(e);
            } else {
                logger.error("An error occurred", e);
                if(monitor!=null) monitor.stopMonitoring();
                stop();
            }
        } finally {
            // However ffmpeg went away - clean exit, failed start, or crash - the recorder
            // feeding it must not stay behind
            stopPwRecord();
        }
    }

    private void stopPwRecord() {
        if (pwRecordProcess != null) {
            pwRecordProcess.destroy();
            pwRecordProcess = null;
        }
    }

    public Process getProcess() {
        return process;
    }

    /**
     * The command as text for the Information tab. The arguments themselves carry no quotes, so
     * they are quoted here for display only; pasting the result into a shell runs the same thing.
     * <p>
     * On macOS this also opens the audio capture, because the command cannot be written until the
     * rate and channel count the device is giving us are known.
     */
    public String getFFMpegCommand() {
        openAudioPipe();
        preparedCommand = initialiseFFMpegCommand();
        return formatForDisplay(preparedCommand);
    }

    /**
     * Starts reading the audio device so its samples can be sent to ffmpeg. Does nothing on
     * Windows, where ffmpeg opens the device itself.
     *
     * @return the format being captured, or null when there is nothing to pipe
     */
    private AudioFormat openAudioPipe() {
        if (!Host.isMac() || audioDevicesList.isEmpty()) {
            return null;
        }
        String deviceName = audioDevicesList.get(0);
        if (audioPipe != null && deviceName.equals(pipedDeviceName)) {
            return audioPipe.getCaptureFormat();
        }
        closeAudioPipe();
        Mixer.Info device = AudioCaptureManager.findCaptureDevice(deviceName);
        if (device == null) {
            logger.error("The audio device {} was not found", deviceName);
            return null;
        }
        audioPipe = new AudioPipe(device);
        pipedDeviceName = deviceName;
        return audioPipe.open();
    }

    private void closeAudioPipe() {
        if (audioPipe != null) {
            audioPipe.stop();
            audioPipe = null;
            pipedDeviceName = null;
        }
    }

    /**
     * The command to run. The GUI asks for the text of the command just before starting, and we
     * reuse exactly that one so the recording shown is the recording made, timestamp included.
     */
    private List<String> takeCommand() {
        if (preparedCommand == null) {
            openAudioPipe();
        }
        List<String> command = preparedCommand != null ? preparedCommand : initialiseFFMpegCommand();
        preparedCommand = null;
        return command;
    }

    static String formatForDisplay(List<String> command) {
        StringBuilder text = new StringBuilder();
        for (String argument : command) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            if (argument.isEmpty() || argument.matches(".*[\\s\"'|;&<>()$*?\\[\\]].*")) {
                text.append('"').append(argument.replace("\"", "\\\"")).append('"');
            } else {
                text.append(argument);
            }
        }
        return text.toString();
    }

    private List<String> initialiseFFMpegCommand() {
        pwRecordCommand = null;
        List<String> devicesListCommand = new ArrayList<>();
        List<String> filterComplexCommand = new ArrayList<>();
        List<String> mapCommand = new ArrayList<>();
        List<String> parameterCommand = new ArrayList<>();
        List<String> outputCommand = new ArrayList<>();
        //The video device
        addVideoInput(devicesListCommand);
        mapCommand.add("-map");
        //The device 0 is the video input
        mapCommand.add("0:v");

        StringBuilder filterCommand = new StringBuilder();
        int i = 0;
        int j = 0;
        int audioDelay = delay;
        int languageCount = 0;

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
                addAudioInput(devicesListCommand, audioDevice);
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
                filterCommand.append(pickChannel(deviceNumber, audioInputsChannel.get(0), audioDelay)).append("[prayers];");
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
                filterCommand.append(pickChannel(deviceNumber, audioInputsChannel.get(i-1), audioDelayToRemoveEcho))
                        .append("[englishToBeMixed];");
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
                filterCommand.append(pickChannel(deviceNumber, audioInputsChannel.get(i-1), audioDelay));
                //For the english language, the noiseReduction value has a different meaning that the other languages
                //Here, 3 means we use 100% of the filter, 2 means 75% and 1 means 50%
                //We also use the bd model that is trained to remove general noise but not human noises
                //We had 8dB because the filter decrease the sound
                String generalNoiseModel = NoiseModels.modelPathForFilter(NoiseModels.GENERAL_NOISE_MODEL);
                if(noiseReductionValues.get(i - 1)==1) {
                    filterCommand.append(",arnndn=model='").append(generalNoiseModel).append("':mix=0.3");
                }
                if(noiseReductionValues.get(i - 1)==2) {
                    filterCommand.append(",arnndn=model='").append(generalNoiseModel).append("':mix=0.6");
                }
                if(noiseReductionValues.get(i - 1)==3) {
                    filterCommand.append(",arnndn=model='").append(generalNoiseModel).append("'");
                }


                filterCommand.append("[englishfiltered").append(i).append("];");
                filterCommand.append("[prayers").append(i).append("][englishfiltered").append(i).append("]amix=inputs=2,volume=7.6dB").append("[outmixed").append(i).append("];");
                mapCommand.add("-map");
                mapCommand.add("[outmixed" + i + "]");

            } else {
                filterCommand.append(pickChannel(deviceNumber, audioInputsChannel.get(i-1), audioDelay));
                //For the other language, the noiseReduction value is the number of time we apply the filter
                //We use the sh model that is quite a strong filter
                String speechModel = ",arnndn=model='" + NoiseModels.modelPathForFilter(NoiseModels.SPEECH_MODEL) + "'";
                filterCommand.append(speechModel.repeat(Math.max(0, noiseReductionValues.get(i - 1))));
                filterCommand.append("[outfiltered").append(i).append("];");
                filterCommand.append("[prayers").append(i).append("][englishToBeMixed").append(i).append("][outfiltered").append(i).append("]amix=inputs=3,volume=9.3dB").append("[outmixed").append(i).append("];");
                mapCommand.add("-map");
                mapCommand.add("[outmixed" + i + "]");
            }
        }

        for (int k = 2; k < Settings.LANGUAGES.length; k++) {
            if (!appContext.getInputAudioSources()[k].getValue().equals("Not Used")) {
                parameterCommand.add("-metadata:s:a:" + languageCount);
                // Use nativeName if available, otherwise use name
                String title = Settings.LANGUAGES[k].nativeName() != null ? Settings.LANGUAGES[k].nativeName() : Settings.LANGUAGES[k].name();
                parameterCommand.add("title=" + title);
                parameterCommand.add("-metadata:s:a:" + languageCount);
                parameterCommand.add("language=" + Settings.LANGUAGES[k].code());
                languageCount++;
            }
        }


        filterComplexCommand.add("-filter_complex");
        filterComplexCommand.add(filterCommand.toString());
        parameterCommand.add("-s");
        parameterCommand.add(outputResolution);
        parameterCommand.add("-c:v");
        parameterCommand.add(Host.encoderCodec(encoder));
        parameterCommand.add("-b:v");
        parameterCommand.add(videoBitrate);
        parameterCommand.add("-minrate:v");
        parameterCommand.add(videoBitrate);
        parameterCommand.add("-maxrate:v");
        parameterCommand.add(videoBitrate);
        addStreamingEncoderSettings(parameterCommand);
        parameterCommand.add("-pix_fmt");
        parameterCommand.add(pixelFormat);
        parameterCommand.add("-c:a");
        parameterCommand.add("aac");
        //Pinned rather than inherited from the capture device: see setAudioSampleRate
        parameterCommand.add("-ar");
        parameterCommand.add(audioSampleRate);
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


        LocalDateTime now = LocalDateTime.now();
        // Create a formatter to define the output format
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        String formattedDateTime = now.format(formatter);
        String fileName = "recorded-video-"+formattedDateTime+"-"+outputResolution+".mp4";
        if(outputType==FILE_AND_URL) {
            outputCommand.add("-f");
            outputCommand.add("tee");
            outputCommand.add("[f=mpegts:onfail=ignore]" + formatForFFmpegTee(outputDirectory + File.separatorChar + fileName) + " | [f=mpegts]"+ srtUrl);

        } else if(outputType==FILE) {
                outputCommand.add(outputDirectory + File.separatorChar + fileName);
            }
            else {
                outputCommand.add(srtUrl);
            }
        List<String> finalCommand = new ArrayList<>();
        finalCommand.add(Host.ffmpegExecutable(ffmpegPath));
        finalCommand.addAll(devicesListCommand);
        finalCommand.addAll(filterComplexCommand);
        finalCommand.addAll(mapCommand);
        finalCommand.addAll(parameterCommand);

        finalCommand.addAll(outputCommand);
        return finalCommand;
    }

    /**
     * The encoder settings a distributor expects from a live stream, none of which ffmpeg does by
     * default. Not on Windows: that machine has been streaming happily for years and its command
     * is deliberately left alone.
     * <p>
     * Castr's requirements are constant bitrate, a two second keyframe interval and x264, and the
     * stream was meeting none of them. Without an explicit buffer x264 never switches its rate
     * controller on, so {@code -maxrate} did nothing and the measured bitrate swung between 728
     * and 4288kbps against a 2500kbps target, which is what was stalling players. Without
     * {@code -sc_threshold 0} it also inserts its own keyframes wherever the picture changes,
     * giving intervals of 36 and 48 frames where 60 was asked for. And ffmpeg's default keyframe
     * interval is twelve frames, which spends nearly 40% of the bitrate on keyframes and leaves
     * too little for everything in between.
     */
    private void addStreamingEncoderSettings(List<String> parameterCommand) {
        if (Host.isWindows()) {
            return;
        }
        // A one second buffer, which is what OBS uses for constant bitrate
        parameterCommand.add("-bufsize:v");
        parameterCommand.add(videoBitrate);
        parameterCommand.add("-g");
        parameterCommand.add(String.valueOf(Math.max(1, fps * KEYFRAME_INTERVAL_SECONDS)));
        if (X264_ENCODER.equals(Host.encoderCodec(encoder))) {
            /*
             * x264's own default preset leaves too little margin to encode live: on a six core
             * machine it managed only 1.8 times real time on demanding material, before the audio
             * filters take their share. veryfast keeps three times real time and still encodes
             * better than the hardware encoder does at the same bitrate; the settings offer the
             * slower ones for a machine with processor time to spend on picture quality.
             */
            String preset = Host.encoderPreset(encoder);
            parameterCommand.add("-preset");
            parameterCommand.add(preset != null ? preset : X264_PRESET);
            // Holds the transmitted rate steady, which is what the ingest actually measures
            parameterCommand.add("-x264-params");
            parameterCommand.add("nal-hrd=cbr");
            parameterCommand.add("-sc_threshold");
            parameterCommand.add("0");
        }
    }

    /**
     * The video input. DirectShow names the device inside the input URL and negotiates the
     * format itself; AVFoundation takes the name as the URL and refuses to open at all unless
     * it is given a frame rate and a size it has advertised. Video4Linux2 takes the device path
     * and accepts a size and format it advertised, clamping the frame rate itself.
     */
    private void addVideoInput(List<String> devicesListCommand) {
        devicesListCommand.add("-rtbufsize");
        devicesListCommand.add(videoBufferSize);
        devicesListCommand.add("-f");
        devicesListCommand.add(Host.captureFormat());
        if (Host.isMac()) {
            AvFoundationDevices.CaptureMode mode = AvFoundationDevices.CaptureMode.parse(videoInputMode);
            devicesListCommand.add("-framerate");
            devicesListCommand.add(String.valueOf(mode != null ? mode.frameRate() : fps));
            if (mode != null) {
                devicesListCommand.add("-video_size");
                devicesListCommand.add(mode.videoSize());
            }
            // How the frames arrive, which is not the format they are encoded in
            if (videoInputPixelFormat != null && !videoInputPixelFormat.isBlank()) {
                devicesListCommand.add("-pixel_format");
                devicesListCommand.add(videoInputPixelFormat);
            }
            devicesListCommand.add("-i");
            devicesListCommand.add(videoDevice + ":");
        } else if (Host.isLinux()) {
            // While ffmpeg is still opening the later inputs, frames from this one have to
            // queue; the default queue is far too short for that and drops them with a warning
            devicesListCommand.add("-thread_queue_size");
            devicesListCommand.add("1024");
            // The camera stamps frames with the machine's uptime; the sound server stamps its
            // audio with the time of day. Asking for wallclock stamps here puts the two inputs
            // on the same clock, so the offsets ffmpeg reports between them mean something
            devicesListCommand.add("-use_wallclock_as_timestamps");
            devicesListCommand.add("1");
            AvFoundationDevices.CaptureMode mode = AvFoundationDevices.CaptureMode.parse(videoInputMode);
            devicesListCommand.add("-framerate");
            devicesListCommand.add(String.valueOf(mode != null ? mode.frameRate() : fps));
            if (mode != null) {
                devicesListCommand.add("-video_size");
                devicesListCommand.add(mode.videoSize());
            }
            if (videoInputPixelFormat != null && !videoInputPixelFormat.isBlank()) {
                devicesListCommand.add("-input_format");
                devicesListCommand.add(videoInputPixelFormat);
            }
            devicesListCommand.add("-i");
            devicesListCommand.add(V4l2Devices.devicePath(videoDevice));
        } else {
            devicesListCommand.add("-i");
            devicesListCommand.add("video=" + videoDevice);
        }
    }

    /**
     * The audio input. On Windows ffmpeg opens the device itself. On macOS it is handed the
     * samples over its standard input instead, because its AVFoundation input loses a large and
     * varying share of the audio of a multi-channel device: see {@link AudioPipe}. On Linux it
     * reads from the sound server, which shares the device with the level meters and resamples
     * every device to the configured rate whatever its native one: see {@link PulseAudioDevices}.
     */
    private void addAudioInput(List<String> devicesListCommand, String audioDevice) {
        if (Host.isMac()) {
            AudioFormat format = audioPipe != null ? audioPipe.getCaptureFormat() : null;
            devicesListCommand.add("-f");
            devicesListCommand.add("s16le");
            devicesListCommand.add("-ar");
            devicesListCommand.add(String.valueOf(format != null ? (int) format.getSampleRate() : 48000));
            devicesListCommand.add("-ac");
            devicesListCommand.add(String.valueOf(format != null ? format.getChannels() : 2));
            devicesListCommand.add("-i");
            devicesListCommand.add("pipe:0");
            return;
        }
        if (Host.isLinux()) {
            int channels = linuxChannelCount(audioDevice);
            String sourceName = PulseAudioDevices.resolveName(ffmpegPath, audioDevice);
            if (channels > PULSE_CHANNEL_LIMIT && pwRecordCommand == null) {
                // The mixer: read natively and hand the samples to ffmpeg over its standard
                // input. pw-record resamples to the configured rate like the server would.
                pwRecordCommand = List.of("pw-record",
                        "--target", sourceName != null ? sourceName : audioDevice,
                        "--rate", audioSampleRate,
                        "--channels", String.valueOf(channels),
                        "--format", "s16",
                        "-");
                devicesListCommand.add("-f");
                devicesListCommand.add("s16le");
                devicesListCommand.add("-thread_queue_size");
                devicesListCommand.add("1024");
                // The samples are stamped by counting, which is the only clean clock a pipe
                // has. Stamping them on arrival instead was tried for drift correction and
                // withdrawn: under encoding load the arrival times jitter by whole buffers,
                // and a resampler chasing that jitter shreds the audio audibly.
                devicesListCommand.add("-ar");
                devicesListCommand.add(audioSampleRate);
                devicesListCommand.add("-ac");
                devicesListCommand.add(String.valueOf(channels));
                devicesListCommand.add("-i");
                devicesListCommand.add("pipe:0");
                return;
            }
            devicesListCommand.add("-f");
            devicesListCommand.add("pulse");
            devicesListCommand.add("-thread_queue_size");
            devicesListCommand.add("1024");
            // The server resamples this device to the configured rate, so devices with
            // different native rates all arrive uniform
            devicesListCommand.add("-sample_rate");
            devicesListCommand.add(audioSampleRate);
            devicesListCommand.add("-channels");
            devicesListCommand.add(String.valueOf(channels));
            devicesListCommand.add("-i");
            devicesListCommand.add(sourceName != null ? sourceName : audioDevice);
            return;
        }
        devicesListCommand.add("-rtbufsize");
        devicesListCommand.add(audioBufferSize);
        devicesListCommand.add("-f");
        devicesListCommand.add("dshow");
        devicesListCommand.add("-i");
        devicesListCommand.add("audio=" + audioDevice);
    }

    /**
     * How many channels to ask the sound server for: what the hardware has, and never fewer
     * than the highest channel a language actually reads from this device.
     */
    private int linuxChannelCount(String audioDevice) {
        int highestUsed = 0;
        for (int index = 0; index < audioDevicesList.size(); index++) {
            if (audioDevicesList.get(index).equals(audioDevice)) {
                highestUsed = Math.max(highestUsed,
                        SettingsUtil.audioChannelIndex(audioInputsChannel.get(index)) + 1);
            }
        }
        return Math.max(Math.max(2, highestUsed),
                PulseAudioDevices.channelCount(ffmpegPath, audioDevice));
    }

    /**
     * Takes one language off an input and delays it: the head of every audio chain.
     * <p>
     * A single pan covers every case. On the stereo cables of the Windows machine Left and Right
     * are channels 0 and 1, and on a mixer presenting all its inputs as one device the language
     * sits on its own numbered channel. Join keeps the first channel, as it always did.
     */
    private String pickChannel(int deviceNumber, String channel, int audioDelay) {
        return "[" + deviceNumber + ":a]pan=mono|c0=c" + SettingsUtil.audioChannelIndex(channel)
                + ",adelay=" + audioDelay;
    }


    public void stop() {
        closeAudioPipe();
        stopPwRecord();
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
            if (!Objects.equals(deviceName, SettingsUtil.AUDIO_SOURCE_NOT_USED)) {
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
            logger.error("An error occurred", e);
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
    public void setOutputType(int theOutputAFile) {
        outputType = theOutputAFile;
    }

    public void setOutputDirectory(String outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public void setTimeNeededToOpenADevice(int timeNeededToOpenADevice) {
        this.timeNeededToOpenADevice = timeNeededToOpenADevice;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    /** The AVFoundation capture mode, as {@code 1920x1080@30}. Ignored on Windows. */
    public void setVideoInputMode(String videoInputMode) {
        this.videoInputMode = videoInputMode;
    }

    /** The format frames arrive in from the camera, not the format they are encoded in. */
    public void setVideoInputPixelFormat(String videoInputPixelFormat) {
        this.videoInputPixelFormat = videoInputPixelFormat;
    }

    /**
     * The rate the recording is encoded at. It has to be the rate the capture devices actually
     * run at: a device whose declared rate does not match what it delivers produces a recording
     * with a fraction of the audio in it, spread over the full running time.
     */
    public void setAudioSampleRate(String audioSampleRate) {
        if (audioSampleRate != null && !audioSampleRate.isBlank()) {
            this.audioSampleRate = audioSampleRate.trim();
        }
    }

    /**
     * Formats a file path to a tee-compatible FFmpeg output path.
     */
    public static String formatForFFmpegTee(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Path cannot be null or empty.");
        }

        // Convert to forward slashes
        String unixStylePath = path.replace("\\", "/");

        // Escape percent signs for tee muxer
        String escapedPath = unixStylePath.replace("%", "\\%");

        // Format as a tee-compatible slave URL
        return String.format("'%s'", escapedPath);
    }
}