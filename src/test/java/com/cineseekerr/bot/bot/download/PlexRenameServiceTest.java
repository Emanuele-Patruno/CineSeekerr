package com.cineseekerr.bot.bot.download;

import com.cineseekerr.bot.client.ApiClientException;
import com.cineseekerr.bot.client.QbittorrentClient;
import com.cineseekerr.bot.model.QbtTorrentFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlexRenameServiceTest {

    @Mock
    private QbittorrentClient qbittorrent;

    @InjectMocks
    private PlexRenameService service;

    private static QbtTorrentFile file(String name) {
        return new QbtTorrentFile(name, 1000L);
    }

    @Test
    void torrentWithRootFolderGetsItsFolderRenamed() {
        when(qbittorrent.listFiles("h")).thenReturn(List.of(
                file("Dune.Part.Two.2024.1080p/movie.mkv"),
                file("Dune.Part.Two.2024.1080p/subs/ita.srt")));

        boolean renamed = service.renameForPlex("h", "Dune Parte Due (2024)");

        assertThat(renamed).isTrue();
        verify(qbittorrent).renameFolder("h", "Dune.Part.Two.2024.1080p", "Dune Parte Due (2024)");
    }

    @Test
    void singleFileTorrentGetsRenamedKeepingItsExtension() {
        when(qbittorrent.listFiles("h")).thenReturn(List.of(file("Dune.Part.Two.2024.1080p.mkv")));

        boolean renamed = service.renameForPlex("h", "Dune Parte Due (2024)");

        assertThat(renamed).isTrue();
        verify(qbittorrent).renameFile("h", "Dune.Part.Two.2024.1080p.mkv", "Dune Parte Due (2024).mkv");
    }

    @Test
    void alreadyCorrectFolderNameIsLeftAlone() {
        when(qbittorrent.listFiles("h")).thenReturn(List.of(file("Dune Parte Due (2024)/movie.mkv")));

        boolean renamed = service.renameForPlex("h", "Dune Parte Due (2024)");

        assertThat(renamed).isTrue();
        verify(qbittorrent, never()).renameFolder(anyString(), anyString(), anyString());
    }

    @Test
    void looseMultiFileTorrentIsNotTouched() {
        when(qbittorrent.listFiles("h")).thenReturn(List.of(file("a.mkv"), file("b.mkv")));

        assertThat(service.renameForPlex("h", "X (2024)")).isFalse();
        verify(qbittorrent, never()).renameFile(anyString(), anyString(), anyString());
    }

    @Test
    void apiFailureYieldsFalseInsteadOfThrowing() {
        when(qbittorrent.listFiles("h")).thenThrow(new ApiClientException("down"));

        assertThat(service.renameForPlex("h", "X (2024)")).isFalse();
    }

    @Test
    void plexNameStripsIllegalCharactersAndAppendsYear() {
        assertThat(PlexRenameService.plexName("Dune: Part Two", 2024)).isEqualTo("Dune Part Two (2024)");
        assertThat(PlexRenameService.plexName("What If...?", null)).isEqualTo("What If...");
        assertThat(PlexRenameService.plexName("A/B <Test>", 1999)).isEqualTo("AB Test (1999)");
    }
}
