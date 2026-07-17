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
 * @param seasons           TV seasons covered by the release ({@code S01} → [1],
 *                          {@code Stagioni 1-4} → [1..4]); empty for movies
 * @param episode           episode number for single-episode releases ({@code S01E05});
 *                          {@code null} for season packs and movies
 */
public record ParsedRelease(
        String rawName,
        Resolution resolution,
        Set<Language> audioLanguages,
        Set<Language> subtitleLanguages,
        boolean subtitled,
        VideoCodec codec,
        ReleaseSource source,
        Set<Integer> seasons,
        Integer episode) {

    public ParsedRelease {
        audioLanguages = immutableCopy(audioLanguages);
        subtitleLanguages = immutableCopy(subtitleLanguages);
        seasons = seasons == null || seasons.isEmpty() ? Set.of() : Set.copyOf(seasons);
    }

    /** A release from which nothing could be extracted. */
    public static ParsedRelease empty(String rawName) {
        return new ParsedRelease(rawName, Resolution.UNKNOWN, Set.of(), Set.of(), false,
                VideoCodec.UNKNOWN, ReleaseSource.UNKNOWN, Set.of(), null);
    }

    /**
     * True if this release is a full-season pack covering {@code season}: it must carry
     * that season's tag and no single-episode tag.
     */
    public boolean isSeasonPack(int season) {
        return seasons.contains(season) && episode == null;
    }

    /** True if this release is exactly the single episode {@code season}x{@code episode}. */
    public boolean isEpisode(int season, int episode) {
        return seasons.contains(season) && this.episode != null && this.episode == episode;
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
