package org.kadampa.festivalstreaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Carries the Linux capture audio from pw-record into ffmpeg through a buffer large enough
 * to survive ffmpeg's start-up.
 * <p>
 * When the two processes were joined by a plain pipe, every sample recorded before ffmpeg
 * first read its audio input was lost: a pipe holds 64KB — 68 milliseconds of ten-channel
 * audio — and once it was full the sound server dropped whatever followed. The stream's
 * first audio then dated from that first read while its first video frame dated from the
 * v4l2 open, so the audio ran early by the difference and the delay setting had to make up
 * for it — a value that changed with the machine's speed. On this laptop at full clocks the
 * window is small (measured: ~21ms buffered at first read, within what the pipe holds), but
 * while a power profile clamped the machine to a fraction of its speed it stretched to
 * seconds — which is where the historical 2500ms delay setting came from. Windows does not
 * have the problem, because ffmpeg opens the audio device itself and DirectShow timestamps
 * whatever queued; macOS has its own feeder already: see {@link AudioPipe}.
 * <p>
 * The relay reads pw-record without ever blocking it and parks the samples in a ring sized
 * for many seconds, so the backlog survives until ffmpeg starts reading and the first audio
 * and the first video date from the same moment — whatever the machine's speed that day.
 * The delay setting then only covers the real chain latency — the same few hundred
 * milliseconds as on the other platforms. Once ffmpeg
 * has caught up the ring stays near-empty, adding no latency of its own. It can only fill if
 * ffmpeg stops reading for the ring's whole capacity, by which point the stream is beyond
 * rescue anyway, so overflow keeps the newest audio and logs instead of growing without
 * bound. How much was waiting when ffmpeg took its first read is logged: on a healthy
 * start it is the whole start-up backlog that a plain pipe would have dropped.
 */
final class AudioRelay {

    /** Room for about 17 seconds of ten-channel 48kHz 16-bit audio (960KB/s). */
    private static final int RING_CAPACITY = 16 * 1024 * 1024;
    private static final int CHUNK = 64 * 1024;

    private static final Logger logger = LoggerFactory.getLogger(AudioRelay.class);

    private final InputStream source;
    private final OutputStream sink;
    /** Bytes of one second of audio, only for reporting buffer levels as milliseconds. */
    private final int bytesPerSecond;

    private final byte[] ring = new byte[RING_CAPACITY];
    private int head;               // the oldest unread byte
    private int count;              // how many bytes the ring holds
    private boolean sourceDone;     // pw-record has ended; drain and finish
    private volatile boolean stopped;
    private long overflowed;        // bytes sacrificed because ffmpeg stopped reading
    private boolean firstTakeLogged;

    AudioRelay(InputStream source, OutputStream sink, int bytesPerSecond) {
        this.source = source;
        this.sink = sink;
        this.bytesPerSecond = Math.max(1, bytesPerSecond);
    }

    /** Starts the two carrier threads; they end when either process goes away. */
    void start() {
        Thread reader = new Thread(this::readAll, "audio-relay-reader");
        Thread writer = new Thread(this::writeAll, "audio-relay-writer");
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
    }

    /** Lets both threads finish; safe to call more than once. */
    void stop() {
        stopped = true;
        synchronized (this) {
            notifyAll();
        }
    }

    /** Moves pw-record's output into the ring, never letting the recorder block. */
    private void readAll() {
        byte[] buffer = new byte[CHUNK];
        try {
            int read;
            while (!stopped && (read = source.read(buffer)) != -1) {
                put(buffer, read);
            }
        } catch (IOException e) {
            // pw-record was stopped; whatever reached the ring is drained below
        } finally {
            synchronized (this) {
                sourceDone = true;
                notifyAll();
            }
        }
    }

    /** Moves the ring into ffmpeg at whatever pace ffmpeg reads. */
    private void writeAll() {
        byte[] buffer = new byte[CHUNK];
        try {
            int taken;
            while ((taken = take(buffer)) > 0) {
                sink.write(buffer, 0, taken);
                sink.flush();
            }
            // pw-record has ended and the ring is drained: end-of-stream tells ffmpeg
            sink.close();
        } catch (IOException e) {
            // ffmpeg went away; the caller's clean-up stops pw-record, which ends the reader
            logger.debug("ffmpeg stopped taking audio", e);
        }
    }

    private synchronized void put(byte[] data, int length) {
        if (count + length > RING_CAPACITY) {
            // ffmpeg has not read for the ring's whole capacity. Keeping the newest audio
            // lets a miraculously recovered stream carry on near-live, but the loss shifts
            // the sample-counted timestamps, so the operator should restart rather than trust
            // the sync afterwards - hence the warning.
            int sacrificed = count + length - RING_CAPACITY;
            head = (head + sacrificed) % RING_CAPACITY;
            count -= sacrificed;
            if (overflowed == 0) {
                logger.warn("ffmpeg read no audio for {} seconds - dropping the oldest;"
                        + " audio/video sync is not trustworthy until the stream is restarted",
                        RING_CAPACITY / bytesPerSecond);
            }
            overflowed += sacrificed;
        }
        int tail = (head + count) % RING_CAPACITY;
        int firstPart = Math.min(length, RING_CAPACITY - tail);
        System.arraycopy(data, 0, ring, tail, firstPart);
        System.arraycopy(data, firstPart, ring, 0, length - firstPart);
        count += length;
        notifyAll();
    }

    private synchronized int take(byte[] into) {
        while (count == 0 && !sourceDone && !stopped) {
            try {
                wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        if (count == 0) {
            return -1;
        }
        if (!firstTakeLogged) {
            firstTakeLogged = true;
            logger.info("ffmpeg took its first audio with {}ms buffered"
                    + " - the start-up backlog a plain pipe would have lost",
                    count * 1000L / bytesPerSecond);
        }
        int taken = Math.min(count, into.length);
        int firstPart = Math.min(taken, RING_CAPACITY - head);
        System.arraycopy(ring, head, into, 0, firstPart);
        System.arraycopy(ring, 0, into, firstPart, taken - firstPart);
        head = (head + taken) % RING_CAPACITY;
        count -= taken;
        return taken;
    }
}
