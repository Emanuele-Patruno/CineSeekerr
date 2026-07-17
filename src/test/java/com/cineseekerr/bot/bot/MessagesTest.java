package com.cineseekerr.bot.bot;

import com.cineseekerr.bot.config.CineSeekerrProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessagesTest {

    private Messages messages(String language) {
        return new Messages(new CineSeekerrProperties(null, null, null, null, null, language));
    }

    @Test
    void englishIsTheDefaultLanguage() {
        assertThat(messages(null).get("status.none")).isEqualTo("No downloads in progress.");
    }

    @Test
    void italianBundleIsUsedWhenConfigured() {
        assertThat(messages("it").get("status.none")).isEqualTo("Nessun download in corso.");
    }

    @Test
    void unsupportedLanguagesFallBackToEnglish() {
        assertThat(messages("fr").get("status.none")).isEqualTo("No downloads in progress.");
    }

    @Test
    void placeholdersAreFilledAndEscapedApostrophesUnescaped() {
        // "un''altra" in the bundle must come out as "un'altra" after formatting
        String text = messages("it").get("stalled.notice", "Dune (2024)", "2h 5m");
        assertThat(text).contains("Dune (2024)").contains("2h 5m").contains("un'altra release");
    }

    @Test
    void pluralizedEnglishReleaseCount() {
        assertThat(messages(null).get("step.header.releases", 1, "no filters"))
                .isEqualTo("1 release (no filters)");
        assertThat(messages(null).get("step.header.releases", 14, "ITA"))
                .isEqualTo("14 releases (ITA)");
    }
}
