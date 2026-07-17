package com.cineseekerr.bot.bot;

import com.cineseekerr.bot.model.Language;
import com.cineseekerr.bot.model.ParsedRelease;
import com.cineseekerr.bot.model.ProwlarrRelease;
import com.cineseekerr.bot.model.ReleaseSource;
import com.cineseekerr.bot.model.Resolution;
import com.cineseekerr.bot.model.SearchResult;
import com.cineseekerr.bot.model.VideoCodec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseFiltersTest {

    private static SearchResult result(Resolution resolution, Set<Language> audio,
                                       Set<Language> subs, int seeders) {
        ProwlarrRelease release = new ProwlarrRelease(
                "guid", "name", 1000L, seeders, 0, "indexer", 1,
                "http://dl", null, null, "torrent");
        ParsedRelease parsed = new ParsedRelease("name", resolution, audio, subs,
                !subs.isEmpty(), VideoCodec.X264, ReleaseSource.BLURAY, Set.of(), null);
        return new SearchResult(release, parsed);
    }

    private final List<SearchResult> results = List.of(
            result(Resolution.R1080P, Set.of(Language.ITA, Language.ENG), Set.of(), 100),
            result(Resolution.R1080P, Set.of(Language.ENG), Set.of(Language.ITA), 80),
            result(Resolution.R2160P, Set.of(Language.MULTI), Set.of(), 50),
            result(Resolution.UNKNOWN, Set.of(), Set.of(), 10));

    @Test
    void qualityBucketsAreOrderedBestFirstAndOmitEmptyOnes() {
        Map<Resolution, Long> buckets = ReleaseFilters.qualityBuckets(results);
        assertThat(buckets).containsExactly(
                Map.entry(Resolution.R2160P, 1L),
                Map.entry(Resolution.R1080P, 2L),
                Map.entry(Resolution.UNKNOWN, 1L));
        assertThat(buckets).as("720p has zero results, so it must not be offered")
                .doesNotContainKey(Resolution.R720P);
    }

    @Test
    void audioBucketsCountMultiReleasesInEveryRealLanguage() {
        Map<Language, Long> buckets = ReleaseFilters.audioBuckets(results);
        // ITA: 1 explicit + 1 MULTI; ENG: 2 explicit + 1 MULTI; MULTI: 1 explicit
        assertThat(buckets).containsExactly(
                Map.entry(Language.ITA, 2L),
                Map.entry(Language.ENG, 3L),
                Map.entry(Language.MULTI, 1L));
    }

    @Test
    void multiOnlyResultsDoNotSpawnBucketsForEveryLanguage() {
        Map<Language, Long> buckets = ReleaseFilters.audioBuckets(
                List.of(result(Resolution.R1080P, Set.of(Language.MULTI), Set.of(), 1)));
        assertThat(buckets)
                .as("no phantom FRE/GER/SPA buckets out of a lone MULTI release")
                .containsExactly(Map.entry(Language.MULTI, 1L));
    }

    @Test
    void unknownAudioReleasesAppearInNoLanguageBucket() {
        Map<Language, Long> buckets = ReleaseFilters.audioBuckets(
                List.of(result(Resolution.R1080P, Set.of(), Set.of(), 1)));
        assertThat(buckets).isEmpty();
    }

    @Test
    void subtitleBucketsOnlyContainSubtitleLanguages() {
        Map<Language, Long> buckets = ReleaseFilters.subtitleBuckets(results);
        assertThat(buckets).containsExactly(Map.entry(Language.ITA, 1L));
    }

    @Test
    void byQualityFiltersAndNullMeansAny() {
        assertThat(ReleaseFilters.byQuality(results, Resolution.R1080P)).hasSize(2);
        assertThat(ReleaseFilters.byQuality(results, null)).hasSize(4);
    }

    @Test
    void byAudioItalianIncludesMultiReleases() {
        List<SearchResult> filtered = ReleaseFilters.byAudio(results, Language.ITA);
        assertThat(filtered).hasSize(2);
        assertThat(filtered)
                .anyMatch(r -> r.parsed().hasAudio(Language.MULTI))
                .anyMatch(r -> r.parsed().hasAudio(Language.ITA));
    }

    @Test
    void byAudioMultiOnlyMatchesExplicitMultiTags() {
        assertThat(ReleaseFilters.byAudio(results, Language.MULTI)).hasSize(1);
    }

    @Test
    void bySubtitleFilters() {
        List<SearchResult> filtered = ReleaseFilters.bySubtitle(results, Language.ITA);
        assertThat(filtered).hasSize(1);
        assertThat(filtered.getFirst().parsed().subtitleLanguages()).containsExactly(Language.ITA);
    }

    @Test
    void bucketCountsAlwaysMatchWhatTheFilterWouldReturn() {
        // the invariant behind "never offer a zero-result option"
        ReleaseFilters.qualityBuckets(results).forEach((resolution, count) ->
                assertThat(ReleaseFilters.byQuality(results, resolution)).hasSize(count.intValue()));
        ReleaseFilters.audioBuckets(results).forEach((language, count) ->
                assertThat(ReleaseFilters.byAudio(results, language)).hasSize(count.intValue()));
        ReleaseFilters.subtitleBuckets(results).forEach((language, count) ->
                assertThat(ReleaseFilters.bySubtitle(results, language)).hasSize(count.intValue()));
    }
}
