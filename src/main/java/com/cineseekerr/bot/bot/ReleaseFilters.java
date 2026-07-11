package com.cineseekerr.bot.bot;

import com.cineseekerr.bot.model.Language;
import com.cineseekerr.bot.model.ParsedRelease;
import com.cineseekerr.bot.model.Resolution;
import com.cineseekerr.bot.model.SearchResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the dynamic filter buckets shown to the user and applies the chosen filters.
 *
 * <p>Buckets are computed from the actual result set at each step, so an option that would
 * lead to zero results is never offered. A {@code null} filter value always means
 * "any / no filter".
 *
 * <p>Audio buckets use {@link ParsedRelease#mightContainAudio(Language)} for real
 * languages, so a release tagged only {@code MULTI} or {@code DUAL} is counted in (and
 * matched by) the ITA bucket too — those releases usually bundle the local language.
 * The MULTI/DUAL buckets themselves count only explicit tags.
 */
public final class ReleaseFilters {

    private static final List<Resolution> RESOLUTION_ORDER =
            List.of(Resolution.R2160P, Resolution.R1080P, Resolution.R720P, Resolution.UNKNOWN);

    private ReleaseFilters() {
    }

    /** Resolution buckets with counts, best quality first; empty buckets are omitted. */
    public static Map<Resolution, Long> qualityBuckets(List<SearchResult> results) {
        Map<Resolution, Long> buckets = new LinkedHashMap<>();
        for (Resolution resolution : RESOLUTION_ORDER) {
            long count = results.stream()
                    .filter(r -> r.parsed().resolution() == resolution)
                    .count();
            if (count > 0) {
                buckets.put(resolution, count);
            }
        }
        return buckets;
    }

    /**
     * Audio buckets with counts, in {@link Language} declaration order (ITA first).
     * A bucket is offered only for languages that at least one release tags
     * <em>explicitly</em> — otherwise a single MULTI release would spawn a bucket for
     * every known language. Counts still include MULTI/DUAL releases for real languages.
     */
    public static Map<Language, Long> audioBuckets(List<SearchResult> results) {
        Map<Language, Long> buckets = new LinkedHashMap<>();
        for (Language language : Language.values()) {
            boolean explicitlyTagged = results.stream()
                    .anyMatch(r -> r.parsed().hasAudio(language));
            if (!explicitlyTagged) {
                continue;
            }
            long count = results.stream()
                    .filter(r -> matchesAudio(r.parsed(), language))
                    .count();
            buckets.put(language, count);
        }
        return buckets;
    }

    /** Subtitle-language buckets with counts; MULTI/DUAL are never subtitle languages. */
    public static Map<Language, Long> subtitleBuckets(List<SearchResult> results) {
        Map<Language, Long> buckets = new LinkedHashMap<>();
        for (Language language : Language.values()) {
            if (language == Language.MULTI || language == Language.DUAL) {
                continue;
            }
            long count = results.stream()
                    .filter(r -> matchesSubtitle(r.parsed(), language))
                    .count();
            if (count > 0) {
                buckets.put(language, count);
            }
        }
        return buckets;
    }

    public static List<SearchResult> byQuality(List<SearchResult> results, Resolution resolution) {
        if (resolution == null) {
            return results;
        }
        return results.stream()
                .filter(r -> r.parsed().resolution() == resolution)
                .toList();
    }

    public static List<SearchResult> byAudio(List<SearchResult> results, Language language) {
        if (language == null) {
            return results;
        }
        return results.stream()
                .filter(r -> matchesAudio(r.parsed(), language))
                .toList();
    }

    public static List<SearchResult> bySubtitle(List<SearchResult> results, Language language) {
        if (language == null) {
            return results;
        }
        return results.stream()
                .filter(r -> matchesSubtitle(r.parsed(), language))
                .toList();
    }

    static boolean matchesAudio(ParsedRelease parsed, Language language) {
        if (language == Language.MULTI || language == Language.DUAL) {
            return parsed.hasAudio(language);
        }
        return parsed.mightContainAudio(language);
    }

    private static boolean matchesSubtitle(ParsedRelease parsed, Language language) {
        return parsed.subtitleLanguages().contains(language);
    }
}
