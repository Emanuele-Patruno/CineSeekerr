package com.cineseekerr.bot.bot;

import com.cineseekerr.bot.bot.download.DownloadTracker;
import com.cineseekerr.bot.bot.state.ConversationState;
import com.cineseekerr.bot.bot.state.ConversationStep;
import com.cineseekerr.bot.bot.state.ConversationStateStore;
import com.cineseekerr.bot.bot.state.InMemoryConversationStateStore;
import com.cineseekerr.bot.bot.telegram.TelegramMessenger;
import com.cineseekerr.bot.client.ApiClientException;
import com.cineseekerr.bot.client.ProwlarrClient;
import com.cineseekerr.bot.client.QbittorrentClient;
import com.cineseekerr.bot.client.TmdbClient;
import com.cineseekerr.bot.config.CineSeekerrProperties;
import com.cineseekerr.bot.model.Language;
import com.cineseekerr.bot.model.MediaType;
import com.cineseekerr.bot.model.ProwlarrIndexer;
import com.cineseekerr.bot.model.ProwlarrRelease;
import com.cineseekerr.bot.model.QbtTorrent;
import com.cineseekerr.bot.model.TmdbSeason;
import com.cineseekerr.bot.model.TmdbTitle;
import com.cineseekerr.bot.parser.ReleaseNameParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConversationHandlerTest {

    private static final long CHAT = 42L;
    private static final int MSG = 100;

    @Mock
    private TmdbClient tmdb;
    @Mock
    private ProwlarrClient prowlarr;
    @Mock
    private QbittorrentClient qbittorrent;
    @Mock
    private TelegramMessenger messenger;
    @Mock
    private DownloadTracker tracker;

    private final ConversationStateStore store = new InMemoryConversationStateStore();

    private ConversationHandler handler;

    @BeforeEach
    void setUp() {
        // Italian bundle: the assertions below check the actual texts sent to Telegram
        CineSeekerrProperties properties = new CineSeekerrProperties(
                null, null, null, null,
                new CineSeekerrProperties.Media("/volume1/media/Film", "/volume1/media/Serie TV"),
                "it");
        Messages messages = new Messages(properties);
        handler = new ConversationHandler(tmdb, prowlarr, qbittorrent, new ReleaseNameParser(),
                messenger, store, tracker, properties, messages, new MessageFormatter(messages));
        when(messenger.sendHtml(anyLong(), anyString(), any())).thenReturn(MSG);
    }

    private static TmdbTitle movie() {
        return new TmdbTitle(872585, "movie", "Dune: Parte Due", "Dune: Part Two",
                "2024-02-28", "/poster.jpg", "Paul Atreides si unisce ai Fremen.");
    }

    private static TmdbTitle tvShow() {
        return new TmdbTitle(1396, "tv", "Breaking Bad", "Breaking Bad",
                "2008-01-20", "/bb.jpg", "Un professore di chimica...");
    }

    private static ProwlarrRelease release(String title, int seeders, String downloadUrl) {
        return new ProwlarrRelease("guid-" + seeders, title, 15_000_000_000L, seeders, 1,
                "TheIndexer", 3, downloadUrl, null, null, "torrent");
    }

    /** Two 1080p (one ITA+ENG, one ENG with ITA subs) and one 2160p MULTI. */
    private static List<ProwlarrRelease> releases() {
        return List.of(
                release("Dune.Part.Two.2024.iTA.ENG.1080p.BluRay.x264-GRP", 100, "http://dl-ita"),
                release("Dune.Part.Two.2024.ENG.1080p.WEBRip.x264.SUB.iTA-GRP", 80, "http://dl-eng"),
                release("Dune.Part.Two.2024.MULTI.2160p.WEB-DL.x265-GRP", 50, "http://dl-multi"));
    }

    private void reachMovieChoice() {
        when(tmdb.searchTitles("dune")).thenReturn(List.of(movie()));
        handler.onTextMessage(CHAT, "dune");
    }

    @Nested
    class MovieSearch {

        @Test
        void freeTextTriggersTmdbSearchAndShowsCandidates() {
            reachMovieChoice();

            ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
            verify(messenger).sendHtml(eq(CHAT), contains("Dune: Parte Due"), keyboard.capture());
            assertThat(keyboard.getValue().getKeyboard())
                    .as("one row per candidate plus the cancel row")
                    .hasSize(2);
            assertThat(store.find(CHAT)).isPresent();
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_MOVIE_CHOICE);
        }

        @Test
        void cercaCommandBehavesLikeFreeText() {
            when(tmdb.searchTitles("dune")).thenReturn(List.of(movie()));
            handler.onTextMessage(CHAT, "/cerca dune");
            verify(messenger).sendHtml(eq(CHAT), contains("Quale intendi"), any());
        }

        @Test
        void noTmdbResultsInformTheUserAndKeepNoState() {
            when(tmdb.searchTitles("xyzabc")).thenReturn(List.of());
            handler.onTextMessage(CHAT, "xyzabc");
            verify(messenger).sendHtml(eq(CHAT), contains("Nessun film o serie TV"), isNull());
            assertThat(store.find(CHAT)).isEmpty();
        }

        @Test
        void tmdbFailureIsReportedGracefully() {
            when(tmdb.searchTitles(anyString())).thenThrow(new ApiClientException("TMDB search failed"));
            handler.onTextMessage(CHAT, "dune");
            verify(messenger).sendHtml(eq(CHAT), contains("⚠️"), isNull());
        }

        @Test
        void filmCommandSearchesMoviesOnly() {
            when(tmdb.searchMovies("dune")).thenReturn(List.of(movie()));

            handler.onTextMessage(CHAT, "/film dune");

            verify(messenger).sendHtml(eq(CHAT), contains("Dune: Parte Due"), any());
            verify(tmdb, never()).searchTitles(anyString());
        }

        @Test
        void filmCommandWithoutArgsShowsUsage() {
            handler.onTextMessage(CHAT, "/film");
            verify(messenger).sendHtml(eq(CHAT), contains("/film"), isNull());
            verifyNoInteractions(tmdb);
        }

        @Test
        void filmCommandWithNoResultsShowsMovieSpecificMessage() {
            when(tmdb.searchMovies("xyzabc")).thenReturn(List.of());
            handler.onTextMessage(CHAT, "/film xyzabc");
            verify(messenger).sendHtml(eq(CHAT), contains("Nessun film trovato"), isNull());
        }

        @Test
        void serieCommandSearchesTvOnly() {
            when(tmdb.searchTv("breaking bad")).thenReturn(List.of(tvShow()));

            handler.onTextMessage(CHAT, "/serie breaking bad");

            verify(messenger).sendHtml(eq(CHAT), contains("Breaking Bad"), any());
            verify(tmdb, never()).searchTitles(anyString());
        }

        @Test
        void serieCommandWithoutArgsShowsUsage() {
            handler.onTextMessage(CHAT, "/serie");
            verify(messenger).sendHtml(eq(CHAT), contains("/serie"), isNull());
            verifyNoInteractions(tmdb);
        }

        @Test
        void serieCommandWithNoResultsShowsTvSpecificMessage() {
            when(tmdb.searchTv("xyzabc")).thenReturn(List.of());
            handler.onTextMessage(CHAT, "/serie xyzabc");
            verify(messenger).sendHtml(eq(CHAT), contains("Nessuna serie TV trovata"), isNull());
        }

        @Test
        void englishCommandAliasesWorkTheSameAsTheItalianOnes() {
            when(tmdb.searchTitles("dune")).thenReturn(List.of(movie()));
            when(tmdb.searchMovies("dune")).thenReturn(List.of(movie()));
            when(tmdb.searchTv("breaking bad")).thenReturn(List.of(tvShow()));

            handler.onTextMessage(CHAT, "/search dune");
            verify(tmdb).searchTitles("dune");

            handler.onTextMessage(CHAT, "/movie dune");
            verify(tmdb).searchMovies("dune");

            handler.onTextMessage(CHAT, "/tv breaking bad");
            verify(tmdb).searchTv("breaking bad");
        }
    }

    @Nested
    class FilterFlow {

        @Test
        void movieSelectionSearchesProwlarrWithBothOriginalAndLocalizedTitle() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());

            handler.onCallback(CHAT, MSG, "cb1", "movie:0");

            verify(messenger).answerCallback("cb1");
            verify(prowlarr).search(eq("Dune: Part Two 2024"), eq(MediaType.MOVIE), any());
            verify(prowlarr).search(eq("Dune: Parte Due 2024"), eq(MediaType.MOVIE), any());
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Scegli l'audio"), any());
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_AUDIO);
        }

        @Test
        void resultsFromTheTwoQueriesAreMergedWithoutDuplicates() {
            reachMovieChoice();
            // the iTA release comes back from both queries (same guid) plus an
            // Italian-titled one only the localized query finds
            when(prowlarr.search(eq("Dune: Part Two 2024"), any(), any()))
                    .thenReturn(List.of(release("Dune.Part.Two.2024.iTA.ENG.1080p.BluRay.x264-GRP", 100, "http://dl-ita")));
            when(prowlarr.search(eq("Dune: Parte Due 2024"), any(), any()))
                    .thenReturn(List.of(
                            release("Dune.Part.Two.2024.iTA.ENG.1080p.BluRay.x264-GRP", 100, "http://dl-ita"),
                            release("Dune.Parte.Due.2024.iTA.2160p.WEB-DL-GRP", 40, "http://dl-loc")));

            handler.onCallback(CHAT, MSG, "cb1", "movie:0");

            assertThat(store.find(CHAT).get().filtered())
                    .as("duplicate guid counted once, localized-only release included")
                    .hasSize(2);
        }

        @Test
        void identicalOriginalAndLocalizedTitlesTriggerASingleQuery() {
            when(tmdb.searchTitles("breaking bad")).thenReturn(List.of(tvShow()));
            when(tmdb.seasons(1396)).thenReturn(List.of(new TmdbSeason(1, 7)));
            when(prowlarr.search(anyString(), any(), any())).thenReturn(List.of(
                    release("Breaking.Bad.S01.iTA.1080p-GRP", 50, "http://dl")));
            handler.onTextMessage(CHAT, "breaking bad");
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "season:1");

            handler.onCallback(CHAT, MSG, "cb3", "episode:all");

            verify(prowlarr).listIndexers();
            verify(prowlarr).search(eq("Breaking Bad"), eq(MediaType.TV), any());
            verifyNoMoreInteractions(prowlarr);
        }

        @Test
        void noReleasesEndsTheConversation() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(List.of());

            handler.onCallback(CHAT, MSG, "cb1", "movie:0");

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Nessuna release"), isNull());
            assertThat(store.find(CHAT)).isEmpty();
        }

        @Test
        void audioItalianNarrowsResultsThenAsksForQualityWhenSubsOfferNoChoice() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");

            handler.onCallback(CHAT, MSG, "cb2", "audio:ITA");

            // explicit iTA + the MULTI release remain; neither has subs → subtitle step
            // skipped → quality is asked (1080p vs 2160p)
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Scegli la qualità"), any());
            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_QUALITY);
            assertThat(state.filtered()).hasSize(2);
        }

        @Test
        void qualityIsTheLastFilterBeforeTheShortlist() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "audio:ITA");

            handler.onCallback(CHAT, MSG, "cb3", "quality:R1080P");

            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_RELEASE_CHOICE);
            assertThat(state.shortlist()).hasSize(1);
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Migliori release"), any());
        }

        @Test
        void shortlistIsSortedBySeeders() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "audio:any");
            handler.onCallback(CHAT, MSG, "cb3", "subs:any");
            handler.onCallback(CHAT, MSG, "cb4", "quality:any");
            // x264 (2 releases) vs x265 (1 release) still differ → format is asked too
            handler.onCallback(CHAT, MSG, "cb5", "format:any");

            ConversationState state = store.find(CHAT).get();
            assertThat(state.shortlist()).extracting(r -> r.release().seeders())
                    .containsExactly(100, 80, 50);
        }

        @Test
        void formatStepAsksWhenCodecsDifferAndNarrowsResults() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "audio:any");
            handler.onCallback(CHAT, MSG, "cb3", "subs:any");
            handler.onCallback(CHAT, MSG, "cb4", "quality:any");

            handler.onCallback(CHAT, MSG, "cb5", "format:X264");

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Scegli il formato"), any());
            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_RELEASE_CHOICE);
            assertThat(state.filtered()).hasSize(2);
        }

        @Test
        void backButtonOnShortlistReturnsToFormatStepWhenItWasActuallyShown() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "audio:any");
            handler.onCallback(CHAT, MSG, "cb3", "subs:any");
            handler.onCallback(CHAT, MSG, "cb4", "quality:any");
            handler.onCallback(CHAT, MSG, "cb5", "format:X264");
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_RELEASE_CHOICE);

            handler.onCallback(CHAT, MSG, "cb6", "back");

            verify(messenger, times(2)).editHtml(eq(CHAT), eq(MSG), contains("Scegli il formato"), any());
            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_FORMAT);
            assertThat(state.formatFilter()).isNull();
            assertThat(state.filtered()).as("back to format resets to the full result set").hasSize(3);
        }

        @Test
        void reselectingTheSameQualityAfterGoingBackAsksFormatAgain() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "audio:any");
            handler.onCallback(CHAT, MSG, "cb3", "subs:any");
            handler.onCallback(CHAT, MSG, "cb4", "quality:any");
            handler.onCallback(CHAT, MSG, "cb5", "format:X264");
            handler.onCallback(CHAT, MSG, "cb6", "back"); // shortlist -> format
            handler.onCallback(CHAT, MSG, "cb7", "back"); // format -> quality
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_QUALITY);

            handler.onCallback(CHAT, MSG, "cb8", "quality:any");

            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_FORMAT);
        }

        @Test
        void audioStepHasNoBackButtonSinceItIsTheFirstStep() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());

            handler.onCallback(CHAT, MSG, "cb1", "movie:0");

            assertThat(store.find(CHAT).get().backAction()).isNull();
        }

        @Test
        void backButtonOnSubtitleStepReturnsToAudioStepWithFreshBuckets() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "audio:any");
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_SUBTITLES);

            handler.onCallback(CHAT, MSG, "cb3", "back");

            // shown once on the way forward, once again on the way back
            verify(messenger, times(2)).editHtml(eq(CHAT), eq(MSG), contains("Scegli l'audio"), any());
            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_AUDIO);
            assertThat(state.audioFilter()).isNull();
            assertThat(state.filtered()).as("back to audio resets to the full result set").hasSize(3);
        }

        @Test
        void backButtonOnQualityStepReturnsToSubtitleStepWhenItWasActuallyShown() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "audio:any");
            handler.onCallback(CHAT, MSG, "cb3", "subs:any");
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_QUALITY);

            handler.onCallback(CHAT, MSG, "cb4", "back");

            verify(messenger, times(2)).editHtml(eq(CHAT), eq(MSG), contains("Sottotitoli"), any());
            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_SUBTITLES);
            assertThat(state.subtitleFilter()).isNull();
        }

        @Test
        void backButtonOnQualityStepSkipsStraightToAudioWhenSubtitleStepHadBeenSkipped() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            // ITA narrows to 2 releases, neither has a subtitle tag -> subtitle step is
            // skipped and quality's back button must point all the way to audio
            handler.onCallback(CHAT, MSG, "cb2", "audio:ITA");
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_QUALITY);

            handler.onCallback(CHAT, MSG, "cb3", "back");

            verify(messenger, times(2)).editHtml(eq(CHAT), eq(MSG), contains("Scegli l'audio"), any());
            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_AUDIO);
            assertThat(state.filtered()).hasSize(3);
        }

        @Test
        void goingBackThenPickingADifferentAudioNarrowsResultsAgain() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "audio:ITA");
            handler.onCallback(CHAT, MSG, "cb3", "back");

            handler.onCallback(CHAT, MSG, "cb4", "audio:ENG");

            ConversationState state = store.find(CHAT).get();
            assertThat(state.audioFilter()).isEqualTo(Language.ENG);
            // ENG explicit (2 releases) plus the MULTI release, which counts for any
            // real language too
            assertThat(state.filtered()).hasSize(3);
        }

        @Test
        void backButtonOnShortlistReturnsToQualityStep() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "audio:ITA");
            handler.onCallback(CHAT, MSG, "cb3", "quality:R1080P");
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_RELEASE_CHOICE);

            handler.onCallback(CHAT, MSG, "cb4", "back");

            verify(messenger, times(2)).editHtml(eq(CHAT), eq(MSG), contains("Scegli la qualità"), any());
            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_QUALITY);
            assertThat(state.qualityFilter()).isNull();
        }

        @Test
        void backButtonChainFromShortlistWalksAllTheWayBackToAudio() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "audio:ITA");
            handler.onCallback(CHAT, MSG, "cb3", "quality:R1080P");

            handler.onCallback(CHAT, MSG, "cb4", "back"); // shortlist -> quality
            handler.onCallback(CHAT, MSG, "cb5", "back"); // quality -> audio (subs was skipped)

            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_AUDIO);
            assertThat(state.filtered()).hasSize(3);
        }
    }

    @Nested
    class IndexerSelection {

        private static ProwlarrIndexer indexer(int id, String name) {
            return new ProwlarrIndexer(id, name, true);
        }

        private void reachIndexerChoice() {
            when(tmdb.searchTitles("dune")).thenReturn(List.of(movie()));
            when(prowlarr.listIndexers()).thenReturn(List.of(indexer(1, "IndexerA"), indexer(2, "IndexerB")));
            handler.onTextMessage(CHAT, "dune");
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
        }

        @Test
        void showsAllIndexersPreselectedWhenThereAreAtLeastTwo() {
            reachIndexerChoice();

            ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
            verify(messenger).editHtml(eq(CHAT), eq(MSG), anyString(), keyboard.capture());
            assertThat(keyboard.getValue().getKeyboard().get(0).get(0).getText())
                    .as("preselected with a checkmark")
                    .isEqualTo("✅ IndexerA");
            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_INDEXER_CHOICE);
            assertThat(state.selectedIndexerIds()).containsExactlyInAnyOrder(1, 2);
            verify(prowlarr, never()).search(anyString(), any(), any());
        }

        @Test
        void togglingAnIndexerDeselectsItAndTogglingAgainReselectsIt() {
            reachIndexerChoice();

            handler.onCallback(CHAT, MSG, "cb2", "idx:1");
            assertThat(store.find(CHAT).get().selectedIndexerIds()).containsExactly(2);

            handler.onCallback(CHAT, MSG, "cb3", "idx:1");
            assertThat(store.find(CHAT).get().selectedIndexerIds()).containsExactlyInAnyOrder(1, 2);
        }

        @Test
        void confirmingSearchWithASubsetOnlyQueriesThoseIndexers() {
            reachIndexerChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());

            handler.onCallback(CHAT, MSG, "cb2", "idx:1"); // deselect indexer 1, keep only 2
            handler.onCallback(CHAT, MSG, "cb3", "idxsearch");

            verify(prowlarr).search(eq("Dune: Part Two 2024"), eq(MediaType.MOVIE), eq(Set.of(2)));
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_AUDIO);
        }

        @Test
        void confirmingWithNothingSelectedShowsAWarningAndStaysOnTheStep() {
            reachIndexerChoice();

            handler.onCallback(CHAT, MSG, "cb2", "idx:1");
            handler.onCallback(CHAT, MSG, "cb3", "idx:2");
            handler.onCallback(CHAT, MSG, "cb4", "idxsearch");

            // shown once as soon as the last indexer is deselected, again after "Search"
            verify(messenger, times(2)).editHtml(eq(CHAT), eq(MSG), contains("Seleziona almeno un indexer"), any());
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_INDEXER_CHOICE);
            verify(prowlarr, never()).search(anyString(), any(), any());
        }

        @Test
        void stepIsSkippedEntirelyWhenFewerThanTwoIndexersAreConfigured() {
            when(tmdb.searchTitles("dune")).thenReturn(List.of(movie()));
            when(prowlarr.listIndexers()).thenReturn(List.of(indexer(1, "OnlyOne")));
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onTextMessage(CHAT, "dune");

            handler.onCallback(CHAT, MSG, "cb1", "movie:0");

            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_AUDIO);
        }
    }

    @Nested
    class Download {

        private void reachShortlist() {
            reachMovieChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "audio:ITA");
            handler.onCallback(CHAT, MSG, "cb3", "quality:R1080P");
        }

        @Test
        void releaseSelectionSendsTorrentToQbittorrentAndTracksIt() {
            reachShortlist();

            handler.onCallback(CHAT, MSG, "cb4", "release:0");

            verify(qbittorrent).addTorrent("http://dl-ita", "/volume1/media/Film", "film");
            verify(tracker).track(CHAT, "Dune.Part.Two.2024.iTA.ENG.1080p.BluRay.x264-GRP",
                    "Dune Parte Due (2024)", movie(), null, null);
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Torrent inviato"), isNull());
            assertThat(store.find(CHAT)).as("conversation is over").isEmpty();
        }

        @Test
        void qbittorrentFailureClearsStateAndInformsUser() {
            reachShortlist();
            org.mockito.Mockito.doThrow(new ApiClientException("qBittorrent is unreachable"))
                    .when(qbittorrent).addTorrent(anyString(), anyString(), anyString());

            handler.onCallback(CHAT, MSG, "cb4", "release:0");

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("⚠️"), isNull());
            assertThat(store.find(CHAT)).isEmpty();
            verify(tracker, never()).track(anyLong(), anyString(), anyString(), any(), any(), any());
        }
    }

    @Nested
    class DownloadStatus {

        private static QbtTorrent torrent(String name, double progress) {
            return new QbtTorrent(name.hashCode() + "", name, progress >= 1.0 ? "uploading" : "downloading",
                    progress, "/downloads", "/downloads/" + name, 0L, 0L, 5);
        }

        @Test
        void statoShowsOnlyIncompleteDownloadsWithAStopButtonEach() {
            when(qbittorrent.listTorrents("film")).thenReturn(List.of(
                    torrent("Still.Downloading.2024", 0.42),
                    torrent("Already.Done.2023", 1.0)));

            handler.onTextMessage(CHAT, "/stato");

            ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
            verify(messenger).sendHtml(eq(CHAT), org.mockito.ArgumentMatchers.argThat(text ->
                    text.contains("Still.Downloading.2024") && !text.contains("Already.Done.2023")),
                    keyboard.capture());
            assertThat(keyboard.getValue().getKeyboard())
                    .as("one stop button, only for the incomplete download")
                    .hasSize(1);
        }

        @Test
        void statoWithOnlyCompletedDownloadsReportsNoneInProgress() {
            when(qbittorrent.listTorrents("film")).thenReturn(List.of(torrent("Already.Done.2023", 1.0)));

            handler.onTextMessage(CHAT, "/stato");

            verify(messenger).sendHtml(eq(CHAT), contains("Nessun download in corso"), isNull());
        }

        @Test
        void statusIsAnAliasForStato() {
            when(qbittorrent.listTorrents("film")).thenReturn(List.of(torrent("Already.Done.2023", 1.0)));

            handler.onTextMessage(CHAT, "/status");

            verify(messenger).sendHtml(eq(CHAT), contains("Nessun download in corso"), isNull());
        }

        @Test
        void stopButtonDeletesTorrentWithFilesAndEditsMessage() {
            handler.onCallback(CHAT, MSG, "cb1", "stopdl:abc123");

            verify(qbittorrent).deleteTorrent("abc123", true);
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("fermato"), isNull());
        }

        @Test
        void stopButtonWorksWithoutAnyConversationState() {
            // unlike the movie-search flow, stopping a download must not require
            // an in-progress conversation (the user might not have searched anything)
            assertThat(store.find(CHAT)).isEmpty();

            handler.onCallback(CHAT, MSG, "cb1", "stopdl:abc123");

            verify(messenger, never()).editHtml(eq(CHAT), eq(MSG), contains("Sessione scaduta"), any());
            verify(qbittorrent).deleteTorrent("abc123", true);
        }

        @Test
        void stopButtonFailureIsReportedGracefully() {
            org.mockito.Mockito.doThrow(new ApiClientException("qBittorrent is unreachable"))
                    .when(qbittorrent).deleteTorrent(anyString(), anyBoolean());

            handler.onCallback(CHAT, MSG, "cb1", "stopdl:abc123");

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("⚠️"), isNull());
        }
    }

    @Nested
    class StalledDownloadRetry {

        private static final String TORRENT_NAME = "Dune.Part.Two.2024.iTA.ENG.1080p.BluRay.x264-GRP";

        private static QbtTorrent stalledTorrent() {
            return new QbtTorrent("hash1", TORRENT_NAME, "stalledDL", 0.3,
                    "/downloads", "/downloads/" + TORRENT_NAME, 0L, 0L, 0);
        }

        @Test
        void retryStopsTorrentForgetsItAndStartsANewSearch() {
            when(qbittorrent.listTorrents("film")).thenReturn(List.of(stalledTorrent()));
            when(tracker.findByTorrentName(TORRENT_NAME)).thenReturn(Optional.of(
                    Map.entry("trackerkey", new DownloadTracker.PendingDownload(
                            CHAT, TORRENT_NAME, "Dune Parte Due (2024)", movie(), null, null, Instant.now()))));
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());

            handler.onCallback(CHAT, MSG, "cb1", "retrystalled:hash1");

            verify(qbittorrent).deleteTorrent("hash1", true);
            verify(tracker).remove("trackerkey");
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("fermato"), isNull());
            verify(prowlarr).search(eq("Dune: Part Two 2024"), eq(MediaType.MOVIE), any());
            assertThat(store.find(CHAT)).isPresent();
            assertThat(store.find(CHAT).get().title()).isEqualTo(movie());
        }

        @Test
        void retryWorksWithoutAnyConversationState() {
            assertThat(store.find(CHAT)).isEmpty();
            when(qbittorrent.listTorrents("film")).thenReturn(List.of(stalledTorrent()));
            when(tracker.findByTorrentName(TORRENT_NAME)).thenReturn(Optional.of(
                    Map.entry("trackerkey", new DownloadTracker.PendingDownload(
                            CHAT, TORRENT_NAME, "Dune Parte Due (2024)", movie(), null, null, Instant.now()))));
            when(prowlarr.search(anyString(), any(), any())).thenReturn(releases());

            handler.onCallback(CHAT, MSG, "cb1", "retrystalled:hash1");

            verify(messenger, never()).editHtml(eq(CHAT), eq(MSG), contains("Sessione scaduta"), any());
            verify(qbittorrent).deleteTorrent("hash1", true);
        }

        @Test
        void retryWhenTorrentIsAlreadyGoneJustInformsTheUser() {
            when(qbittorrent.listTorrents("film")).thenReturn(List.of());

            handler.onCallback(CHAT, MSG, "cb1", "retrystalled:hash1");

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Non trovo"), isNull());
            verify(qbittorrent, never()).deleteTorrent(anyString(), anyBoolean());
            verify(tracker, never()).remove(anyString());
        }

        @Test
        void retryFailureIsReportedGracefully() {
            when(qbittorrent.listTorrents("film")).thenThrow(new ApiClientException("qBittorrent is unreachable"));

            handler.onCallback(CHAT, MSG, "cb1", "retrystalled:hash1");

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("⚠️"), isNull());
        }

        @Test
        void retryOfAStalledTvSeasonSearchesPacksOfTheSameSeason() {
            String tvTorrent = "Breaking.Bad.S02.COMPLETA.iTA.1080p.WEBMux-GRP";
            QbtTorrent stalled = new QbtTorrent("hash1", tvTorrent, "stalledDL", 0.1,
                    "/downloads", "/downloads/" + tvTorrent, 0L, 0L, 0);
            when(qbittorrent.listTorrents("film")).thenReturn(List.of(stalled));
            when(tracker.findByTorrentName(tvTorrent)).thenReturn(Optional.of(
                    Map.entry("trackerkey", new DownloadTracker.PendingDownload(
                            CHAT, tvTorrent, "Breaking Bad (2008) — Stagione 2", tvShow(), 2, null,
                            Instant.now()))));
            when(prowlarr.search(anyString(), any(), any())).thenReturn(List.of(
                    release("Breaking.Bad.S02.iTA.ENG.1080p.BDMux-GRP", 60, "http://dl-s02")));

            handler.onCallback(CHAT, MSG, "cb1", "retrystalled:hash1");

            verify(prowlarr).search(eq("Breaking Bad"), eq(MediaType.TV), any());
            assertThat(store.find(CHAT)).isPresent();
            assertThat(store.find(CHAT).get().season()).isEqualTo(2);
        }
    }

    @Nested
    class TvFlow {

        /** An S01 pack, a single S01 episode and an S02 pack. */
        private List<ProwlarrRelease> tvReleases() {
            return List.of(
                    release("Breaking.Bad.S01.iTA.ENG.1080p.BDMux-GRP", 90, "http://dl-s01"),
                    release("Breaking.Bad.S01E05.iTA.1080p.WEB-DL-GRP", 70, "http://dl-ep"),
                    release("Breaking.Bad.Stagione.2.COMPLETA.iTA.1080p-GRP", 50, "http://dl-s02"));
        }

        private void reachSeasonChoice() {
            when(tmdb.searchTitles("breaking bad")).thenReturn(List.of(tvShow()));
            when(tmdb.seasons(1396)).thenReturn(List.of(new TmdbSeason(1, 7), new TmdbSeason(2, 13)));
            handler.onTextMessage(CHAT, "breaking bad");
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
        }

        @Test
        void choosingATvShowAsksForTheSeason() {
            reachSeasonChoice();

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Quale stagione"), any());
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_SEASON_CHOICE);
            verifyNoInteractions(prowlarr);
        }

        @Test
        void singleSeasonShowStillGoesThroughTheSeasonStep() {
            // TMDB's season count can be wrong (e.g. multi-cour anime lumped into one
            // season), so a single reported season must not be auto-picked
            when(tmdb.searchTitles("chernobyl")).thenReturn(List.of(tvShow()));
            when(tmdb.seasons(1396)).thenReturn(List.of(new TmdbSeason(1, 5)));
            handler.onTextMessage(CHAT, "chernobyl");

            handler.onCallback(CHAT, MSG, "cb1", "movie:0");

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Quale stagione"), any());
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_SEASON_CHOICE);
            verifyNoInteractions(prowlarr);
        }

        @Test
        void manualSeasonButtonIsAlwaysOfferedAlongsideTheKnownSeasons() {
            reachSeasonChoice();

            ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Quale stagione"), keyboard.capture());
            boolean hasManualButton = keyboard.getValue().getKeyboard().stream()
                    .flatMap(row -> row.stream())
                    .anyMatch(b -> "season:manual".equals(b.getCallbackData()));
            assertThat(hasManualButton).isTrue();
        }

        @Test
        void manualSeasonOverridePromptsForATypedNumber() {
            reachSeasonChoice();

            handler.onCallback(CHAT, MSG, "cb2", "season:manual");

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("numero della stagione"), any());
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_MANUAL_SEASON);
        }

        @Test
        void typingAManualSeasonNumberNotKnownToTmdbOffersWholeSeasonOrManualEpisode() {
            reachSeasonChoice();
            handler.onCallback(CHAT, MSG, "cb2", "season:manual");

            // season 5 is not in TMDB's list (only 1 and 2 were returned)
            handler.onTextMessage(CHAT, "5");

            ConversationState state = store.find(CHAT).get();
            assertThat(state.season()).isEqualTo(5);
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_EPISODE_CHOICE);
            ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Stagione intera"), keyboard.capture());
            assertThat(keyboard.getValue().getKeyboard().stream().flatMap(row -> row.stream())
                    .map(InlineKeyboardButton::getCallbackData))
                    .as("no per-episode buttons since TMDB has no data for this season")
                    .containsExactlyInAnyOrder("episode:all", "episode:manual", "cancel");
        }

        @Test
        void invalidManualSeasonNumberIsRejectedWithoutAdvancing() {
            reachSeasonChoice();
            handler.onCallback(CHAT, MSG, "cb2", "season:manual");

            handler.onTextMessage(CHAT, "non è un numero");

            verify(messenger).sendHtml(eq(CHAT), contains("valido"), isNull());
            assertThat(store.find(CHAT).get().step())
                    .as("still waiting for a valid number")
                    .isEqualTo(ConversationStep.AWAITING_MANUAL_SEASON);
        }

        @Test
        void manualEpisodeOverridePromptsForATypedNumberAndSearchesForIt() {
            reachSeasonChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(tvReleases());
            handler.onCallback(CHAT, MSG, "cb2", "season:1");

            handler.onCallback(CHAT, MSG, "cb3", "episode:manual");
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_MANUAL_EPISODE);

            handler.onTextMessage(CHAT, "5");

            ConversationState state = store.find(CHAT).get();
            assertThat(state.episode()).isEqualTo(5);
            assertThat(state.filtered())
                    .as("only S01E05 matches")
                    .hasSize(1);
        }

        @Test
        void seasonChoiceOffersWholeSeasonOrOneButtonPerEpisode() {
            reachSeasonChoice();

            handler.onCallback(CHAT, MSG, "cb2", "season:1");

            ArgumentCaptor<InlineKeyboardMarkup> keyboard = ArgumentCaptor.forClass(InlineKeyboardMarkup.class);
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Stagione intera"), keyboard.capture());
            long buttons = keyboard.getValue().getKeyboard().stream()
                    .mapToLong(java.util.Collection::size)
                    .sum();
            assertThat(buttons)
                    .as("whole-season button + 7 episodes + cancel")
                    .isEqualTo(9);
            verifyNoInteractions(prowlarr);
        }

        @Test
        void wholeSeasonKeepsOnlyFullPacksOfThatSeason() {
            reachSeasonChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(tvReleases());
            handler.onCallback(CHAT, MSG, "cb2", "season:1");

            handler.onCallback(CHAT, MSG, "cb3", "episode:all");

            verify(prowlarr).search(eq("Breaking Bad"), eq(MediaType.TV), any());
            ConversationState state = store.find(CHAT).get();
            assertThat(state.filtered())
                    .as("the single S01E05 episode and the S02 pack are excluded")
                    .hasSize(1);
            assertThat(state.filtered().getFirst().release().title()).contains("S01.iTA.ENG");
        }

        @Test
        void singleEpisodeChoiceKeepsOnlyThatEpisode() {
            reachSeasonChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(tvReleases());
            handler.onCallback(CHAT, MSG, "cb2", "season:1");

            handler.onCallback(CHAT, MSG, "cb3", "episode:5");

            ConversationState state = store.find(CHAT).get();
            assertThat(state.filtered())
                    .as("the S01 and S02 packs are excluded, only S01E05 remains")
                    .hasSize(1);
            assertThat(state.filtered().getFirst().release().title()).contains("S01E05");
        }

        @Test
        void noSeasonPacksEndsTheConversationWithAClearMessage() {
            reachSeasonChoice();
            // only a single episode of the requested season exists, no pack
            when(prowlarr.search(anyString(), any(), any())).thenReturn(List.of(
                    release("Breaking.Bad.S01E05.iTA.1080p.WEB-DL-GRP", 70, "http://dl-ep")));
            handler.onCallback(CHAT, MSG, "cb2", "season:1");

            handler.onCallback(CHAT, MSG, "cb3", "episode:all");

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Nessun pack completo"), isNull());
            assertThat(store.find(CHAT)).isEmpty();
        }

        @Test
        void tvDownloadLandsInTheShowFolderAndTracksTheSeason() {
            reachSeasonChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(tvReleases());
            handler.onCallback(CHAT, MSG, "cb2", "season:1");
            handler.onCallback(CHAT, MSG, "cb3", "episode:all");
            // one pack remains: quality step skipped; audio has ITA+ENG buckets → pick any
            handler.onCallback(CHAT, MSG, "cb4", "audio:any");
            handler.onCallback(CHAT, MSG, "cb5", "release:0");

            verify(qbittorrent).addTorrent("http://dl-s01",
                    "/volume1/media/Serie TV/Breaking Bad (2008)", "film");
            verify(tracker).track(CHAT, "Breaking.Bad.S01.iTA.ENG.1080p.BDMux-GRP",
                    "Breaking Bad (2008) — Stagione 1", tvShow(), 1, null);
            assertThat(store.find(CHAT)).as("conversation is over").isEmpty();
        }

        @Test
        void episodeDownloadIsSavedIntoTheSeasonFolderAndTracksTheEpisode() {
            reachSeasonChoice();
            when(prowlarr.search(anyString(), any(), any())).thenReturn(tvReleases());
            handler.onCallback(CHAT, MSG, "cb2", "season:1");
            handler.onCallback(CHAT, MSG, "cb3", "episode:5");
            // single result: audio bucket ITA only → skipped; no subs; single quality → shortlist
            handler.onCallback(CHAT, MSG, "cb4", "release:0");

            verify(qbittorrent).addTorrent("http://dl-ep",
                    "/volume1/media/Serie TV/Breaking Bad (2008)/Season 01", "film");
            verify(tracker).track(CHAT, "Breaking.Bad.S01E05.iTA.1080p.WEB-DL-GRP",
                    "Breaking Bad (2008) — Stagione 1 — Episodio 5", tvShow(), 1, 5);
        }
    }

    @Nested
    class Robustness {

        @Test
        void callbackWithoutStateSignalsExpiredSession() {
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            verify(messenger).answerCallback("cb1");
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Sessione scaduta"), isNull());
            verifyNoInteractions(prowlarr);
        }

        @Test
        void staleCallbackForAnotherStepIsIgnored() {
            reachMovieChoice();
            handler.onCallback(CHAT, MSG, "cb1", "release:0");
            verify(messenger, never()).editHtml(anyLong(), anyInt(), anyString(), any());
            verifyNoInteractions(qbittorrent);
        }

        @Test
        void cancelClearsStateAndConfirms() {
            reachMovieChoice();
            handler.onCallback(CHAT, MSG, "cb1", "cancel");
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("annullata"), isNull());
            assertThat(store.find(CHAT)).isEmpty();
        }

        @Test
        void cancelIsAnAliasForAnnulla() {
            reachMovieChoice();
            handler.onTextMessage(CHAT, "/cancel");
            assertThat(store.find(CHAT)).isEmpty();
        }

        @Test
        void annullaCommandClearsState() {
            reachMovieChoice();
            handler.onTextMessage(CHAT, "/annulla");
            assertThat(store.find(CHAT)).isEmpty();
        }

        @Test
        void helpListsCommands() {
            handler.onTextMessage(CHAT, "/help");
            verify(messenger).sendHtml(eq(CHAT), contains("/cerca"), isNull());
        }

        @Test
        void outOfRangeIndexIsIgnored() {
            reachMovieChoice();
            handler.onCallback(CHAT, MSG, "cb1", "movie:99");
            verifyNoInteractions(prowlarr);
        }
    }
}
