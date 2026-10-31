package com.cineseekerr.bot.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseGroupsTest {

    @Test
    void readsTheBracketedGroupAtTheStartOfTheTitle() {
        assertThat(ReleaseGroups.leadingGroup("[ExampleGroup] Some Show - 01 [1080p][HEVC].mkv"))
                .contains("ExampleGroup");
    }

    @Test
    void keepsGroupNamesWithSeparatorsIntact() {
        assertThat(ReleaseGroups.leadingGroup("[Example-Group] Some Show - 01 [1080p]")).contains("Example-Group");
        assertThat(ReleaseGroups.leadingGroup("[Example Group] Some Show - 01 [1080p]")).contains("Example Group");
    }

    @Test
    void toleratesLeadingWhitespace() {
        assertThat(ReleaseGroups.leadingGroup("  [ExampleGroup] Some Show")).contains("ExampleGroup");
    }

    @Test
    void ignoresBracketsThatAreNotAtTheStart() {
        assertThat(ReleaseGroups.leadingGroup("Some Show - 01 [ExampleGroup]")).isEmpty();
    }

    @Test
    void sceneStyleTitlesHaveNoRecognisedGroup() {
        assertThat(ReleaseGroups.leadingGroup("Some.Movie.2024.1080p.BluRay.x264-ExampleGroup")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1080p] Some Show - 01",
            "[2160p] Some Show - 01",
            "[1920x1080] Some Show - 01",
            "[4K] Some Show - 01",
            "[2024] Some Show - 01",
            "[WEB-DL] Some Show - 01",
            "[BD] Some Show - 01",
            "[HEVC] Some Show - 01",
            "[A1B2C3D4] Some Show - 01",
            "[x] Some Show - 01"})
    void technicalTagsAndNoiseAreNotGroups(String title) {
        assertThat(ReleaseGroups.leadingGroup(title)).isEmpty();
    }

    @Test
    void nullAndBlankTitlesHaveNoGroup() {
        assertThat(ReleaseGroups.leadingGroup(null)).isEmpty();
        assertThat(ReleaseGroups.leadingGroup("")).isEmpty();
    }
}
