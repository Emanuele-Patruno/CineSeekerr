package com.cineseekerr.bot.bot.download;

import com.cineseekerr.bot.bot.Messages;
import com.cineseekerr.bot.bot.telegram.TelegramMessenger;
import com.cineseekerr.bot.client.ApiClientException;
import com.cineseekerr.bot.config.CineSeekerrProperties;
import com.cineseekerr.bot.client.QbittorrentClient;
import com.cineseekerr.bot.model.MediaType;
import com.cineseekerr.bot.model.QbtTorrent;
import com.cineseekerr.bot.model.TmdbTitle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadWatcherTest {

    private static final long CHAT = 42L;
    private static final String RELEASE = "Dune.Part.Two.2024.iTA.ENG.1080p.BluRay.x264-GRP";
    private static final String PLEX_NAME = "Dune Parte Due (2024)";

    @Mock
    private QbittorrentClient qbittorrent;
    @Mock
    private PlexRenameService renameService;
    @Mock
    private TelegramMessenger messenger;

    @TempDir
    private Path tempDir;

    /** Italian bundle: assertions check the actual notification texts. */
    private final Messages messages = new Messages(
            new CineSeekerrProperties(null, null, null, null, null, "it"));

    private DownloadTracker tracker;
    private DownloadWatcher watcher;

    @BeforeEach
    void setUp() {
        tracker = new DownloadTracker(new ObjectMapper().findAndRegisterModules(),
                tempDir.resolve("pending-downloads.json").toString());
        watcher = new DownloadWatcher(tracker, qbittorrent, renameService, messenger, messages);
    }

    private static QbtTorrent torrent(String name, double progress) {
        return torrent(name, progress, 5);
    }

    private static QbtTorrent torrent(String name, double progress, Integer numSeeds) {
        return new QbtTorrent("hash1", name, progress >= 1.0 ? "uploading" : "downloading",
                progress, "/volume1/media/Film", "/volume1/media/Film/" + name, 0L, 0L, numSeeds);
    }

    @Test
    void emptyTrackerNeverCallsQbittorrent() {
        watcher.poll();
        verifyNoInteractions(qbittorrent);
    }

    @Test
    void completedDownloadIsRenamedNotifiedAndForgotten() {
        tracker.track(CHAT, RELEASE, PLEX_NAME);
        // qBittorrent reports the name with spaces instead of dots — must still match
        when(qbittorrent.listTorrents("film"))
                .thenReturn(List.of(torrent("Dune Part Two 2024 iTA ENG 1080p BluRay x264-GRP", 1.0)));
        when(renameService.renameForPlex("hash1", PLEX_NAME)).thenReturn(true);

        watcher.poll();

        verify(renameService).renameForPlex("hash1", PLEX_NAME);
        verify(messenger).sendHtml(eq(CHAT), contains("pronto"), isNull());
        assertThat(tracker.isEmpty()).isTrue();
    }

    @Test
    void failedRenameStillNotifiesWithAWarning() {
        tracker.track(CHAT, RELEASE, PLEX_NAME);
        when(qbittorrent.listTorrents("film")).thenReturn(List.of(torrent(RELEASE, 1.0)));
        when(renameService.renameForPlex(anyString(), anyString())).thenReturn(false);

        watcher.poll();

        verify(messenger).sendHtml(eq(CHAT), contains("non sono riuscito"), isNull());
        assertThat(tracker.isEmpty()).isTrue();
    }

    @Test
    void completedTvSeasonIsRenamedToItsSeasonFolder() {
        TmdbTitle show = new TmdbTitle(1396, "tv", "Breaking Bad", "Breaking Bad",
                "2008-01-20", null, null);
        String pack = "Breaking.Bad.S01.iTA.ENG.1080p.BDMux-GRP";
        tracker.track(CHAT, pack, "Breaking Bad (2008) — Stagione 1", show, 1, null);
        when(qbittorrent.listTorrents("film")).thenReturn(List.of(torrent(pack, 1.0)));
        when(renameService.renameSeasonForPlex("hash1", 1)).thenReturn(true);

        watcher.poll();

        verify(renameService).renameSeasonForPlex("hash1", 1);
        verify(renameService, never()).renameForPlex(anyString(), anyString());
        verify(messenger).sendHtml(eq(CHAT), contains("pronto"), isNull());
        assertThat(tracker.isEmpty()).isTrue();
    }

    @Test
    void completedSingleEpisodeNeedsNoRenameAndNotifies() {
        TmdbTitle show = new TmdbTitle(1396, "tv", "Breaking Bad", "Breaking Bad",
                "2008-01-20", null, null);
        String episode = "Breaking.Bad.S01E05.iTA.1080p.WEB-DL-GRP";
        // saved straight into ".../Show (Year)/Season 01", the scene name is Plex-friendly
        tracker.track(CHAT, episode, "Breaking Bad (2008) — Stagione 1 — Episodio 5", show, 1, 5);
        when(qbittorrent.listTorrents("film")).thenReturn(List.of(torrent(episode, 1.0)));

        watcher.poll();

        verifyNoInteractions(renameService);
        verify(messenger).sendHtml(eq(CHAT), contains("pronto"), isNull());
        assertThat(tracker.isEmpty()).isTrue();
    }

    @Test
    void incompleteDownloadStaysTracked() {
        tracker.track(CHAT, RELEASE, PLEX_NAME);
        when(qbittorrent.listTorrents("film")).thenReturn(List.of(torrent(RELEASE, 0.5)));

        watcher.poll();

        verifyNoInteractions(renameService);
        verify(messenger, never()).sendHtml(anyLong(), anyString(), any());
        assertThat(tracker.isEmpty()).isFalse();
    }

    @Test
    void downloadNeverSeenInQbittorrentExpiresWithAWarning() {
        Instant longAgo = Instant.now().minus(DownloadWatcher.UNMATCHED_TIMEOUT.plusMinutes(1));
        tracker.track(CHAT, RELEASE, PLEX_NAME, longAgo);
        when(qbittorrent.listTorrents("film")).thenReturn(List.of());

        watcher.poll();

        verify(messenger).sendHtml(eq(CHAT), contains("Non trovo"), isNull());
        assertThat(tracker.isEmpty()).isTrue();
    }

    @Test
    void recentlyQueuedDownloadIsGivenTimeToAppear() {
        tracker.track(CHAT, RELEASE, PLEX_NAME);
        when(qbittorrent.listTorrents("film")).thenReturn(List.of());

        watcher.poll();

        verify(messenger, never()).sendHtml(anyLong(), anyString(), any());
        assertThat(tracker.isEmpty()).isFalse();
    }

    @Test
    void qbittorrentOutageIsToleratedAndRetriedNextCycle() {
        tracker.track(CHAT, RELEASE, PLEX_NAME);
        when(qbittorrent.listTorrents("film")).thenThrow(new ApiClientException("down"));

        watcher.poll();

        assertThat(tracker.isEmpty()).isFalse();
        verifyNoInteractions(messenger);
    }

    /** A {@link Clock} whose instant can be advanced, so stall-timeout tests don't sleep. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    void zeroSeedersBelowTheThresholdIsNotFlaggedYet() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        DownloadWatcher stallWatcher = new DownloadWatcher(tracker, qbittorrent, renameService, messenger, messages, clock);
        tracker.track(CHAT, RELEASE, PLEX_NAME);
        when(qbittorrent.listTorrents("film")).thenReturn(List.of(torrent(RELEASE, 0.5, 0)));

        stallWatcher.poll();
        clock.advance(DownloadWatcher.STALL_TIMEOUT.minusMinutes(1));
        stallWatcher.poll();

        verify(messenger, never()).sendHtml(anyLong(), anyString(), any());
        assertThat(tracker.isEmpty()).isFalse();
    }

    @Test
    void zeroSeedersPastTheThresholdIsFlaggedOnceWithARetryButton() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        DownloadWatcher stallWatcher = new DownloadWatcher(tracker, qbittorrent, renameService, messenger, messages, clock);
        tracker.track(CHAT, RELEASE, PLEX_NAME);
        when(qbittorrent.listTorrents("film")).thenReturn(List.of(torrent(RELEASE, 0.5, 0)));

        stallWatcher.poll();
        clock.advance(DownloadWatcher.STALL_TIMEOUT.plusMinutes(1));
        stallWatcher.poll();
        stallWatcher.poll();

        verify(messenger).sendHtml(eq(CHAT), contains("ferma da"),
                org.mockito.ArgumentMatchers.argThat(keyboard -> keyboard != null
                        && keyboard.getKeyboard().get(0).get(0).getCallbackData().equals("retrystalled:hash1")));
        // the download itself is untouched until the user taps the button
        assertThat(tracker.isEmpty()).isFalse();
    }

    @Test
    void recoveringSeedersResetsTheStallClock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        DownloadWatcher stallWatcher = new DownloadWatcher(tracker, qbittorrent, renameService, messenger, messages, clock);
        tracker.track(CHAT, RELEASE, PLEX_NAME);
        when(qbittorrent.listTorrents("film"))
                .thenReturn(List.of(torrent(RELEASE, 0.5, 0)))
                .thenReturn(List.of(torrent(RELEASE, 0.6, 3)))
                .thenReturn(List.of(torrent(RELEASE, 0.7, 0)));

        stallWatcher.poll();
        clock.advance(DownloadWatcher.STALL_TIMEOUT.minusMinutes(1));
        stallWatcher.poll();
        clock.advance(DownloadWatcher.STALL_TIMEOUT.minusMinutes(1));
        stallWatcher.poll();

        // 2 * (timeout - 1min) elapsed, but seeders recovered in between, so the clock
        // restarted after the second poll instead of accumulating
        verify(messenger, never()).sendHtml(anyLong(), anyString(), any());
    }
}
