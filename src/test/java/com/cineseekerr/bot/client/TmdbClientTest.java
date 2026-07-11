package com.cineseekerr.bot.client;

import com.cineseekerr.bot.config.CineSeekerrProperties;
import com.cineseekerr.bot.model.TmdbMovie;
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

class TmdbClientTest {

    private static final String SEARCH_JSON = """
            {
              "page": 1,
              "results": [
                {
                  "id": 872585,
                  "title": "Oppenheimer",
                  "original_title": "Oppenheimer",
                  "release_date": "2023-07-19",
                  "poster_path": "/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg",
                  "overview": "La storia di J. Robert Oppenheimer."
                },
                {
                  "id": 44777,
                  "title": "To End All War: Oppenheimer & the Atomic Bomb",
                  "original_title": "To End All War: Oppenheimer & the Atomic Bomb",
                  "release_date": "",
                  "poster_path": null,
                  "overview": ""
                }
              ]
            }
            """;

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    private TmdbClient client(String apiKey) {
        return new TmdbClient(builder, properties(apiKey));
    }

    private CineSeekerrProperties properties(String apiKey) {
        return new CineSeekerrProperties(
                null,
                new CineSeekerrProperties.Tmdb(apiKey, "https://api.themoviedb.org/3", "it-IT"),
                null, null, null);
    }

    @Test
    void searchMapsMoviesAndDerivedFields() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.themoviedb.org/3/search/movie")))
                .andExpect(queryParam("query", "Oppenheimer"))
                .andExpect(queryParam("language", "it-IT"))
                .andExpect(queryParam("include_adult", "false"))
                .andExpect(queryParam("api_key", "v3-key"))
                .andRespond(withSuccess(SEARCH_JSON, MediaType.APPLICATION_JSON));

        List<TmdbMovie> movies = client("v3-key").searchMovies("Oppenheimer");

        assertThat(movies).hasSize(2);
        TmdbMovie first = movies.getFirst();
        assertThat(first.id()).isEqualTo(872585);
        assertThat(first.title()).isEqualTo("Oppenheimer");
        assertThat(first.year()).isEqualTo(2023);
        assertThat(first.posterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg");

        TmdbMovie second = movies.get(1);
        assertThat(second.year()).as("blank release_date yields no year").isNull();
        assertThat(second.posterUrl()).as("missing poster yields no URL").isNull();
        server.verify();
    }

    @Test
    void v4ReadAccessTokenIsSentAsBearerHeaderInsteadOfQueryParam() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.fake.token";
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.themoviedb.org/3/search/movie")))
                .andExpect(header("Authorization", "Bearer " + jwt))
                .andExpect(request -> assertThat(request.getURI().toString()).doesNotContain("api_key"))
                .andRespond(withSuccess(SEARCH_JSON, MediaType.APPLICATION_JSON));

        client(jwt).searchMovies("Oppenheimer");
        server.verify();
    }

    @Test
    void emptyResultsYieldEmptyList() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/search/movie")))
                .andRespond(withSuccess("{\"page\":1,\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client("k").searchMovies("qwertyuiop")).isEmpty();
    }

    @Test
    void serverErrorIsWrappedInApiClientException() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/search/movie")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client("k").searchMovies("Dune"))
                .isInstanceOf(ApiClientException.class)
                .hasMessageContaining("TMDB");
    }
}
