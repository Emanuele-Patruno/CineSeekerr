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
import com.cineseekerr.bot.model.ProwlarrRelease;
import com.cineseekerr.bot.model.QbtTorrent;
import com.cineseekerr.bot.model.TmdbMovie;
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

import java.util.List;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        CineSeekerrProperties properties = new CineSeekerrProperties(
                null, null, null, null,
                new CineSeekerrProperties.Media("/volume1/media/Film"));
        handler = new ConversationHandler(tmdb, prowlarr, qbittorrent, new ReleaseNameParser(),
                messenger, store, tracker, properties);
        when(messenger.sendHtml(anyLong(), anyString(), any())).thenReturn(MSG);
    }

    private static TmdbMovie movie() {
        return new TmdbMovie(872585, "Dune: Parte Due", "Dune: Part Two", "2024-02-28",
                "/poster.jpg", "Paul Atreides si unisce ai Fremen.");
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
        when(tmdb.searchMovies("dune")).thenReturn(List.of(movie()));
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
            when(tmdb.searchMovies("dune")).thenReturn(List.of(movie()));
            handler.onTextMessage(CHAT, "/cerca dune");
            verify(messenger).sendHtml(eq(CHAT), contains("Quale film"), any());
        }

        @Test
        void noTmdbResultsInformTheUserAndKeepNoState() {
            when(tmdb.searchMovies("xyzabc")).thenReturn(List.of());
            handler.onTextMessage(CHAT, "xyzabc");
            verify(messenger).sendHtml(eq(CHAT), contains("Nessun film trovato"), isNull());
            assertThat(store.find(CHAT)).isEmpty();
        }

        @Test
        void tmdbFailureIsReportedGracefully() {
            when(tmdb.searchMovies(anyString())).thenThrow(new ApiClientException("TMDB search failed"));
            handler.onTextMessage(CHAT, "dune");
            verify(messenger).sendHtml(eq(CHAT), contains("⚠️"), isNull());
        }
    }

    @Nested
    class FilterFlow {

        @Test
        void movieSelectionSearchesProwlarrWithOriginalTitleAndYear() {
            reachMovieChoice();
            when(prowlarr.search("Dune: Part Two 2024")).thenReturn(releases());

            handler.onCallback(CHAT, MSG, "cb1", "movie:0");

            verify(messenger).answerCallback("cb1");
            verify(prowlarr).search("Dune: Part Two 2024");
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Scegli la qualità"), any());
            assertThat(store.find(CHAT).get().step()).isEqualTo(ConversationStep.AWAITING_QUALITY);
        }

        @Test
        void noReleasesEndsTheConversation() {
            reachMovieChoice();
            when(prowlarr.search(anyString())).thenReturn(List.of());

            handler.onCallback(CHAT, MSG, "cb1", "movie:0");

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Nessuna release"), isNull());
            assertThat(store.find(CHAT)).isEmpty();
        }

        @Test
        void qualityChoiceNarrowsResultsAndShowsAudioStep() {
            reachMovieChoice();
            when(prowlarr.search(anyString())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");

            handler.onCallback(CHAT, MSG, "cb2", "quality:R1080P");

            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Scegli l'audio"), any());
            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_AUDIO);
            assertThat(state.filtered()).hasSize(2);
        }

        @Test
        void audioItalianKeepsExplicitItaAndSkipsSubtitleStepWhenNoVariety() {
            reachMovieChoice();
            when(prowlarr.search(anyString())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "quality:R1080P");

            handler.onCallback(CHAT, MSG, "cb3", "audio:ITA");

            // only the explicit iTA release remains; it has no subs → subtitle step skipped
            ConversationState state = store.find(CHAT).get();
            assertThat(state.step()).isEqualTo(ConversationStep.AWAITING_RELEASE_CHOICE);
            assertThat(state.shortlist()).hasSize(1);
            verify(messenger).editHtml(eq(CHAT), eq(MSG), contains("Migliori release"), any());
        }

        @Test
        void shortlistIsSortedBySeeders() {
            reachMovieChoice();
            when(prowlarr.search(anyString())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "quality:any");
            handler.onCallback(CHAT, MSG, "cb3", "audio:any");
            handler.onCallback(CHAT, MSG, "cb4", "subs:any");

            ConversationState state = store.find(CHAT).get();
            assertThat(state.shortlist()).extracting(r -> r.release().seeders())
                    .containsExactly(100, 80, 50);
        }
    }

    @Nested
    class Download {

        private void reachShortlist() {
            reachMovieChoice();
            when(prowlarr.search(anyString())).thenReturn(releases());
            handler.onCallback(CHAT, MSG, "cb1", "movie:0");
            handler.onCallback(CHAT, MSG, "cb2", "quality:R1080P");
            handler.onCallback(CHAT, MSG, "cb3", "audio:ITA");
        }

        @Test
        void releaseSelectionSendsTorrentToQbittorrentAndTracksIt() {
            reachShortlist();

            handler.onCallback(CHAT, MSG, "cb4", "release:0");

            verify(qbittorrent).addTorrent("http://dl-ita", "/volume1/media/Film", "film");
            verify(tracker).track(CHAT, "Dune.Part.Two.2024.iTA.ENG.1080p.BluRay.x264-GRP",
                    "Dune Parte Due (2024)");
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
            verify(tracker, never()).track(anyLong(), anyString(), anyString());
        }
    }

    @Nested
    class DownloadStatus {

        private static QbtTorrent torrent(String name, double progress) {
            return new QbtTorrent(name.hashCode() + "", name, progress >= 1.0 ? "uploading" : "downloading",
                    progress, "/downloads", "/downloads/" + name, 0L, 0L);
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
