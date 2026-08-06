package org.kadampa.festivalstreaming.macos;

import org.kadampa.festivalstreaming.AudioCaptureManager;
import org.kadampa.festivalstreaming.AudioSamples;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.Mixer;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feeds ffmpeg the audio of one capture device over its standard input.
 * <p>
 * ffmpeg opens capture devices itself on Windows and that works well, but on macOS its
 * AVFoundation input discards whatever arrives while it is still reading the previous buffer.
 * On a mixer presenting many channels at a high rate that loses more than half the audio, with
 * nothing in the output to show for it: the recording simply comes out almost silent. The same
 * device read through Java Sound, which is what the level meters have always used, arrives
 * complete, so on macOS the application reads it and hands the samples to ffmpeg instead.
 * <p>
 * The capture is shared with the level meters through {@link AudioCaptureManager}, so the device
 * is still only opened once.
 */
public class AudioPipe implements AudioCaptureManager.AudioDataListener {

    private static final Logger logger = LoggerFactory.getLogger(AudioPipe.class);

    /** A ceiling on the backlog. The age limit below is what actually keeps the audio current. */
    private static final int QUEUE_CAPACITY = 64;
    private static final int FORMAT_WAIT_MS = 4000;

    /**
     * How old a buffer may be when it is handed to ffmpeg.
     * <p>
     * Raw PCM carries no timestamps: ffmpeg counts samples from the first byte it reads, so
     * anything captured before it started reading is silently treated as though it were captured
     * at that moment, and the whole recording ends up with the sound running ahead of the picture.
     * ffmpeg does not start reading until it has opened the camera, which takes it a second or
     * more, so without this the backlog built up during that wait sets the lip-sync error.
     * <p>
     * Under 100ms nothing is ever dropped in normal running, because the writer keeps up.
     */
    private static final long MAX_AUDIO_AGE_MS = 100;

    /** One captured buffer and the moment it was captured, so its age can be judged later. */
    private record Captured(byte[] samples, long capturedAtNanos) {
        long ageMillis() {
            return (System.nanoTime() - capturedAtNanos) / 1_000_000L;
        }
    }

    private final Mixer.Info device;
    private final BlockingQueue<Captured> pending = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final CountDownLatch formatKnown = new CountDownLatch(1);
    private volatile AudioFormat captureFormat;
    private volatile boolean running;
    private volatile long droppedBuffers;
    private volatile long staleBuffers;
    private Thread writer;

    public AudioPipe(Mixer.Info device) {
        this.device = device;
    }

    /**
     * Opens the device and waits until its format is known, which is only after the first buffer
     * arrives. The command line needs the rate and the channel count before ffmpeg is started.
     *
     * @return the format the device is being captured in, or null if it never produced audio
     */
    public AudioFormat open() {
        AudioCaptureManager.getInstance().registerListener(device, this);
        try {
            if (!formatKnown.await(FORMAT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                logger.error("The audio device {} produced no audio to send to ffmpeg", device.getName());
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return captureFormat;
    }

    /**
     * Starts forwarding the captured audio, discarding anything that has gone stale waiting.
     * The stream is closed when the pipe stops.
     */
    public void start(OutputStream toFfmpeg) {
        running = true;
        writer = new Thread(() -> {
            boolean firstWrite = true;
            try (OutputStream out = toFfmpeg) {
                while (running) {
                    Captured captured = pending.poll(200, TimeUnit.MILLISECONDS);
                    if (captured == null) {
                        continue;
                    }
                    // ffmpeg reads nothing until it has the camera open, and audio that waited
                    // through that would be passed off as current: see MAX_AUDIO_AGE_MS
                    if (captured.ageMillis() > MAX_AUDIO_AGE_MS) {
                        staleBuffers++;
                        continue;
                    }
                    if (firstWrite) {
                        firstWrite = false;
                        logger.info("Audio reached ffmpeg {}ms after capture, {} stale buffers discarded",
                                captured.ageMillis(), staleBuffers);
                    }
                    out.write(captured.samples());
                }
            } catch (IOException e) {
                // ffmpeg closing its input first is the normal way a recording ends
                logger.debug("The audio pipe to ffmpeg closed: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        writer.setDaemon(true);
        writer.setName("AudioPipe-" + device.getName());
        writer.start();
    }

    public void stop() {
        running = false;
        AudioCaptureManager.getInstance().unregisterListener(device, this);
        if (writer != null) {
            try {
                writer.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writer = null;
        }
        if (droppedBuffers > 0) {
            logger.warn("Dropped {} audio buffers on the way to ffmpeg", droppedBuffers);
        }
        if (staleBuffers > 0) {
            // Expected at the start, while ffmpeg is still opening the camera; not during a recording
            logger.info("Discarded {} audio buffers that had gone stale waiting for ffmpeg", staleBuffers);
        }
        pending.clear();
    }

    public long getDroppedBuffers() {
        return droppedBuffers;
    }

    /** The format the device is being captured in, known once {@link #open()} has returned. */
    public AudioFormat getCaptureFormat() {
        return captureFormat;
    }

    @Override
    public void onAudioData(byte[] buffer, int bytesRead, AudioFormat format) {
        if (captureFormat == null) {
            captureFormat = format;
            formatKnown.countDown();
        }
        if (!running || bytesRead <= 0) {
            return;
        }
        // The buffer belongs to the capture thread and is reused, so it has to be copied
        byte[] copy = new byte[bytesRead];
        System.arraycopy(buffer, 0, copy, 0, bytesRead);
        Captured captured = new Captured(copy, System.nanoTime());
        // Never block here: the capture thread also drives the level meters
        if (!pending.offer(captured)) {
            pending.poll();
            pending.offer(captured);
            droppedBuffers++;
        }
    }

    @Override
    public void onCaptureError(String message) {
        logger.error("The audio capture feeding ffmpeg failed: {}", message);
        running = false;
        formatKnown.countDown();
    }
}
