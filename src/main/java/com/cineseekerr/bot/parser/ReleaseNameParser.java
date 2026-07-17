package com.cineseekerr.bot.parser;

import com.cineseekerr.bot.model.Language;
import com.cineseekerr.bot.model.ParsedRelease;
import com.cineseekerr.bot.model.ReleaseSource;
import com.cineseekerr.bot.model.Resolution;
import com.cineseekerr.bot.model.VideoCodec;

import java.util.EnumSet;
import java.util.HashSet;
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
            "ITA(?:LIAN)?|ENG(?:LISH)?|FRENCH|FRE|FRA|GERMAN|GER|SPANISH|SPA|ESP"
                    + "|JAP(?:ANESE)?|JPN|KOR(?:EAN)?|RUS(?:SIAN)?|POR(?:TUGUESE)?";
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

    /**
     * Season/episode patterns, most specific first. An episode <em>range</em>
     * ({@code S01E01-E10}) counts as a pack, not an episode.
     */
    private static final Pattern SEASON_EPISODE_RANGE =
            Pattern.compile("\\bS(\\d{1,2})\\s?E(\\d{1,3})\\s?-\\s?E?(\\d{1,3})\\b");
    private static final Pattern SEASON_EPISODE = Pattern.compile("\\bS(\\d{1,2})\\s?E(\\d{1,3})\\b");
    private static final Pattern SEASON_RANGE = Pattern.compile("\\bS(\\d{1,2})\\s?-\\s?S(\\d{1,2})\\b");
    private static final Pattern SEASON_X_EPISODE = Pattern.compile("\\b(\\d{1,2})X(\\d{2,3})\\b");
    private static final Pattern SEASON_WORD = Pattern.compile(
            "\\b(?:STAGION[EI]|SEASONS?)\\s?(\\d{1,2})(?:\\s?-\\s?(\\d{1,2}))?\\b");
    private static final Pattern SEASON_ONLY = Pattern.compile("\\bS(\\d{1,2})\\b");

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

        SeasonEpisode seasonEpisode = detectSeasonEpisode(withoutSubs);

        return new ParsedRelease(
                rawName,
                detectResolution(withoutSubs),
                detectAudioLanguages(withoutSubs),
                subtitleLanguages,
                subtitled,
                detectFirst(CODECS, withoutSubs, VideoCodec.UNKNOWN),
                detectFirst(SOURCES, withoutSubs, ReleaseSource.UNKNOWN),
                seasonEpisode.seasons(),
                seasonEpisode.episode());
    }

    private record SeasonEpisode(Set<Integer> seasons, Integer episode) {
        static final SeasonEpisode NONE = new SeasonEpisode(Set.of(), null);
    }

    private static SeasonEpisode detectSeasonEpisode(String text) {
        Matcher matcher = SEASON_EPISODE_RANGE.matcher(text);
        if (matcher.find()) {
            // an episode range (S01E01-E10) is effectively a pack of that season
            return new SeasonEpisode(Set.of(Integer.parseInt(matcher.group(1))), null);
        }
        matcher = SEASON_EPISODE.matcher(text);
        if (matcher.find()) {
            return new SeasonEpisode(Set.of(Integer.parseInt(matcher.group(1))),
                    Integer.parseInt(matcher.group(2)));
        }
        matcher = SEASON_RANGE.matcher(text);
        if (matcher.find()) {
            return new SeasonEpisode(
                    range(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))), null);
        }
        matcher = SEASON_WORD.matcher(text);
        if (matcher.find()) {
            int from = Integer.parseInt(matcher.group(1));
            int to = matcher.group(2) == null ? from : Integer.parseInt(matcher.group(2));
            return new SeasonEpisode(range(from, to), null);
        }
        matcher = SEASON_X_EPISODE.matcher(text);
        if (matcher.find()) {
            return new SeasonEpisode(Set.of(Integer.parseInt(matcher.group(1))),
                    Integer.parseInt(matcher.group(2)));
        }
        matcher = SEASON_ONLY.matcher(text);
        if (matcher.find()) {
            return new SeasonEpisode(Set.of(Integer.parseInt(matcher.group(1))), null);
        }
        return SeasonEpisode.NONE;
    }

    private static Set<Integer> range(int from, int to) {
        if (to < from) {
            return Set.of(from);
        }
        Set<Integer> seasons = new HashSet<>();
        for (int i = from; i <= to; i++) {
            seasons.add(i);
        }
        return seasons;
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
            case "JAP", "JAPANESE", "JPN" -> Language.JAP;
            case "KOR", "KOREAN" -> Language.KOR;
            case "RUS", "RUSSIAN" -> Language.RUS;
            case "POR", "PORTUGUESE" -> Language.POR;
            case "MULTI", "MULTILANG" -> Language.MULTI;
            case "DUAL" -> Language.DUAL;
            default -> throw new IllegalStateException("Unmapped language token: " + token);
        };
    }

    private static <T> Map.Entry<Pattern, T> entry(String regex, T value) {
        return Map.entry(Pattern.compile(regex), value);
    }
}
