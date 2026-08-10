package org.kadampa.festivalstreaming;

import java.util.List;

/**
 * The languages the Languages tab offers to add with one click, so a festival that gains an
 * interpreter does not have to look an ISO code up under time pressure.
 *
 * <p>The entries reproduce what the festival machines are actually running, deviations from strict
 * ISO 639-2 included, because neither the code nor the native name is cosmetic: the code goes out
 * as ffmpeg's {@code -metadata language=} and the native name as the track title a viewer reads and
 * as the track name in the Castr player URL. Changing one changes what the audience sees, so it is
 * an operational decision taken deliberately, never a code tidy-up. The known deviations:
 * <ul>
 *   <li>Greek is {@code grc}, which is really Ancient Greek; modern Greek is {@code ell}.</li>
 *   <li>Cantonese and Mandarin both carry {@code chi}, the 639-2 code for Chinese as a whole -
 *       639-3 separates them as {@code yue} and {@code cmn}. Two languages sharing a code is
 *       therefore legitimate, and the editor must not treat it as a mistake.</li>
 *   <li>Vietnamese's native name carries an underscore, from before the player URL escaped spaces.</li>
 * </ul>
 * Every one of them can be edited per language in the tab, so these are a starting point rather
 * than a ruling.
 */
public final class LanguagePresets {

    private static final List<Settings.Language> CATALOGUE = List.of(
            new Settings.Language("Cantonese", "廣東話", "chi"),
            new Settings.Language("Dutch", "Nederlands", "nld"),
            new Settings.Language(Settings.ENGLISH_LANGUAGE, "English", "eng"),
            new Settings.Language("Finnish", "Suomi", "fin"),
            new Settings.Language("French", "Français", "fra"),
            new Settings.Language("German", "Deutsch", "deu"),
            new Settings.Language("Greek", "Ελληνικά", "grc"),
            new Settings.Language("Italian", "Italiano", "ita"),
            new Settings.Language("Mandarin", "普通话", "chi"),
            new Settings.Language("Portuguese", "Português", "por"),
            new Settings.Language("Spanish", "Español", "spa"),
            new Settings.Language("Swedish", "Svenska", "swe"),
            new Settings.Language("Vietnamese", "Tiếng_Việt", "vie"));

    private LanguagePresets() {
    }

    /** In the order the Add menu shows them: alphabetical by the English name. */
    public static List<Settings.Language> catalogue() {
        return CATALOGUE;
    }
}
