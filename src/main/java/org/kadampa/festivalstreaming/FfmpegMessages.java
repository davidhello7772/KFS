package org.kadampa.festivalstreaming;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What ffmpeg's own words mean for this stream.
 *
 * <p>The console used to judge a line by looking for ordinary English words in it - "error",
 * "failed", "ignored". None of those is a string ffmpeg prints for the faults this project
 * actually hits, so the most diagnostic line it produces, {@code More than 1000 frames duplicated},
 * scrolled past in plain black while {@code err_detect} in a start-up banner sounded the alarm.
 * The rules below are keyed on the real format strings of ffmpeg 8 instead, each carrying the
 * one-line remedy that was paid for the hard way - see docs/debugging-linux.md, whose write-ups
 * these are the short form of. When a new fault is diagnosed live, it becomes one more rule here
 * and one more paragraph there.
 *
 * <p>The generic word list is kept, but only as the tier below: it catches the shape of a problem
 * nobody has met yet, and it no longer decides anything a rule has already spoken for.
 */
final class FfmpegMessages {

    /** What a line means: how loudly to say it, and what the operator should do about it. */
    record Diagnosis(ConsoleSeverity severity, String advice) {
        boolean hasAdvice() {
            return advice != null && !advice.isEmpty();
        }
    }

    private record Rule(Pattern pattern, ConsoleSeverity severity, String advice) { }

    /**
     * Duplicated frames are the signature of the v4l2loopback attach race: OBS pushes frames
     * normally, the reader receives one every few seconds, and ffmpeg pads the gap so the stream
     * plays as a slideshow while every other diagnostic looks healthy. ffmpeg announces it at 10,
     * then 100, then 1000, then 10000 - the escalation is the signal, so the count is read out of
     * the line and decides how loud to be. The poisoned capture session never recovers, which is
     * why the advice is to restart rather than to wait.
     */
    private static final Pattern FRAMES_DUPLICATED =
            Pattern.compile("More than (\\d+) frames duplicated");
    /** Past this many announced duplicates the session is not going to come back on its own. */
    private static final long DUPLICATED_FRAMES_ALARM = 100;

    private static final String SLIDESHOW_ADVICE =
            "video frames are not arriving - the stream is playing as a slideshow."
            + " The capture session does not recover: stop, then start again."
            + " If it comes back, check v4l2loopback is 0.15.4 or newer.";

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("frame duplication too large, skipping"),
                    ConsoleSeverity.ERROR, SLIDESHOW_ADVICE),
            new Rule(Pattern.compile("Dequeued v4l2 buffer contains corrupted data"),
                    ConsoleSeverity.WARNING, "the capture device is handing back bad frames."),
            // A virtual camera admits one capture client and refuses the second outright, so this
            // is what a second KFS window looks like from inside ffmpeg: the window opened fine and
            // Start failed. The remedy names the two ways out because both are real - close the
            // other window, or give this one a camera of its own through the fan-out.
            // Matched on the input-open line rather than on the words alone: ffmpeg says the same
            // thing again in its closing summary, and the remedy is worth printing once
            new Rule(Pattern.compile("Error opening input: Device or resource busy"),
                    ConsoleSeverity.ERROR,
                    "something else is already capturing this camera - a second KFS window, or a"
                    + " leftover ffmpeg. A virtual camera allows only one reader: close the other"
                    + " one (check with \"fuser -v /dev/videoN\"), or run scripts/vcam-fanout.sh"
                    + " to give each instance a camera of its own."),
            new Rule(Pattern.compile("Dequeued v4l2 buffer contains \\d+ bytes, but \\d+ were expected"),
                    ConsoleSeverity.WARNING,
                    "the capture device's frame size and the video input mode disagree -"
                    + " check what OBS is set to output."),
            new Rule(Pattern.compile("Total changed input frames dropped"),
                    ConsoleSeverity.WARNING, "frames arrived faster than they were declared."),
            new Rule(Pattern.compile("(Circular|Fifo) buffer overrun"),
                    ConsoleSeverity.WARNING, "an input is producing faster than it is being read."),
            new Rule(Pattern.compile("Conversion failed"),
                    ConsoleSeverity.ERROR, null),
            new Rule(Pattern.compile("Connection setup failure|Connection timed out"),
                    ConsoleSeverity.ERROR,
                    "the streaming platform could not be reached - check the SRT url and the network."),
            new Rule(Pattern.compile("continuing with \\d+/\\d+ slaves"),
                    ConsoleSeverity.ERROR,
                    "one output died and the rest carried on - the stream and the recording"
                    + " are no longer both running."),
            new Rule(Pattern.compile("No space left on device"),
                    ConsoleSeverity.ERROR,
                    "the disk being recorded to is full."),
            // Routine while the inputs settle, and worth greying out rather than colouring: it is
            // only interesting when it keeps coming long after the start
            new Rule(Pattern.compile("Past duration [\\d.]+ too large"
                    + "|Non-monotonic DTS"
                    + "|non monotonically increasing dts"),
                    ConsoleSeverity.NOTICE, null));

    /** The last-resort tier: the shape of a problem no rule above has a name for yet. */
    private static final Pattern GENERIC_ERROR = Pattern.compile(
            "\\b(error|fatal|failed|invalid|unable|incompatible)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_WARNING = Pattern.compile(
            "\\b(ignored|deprecated|unsupported|could not|incorrect|confused)\\b",
            Pattern.CASE_INSENSITIVE);

    private FfmpegMessages() {
    }

    /** Reads one line of ffmpeg output and says what it means. */
    static Diagnosis diagnose(String line) {
        Matcher duplicated = FRAMES_DUPLICATED.matcher(line);
        if (duplicated.find()) {
            long frames = Long.parseLong(duplicated.group(1));
            ConsoleSeverity severity = frames >= DUPLICATED_FRAMES_ALARM
                    ? ConsoleSeverity.ERROR : ConsoleSeverity.WARNING;
            return new Diagnosis(severity, SLIDESHOW_ADVICE);
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(line).find()) {
                return new Diagnosis(rule.severity(), rule.advice());
            }
        }
        if (GENERIC_ERROR.matcher(line).find()) {
            return new Diagnosis(ConsoleSeverity.ERROR, null);
        }
        if (GENERIC_WARNING.matcher(line).find()) {
            return new Diagnosis(ConsoleSeverity.WARNING, null);
        }
        return new Diagnosis(ConsoleSeverity.PLAIN, null);
    }

    /**
     * Whether this line is the one fault that outlives the moment it is reported. Everything else
     * the console says describes a condition that can clear; a poisoned capture session cannot, so
     * the window has to keep saying so rather than fading back to a healthy green three seconds later.
     */
    static boolean isUnrecoverableCaptureFault(String line) {
        Matcher duplicated = FRAMES_DUPLICATED.matcher(line);
        if (duplicated.find()) {
            return Long.parseLong(duplicated.group(1)) >= DUPLICATED_FRAMES_ALARM;
        }
        return line.contains("frame duplication too large, skipping");
    }
}
