package com.cineseekerr.bot.bot.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PreferredAuthorsTest {

    @TempDir
    Path dir;

    private PreferredAuthors create() {
        return new PreferredAuthors(new ObjectMapper(), dir.resolve("pending-downloads.json").toString());
    }

    @Test
    void remembersTheAuthorPerTitle() {
        PreferredAuthors authors = create();

        authors.remember(1, "GroupA");
        authors.remember(2, "GroupB");

        assertThat(authors.find(1)).contains("GroupA");
        assertThat(authors.find(2)).contains("GroupB");
        assertThat(authors.find(3)).isEmpty();
    }

    @Test
    void theLatestAuthorReplacesTheOldOne() {
        PreferredAuthors authors = create();

        authors.remember(1, "GroupA");
        authors.remember(1, "GroupB");

        assertThat(authors.find(1)).contains("GroupB");
    }

    @Test
    void blankAuthorsAreIgnored() {
        PreferredAuthors authors = create();
        authors.remember(1, "GroupA");

        authors.remember(1, "   ");
        authors.remember(1, null);

        assertThat(authors.find(1)).contains("GroupA");
    }

    @Test
    void survivesARestartThroughTheSiblingFile() {
        create().remember(1, "GroupA");

        assertThat(dir.resolve("preferred-authors.json")).exists();
        assertThat(create().find(1)).contains("GroupA");
    }

    @Test
    void aCorruptFileStartsEmptyInsteadOfFailing() throws Exception {
        Files.writeString(dir.resolve("preferred-authors.json"), "not json");

        assertThat(create().find(1)).isEmpty();
    }
}
