package org.kadampa.festivalstreaming;

import javax.sound.sampled.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class AudioCaptureManager {
    private static final AudioCaptureManager INSTANCE = new AudioCaptureManager();

    private final Map<Mixer.Info, DeviceCapture> deviceCaptures = new ConcurrentHashMap<>();

    private AudioCaptureManager() {}

    public static AudioCaptureManager getInstance() {
        return INSTANCE;
    }

    public void startMonitoring(Mixer.Info mixerInfo) {
        DeviceCapture capture = deviceCaptures.get(mixerInfo);
        if (capture != null) {
            capture.startMonitoring();
        }
    }

    public void stopMonitoring(Mixer.Info mixerInfo) {
        DeviceCapture capture = deviceCaptures.get(mixerInfo);
        if (capture != null) {
            capture.stopMonitoring();
        }
    }

    public interface AudioDataListener {
        void onAudioData(byte[] buffer, int bytesRead, AudioFormat format);
    }

    private static class DeviceCapture {
        private final Mixer.Info mixerInfo;
        private final List<AudioDataListener> listeners = new CopyOnWriteArrayList<>();
        private Thread captureThread;
        private TargetDataLine inputLine;
        private SourceDataLine outputLine;
        private volatile boolean running = false;
        private volatile boolean monitoring = false;
        private AudioFormat captureFormat;
        private AudioFormat playbackFormat;

        DeviceCapture(Mixer.Info mixerInfo) {
            this.mixerInfo = mixerInfo;
        }

        void addListener(AudioDataListener listener) {
            listeners.add(listener);
            if (listeners.size() == 1) {
                start();
            }
        }

        void removeListener(AudioDataListener listener) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                stop();
            }
        }

        void startMonitoring() {
            monitoring = true;
        }

        void stopMonitoring() {
            monitoring = false;
        }

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

                long monoSample = (inputChannels == 2) ? (leftSample + rightSample) / 2 : leftSample;

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


        private void start() {
            if (running) return;
            running = true;
            captureThread = new Thread(() -> {
                try {
                    captureFormat = findBestCaptureFormat();
                    DataLine.Info inputInfo = new DataLine.Info(TargetDataLine.class, captureFormat);
                    inputLine = (TargetDataLine) AudioSystem.getMixer(mixerInfo).getLine(inputInfo);

                    // Increased buffer to 100ms for more stability
                    float bufferDurationMs = 100.0f;
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
                    e.printStackTrace();
                } finally {
                    cleanup();
                }
            });
            captureThread.setDaemon(true);
            captureThread.setName("AudioCapture-" + mixerInfo.getName());
            captureThread.start();
        }

        private void cleanup() {
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

        private void stop() {
            running = false;
            if (captureThread != null) {
                captureThread.interrupt();
            }
        }

        boolean hasListeners() {
            return !listeners.isEmpty();
        }

        private void writeToMonitor(byte[] buffer, int bytesRead, byte[] outputBuffer) {
            if (outputLine != null && outputLine.isOpen()) {
                int bytesToWrite = processAudioForMonitoring(buffer, bytesRead, outputBuffer);
                if (bytesToWrite > 0) {
                    outputLine.write(outputBuffer, 0, bytesToWrite);
                }
            }
        }
    }

    public void registerListener(Mixer.Info mixerInfo, AudioDataListener listener) {
        if (mixerInfo == null) return;
        deviceCaptures.computeIfAbsent(mixerInfo, DeviceCapture::new).addListener(listener);
    }

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
