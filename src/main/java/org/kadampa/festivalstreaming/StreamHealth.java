package org.kadampa.festivalstreaming;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the health of the stream out of ffmpeg's status line.
 *
 * <p>{@code frame= fps= dup= drop= speed=} has always been the one line that says whether the
 * broadcast is actually working, and CLAUDE.md has always explained how to read it - but nothing
 * read it. This does, so a fault reaches the operator instead of waiting to be noticed by someone
 * watching the console.
 *
 * <p>It complements the message rules in {@link FfmpegMessages} rather than replacing them: ffmpeg
 * only says "More than 10 frames duplicated" once ten frames have already gone, while {@code dup=}
 * starts climbing at the first one, so this sees the same fault several seconds earlier and the
 * message then confirms it.
 *
 * <p>Two things make it usable rather than noisy. The counters are cumulative, so it judges
 * <em>rates</em> - a thousand duplicated frames across a six hour festival is nothing, a thousand
 * in a minute is a dead picture. And a condition has to hold before it is announced, the shape
 * VolumeMonitor already uses for the audio alarms, so a hiccup while the encoder settles does not
 * light the window up.
 *
 * <p>The thresholds are a first calibration from the incidents in docs/debugging-linux.md - the
 * slideshow ran at roughly the whole frame rate duplicated, the power-saver clamp showed up as
 * {@code speed=} sagging. They are meant to be re-measured, not treated as facts.
 */
final class StreamHealth {

    /** How the stream is doing; the console and the status bar both speak in these. */
    enum Level {
        OK(ConsoleSeverity.PLAIN),
        WARNING(ConsoleSeverity.WARNING),
        ERROR(ConsoleSeverity.ERROR);

        private final ConsoleSeverity severity;

        Level(ConsoleSeverity severity) {
            this.severity = severity;
        }

        /** How loudly the window should say it. */
        ConsoleSeverity severity() {
            return severity;
        }
    }

    /** Raised when the level changes, so a sustained fault is said once and not twice a second. */
    record Alert(Level level, String message) { }

    /** A rate this fraction of the frame rate means most frames are being invented. */
    private static final double DUP_WARNING_FRACTION = 0.25;
    private static final double DUP_ERROR_FRACTION = 0.80;
    /** Any steady loss at all is worth saying; one frame a second is above measurement noise. */
    private static final double DROP_WARNING_PER_SECOND = 1.0;
    /** Real time is 1.00x. Below this the machine is behind and the stream will not catch up. */
    private static final double SPEED_WARNING = 0.97;
    private static final double SPEED_ERROR = 0.90;
    /** A capture running this far under the rate it was configured for is losing pictures. */
    private static final double FPS_WARNING_FRACTION = 0.80;

    private static final long WARNING_AFTER_MS = 5000;
    private static final long ERROR_AFTER_MS = 20000;
    private static final long SPEED_ERROR_AFTER_MS = 10000;

    /** ffmpeg leaves dup= and drop= out of the line entirely while they are zero. */
    private static final Pattern FPS = Pattern.compile("fps=\\s*([\\d.]+)");
    private static final Pattern DUP = Pattern.compile("dup=\\s*(\\d+)");
    private static final Pattern DROP = Pattern.compile("drop=\\s*(\\d+)");
    private static final Pattern SPEED = Pattern.compile("speed=\\s*([\\d.]+)x");

    private final Sustained duplicating = new Sustained();
    private final Sustained dropping = new Sustained();
    private final Sustained slow = new Sustained();
    private final Sustained starved = new Sustained();

    private long previousMillis;
    private long previousDup;
    private long previousDrop;
    private double dupPerSecond;
    private double dropPerSecond;
    private double fps;
    private double speed;
    /** The frame rate the fractions above are measured against; the configured one when known. */
    private double rate;
    private Level level = Level.OK;

    /** Whether a line is one of ffmpeg's status reports rather than a message. */
    static boolean isStatusLine(String line) {
        return line.startsWith("frame=");
    }

    /** Forgets the previous stream, so a new one is not judged against the last one's counters. */
    void reset() {
        previousMillis = 0;
        previousDup = 0;
        previousDrop = 0;
        dupPerSecond = 0;
        dropPerSecond = 0;
        fps = 0;
        speed = 0;
        level = Level.OK;
        duplicating.clear();
        dropping.clear();
        slow.clear();
        starved.clear();
    }

    /**
     * Takes one status line and returns an alert only when the verdict changes - including the
     * change back to healthy, because an operator who saw the warning needs to be told it cleared
     * rather than left wondering whether it scrolled away.
     */
    Alert observe(String statusLine, int targetFps, long nowMillis) {
        fps = readDouble(FPS, statusLine, fps);
        speed = readDouble(SPEED, statusLine, 0);
        long dup = readLong(DUP, statusLine);
        long drop = readLong(DROP, statusLine);

        if (previousMillis > 0 && nowMillis > previousMillis) {
            double elapsedSeconds = (nowMillis - previousMillis) / 1000.0;
            dupPerSecond = Math.max(0, dup - previousDup) / elapsedSeconds;
            dropPerSecond = Math.max(0, drop - previousDrop) / elapsedSeconds;
        }
        previousMillis = nowMillis;
        previousDup = dup;
        previousDrop = drop;

        // The first report of a run carries the whole start-up in its averages; judging it would
        // mean warning about every stream in its first second
        if (dupPerSecond == 0 && dropPerSecond == 0 && speed == 0) {
            return null;
        }

        rate = targetFps > 0 ? targetFps : fps;
        long duplicatingFor = duplicating.heldFor(rate > 0 && dupPerSecond >= DUP_WARNING_FRACTION * rate, nowMillis);
        long droppingFor = dropping.heldFor(dropPerSecond >= DROP_WARNING_PER_SECOND, nowMillis);
        long slowFor = slow.heldFor(speed > 0 && speed < SPEED_WARNING, nowMillis);
        long starvedFor = starved.heldFor(rate > 0 && fps > 0 && fps < FPS_WARNING_FRACTION * rate, nowMillis);

        Level now = Level.OK;
        String reason = null;
        if (duplicatingFor >= WARNING_AFTER_MS) {
            boolean severe = duplicatingFor >= ERROR_AFTER_MS || dupPerSecond >= DUP_ERROR_FRACTION * rate;
            now = severe ? Level.ERROR : Level.WARNING;
            reason = String.format(Locale.ROOT,
                    "video frames are being duplicated (%.0f/s): the picture is freezing", dupPerSecond);
        } else if (droppingFor >= WARNING_AFTER_MS) {
            now = droppingFor >= ERROR_AFTER_MS ? Level.ERROR : Level.WARNING;
            reason = String.format(Locale.ROOT, "video frames are being dropped (%.0f/s)", dropPerSecond);
        } else if (slowFor >= WARNING_AFTER_MS) {
            now = speed < SPEED_ERROR && slowFor >= SPEED_ERROR_AFTER_MS ? Level.ERROR : Level.WARNING;
            reason = String.format(Locale.ROOT,
                    "encoding at %.2fx real time: the machine is not keeping up", speed);
        } else if (starvedFor >= WARNING_AFTER_MS) {
            now = Level.WARNING;
            reason = String.format(Locale.ROOT,
                    "capturing %.0f frames per second instead of %.0f", fps, rate);
        }

        if (now == level) {
            return null;
        }
        level = now;
        return new Alert(now, now == Level.OK ? "Video is back to normal" : reason);
    }

    /** The one-glance summary for the status bar; empty until ffmpeg has reported something. */
    String readout() {
        if (speed <= 0 && fps <= 0) {
            return "";
        }
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "%.0f fps · %.2fx", fps, speed));
        if (dupPerSecond >= 1) {
            text.append(String.format(Locale.ROOT, " · dup %.0f/s", dupPerSecond));
        }
        if (dropPerSecond >= 1) {
            text.append(String.format(Locale.ROOT, " · drop %.0f/s", dropPerSecond));
        }
        return text.toString();
    }

    Level level() {
        return level;
    }

    private static long readLong(Pattern pattern, String line) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private static double readDouble(Pattern pattern, String line, double fallback) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? Double.parseDouble(matcher.group(1)) : fallback;
    }

    /** A condition that only counts once it has held without a break, timed from when it started. */
    private static final class Sustained {
        private long since;

        /** How long it has held, or zero if it does not hold now. */
        long heldFor(boolean holding, long nowMillis) {
            if (!holding) {
                since = 0;
                return 0;
            }
            if (since == 0) {
                since = nowMillis;
            }
            // A condition that has only just started still counts as held, just not for long
            return Math.max(1, nowMillis - since);
        }

        void clear() {
            since = 0;
        }
    }
}
