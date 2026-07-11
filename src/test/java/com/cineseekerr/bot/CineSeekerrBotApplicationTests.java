package com.cineseekerr.bot;

import com.cineseekerr.bot.client.ProwlarrClient;
import com.cineseekerr.bot.client.QbittorrentClient;
import com.cineseekerr.bot.client.TmdbClient;
import com.cineseekerr.bot.config.CineSeekerrProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

// bot-token is left empty on purpose: TelegramBotLifecycle then skips polling, so the
// context can start without reaching api.telegram.org.
@SpringBootTest(properties = {
        "cineseekerr.telegram.bot-token=",
        "cineseekerr.telegram.allowed-chat-ids=123456789,987654321",
        "cineseekerr.tmdb.api-key=dummy",
        "cineseekerr.prowlarr.base-url=http://localhost:9696",
        "cineseekerr.prowlarr.api-key=dummy",
        "cineseekerr.qbittorrent.base-url=http://localhost:8080",
        "cineseekerr.qbittorrent.username=admin",
        "cineseekerr.qbittorrent.password=dummy",
})
class CineSeekerrBotApplicationTests {

    @Autowired
    private CineSeekerrProperties properties;

    @Autowired
    private TmdbClient tmdbClient;

    @Autowired
    private ProwlarrClient prowlarrClient;

    @Autowired
    private QbittorrentClient qbittorrentClient;

    @Test
    void contextLoadsAndBindsProperties() {
        assertThat(properties.telegram().allowedChatIds())
                .containsExactlyInAnyOrder(123456789L, 987654321L);
        assertThat(properties.tmdb().language()).isEqualTo("it-IT");
        assertThat(properties.media().rootFolder()).isEqualTo("/volume1/media/Film");
        assertThat(tmdbClient).isNotNull();
        assertThat(prowlarrClient).isNotNull();
        assertThat(qbittorrentClient).isNotNull();
    }
}
