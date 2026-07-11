package com.cineseekerr.bot.parser;

import com.cineseekerr.bot.model.Language;
import com.cineseekerr.bot.model.ParsedRelease;
import com.cineseekerr.bot.model.ReleaseSource;
import com.cineseekerr.bot.model.Resolution;
import com.cineseekerr.bot.model.VideoCodec;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseNameParserTest {

    private final ReleaseNameParser parser = new ReleaseNameParser();

    private ParsedRelease parse(String name) {
        return parser.parse(name);
    }

    @Nested
    class ResolutionDetection {

        @ParameterizedTest
        @CsvSource({
                "The.Matrix.1999.720p.BRRip.XviD.AC3-GROUP,        R720P",
                "Oppenheimer.2023.1080p.BluRay.x264-VETO,          R1080P",
                "Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.HEVC-FLUX, R2160P",
                "Movie.Name.2019.4K.HDR.x265,                      R2160P",
                "Film.2022.UHD.BluRay.x265,                        R2160P",
                "Concert.2019.1080i.HDTV.H264,                     R1080P",
                "La.Vita.E.Bella.1997.iTALiAN.DVDRip.XviD-TRL,     UNKNOWN",
        })
        void detectsResolution(String name, Resolution expected) {
            assertThat(parse(name).resolution()).isEqualTo(expected);
        }

        @Test
        void yearIsNotMistakenForResolution() {
            assertThat(parse("Blade.Runner.2049.2017.1080p.BluRay.x264").resolution())
                    .isEqualTo(Resolution.R1080P);
            assertThat(parse("2001.A.Space.Odyssey.1968.BluRay").resolution())
                    .isEqualTo(Resolution.UNKNOWN);
        }
    }

    @Nested
    class AudioLanguageDetection {

        @Test
        void detectsItalianEnglishPair() {
            assertThat(parse("Interstellar.2014.iTA.ENG.1080p.BluRay.x264-Bella").audioLanguages())
                    .containsExactlyInAnyOrder(Language.ITA, Language.ENG);
        }

        @Test
        void detectsHyphenatedItaEng() {
            assertThat(parse("Blade.Runner.2049.2017.iTA-ENG.2160p.UHD.BluRay.x265-Pool").audioLanguages())
                    .containsExactlyInAnyOrder(Language.ITA, Language.ENG);
        }

        @Test
        void detectsSceneStyleItalian() {
            assertThat(parse("Il.Ragazzo.E.L'Airone.2023.iTALiAN.1080p.WEBRip.x265").audioLanguages())
                    .containsExactly(Language.ITA);
        }

        @Test
        void detectsFullEnglishWord() {
            assertThat(parse("Casablanca.1942.ENGLISH.720p.BluRay.x264").audioLanguages())
                    .containsExactly(Language.ENG);
        }

        @Test
        void detectsMulti() {
            assertThat(parse("Perfect.Days.2023.MULTI.1080p.BluRay.x264").audioLanguages())
                    .containsExactly(Language.MULTI);
        }

        @Test
        void detectsDual() {
            assertThat(parse("Everything.Everywhere.All.At.Once.2022.DUAL.1080p.BDRip.x264").audioLanguages())
                    .containsExactly(Language.DUAL);
        }

        @ParameterizedTest
        @CsvSource({
                "Film.2020.SPANiSH.1080p.WEBRip.x264,   SPA",
                "Film.2021.FRENCH.720p.BluRay.x264,     FRE",
                "Film.2019.GERMAN.1080p.BluRay.x264,    GER",
        })
        void detectsOtherLanguages(String name, Language expected) {
            assertThat(parse(name).audioLanguages()).containsExactly(expected);
        }

        @Test
        void audioCodecTagsDoNotProduceLanguages() {
            assertThat(parse("Top.Gun.Maverick.2022.iTA.AC3.5.1.BDRip.1080p.x264-V3SP4EV3R").audioLanguages())
                    .containsExactly(Language.ITA);
        }

        @Test
        void multiReleaseMightContainAnyLanguage() {
            ParsedRelease release = parse("Perfect.Days.2023.MULTI.1080p.BluRay.x264");
            assertThat(release.hasAudio(Language.ITA)).isFalse();
            assertThat(release.mightContainAudio(Language.ITA))
                    .as("MULTI releases usually bundle the local language too")
                    .isTrue();
            assertThat(release.mightContainAudio(Language.ENG)).isTrue();
        }

        @Test
        void dualReleaseMightContainAnyLanguage() {
            ParsedRelease release = parse("Everything.Everywhere.All.At.Once.2022.DUAL.1080p.BDRip.x264");
            assertThat(release.hasAudio(Language.ITA)).isFalse();
            assertThat(release.mightContainAudio(Language.ITA)).isTrue();
        }

        @Test
        void explicitLanguageReleaseDoesNotClaimOtherLanguages() {
            ParsedRelease release = parse("Interstellar.2014.iTA.ENG.1080p.BluRay.x264-Bella");
            assertThat(release.mightContainAudio(Language.ITA)).isTrue();
            assertThat(release.mightContainAudio(Language.FRE))
                    .as("without a MULTI/DUAL tag, only explicit languages count")
                    .isFalse();
        }

        @Test
        void noLanguageInfoYieldsEmptySet() {
            assertThat(parse("Oppenheimer.2023.1080p.BluRay.x264-VETO").audioLanguages()).isEmpty();
        }

        @Test
        void languageSubstringsInsideWordsAreIgnored() {
            // DIGITAL contains "ITA", THE SUBSTANCE contains "SUB", ENGINE contains "ENG"
            ParsedRelease release = parse("The.Substance.2024.Digital.Engine.1080p.WEB-DL");
            assertThat(release.audioLanguages()).isEmpty();
            assertThat(release.subtitled()).isFalse();
        }
    }

    @Nested
    class SubtitleDetection {

        @Test
        void detectsItalianSubsAfterMarker() {
            ParsedRelease release = parse("Parasite.2019.1080p.BluRay.x264.SUB.ITA");
            assertThat(release.subtitled()).isTrue();
            assertThat(release.subtitleLanguages()).containsExactly(Language.ITA);
            assertThat(release.audioLanguages()).as("SUB.ITA must not count as Italian audio").isEmpty();
        }

        @Test
        void detectsHyphenatedSubIta() {
            ParsedRelease release = parse("Movie.Title.2021.SUB-ITA.720p.WEBRip.x264");
            assertThat(release.subtitleLanguages()).containsExactly(Language.ITA);
            assertThat(release.audioLanguages()).isEmpty();
        }

        @Test
        void detectsAttachedSubIta() {
            ParsedRelease release = parse("Movie.2021.1080p.iTA.SubITA.x264");
            assertThat(release.subtitleLanguages()).containsExactly(Language.ITA);
            assertThat(release.audioLanguages()).as("standalone iTA is still audio").containsExactly(Language.ITA);
        }

        @Test
        void detectsGenericSubsWithoutLanguage() {
            ParsedRelease release = parse("Old.Boy.2003.ENG.1080p.WEB-DL.SUBS");
            assertThat(release.subtitled()).isTrue();
            assertThat(release.subtitleLanguages()).isEmpty();
            assertThat(release.audioLanguages()).containsExactly(Language.ENG);
        }

        @Test
        void detectsLanguageBeforeSubbedMarker() {
            ParsedRelease release = parse("Godzilla.Minus.One.2023.ENG.SUBBED.1080p.WEBRip");
            assertThat(release.subtitleLanguages()).containsExactly(Language.ENG);
            assertThat(release.audioLanguages()).isEmpty();
        }

        @Test
        void distinguishesAudioFromSubsInMixedName() {
            // classic Italian pattern: English audio, Italian subs
            ParsedRelease release = parse("Movie.2020.ENG.SUB.iTA.1080p.WEB-DL.x264");
            assertThat(release.audioLanguages()).containsExactly(Language.ENG);
            assertThat(release.subtitleLanguages()).containsExactly(Language.ITA);
        }

        @Test
        void consumesConsecutiveSubtitleLanguages() {
            ParsedRelease release = parse("Film.2023.iTA.ENG.SUB.iTA-ENG.1080p.BluRay.x264");
            assertThat(release.audioLanguages()).containsExactlyInAnyOrder(Language.ITA, Language.ENG);
            assertThat(release.subtitleLanguages()).containsExactlyInAnyOrder(Language.ITA, Language.ENG);
        }

        @Test
        void multiIsNeverASubtitleLanguage() {
            ParsedRelease release = parse("Film.2024.MULTi.SUBS.1080p.WEB.x264");
            assertThat(release.audioLanguages()).containsExactly(Language.MULTI);
            assertThat(release.subtitled()).isTrue();
            assertThat(release.subtitleLanguages()).isEmpty();
        }

        @Test
        void noSubsMarkerMeansNotSubtitled() {
            ParsedRelease release = parse("Interstellar.2014.iTA.ENG.1080p.BluRay.x264-Bella");
            assertThat(release.subtitled()).isFalse();
            assertThat(release.subtitleLanguages()).isEmpty();
        }
    }

    @Nested
    class CodecDetection {

        @ParameterizedTest
        @CsvSource({
                "Oppenheimer.2023.1080p.BluRay.x264-VETO,      X264",
                "Movie.2020.1080p.AMZN.WEB.H264-GROUP,         X264",
                "Movie.2018.[1080p].[H.264].[iTA.ENG.AC3],     X264",
                "Dune.2024.2160p.WEB-DL.HEVC-FLUX,             X265",
                "Film.2022.2160p.UHD.BluRay.x265.HDR,          X265",
                "Show.2021.1080p.H.265.WEBRip,                 X265",
                "The.Matrix.1999.720p.BRRip.XviD.AC3-GROUP,    XVID",
                "Vecchio.Film.1960.iTALiAN.AC3.DVDRip.DivX,    XVID",
                "Film.2023.720p.WEB-DL.AV1.Opus,               AV1",
                "Obscure.Film.1975.Screener,                   UNKNOWN",
        })
        void detectsCodec(String name, VideoCodec expected) {
            assertThat(parse(name).codec()).isEqualTo(expected);
        }
    }

    @Nested
    class SourceDetection {

        @ParameterizedTest
        @CsvSource({
                "Oppenheimer.2023.1080p.BluRay.x264,           BLURAY",
                "Film.2021.1080p.Blu-ray.AVC,                  BLURAY",
                "Movie.2022.REMUX.2160p.HDR10,                 BLURAY",
                "The.Matrix.1999.720p.BRRip.XviD,              BDRIP",
                "Movie.2020.1080p.BDRip.x264,                  BDRIP",
                "Film.2019.iTA.AC3.BDMux.x264-GRP,             BDRIP",
                "Dune.2024.2160p.WEB-DL.HEVC,                  WEB_DL",
                "Film.2021.iTA.ENG.AC3.5.1.DLMux.H264-Group,   WEB_DL",
                "Movie.2020.1080p.AMZN.WEB.H264-GROUP,         WEB_DL",
                "Il.Ragazzo.2023.iTALiAN.1080p.WEBRip.x265,    WEBRIP",
                "Film.2022.iTA.WEBMux.x264,                    WEBRIP",
                "Some.Movie.2018.HDTV.x264,                    HDTV",
                "La.Vita.E.Bella.1997.iTALiAN.DVDRip.XviD-TRL, DVDRIP",
                "New.Release.2024.HDCAM.x264,                  CAM",
                "Another.2023.TS.x264,                         TELESYNC",
                "Obscure.Film.1975,                            UNKNOWN",
        })
        void detectsSource(String name, ReleaseSource expected) {
            assertThat(parse(name).source()).isEqualTo(expected);
        }
    }

    @Nested
    class RealWorldReleases {

        @Test
        void italianSceneBdRip() {
            ParsedRelease release = parse("Top.Gun.Maverick.2022.iTA.AC3.5.1.BDRip.1080p.x264-V3SP4EV3R");
            assertThat(release.resolution()).isEqualTo(Resolution.R1080P);
            assertThat(release.audioLanguages()).containsExactly(Language.ITA);
            assertThat(release.subtitled()).isFalse();
            assertThat(release.codec()).isEqualTo(VideoCodec.X264);
            assertThat(release.source()).isEqualTo(ReleaseSource.BDRIP);
        }

        @Test
        void ultraHdWebDl() {
            ParsedRelease release = parse("Dune.Part.Two.2024.iTA.ENG.2160p.WEB-DL.DDP5.1.Atmos.HEVC-FLUX");
            assertThat(release.resolution()).isEqualTo(Resolution.R2160P);
            assertThat(release.audioLanguages()).containsExactlyInAnyOrder(Language.ITA, Language.ENG);
            assertThat(release.codec()).isEqualTo(VideoCodec.X265);
            assertThat(release.source()).isEqualTo(ReleaseSource.WEB_DL);
        }

        @Test
        void bracketStyleRelease() {
            ParsedRelease release = parse("Movie 2018 [1080p] [H.264] [iTA ENG AC3]");
            assertThat(release.resolution()).isEqualTo(Resolution.R1080P);
            assertThat(release.audioLanguages()).containsExactlyInAnyOrder(Language.ITA, Language.ENG);
            assertThat(release.codec()).isEqualTo(VideoCodec.X264);
        }

        @Test
        void italianDlMux() {
            ParsedRelease release = parse("Film.2021.iTA.ENG.AC3.5.1.DLMux.H264-Group");
            assertThat(release.audioLanguages()).containsExactlyInAnyOrder(Language.ITA, Language.ENG);
            assertThat(release.source()).isEqualTo(ReleaseSource.WEB_DL);
            assertThat(release.codec()).isEqualTo(VideoCodec.X264);
        }

        @Test
        void oldItalianDvdRip() {
            ParsedRelease release = parse("La.Vita.E.Bella.1997.iTALiAN.DVDRip.XviD-TRL");
            assertThat(release.resolution()).isEqualTo(Resolution.UNKNOWN);
            assertThat(release.audioLanguages()).containsExactly(Language.ITA);
            assertThat(release.codec()).isEqualTo(VideoCodec.XVID);
            assertThat(release.source()).isEqualTo(ReleaseSource.DVDRIP);
        }

        @Test
        void rawNameIsPreservedVerbatim() {
            String name = "Oppenheimer.2023.1080p.BluRay.x264-VETO";
            assertThat(parse(name).rawName()).isEqualTo(name);
        }
    }

    @Nested
    class EdgeCases {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void blankInputYieldsEmptyRelease(String name) {
            ParsedRelease release = parse(name);
            assertThat(release.resolution()).isEqualTo(Resolution.UNKNOWN);
            assertThat(release.audioLanguages()).isEmpty();
            assertThat(release.subtitleLanguages()).isEmpty();
            assertThat(release.subtitled()).isFalse();
            assertThat(release.codec()).isEqualTo(VideoCodec.UNKNOWN);
            assertThat(release.source()).isEqualTo(ReleaseSource.UNKNOWN);
        }

        @Test
        void titleOnlyReleaseYieldsAllUnknown() {
            ParsedRelease release = parse("Obscure.Film.1975");
            assertThat(release).isEqualTo(ParsedRelease.empty("Obscure.Film.1975"));
        }

        @Test
        void parsedSetsAreImmutable() {
            ParsedRelease release = parse("Interstellar.2014.iTA.ENG.1080p.BluRay.x264");
            org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                    () -> release.audioLanguages().add(Language.FRE));
        }
    }
}
