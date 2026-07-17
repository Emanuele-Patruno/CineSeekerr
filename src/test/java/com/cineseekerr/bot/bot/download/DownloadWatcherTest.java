package com.cineseekerr.bot.bot.download;

import com.cineseekerr.bot.bot.telegram.TelegramMessenger;
import com.cineseekerr.bot.client.ApiClientException;
import com.cineseekerr.bot.client.QbittorrentClient;
import com.cineseekerr.bot.model.QbtTorrent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
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

    private DownloadTracker tracker;
    private DownloadWatcher watcher;

    @BeforeEach
    void setUp() {
        tracker = new DownloadTracker(new ObjectMapper().findAndRegisterModules(),
                tempDir.resolve("pending-downloads.json").toString());
        watcher = new DownloadWatcher(tracker, qbittorrent, renameService, messenger);
    }

    private static QbtTorrent torrent(String name, double progress) {
        return new QbtTorrent("hash1", name, progress >= 1.0 ? "uploading" : "downloading",
                progress, "/volume1/media/Film", "/volume1/media/Film/" + name, 0L, 0L);
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
}
