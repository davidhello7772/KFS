package org.kadampa.festivalstreaming;

import javax.sound.sampled.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages audio capture from various input devices using a singleton pattern.
 * This class handles the lifecycle of audio capture threads for each device,
 * dispatching audio data to registered listeners and managing live monitoring output.
 */
public class AudioCaptureManager {
    private static final AudioCaptureManager INSTANCE = new AudioCaptureManager();

    private final Map<Mixer.Info, DeviceCapture> deviceCaptures = new ConcurrentHashMap<>();

    /** The rate to ask devices for, from the settings. It is also the rate recordings are made at. */
    private static volatile int preferredSampleRate = 48000;

    private AudioCaptureManager() {}

    /**
     * Sets the rate capture devices are opened at. Takes effect the next time a device is opened,
     * so it is set from the settings before any meter or recording starts.
     */
    public static void setPreferredSampleRate(int sampleRate) {
        if (sampleRate > 0) {
            preferredSampleRate = sampleRate;
        }
    }

    /**
     * Returns the singleton instance of the AudioCaptureManager.
     *
     * @return The single instance of this class.
     */
    public static AudioCaptureManager getInstance() {
        return INSTANCE;
    }

    /**
     * The capture device of a given name, or null when there is none.
     * <p>
     * A device usually appears twice, once as a port and once as the line that can actually be
     * recorded from, so the one with a capture line is the one wanted.
     */
    public static Mixer.Info findCaptureDevice(String deviceName) {
        if (deviceName == null || deviceName.isBlank() || SettingsUtil.AUDIO_SOURCE_NOT_USED.equals(deviceName)) {
            return null;
        }
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (!deviceName.equals(info.getName())) {
                continue;
            }
            for (Line.Info lineInfo : AudioSystem.getMixer(info).getTargetLineInfo()) {
                if (TargetDataLine.class.isAssignableFrom(lineInfo.getLineClass())) {
                    return info;
                }
            }
        }
        return null;
    }

    /**
     * Starts live monitoring for a specific audio device and channel.
     *
     * @param mixerInfo The mixer info for the device to monitor.
     * @param channel   The audio channel to monitor ("Left", "Right", or "Stereo").
     */
    public void startMonitoring(Mixer.Info mixerInfo, String channel) {
        DeviceCapture capture = deviceCaptures.get(mixerInfo);
        if (capture != null) {
            capture.startMonitoring(channel);
        }
    }

    /**
     * Stops live monitoring for a specific audio device.
     *
     * @param mixerInfo The mixer info for the device to stop monitoring.
     */
    public void stopMonitoring(Mixer.Info mixerInfo) {
        DeviceCapture capture = deviceCaptures.get(mixerInfo);
        if (capture != null) {
            capture.stopMonitoring();
        }
    }

    /**
     * A listener interface for receiving raw audio data from a capture device.
     */
    public interface AudioDataListener {
        /**
         * Called when new audio data is available.
         *
         * @param buffer     The byte buffer containing the audio data.
         * @param bytesRead  The number of bytes read into the buffer.
         * @param format     The AudioFormat of the captured data.
         */
        void onAudioData(byte[] buffer, int bytesRead, AudioFormat format);

        /**
         * Called when the capture thread dies on an error (e.g. the device is
         * held exclusively by another process and the line cannot be opened).
         *
         * @param message A human-readable description of the failure.
         */
        default void onCaptureError(String message) {}
    }

    /**
     * Represents and manages the audio capture process for a single audio device.
     * It handles the capture thread, listeners, and monitoring output.
     */
    private static class DeviceCapture {
        private final Mixer.Info mixerInfo;
        private final List<AudioDataListener> listeners = new CopyOnWriteArrayList<>();
        private Thread captureThread;
        private TargetDataLine inputLine;
        private SourceDataLine outputLine;
        private volatile boolean running = false;
        private volatile boolean monitoring = false;
        // Set by the FX thread; the capture thread opens/prefills/starts the output
        // line itself so playback never begins on an empty running line (crackle).
        private volatile boolean monitorStartPending = false;
        private volatile String channel;
        private AudioFormat captureFormat;
        private AudioFormat playbackFormat;

        DeviceCapture(Mixer.Info mixerInfo) {
            this.mixerInfo = mixerInfo;
        }

        /**
         * Adds a listener to receive audio data. Starts the capture thread if it's the first listener.
         *
         * @param listener The listener to add.
         */
        void addListener(AudioDataListener listener) {
            listeners.add(listener);
            if (listeners.size() == 1) {
                start();
            }
        }

        /**
         * Removes a listener. Stops the capture thread if it's the last listener.
         *
         * @param listener The listener to remove.
         */
        void removeListener(AudioDataListener listener) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                stop();
            }
        }

        /**
         * Enables audio monitoring for a specific channel.
         *
         * @param channel The channel to monitor ("Left", "Right", "Stereo").
         */
        void startMonitoring(String channel) {
            this.channel = channel;
            monitorStartPending = true;
        }

        /**
         * Disables audio monitoring.
         */
        void stopMonitoring() {
            monitorStartPending = false;
            monitoring = false;
            SourceDataLine line = outputLine;
            if (line != null) {
                line.stop();
                line.flush();
            }
        }

        /**
         * The capture formats to try, best first.
         * <p>
         * A device with more than two inputs is a mixer carrying one language per channel, and
         * all of them have to be captured together: the meters share a single line and each
         * reads its own channel out of it. A stereo cable keeps the original preferences.
         *
         * @return supported formats in preference order, never empty
         */
        private List<AudioFormat> findCaptureFormats() {
            List<AudioFormat> candidates = new ArrayList<>();
            int deviceChannels = maxDeviceChannels();
            int preferred = preferredSampleRate;
            if (deviceChannels > 2) {
                // CoreAudio converts from whatever the device runs at, so the configured rate
                // is asked for first and the device's own common rates are only fallbacks
                for (float rate : new float[]{preferred, 48000, 44100, 96000}) {
                    candidates.add(new AudioFormat(rate, 16, deviceChannels, true, false));
                }
                candidates.add(new AudioFormat(preferred, 24, deviceChannels, true, false));
            }
            candidates.add(new AudioFormat(preferred, 16, 2, true, false));
            candidates.add(new AudioFormat(48000, 16, 2, true, false));
            candidates.add(new AudioFormat(44100, 16, 2, true, false));
            candidates.add(new AudioFormat(48000, 24, 2, true, false));
            candidates.add(new AudioFormat(44100, 24, 2, true, false));
            candidates.add(new AudioFormat(preferred, 16, 1, true, false));
            candidates.add(new AudioFormat(48000, 16, 1, true, false));
            candidates.add(new AudioFormat(44100, 16, 1, true, false));

            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            List<AudioFormat> supported = new ArrayList<>();
            for (AudioFormat format : candidates) {
                if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, format))) {
                    supported.add(format);
                }
            }
            if (supported.isEmpty()) {
                supported.add(new AudioFormat(44100, 16, 2, true, false)); // Fallback
            }
            return supported;
        }

        /** How many inputs this device offers, which is how many languages it can carry. */
        private int maxDeviceChannels() {
            int maximum = 0;
            for (Line.Info lineInfo : AudioSystem.getMixer(mixerInfo).getTargetLineInfo()) {
                if (lineInfo instanceof DataLine.Info dataLineInfo) {
                    for (AudioFormat format : dataLineInfo.getFormats()) {
                        maximum = Math.max(maximum, format.getChannels());
                    }
                }
            }
            return maximum;
        }

        /**
         * Sets up the output line (SourceDataLine) for audio monitoring playback.
         * The line is opened but NOT started: the capture thread prefills it with
         * silence and starts it only when monitoring actually begins.
         */
        private void setupOutputLine() {
            try {
                DataLine.Info outputInfo = new DataLine.Info(SourceDataLine.class, playbackFormat);
                outputLine = (SourceDataLine) AudioSystem.getLine(outputInfo);
                // Increased buffer to 400ms for more stability
                int outputBufferSize = (int) (playbackFormat.getSampleRate() * playbackFormat.getFrameSize() * 0.4f);
                outputLine.open(playbackFormat, outputBufferSize);
            } catch (LineUnavailableException e) {
                System.err.println("Failed to setup output line: " + e.getMessage());
                outputLine = null;
            }
        }

        /**
         * Transitions monitoring on, called on the capture thread. Lazily opens the
         * output line, then prefills it with ~200ms of silence before starting it so
         * the play cursor never catches the write pointer while the buffer ramps up
         * (that starvation is what caused the crackling at monitoring start).
         */
        private void beginMonitoringPlayback() {
            if (outputLine == null) {
                setupOutputLine();
            }
            if (outputLine == null) {
                return;
            }
            outputLine.stop();
            outputLine.flush();
            int silenceBytes = (int) (playbackFormat.getSampleRate() * playbackFormat.getFrameSize() * 0.2f);
            silenceBytes -= silenceBytes % playbackFormat.getFrameSize();
            outputLine.write(new byte[silenceBytes], 0, silenceBytes);
            outputLine.start();
            monitoring = true;
        }

        /**
         * Processes raw audio data for monitoring. This includes channel selection,
         * down-mixing from stereo to mono, and bit-depth conversion.
         *
         * @param inputBuffer  The raw audio buffer from the input line.
         * @param bytesRead    The number of bytes read from the input buffer.
         * @param outputBuffer The buffer to write the processed audio data into.
         * @return The number of bytes written to the output buffer.
         */
        private int processAudioForMonitoring(byte[] inputBuffer, int bytesRead, byte[] outputBuffer) {
            int inputChannels = captureFormat.getChannels();
            int inputBitDepth = captureFormat.getSampleSizeInBits();
            int outputChannels = playbackFormat.getChannels();
            int outputBitDepth = playbackFormat.getSampleSizeInBits();

            if (inputChannels == 1 && inputBitDepth == 16 && outputChannels == 1 && outputBitDepth == 16) {
                System.arraycopy(inputBuffer, 0, outputBuffer, 0, bytesRead);
                return bytesRead;
            }

            int inputFrameSize = captureFormat.getFrameSize();
            int outputFrameSize = playbackFormat.getFrameSize();
            int frameCount = bytesRead / inputFrameSize;
            int outputBytes = frameCount * outputFrameSize;

            // Default to stereo if channel is not specified
            if (channel == null) {
                channel = SettingsUtil.AUDIO_CHANNEL_STEREO;
            }
            // Stereo mixes both sides; anything else names a single input to listen to
            boolean mixBothChannels = inputChannels == 2
                    && !SettingsUtil.AUDIO_CHANNEL_LEFT.equals(channel)
                    && !SettingsUtil.AUDIO_CHANNEL_RIGHT.equals(channel);
            int selectedChannel = Math.min(SettingsUtil.audioChannelIndex(channel), inputChannels - 1);

            for (int i = 0; i < frameCount; i++) {
                long monoSample;
                if (mixBothChannels) {
                    monoSample = (AudioSamples.readSample(inputBuffer, i * inputFrameSize, 0, inputBitDepth)
                            + AudioSamples.readSample(inputBuffer, i * inputFrameSize, 1, inputBitDepth)) / 2;
                } else {
                    monoSample = AudioSamples.readSample(inputBuffer, i * inputFrameSize, selectedChannel, inputBitDepth);
                }

                if (outputBitDepth == 16) {
                    short finalSample;
                    if (inputBitDepth == 24) {
                        finalSample = (short) (monoSample >> 8);
                    } else {
                        finalSample = (short) monoSample;
                    }
                    outputBuffer[i * outputFrameSize] = (byte) (finalSample & 0xFF);
                    outputBuffer[i * outputFrameSize + 1] = (byte) (finalSample >> 8);
                }
            }
            return outputBytes;
        }

        /**
         * Starts the audio capture thread.
         * The thread reads from the TargetDataLine, dispatches data to listeners,
         * and writes to the monitoring line if enabled.
         */
        private void start() {
            if (running) return;
            running = true;
            captureThread = new Thread(() -> {
                try {
                    openInputLine();
                    inputLine.start();
                    playbackFormat = new AudioFormat(captureFormat.getSampleRate(), 16, 1, true, false);

                    byte[] inputBuffer = new byte[inputLine.getBufferSize()];
                    int outputBufferSize = (inputBuffer.length / captureFormat.getFrameSize()) * playbackFormat.getFrameSize();
                    byte[] outputBuffer = new byte[outputBufferSize];

                    // Read a quarter of the line buffer per call (~37ms): keeps the meters
                    // responsive and leaves headroom in the line buffer against overruns.
                    int chunkSize = inputBuffer.length / 4;
                    chunkSize -= chunkSize % captureFormat.getFrameSize();
                    if (chunkSize <= 0) {
                        chunkSize = captureFormat.getFrameSize();
                    }

                    System.out.println("Started capture: " + captureFormat + ", buffer: " + inputBuffer.length + " bytes");

                    while (running) {
                        int bytesRead = inputLine.read(inputBuffer, 0, chunkSize);
                        if (bytesRead > 0) {
                            for (AudioDataListener listener : listeners) {
                                listener.onAudioData(inputBuffer, bytesRead, captureFormat);
                            }
                            if (monitorStartPending) {
                                monitorStartPending = false;
                                beginMonitoringPlayback();
                            }
                            if (monitoring) {
                                writeToMonitor(inputBuffer, bytesRead, outputBuffer);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error in audio capture thread for " + mixerInfo.getName() + ": " + e.getMessage());
                    for (AudioDataListener listener : listeners) {
                        listener.onCaptureError(e.getMessage());
                    }
                } finally {
                    cleanup();
                }
            });
            captureThread.setDaemon(true);
            captureThread.setName("AudioCapture-" + mixerInfo.getName());
            captureThread.start();
        }

        /**
         * Opens the capture line, walking down the supported formats. A device can advertise a
         * format it then refuses to open, so being told "supported" is not enough on its own.
         *
         * @throws LineUnavailableException if none of the formats could be opened
         */
        private void openInputLine() throws LineUnavailableException {
            LineUnavailableException lastFailure = null;
            for (AudioFormat format : findCaptureFormats()) {
                try {
                    DataLine.Info inputInfo = new DataLine.Info(TargetDataLine.class, format);
                    TargetDataLine line = (TargetDataLine) AudioSystem.getMixer(mixerInfo).getLine(inputInfo);

                    // Increased buffer to 150ms for more stability
                    float bufferDurationMs = 150.0f;
                    int inputBufferSize = (int) (format.getSampleRate() * format.getFrameSize() * bufferDurationMs / 1000.0f);
                    inputBufferSize -= inputBufferSize % format.getFrameSize();

                    line.open(format, inputBufferSize);
                    inputLine = line;
                    captureFormat = format;
                    return;
                } catch (LineUnavailableException | IllegalArgumentException e) {
                    lastFailure = e instanceof LineUnavailableException unavailable
                            ? unavailable : new LineUnavailableException(e.getMessage());
                    System.err.println("Could not open " + mixerInfo.getName() + " as " + format + ": " + e.getMessage());
                }
            }
            throw lastFailure != null ? lastFailure
                    : new LineUnavailableException("No usable capture format for " + mixerInfo.getName());
        }

        /**
         * Cleans up audio resources, stopping and closing input and output lines.
         */
        private void cleanup() {
            System.out.println("Cleaning capture for " + mixerInfo.getName() + " - input:" + inputLine + " - output:" + outputLine);
            if (inputLine != null) {
                inputLine.stop();
                inputLine.close();
                inputLine = null;
            }
            if (outputLine != null) {
                outputLine.stop();
                outputLine.close();
                outputLine = null;
            }
        }

        /**
         * Stops the audio capture thread and waits briefly for it to release the line,
         * so a capture for the same device can be reopened immediately afterwards.
         * Safe to call more than once.
         */
        private void stop() {
            running = false;
            Thread thread = captureThread;
            captureThread = null;
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("Stopped capture for " + mixerInfo.getName());
            }
        }

        /**
         * Checks if there are any active listeners for this device.
         *
         * @return True if there is at least one listener, false otherwise.
         */
        boolean hasListeners() {
            return !listeners.isEmpty();
        }

        /**
         * Writes processed audio data to the output line for monitoring.
         *
         * @param buffer      The raw audio buffer.
         * @param bytesRead   The number of bytes read from the raw buffer.
         * @param outputBuffer The buffer to write the processed data into.
         */
        private void writeToMonitor(byte[] buffer, int bytesRead, byte[] outputBuffer) {
            if (outputLine != null && outputLine.isOpen()) {
                int bytesToWrite = processAudioForMonitoring(buffer, bytesRead, outputBuffer);
                if (bytesToWrite > 0) {
                    outputLine.write(outputBuffer, 0, bytesToWrite);
                }
            }
        }
    }

    /**
     * Registers a listener for a specific audio device.
     *
     * @param mixerInfo The mixer info for the device.
     * @param listener  The listener to register.
     */
    public void registerListener(Mixer.Info mixerInfo, AudioDataListener listener) {
        if (mixerInfo == null) return;
        deviceCaptures.computeIfAbsent(mixerInfo, DeviceCapture::new).addListener(listener);
    }

    /**
     * Unregisters a listener from a specific audio device.
     * If no listeners remain for a device, its capture resources are released.
     *
     * @param mixerInfo The mixer info for the device.
     * @param listener  The listener to unregister.
     */
    public void unregisterListener(Mixer.Info mixerInfo, AudioDataListener listener) {
        if (mixerInfo == null) return;
        DeviceCapture capture = deviceCaptures.get(mixerInfo);
        if (capture != null) {
            capture.removeListener(listener);
            if (!capture.hasListeners()) {
                deviceCaptures.remove(mixerInfo);
            }
        }
    }
}
