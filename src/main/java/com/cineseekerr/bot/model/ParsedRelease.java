package com.cineseekerr.bot.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Attributes extracted from a torrent release name by
 * {@link com.cineseekerr.bot.parser.ReleaseNameParser}.
 *
 * <p>Size and seeders are intentionally absent: they come from the Prowlarr API response,
 * not from the release name.
 *
 * @param rawName           the original, unmodified release name
 * @param resolution        video resolution, {@link Resolution#UNKNOWN} if not detected
 * @param audioLanguages    audio language tags (may include {@link Language#MULTI} /
 *                          {@link Language#DUAL}); empty if none detected
 * @param subtitleLanguages subtitle languages; empty if none detected or if the release
 *                          only carries a generic "SUBS" marker
 * @param subtitled         {@code true} if any subtitle marker was found, even without an
 *                          explicit language
 * @param codec             video codec, {@link VideoCodec#UNKNOWN} if not detected
 * @param source            source medium, {@link ReleaseSource#UNKNOWN} if not detected
 */
public record ParsedRelease(
        String rawName,
        Resolution resolution,
        Set<Language> audioLanguages,
        Set<Language> subtitleLanguages,
        boolean subtitled,
        VideoCodec codec,
        ReleaseSource source) {

    public ParsedRelease {
        audioLanguages = immutableCopy(audioLanguages);
        subtitleLanguages = immutableCopy(subtitleLanguages);
    }

    /** A release from which nothing could be extracted. */
    public static ParsedRelease empty(String rawName) {
        return new ParsedRelease(rawName, Resolution.UNKNOWN, Set.of(), Set.of(), false,
                VideoCodec.UNKNOWN, ReleaseSource.UNKNOWN);
    }

    /** True only if the release name explicitly lists {@code language} as an audio track. */
    public boolean hasAudio(Language language) {
        return audioLanguages.contains(language);
    }

    /**
     * True if the release explicitly lists {@code language}, or is tagged
     * {@link Language#MULTI} / {@link Language#DUAL}: those releases bundle several audio
     * tracks and in most cases include the local language even when it is not spelled out.
     * This is the predicate the bot uses when filtering by audio language, so a user asking
     * for ITA also sees MULTI releases instead of missing them.
     */
    public boolean mightContainAudio(Language language) {
        return audioLanguages.contains(language)
                || audioLanguages.contains(Language.MULTI)
                || audioLanguages.contains(Language.DUAL);
    }

    private static Set<Language> immutableCopy(Set<Language> languages) {
        if (languages == null || languages.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(languages));
    }
}
