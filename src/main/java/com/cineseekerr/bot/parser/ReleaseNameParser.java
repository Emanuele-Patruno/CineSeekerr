package com.cineseekerr.bot.parser;

import com.cineseekerr.bot.model.Language;
import com.cineseekerr.bot.model.ParsedRelease;
import com.cineseekerr.bot.model.ReleaseSource;
import com.cineseekerr.bot.model.Resolution;
import com.cineseekerr.bot.model.VideoCodec;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts structured attributes (resolution, audio languages, subtitles, codec, source)
 * from torrent release names such as
 * {@code Interstellar.2014.iTA.ENG.1080p.BluRay.x264-Bella}.
 *
 * <p>This class is pure and stateless: no Spring, no I/O, no mutable state. It is safe to
 * share a single instance across threads.
 *
 * <h2>Subtitle vs audio disambiguation</h2>
 * Language tokens adjacent to a {@code SUB}/{@code SUBS}/{@code SUBBED} marker are treated
 * as subtitle languages and are <em>excluded</em> from audio detection, so
 * {@code ENG.SUB.iTA} yields English audio with Italian subtitles, not Italian audio.
 * Detection runs in three passes:
 * <ol>
 *   <li>{@code SUB <lang>...} — languages following the marker (dominant Italian pattern,
 *       e.g. {@code SUB.iTA}); consecutive languages are all consumed
 *       ({@code SUB.iTA-ENG} → subtitles in both);</li>
 *   <li>{@code <lang>... SUB} — languages preceding the marker (e.g. {@code ENG.SUBBED});</li>
 *   <li>a bare marker with no language sets {@link ParsedRelease#subtitled()} only.</li>
 * </ol>
 * {@code MULTI}/{@code DUAL} are never captured as subtitle languages, so
 * {@code MULTi.SUBS} keeps MULTI as an audio tag.
 *
 * <p>Inherently ambiguous names (e.g. the movie title "Dual") may produce false positives;
 * the parser favours predictable rules over title-awareness.
 */
public final class ReleaseNameParser {

    /** Real languages — valid for both audio and subtitles. */
    private static final String LANG_ALT =
            "ITA(?:LIAN)?|ENG(?:LISH)?|FRENCH|FRE|FRA|GERMAN|GER|SPANISH|SPA|ESP";
    /** Audio-only pseudo-tags in addition to real languages. */
    private static final String AUDIO_ALT = LANG_ALT + "|MULTI(?:LANG)?|DUAL";

    private static final Pattern SEPARATORS = Pattern.compile("[._\\[\\](){}+,;:]+");
    private static final Pattern LANG_TOKEN = Pattern.compile("\\b(" + LANG_ALT + ")\\b");
    private static final Pattern AUDIO_TOKEN = Pattern.compile("\\b(" + AUDIO_ALT + ")\\b");

    private static final Pattern SUBS_LANGS_AFTER = Pattern.compile(
            "\\bSUB(?:S|BED)?[\\s\\-]*((?:(?:" + LANG_ALT + ")\\b[\\s\\-]*)+)");
    private static final Pattern SUBS_LANGS_BEFORE = Pattern.compile(
            "\\b((?:(?:" + LANG_ALT + ")\\b[\\s\\-]+)+)SUB(?:S|BED)?\\b");
    private static final Pattern SUBS_GENERIC = Pattern.compile("\\bSUB(?:S|BED)?\\b");

    private static final Pattern RES_2160 = Pattern.compile("\\b(2160[PI]?|4K|UHD)\\b");
    private static final Pattern RES_1080 = Pattern.compile("\\b1080[PI]?\\b");
    private static final Pattern RES_720 = Pattern.compile("\\b720[PI]?\\b");

    /** Ordered: first match wins, most specific patterns first. */
    private static final List<Map.Entry<Pattern, VideoCodec>> CODECS = List.of(
            entry("\\b(X ?265|H ?265|HEVC)\\b", VideoCodec.X265),
            entry("\\b(X ?264|H ?264|AVC)\\b", VideoCodec.X264),
            entry("\\b(XVID|DIVX)\\b", VideoCodec.XVID),
            entry("\\bAV1\\b", VideoCodec.AV1));

    /** Ordered: first match wins, most specific patterns first (rips before their medium). */
    private static final List<Map.Entry<Pattern, ReleaseSource>> SOURCES = List.of(
            entry("\\b(BD[\\s\\-]?RIP|BR[\\s\\-]?RIP|BD[\\s\\-]?MUX)\\b", ReleaseSource.BDRIP),
            entry("\\b(BLU[\\s\\-]?RAY|BD[\\s\\-]?REMUX|REMUX)\\b", ReleaseSource.BLURAY),
            entry("\\b(WEB[\\s\\-]?DL|DL[\\s\\-]?MUX)\\b", ReleaseSource.WEB_DL),
            entry("\\b(WEB[\\s\\-]?RIP|WEB[\\s\\-]?MUX)\\b", ReleaseSource.WEBRIP),
            entry("\\bWEB\\b", ReleaseSource.WEB_DL),
            entry("\\bHDTV\\b", ReleaseSource.HDTV),
            entry("\\b(DVD[\\s\\-]?RIP|DVD[\\s\\-]?MUX)\\b", ReleaseSource.DVDRIP),
            entry("\\b(HD[\\s\\-]?CAM|CAM(?:RIP)?)\\b", ReleaseSource.CAM),
            entry("\\b(TELESYNC|HDTS|TS)\\b", ReleaseSource.TELESYNC));

    public ParsedRelease parse(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return ParsedRelease.empty(rawName == null ? "" : rawName);
        }

        String normalized = normalize(rawName);

        Set<Language> subtitleLanguages = EnumSet.noneOf(Language.class);
        String withoutSubs = extractSubtitleLanguages(normalized, SUBS_LANGS_AFTER, subtitleLanguages);
        withoutSubs = extractSubtitleLanguages(withoutSubs, SUBS_LANGS_BEFORE, subtitleLanguages);

        Matcher genericSubs = SUBS_GENERIC.matcher(withoutSubs);
        boolean subtitled = !subtitleLanguages.isEmpty() || genericSubs.find();
        withoutSubs = genericSubs.reset().replaceAll(" ");

        return new ParsedRelease(
                rawName,
                detectResolution(withoutSubs),
                detectAudioLanguages(withoutSubs),
                subtitleLanguages,
                subtitled,
                detectFirst(CODECS, withoutSubs, VideoCodec.UNKNOWN),
                detectFirst(SOURCES, withoutSubs, ReleaseSource.UNKNOWN));
    }

    /** Uppercases and replaces scene separators (dots, brackets, ...) with spaces. */
    private static String normalize(String rawName) {
        String upper = rawName.toUpperCase(Locale.ROOT);
        return " " + SEPARATORS.matcher(upper).replaceAll(" ") + " ";
    }

    /**
     * Collects the subtitle languages matched by {@code pattern} into {@code out} and
     * returns the input with the matched text blanked out, so subtitle languages never
     * leak into audio detection.
     */
    private static String extractSubtitleLanguages(String text, Pattern pattern, Set<Language> out) {
        return pattern.matcher(text).replaceAll(match -> {
            Matcher lang = LANG_TOKEN.matcher(match.group(1));
            while (lang.find()) {
                out.add(toLanguage(lang.group(1)));
            }
            return " ";
        });
    }

    private static Set<Language> detectAudioLanguages(String text) {
        Set<Language> audio = EnumSet.noneOf(Language.class);
        Matcher matcher = AUDIO_TOKEN.matcher(text);
        while (matcher.find()) {
            audio.add(toLanguage(matcher.group(1)));
        }
        return audio;
    }

    private static Resolution detectResolution(String text) {
        if (RES_2160.matcher(text).find()) {
            return Resolution.R2160P;
        }
        if (RES_1080.matcher(text).find()) {
            return Resolution.R1080P;
        }
        if (RES_720.matcher(text).find()) {
            return Resolution.R720P;
        }
        return Resolution.UNKNOWN;
    }

    private static <T> T detectFirst(List<Map.Entry<Pattern, T>> patterns, String text, T fallback) {
        for (Map.Entry<Pattern, T> entry : patterns) {
            if (entry.getKey().matcher(text).find()) {
                return entry.getValue();
            }
        }
        return fallback;
    }

    private static Language toLanguage(String token) {
        return switch (token) {
            case "ITA", "ITALIAN" -> Language.ITA;
            case "ENG", "ENGLISH" -> Language.ENG;
            case "FRENCH", "FRE", "FRA" -> Language.FRE;
            case "GERMAN", "GER" -> Language.GER;
            case "SPANISH", "SPA", "ESP" -> Language.SPA;
            case "MULTI", "MULTILANG" -> Language.MULTI;
            case "DUAL" -> Language.DUAL;
            default -> throw new IllegalStateException("Unmapped language token: " + token);
        };
    }

    private static <T> Map.Entry<Pattern, T> entry(String regex, T value) {
        return Map.entry(Pattern.compile(regex), value);
    }
}
