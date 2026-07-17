package com.cineseekerr.bot.bot.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadTrackerTest {

    private static final long CHAT = 42L;
    private static final String RELEASE = "Dune.Part.Two.2024.iTA.ENG.1080p.BluRay.x264-GRP";
    private static final String PLEX_NAME = "Dune Parte Due (2024)";

    @TempDir
    private Path tempDir;

    private DownloadTracker newTracker(Path stateFile) {
        return new DownloadTracker(new ObjectMapper().findAndRegisterModules(), stateFile.toString());
    }

    @Test
    void survivesARestartByReloadingTheStateFile() {
        Path stateFile = tempDir.resolve("pending-downloads.json");
        DownloadTracker beforeRestart = newTracker(stateFile);
        beforeRestart.track(CHAT, RELEASE, PLEX_NAME);

        DownloadTracker afterRestart = newTracker(stateFile);

        Map<String, DownloadTracker.PendingDownload> restored = afterRestart.snapshot();
        assertThat(restored).hasSize(1);
        DownloadTracker.PendingDownload download = restored.values().iterator().next();
        assertThat(download.chatId()).isEqualTo(CHAT);
        assertThat(download.releaseTitle()).isEqualTo(RELEASE);
        assertThat(download.plexName()).isEqualTo(PLEX_NAME);
    }

    @Test
    void removedDownloadsAreGoneAfterARestart() {
        Path stateFile = tempDir.resolve("pending-downloads.json");
        DownloadTracker beforeRestart = newTracker(stateFile);
        beforeRestart.track(CHAT, RELEASE, PLEX_NAME);
        beforeRestart.remove(DownloadTracker.normalize(RELEASE));

        DownloadTracker afterRestart = newTracker(stateFile);

        assertThat(afterRestart.isEmpty()).isTrue();
    }

    @Test
    void missingStateFileStartsEmptyWithoutError() {
        DownloadTracker tracker = newTracker(tempDir.resolve("does-not-exist.json"));

        assertThat(tracker.isEmpty()).isTrue();
    }

    @Test
    void corruptStateFileIsIgnoredWithoutCrashing() throws Exception {
        Path stateFile = tempDir.resolve("pending-downloads.json");
        Files.writeString(stateFile, "{ this is not valid json");

        DownloadTracker tracker = newTracker(stateFile);

        assertThat(tracker.isEmpty()).isTrue();
    }

    @Test
    void stateFileParentDirectoryIsCreatedOnFirstWrite() {
        Path stateFile = tempDir.resolve("nested/dir/pending-downloads.json");
        DownloadTracker tracker = newTracker(stateFile);

        tracker.track(CHAT, RELEASE, PLEX_NAME, Instant.now());

        assertThat(Files.exists(stateFile)).isTrue();
    }
}
