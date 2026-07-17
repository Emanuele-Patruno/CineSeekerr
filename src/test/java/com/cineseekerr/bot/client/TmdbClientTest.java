package com.cineseekerr.bot.client;

import com.cineseekerr.bot.config.CineSeekerrProperties;
import com.cineseekerr.bot.model.TmdbSeason;
import com.cineseekerr.bot.model.TmdbTitle;
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

    /**
     * A movie, a TV series (with TMDB's tv-specific field names), a person and a
     * collection to drop — real {@code /search/multi} response shape for "Jujutsu Kaisen".
     */
    private static final String SEARCH_JSON = """
            {
              "page": 1,
              "results": [
                {
                  "id": 872585,
                  "media_type": "movie",
                  "title": "Oppenheimer",
                  "original_title": "Oppenheimer",
                  "release_date": "2023-07-19",
                  "poster_path": "/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg",
                  "overview": "La storia di J. Robert Oppenheimer."
                },
                {
                  "id": 1396,
                  "media_type": "tv",
                  "name": "Breaking Bad",
                  "original_name": "Breaking Bad",
                  "first_air_date": "2008-01-20",
                  "poster_path": "/breaking.jpg",
                  "overview": "Un professore di chimica..."
                },
                {
                  "id": 17419,
                  "media_type": "person",
                  "name": "Cillian Murphy"
                },
                {
                  "id": 1529614,
                  "media_type": "collection",
                  "title": "呪術廻戦 シリーズ",
                  "original_title": "呪術廻戦 シリーズ"
                }
              ]
            }
            """;

    private static final String TV_DETAILS_JSON = """
            {
              "id": 1396,
              "name": "Breaking Bad",
              "seasons": [
                {"season_number": 0, "episode_count": 11, "name": "Specials"},
                {"season_number": 1, "episode_count": 7, "name": "Stagione 1"},
                {"season_number": 2, "episode_count": 13, "name": "Stagione 2"}
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
                null, null, null, null);
    }

    @Test
    void searchMixesMoviesAndTvAndDropsPeopleAndCollections() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.themoviedb.org/3/search/multi")))
                .andExpect(queryParam("query", "Oppenheimer"))
                .andExpect(queryParam("language", "it-IT"))
                .andExpect(queryParam("include_adult", "false"))
                .andExpect(queryParam("api_key", "v3-key"))
                .andRespond(withSuccess(SEARCH_JSON, MediaType.APPLICATION_JSON));

        // this must not throw: an unmapped media_type (e.g. "collection") used to break
        // deserialization of the whole response instead of just being filtered out
        List<TmdbTitle> titles = client("v3-key").searchTitles("Oppenheimer");

        assertThat(titles).as("the person and collection entries are dropped").hasSize(2);

        TmdbTitle movie = titles.getFirst();
        assertThat(movie.isTv()).isFalse();
        assertThat(movie.title()).isEqualTo("Oppenheimer");
        assertThat(movie.year()).isEqualTo(2023);
        assertThat(movie.posterUrl()).isEqualTo("https://image.tmdb.org/t/p/w342/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg");

        TmdbTitle tv = titles.get(1);
        assertThat(tv.isTv()).isTrue();
        assertThat(tv.title()).as("tv 'name' maps to title").isEqualTo("Breaking Bad");
        assertThat(tv.originalTitle()).as("tv 'original_name' maps to originalTitle").isEqualTo("Breaking Bad");
        assertThat(tv.year()).as("tv 'first_air_date' maps to the year").isEqualTo(2008);
        server.verify();
    }

    @Test
    void searchMoviesQueriesTheDedicatedEndpointAndTagsResultsAsMovies() {
        String moviesJson = """
                {
                  "page": 1,
                  "results": [
                    {"id": 1, "title": "Oppenheimer", "original_title": "Oppenheimer", "release_date": "2023-07-19"}
                  ]
                }
                """;
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.themoviedb.org/3/search/movie")))
                .andExpect(queryParam("query", "Oppenheimer"))
                .andRespond(withSuccess(moviesJson, MediaType.APPLICATION_JSON));

        List<TmdbTitle> titles = client("v3-key").searchMovies("Oppenheimer");

        assertThat(titles).hasSize(1);
        // /search/movie doesn't return a media_type field at all; the client must fill it in
        assertThat(titles.getFirst().isMovie()).isTrue();
        assertThat(titles.getFirst().isTv()).isFalse();
        server.verify();
    }

    @Test
    void searchTvQueriesTheDedicatedEndpointAndTagsResultsAsTv() {
        String tvJson = """
                {
                  "page": 1,
                  "results": [
                    {"id": 1396, "name": "Breaking Bad", "original_name": "Breaking Bad", "first_air_date": "2008-01-20"}
                  ]
                }
                """;
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.themoviedb.org/3/search/tv")))
                .andExpect(queryParam("query", "Breaking%20Bad"))
                .andRespond(withSuccess(tvJson, MediaType.APPLICATION_JSON));

        List<TmdbTitle> titles = client("v3-key").searchTv("Breaking Bad");

        assertThat(titles).hasSize(1);
        assertThat(titles.getFirst().isTv()).isTrue();
        assertThat(titles.getFirst().isMovie()).isFalse();
        server.verify();
    }

    @Test
    void seasonsDropsSpecialsAndMapsEpisodeCounts() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.themoviedb.org/3/tv/1396")))
                .andExpect(queryParam("language", "it-IT"))
                .andRespond(withSuccess(TV_DETAILS_JSON, MediaType.APPLICATION_JSON));

        List<TmdbSeason> seasons = client("k").seasons(1396);

        assertThat(seasons).as("season 0 (specials) is dropped").hasSize(2);
        assertThat(seasons.getFirst().seasonNumber()).isEqualTo(1);
        assertThat(seasons.getFirst().episodeCount()).isEqualTo(7);
    }

    @Test
    void v4ReadAccessTokenIsSentAsBearerHeaderInsteadOfQueryParam() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.fake.token";
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://api.themoviedb.org/3/search/multi")))
                .andExpect(header("Authorization", "Bearer " + jwt))
                .andExpect(request -> assertThat(request.getURI().toString()).doesNotContain("api_key"))
                .andRespond(withSuccess(SEARCH_JSON, MediaType.APPLICATION_JSON));

        client(jwt).searchTitles("Oppenheimer");
        server.verify();
    }

    @Test
    void emptyResultsYieldEmptyList() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/search/multi")))
                .andRespond(withSuccess("{\"page\":1,\"results\":[]}", MediaType.APPLICATION_JSON));

        assertThat(client("k").searchTitles("qwertyuiop")).isEmpty();
    }

    @Test
    void serverErrorIsWrappedInApiClientException() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/search/multi")))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client("k").searchTitles("Dune"))
                .isInstanceOf(ApiClientException.class)
                .hasMessageContaining("TMDB");
    }
}
