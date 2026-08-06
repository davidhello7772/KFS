package org.kadampa.festivalstreaming;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Properties;

/**
 * What the running program knows about its own build. The commit count and hash come from
 * the git.properties file the git-commit-id plugin writes into the jar (the version scheme
 * matches the jar name: 1.0.&lt;commit count&gt; plus the abbreviated hash); the build
 * moment comes from build.properties, which Maven filters with its own timestamp — the
 * plugin stopped generating git.build.time in the name of reproducible builds. When the
 * files are absent or unfiltered — an IDE launch that skipped Maven — everything degrades
 * to a "Development build" label and the What's new link falls back to the repository's
 * main history.
 */
final class BuildInfo {

    private static final String REPOSITORY = "https://github.com/davidhello7772/KFS";
    /** Maven's stamp format as set in the pom, always UTC: {@code 2026-08-06T07:30:12Z}. */
    private static final DateTimeFormatter BUILD_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssX");
    private static final Properties GIT = load("/git.properties");
    private static final Properties BUILD = load("/build.properties");

    private BuildInfo() {
    }

    /** "Version 1.0.78 (abc1234), built 6 Aug 2026, 09:12" — or "Development build". */
    static String versionLine() {
        String count = GIT.getProperty("git.total.commit.count");
        String hash = GIT.getProperty("git.commit.id.abbrev");
        if (count == null || hash == null) {
            return "Development build";
        }
        return "Version 1.0." + count + " (" + hash + "), built " + buildTime();
    }

    /** The commit history that leads to this very build: the top entries are what's new. */
    static String whatsNewUrl() {
        String hash = GIT.getProperty("git.commit.id.abbrev");
        return REPOSITORY + "/commits/" + (hash != null ? hash : "main");
    }

    private static String buildTime() {
        String time = BUILD.getProperty("build.time");
        if (time == null || time.startsWith("${")) {
            return "unknown time";  // unfiltered copy: the jar was not made by Maven
        }
        try {
            // Stamped in UTC, shown in the operator's own time zone
            return OffsetDateTime.parse(time, BUILD_TIME)
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH));
        } catch (DateTimeParseException e) {
            return time;  // an unexpected format is still better shown than hidden
        }
    }

    private static Properties load(String resource) {
        Properties properties = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream(resource)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException ignored) {
            // the footer just says development build
        }
        return properties;
    }
}
