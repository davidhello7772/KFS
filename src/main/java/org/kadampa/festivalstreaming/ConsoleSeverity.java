package org.kadampa.festivalstreaming;

/**
 * How much a console line matters. Severity is a value the caller states, not something guessed
 * from the wording: the alarm used to be raised by the substring "error" appearing anywhere in a
 * message, which meant a line had to be phrased carefully to be heard - and meant the perfectly
 * normal "exited with error code 255" of a pressed Stop button sounded like a lost stream.
 *
 * <p>The colours live in javafx@main.css, so the console shares one palette with the rest of the
 * window instead of hard-coding {@code Color} constants of its own.
 */
public enum ConsoleSeverity {
    /** Ordinary ffmpeg output: shown as it comes, in the default text colour. */
    PLAIN(null),
    /** Chatter that is normal at start-up and only interesting when it keeps coming. */
    NOTICE("secondary-text"),
    /** Something the application wants the operator to read. */
    INFO("primary-text"),
    /** The stream is degraded or something is misconfigured - visible, but no alarm. */
    WARNING("warning-text"),
    /** The stream is broken or about to be: red, the status bar turns, the alarm sounds. */
    ERROR("danger-text");

    private final String styleClass;

    ConsoleSeverity(String styleClass) {
        this.styleClass = styleClass;
    }

    /** The CSS class this severity paints with, or null to leave the text its default colour. */
    public String styleClass() {
        return styleClass;
    }

    /** The louder of the two, so a line can be raised by several rules without any losing out. */
    public ConsoleSeverity max(ConsoleSeverity other) {
        return compareTo(other) >= 0 ? this : other;
    }
}
