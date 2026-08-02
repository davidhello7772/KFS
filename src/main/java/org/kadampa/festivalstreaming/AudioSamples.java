package org.kadampa.festivalstreaming;

/**
 * Reads one channel out of an interleaved PCM frame.
 * <p>
 * A stereo cable carries two languages and a mixer carries one per input, so both the meters and
 * the headphone monitoring have to pick a numbered channel rather than assume left and right.
 * Signed little-endian only, which is what {@code findCaptureFormats} asks the device for.
 */
final class AudioSamples {

    private AudioSamples() {
    }

    /**
     * @param buffer      the captured bytes
     * @param frameOffset the byte offset of the frame within the buffer
     * @param channel     the channel to read, counting from zero
     * @param bitDepth    16 or 24; anything else reads as silence
     * @return the sample, sign extended
     */
    static long readSample(byte[] buffer, int frameOffset, int channel, int bitDepth) {
        int bytesPerSample = bitDepth / 8;
        int offset = frameOffset + channel * bytesPerSample;
        if (offset < 0 || offset + bytesPerSample > buffer.length) {
            return 0;
        }
        if (bitDepth == 16) {
            return (short) ((buffer[offset] & 0xFF) | (buffer[offset + 1] << 8));
        }
        if (bitDepth == 24) {
            long sample = (buffer[offset] & 0xFF)
                    | ((buffer[offset + 1] & 0xFF) << 8)
                    | (buffer[offset + 2] << 16);
            if ((sample & 0x800000) != 0) {
                sample |= 0xFFFFFFFFFF000000L; // Sign extend
            }
            return sample;
        }
        return 0;
    }

    /** The largest value a sample of this depth can hold, for normalising to 0..1. */
    static double fullScale(int bitDepth) {
        return bitDepth == 24 ? 8388607.0 : 32767.0;
    }
}
