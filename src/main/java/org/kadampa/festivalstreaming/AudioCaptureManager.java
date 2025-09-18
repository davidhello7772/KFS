package org.kadampa.festivalstreaming;

import javax.sound.sampled.*;
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

    private AudioCaptureManager() {}

    /**
     * Returns the singleton instance of the AudioCaptureManager.
     *
     * @return The single instance of this class.
     */
    public static AudioCaptureManager getInstance() {
        return INSTANCE;
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
        private String channel;
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
            monitoring = true;
        }

        /**
         * Disables audio monitoring.
         */
        void stopMonitoring() {
            monitoring = false;
            if (outputLine != null) {
                outputLine.flush();
            }
        }

        /**
         * Finds the best supported audio format for capture, preferring higher sample rates and bit depths.
         *
         * @return The optimal supported AudioFormat, or a fallback format.
         */
        private AudioFormat findBestCaptureFormat() {
            AudioFormat[] preferredFormats = {
                    new AudioFormat(48000, 16, 2, true, false),
                    new AudioFormat(44100, 16, 2, true, false),
                    new AudioFormat(48000, 24, 2, true, false),
                    new AudioFormat(44100, 24, 2, true, false),
                    new AudioFormat(48000, 16, 1, true, false),
                    new AudioFormat(44100, 16, 1, true, false),
            };

            for (AudioFormat format : preferredFormats) {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                if (AudioSystem.getMixer(mixerInfo).isLineSupported(info)) {
                    return format;
                }
            }
            return new AudioFormat(44100, 16, 2, true, false); // Fallback
        }

        /**
         * Sets up the output line (SourceDataLine) for audio monitoring playback.
         */
        private void setupOutputLine() {
            try {
                playbackFormat = new AudioFormat(captureFormat.getSampleRate(), 16, 1, true, false);
                DataLine.Info outputInfo = new DataLine.Info(SourceDataLine.class, playbackFormat);
                outputLine = (SourceDataLine) AudioSystem.getLine(outputInfo);
                // Increased buffer to 400ms for more stability
                int outputBufferSize = (int) (playbackFormat.getSampleRate() * playbackFormat.getFrameSize() * 0.4f);
                outputLine.open(playbackFormat, outputBufferSize);
                outputLine.start();
            } catch (LineUnavailableException e) {
                System.err.println("Failed to setup output line: " + e.getMessage());
                outputLine = null;
            }
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

            for (int i = 0; i < frameCount; i++) {
                long leftSample = 0;
                long rightSample = 0;

                if (inputBitDepth == 16) {
                    leftSample = (short) ((inputBuffer[i * inputFrameSize] & 0xFF) | (inputBuffer[i * inputFrameSize + 1] << 8));
                    if (inputChannels == 2) {
                        rightSample = (short) ((inputBuffer[i * inputFrameSize + 2] & 0xFF) | (inputBuffer[i * inputFrameSize + 3] << 8));
                    }
                } else if (inputBitDepth == 24) {
                    leftSample = (inputBuffer[i * inputFrameSize] & 0xFF) | ((inputBuffer[i * inputFrameSize + 1] & 0xFF) << 8) | (inputBuffer[i * inputFrameSize + 2] << 16);
                    if ((leftSample & 0x800000) != 0) leftSample |= 0xFFFFFFFFFF000000L; // Sign extend
                    if (inputChannels == 2) {
                        rightSample = (inputBuffer[i * inputFrameSize + 3] & 0xFF) | ((inputBuffer[i * inputFrameSize + 4] & 0xFF) << 8) | (inputBuffer[i * inputFrameSize + 5] << 16);
                        if ((rightSample & 0x800000) != 0) rightSample |= 0xFFFFFFFFFF000000L; // Sign extend
                    }
                }

                long monoSample;
                if (inputChannels == 2) {
                    // Default to stereo if channel is not specified
                    if (channel == null) {
                        channel = "Stereo";
                    }
                    monoSample = switch (channel) {
                        case SettingsUtil.AUDIO_CHANNEL_LEFT -> leftSample;
                        case SettingsUtil.AUDIO_CHANNEL_RIGHT -> rightSample;
                        default -> (leftSample + rightSample) / 2;
                    };
                } else {
                    monoSample = leftSample;
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
                    captureFormat = findBestCaptureFormat();
                    DataLine.Info inputInfo = new DataLine.Info(TargetDataLine.class, captureFormat);
                    inputLine = (TargetDataLine) AudioSystem.getMixer(mixerInfo).getLine(inputInfo);

                    // Increased buffer to 150ms for more stability
                    float bufferDurationMs = 150.0f;
                    int inputBufferSize = (int) (captureFormat.getSampleRate() * captureFormat.getFrameSize() * bufferDurationMs / 1000.0f);
                    inputBufferSize -= inputBufferSize % captureFormat.getFrameSize();

                    inputLine.open(captureFormat, inputBufferSize);
                    inputLine.start();
                    setupOutputLine();

                    byte[] inputBuffer = new byte[inputLine.getBufferSize()];
                    int outputBufferSize = (inputBuffer.length / captureFormat.getFrameSize()) * playbackFormat.getFrameSize();
                    byte[] outputBuffer = new byte[outputBufferSize];

                    System.out.println("Started capture: " + captureFormat + ", buffer: " + inputBuffer.length + " bytes");

                    while (running) {
                        int bytesRead = inputLine.read(inputBuffer, 0, inputBuffer.length);
                        if (bytesRead > 0) {
                            for (AudioDataListener listener : listeners) {
                                listener.onAudioData(inputBuffer, bytesRead, captureFormat);
                            }
                            if (monitoring) {
                                writeToMonitor(inputBuffer, bytesRead, outputBuffer);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error in audio capture thread for " + mixerInfo.getName() + ": " + e.getMessage());
                   // e.printStackTrace();
                } finally {
                    cleanup();
                }
            });
            captureThread.setDaemon(true);
            captureThread.setName("AudioCapture-" + mixerInfo.getName());
            captureThread.start();
        }

        /**
         * Cleans up audio resources, stopping and closing input and output lines.
         */
        private void cleanup() {
            System.out.println("Cleaning - input:" + inputLine.toString()+" - output:"+outputLine.toString());
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
         * Stops the audio capture thread and triggers cleanup.
         */
        private void stop() {
            running = false;
            if (captureThread != null) {
                captureThread.interrupt();
            }
            System.out.println("Stop capture: " + inputLine.toString());
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
