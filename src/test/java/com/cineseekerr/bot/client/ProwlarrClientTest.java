package com.cineseekerr.bot.client;

import com.cineseekerr.bot.config.CineSeekerrProperties;
import com.cineseekerr.bot.model.ProwlarrRelease;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProwlarrClientTest {

    private static final String SEARCH_JSON = """
            [
              {
                "guid": "torrent-guid-1",
                "title": "Dune.Part.Two.2024.iTA.ENG.2160p.WEB-DL.HEVC-FLUX",
                "size": 15032385536,
                "seeders": 120,
                "leechers": 4,
                "indexer": "TheIndexer",
                "indexerId": 3,
                "downloadUrl": "http://prowlarr:9696/3/download?apikey=x&link=abc",
                "magnetUrl": "magnet:?xt=urn:btih:abc",
                "infoUrl": "http://indexer/details/1",
                "protocol": "torrent"
              },
              {
                "guid": "usenet-guid",
                "title": "Dune.Part.Two.2024.1080p.WEB-DL",
                "size": 8000000000,
                "indexer": "SomeUsenet",
                "indexerId": 7,
                "downloadUrl": "http://prowlarr:9696/7/download?apikey=x&link=nzb",
                "protocol": "usenet"
              },
              {
                "guid": "broken-guid",
                "title": "Dune.2024.720p.NoLinks",
                "seeders": 5,
                "indexer": "Broken",
                "indexerId": 9,
                "protocol": "torrent"
              }
            ]
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ProwlarrClient client = new ProwlarrClient(builder, properties());

    private CineSeekerrProperties properties() {
        return new CineSeekerrProperties(
                null, null,
                new CineSeekerrProperties.Prowlarr("http://prowlarr:9696", "secret-key"),
                null, null);
    }

    @Test
    void searchSendsApiKeyAndMoviesCategory() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://prowlarr:9696/api/v1/search")))
                .andExpect(header("X-Api-Key", "secret-key"))
                .andExpect(queryParam("query", "Dune%20Part%20Two%202024"))
                .andExpect(queryParam("categories", "2000"))
                .andExpect(queryParam("type", "search"))
                .andRespond(withSuccess(SEARCH_JSON, MediaType.APPLICATION_JSON));

        client.search("Dune Part Two 2024");
        server.verify();
    }

    @Test
    void searchMapsFieldsAndKeepsOnlyDownloadableTorrents() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/search")))
                .andRespond(withSuccess(SEARCH_JSON, MediaType.APPLICATION_JSON));

        List<ProwlarrRelease> releases = client.search("Dune Part Two 2024");

        assertThat(releases)
                .as("usenet results and results without any download link are dropped")
                .hasSize(1);
        ProwlarrRelease release = releases.getFirst();
        assertThat(release.title()).isEqualTo("Dune.Part.Two.2024.iTA.ENG.2160p.WEB-DL.HEVC-FLUX");
        assertThat(release.size()).isEqualTo(15032385536L);
        assertThat(release.seeders()).isEqualTo(120);
        assertThat(release.indexer()).isEqualTo("TheIndexer");
        assertThat(release.preferredDownloadUrl())
                .as("the Prowlarr-proxied URL wins over the magnet link")
                .isEqualTo("http://prowlarr:9696/3/download?apikey=x&link=abc");
    }

    @Test
    void emptyBodyYieldsEmptyList() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/search")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.search("nothing")).isEmpty();
    }

    @Test
    void serverErrorIsWrappedInApiClientException() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/search")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.search("Dune"))
                .isInstanceOf(ApiClientException.class)
                .hasMessageContaining("Prowlarr");
    }
}
