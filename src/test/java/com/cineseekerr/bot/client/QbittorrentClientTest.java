package com.cineseekerr.bot.client;

import com.cineseekerr.bot.config.CineSeekerrProperties;
import com.cineseekerr.bot.model.QbtTorrent;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class QbittorrentClientTest {

    private static final String BASE = "http://qbittorrent:8080";
    private static final String TORRENTS_JSON = """
            [
              {
                "hash": "abc123",
                "name": "Dune.Part.Two.2024.iTA.ENG.2160p.WEB-DL.HEVC-FLUX",
                "state": "uploading",
                "progress": 1.0,
                "save_path": "/volume1/media/Film",
                "content_path": "/volume1/media/Film/Dune.Part.Two.2024.iTA.ENG.2160p.WEB-DL.HEVC-FLUX",
                "eta": 8640000,
                "dlspeed": 0
              },
              {
                "hash": "def456",
                "name": "Still.Downloading.2023.1080p",
                "state": "downloading",
                "progress": 0.42,
                "save_path": "/volume1/media/Film",
                "content_path": "/volume1/media/Film/Still.Downloading.2023.1080p",
                "eta": 1200,
                "dlspeed": 5242880
              }
            ]
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final QbittorrentClient client = new QbittorrentClient(builder, properties());

    private CineSeekerrProperties properties() {
        return new CineSeekerrProperties(null, null, null,
                new CineSeekerrProperties.Qbittorrent(BASE, "admin", "secret"), null, null);
    }

    private DefaultResponseCreator loginOk(String sid) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.SET_COOKIE, "SID=" + sid + "; HttpOnly; path=/");
        return withSuccess("Ok.", MediaType.TEXT_PLAIN).headers(headers);
    }

    private void expectLogin(DefaultResponseCreator response) {
        MultiValueMap<String, String> credentials = new LinkedMultiValueMap<>();
        credentials.add("username", "admin");
        credentials.add("password", "secret");
        server.expect(requestTo(BASE + "/api/v2/auth/login"))
                .andExpect(method(POST))
                .andExpect(content().formData(credentials))
                .andRespond(response);
    }

    @Test
    void addTorrentLogsInThenSendsFormWithSessionCookie() {
        expectLogin(loginOk("session-1"));

        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("urls", "magnet:?xt=urn:btih:abc");
        expectedForm.add("savepath", "/volume1/media/Film");
        expectedForm.add("category", "film");
        server.expect(requestTo(BASE + "/api/v2/torrents/add"))
                .andExpect(method(POST))
                .andExpect(header(HttpHeaders.COOKIE, "SID=session-1"))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess("Ok.", MediaType.TEXT_PLAIN));

        client.addTorrent("magnet:?xt=urn:btih:abc", "/volume1/media/Film", "film");
        server.verify();
    }

    @Test
    void sessionCookieIsReusedAcrossCalls() {
        expectLogin(loginOk("session-1"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v2/torrents/info")))
                .andExpect(header(HttpHeaders.COOKIE, "SID=session-1"))
                .andRespond(withSuccess(TORRENTS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v2/torrents/info")))
                .andExpect(header(HttpHeaders.COOKIE, "SID=session-1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.listTorrents("film");
        client.listTorrents("film");

        server.verify(); // exactly one login for two calls
    }

    @Test
    void expiredSessionTriggersOneReloginAndRetry() {
        expectLogin(loginOk("stale"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v2/torrents/info")))
                .andExpect(header(HttpHeaders.COOKIE, "SID=stale"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        expectLogin(loginOk("fresh"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v2/torrents/info")))
                .andExpect(header(HttpHeaders.COOKIE, "SID=fresh"))
                .andRespond(withSuccess(TORRENTS_JSON, MediaType.APPLICATION_JSON));

        List<QbtTorrent> torrents = client.listTorrents("film");

        assertThat(torrents).hasSize(2);
        QbtTorrent done = torrents.getFirst();
        assertThat(done.isComplete()).isTrue();
        assertThat(done.contentPath()).startsWith("/volume1/media/Film/Dune");
        assertThat(torrents.get(1).isComplete()).isFalse();
        server.verify();
    }

    @Test
    void listFilesMapsRelativePaths() {
        expectLogin(loginOk("session-1"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v2/torrents/files")))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        [
                          {"name": "Dune.2024/movie.mkv", "size": 15000000000},
                          {"name": "Dune.2024/ita.srt", "size": 90000}
                        ]
                        """, MediaType.APPLICATION_JSON));

        var files = client.listFiles("abc123");

        assertThat(files).hasSize(2);
        assertThat(files.getFirst().name()).isEqualTo("Dune.2024/movie.mkv");
    }

    @Test
    void renameFolderSendsHashAndPaths() {
        expectLogin(loginOk("session-1"));
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("hash", "abc123");
        expectedForm.add("oldPath", "Dune.2024");
        expectedForm.add("newPath", "Dune (2024)");
        server.expect(requestTo(BASE + "/api/v2/torrents/renameFolder"))
                .andExpect(method(POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess());

        client.renameFolder("abc123", "Dune.2024", "Dune (2024)");
        server.verify();
    }

    @Test
    void renameFileSendsHashAndPaths() {
        expectLogin(loginOk("session-1"));
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("hash", "abc123");
        expectedForm.add("oldPath", "movie.mkv");
        expectedForm.add("newPath", "Dune (2024).mkv");
        server.expect(requestTo(BASE + "/api/v2/torrents/renameFile"))
                .andExpect(method(POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess());

        client.renameFile("abc123", "movie.mkv", "Dune (2024).mkv");
        server.verify();
    }

    @Test
    void deleteTorrentSendsHashAndDeleteFilesFlag() {
        expectLogin(loginOk("session-1"));
        MultiValueMap<String, String> expectedForm = new LinkedMultiValueMap<>();
        expectedForm.add("hashes", "abc123");
        expectedForm.add("deleteFiles", "true");
        server.expect(requestTo(BASE + "/api/v2/torrents/delete"))
                .andExpect(method(POST))
                .andExpect(content().formData(expectedForm))
                .andRespond(withSuccess());

        client.deleteTorrent("abc123", true);
        server.verify();
    }

    @Test
    void wrongCredentialsRaiseApiClientException() {
        server.expect(requestTo(BASE + "/api/v2/auth/login"))
                .andRespond(withSuccess("Fails.", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> client.listTorrents("film"))
                .isInstanceOf(ApiClientException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void addTorrentSucceedsRegardlessOfResponseBodyFormat() {
        // Different qBittorrent versions return "Ok." as plain text or a JSON status
        // object; only a non-2xx status should be treated as a failure.
        expectLogin(loginOk("session-1"));
        server.expect(requestTo(BASE + "/api/v2/torrents/add"))
                .andRespond(withSuccess(
                        "{\"added_torrent_ids\":[],\"failure_count\":0,\"pending_count\":1,\"success_count\":0}",
                        MediaType.APPLICATION_JSON));

        client.addTorrent("magnet:?xt=x", "/volume1/media/Film", "film");
        server.verify();
    }

    @Test
    void unreachableServerRaisesApiClientException() {
        expectLogin(loginOk("session-1"));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/api/v2/torrents/info")))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.listTorrents("film"))
                .isInstanceOf(ApiClientException.class);
    }
}
