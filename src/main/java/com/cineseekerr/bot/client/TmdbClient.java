package com.cineseekerr.bot.client;

import com.cineseekerr.bot.config.CineSeekerrProperties;
import com.cineseekerr.bot.model.TmdbSeason;
import com.cineseekerr.bot.model.TmdbTitle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriBuilder;

import java.util.List;

/**
 * Client for the TMDB v3 API, used to disambiguate the user's free-text query into a
 * concrete movie or TV series before searching indexers.
 *
 * <p>Supports both TMDB credential flavours: a classic v3 API key (sent as the
 * {@code api_key} query parameter) and a v4 API Read Access Token, auto-detected by its
 * JWT shape and sent as a {@code Bearer} header.
 */
@Component
public class TmdbClient {

    private static final Logger log = LoggerFactory.getLogger(TmdbClient.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String language;
    private final boolean bearerAuth;

    public TmdbClient(RestClient.Builder restClientBuilder, CineSeekerrProperties properties) {
        CineSeekerrProperties.Tmdb tmdb = properties.tmdb();
        this.apiKey = tmdb.apiKey();
        this.language = tmdb.language();
        this.bearerAuth = apiKey != null && apiKey.startsWith("eyJ");
        this.restClient = restClientBuilder.baseUrl(tmdb.baseUrl()).build();
    }

    /**
     * Searches movies and TV series by free text in a single call. Results come back in
     * TMDB relevance/popularity order with the two media types interleaved; capping them
     * for display is the caller's concern.
     */
    public List<TmdbTitle> searchTitles(String query) {
        return fetchTitles("/search/multi", query).stream()
                .filter(t -> t.isMovie() || t.isTv())
                .toList();
    }

    /** Searches movies only, e.g. for the {@code /film} command. */
    public List<TmdbTitle> searchMovies(String query) {
        return fetchTitles("/search/movie", query).stream()
                .map(t -> t.withMediaType("movie"))
                .toList();
    }

    /** Searches TV series only, e.g. for the {@code /serie} command. */
    public List<TmdbTitle> searchTv(String query) {
        return fetchTitles("/search/tv", query).stream()
                .map(t -> t.withMediaType("tv"))
                .toList();
    }

    private List<TmdbTitle> fetchTitles(String path, String query) {
        try {
            SearchResponse response = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(path)
                                .queryParam("query", query)
                                .queryParam("language", language)
                                .queryParam("include_adult", "false");
                        return withApiKey(uriBuilder).build();
                    })
                    .headers(headers -> {
                        if (bearerAuth) {
                            headers.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(SearchResponse.class);

            List<TmdbTitle> results = response == null || response.results() == null
                    ? List.of()
                    : response.results();
            log.debug("TMDB search '{}' for '{}' returned {} candidates", path, query, results.size());
            return results;
        } catch (RestClientException e) {
            throw new ApiClientException("TMDB search failed for '" + query + "'", e);
        }
    }

    /**
     * The real seasons of a TV series (specials — season 0 — are dropped), in ascending
     * order as TMDB returns them.
     */
    public List<TmdbSeason> seasons(int tvId) {
        try {
            TvDetails details = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/tv/" + tvId).queryParam("language", language);
                        return withApiKey(uriBuilder).build();
                    })
                    .headers(headers -> {
                        if (bearerAuth) {
                            headers.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(TvDetails.class);

            return details == null || details.seasons() == null
                    ? List.of()
                    : details.seasons().stream()
                            .filter(s -> s.seasonNumber() > 0)
                            .toList();
        } catch (RestClientException e) {
            throw new ApiClientException("TMDB season lookup failed for tv/" + tvId, e);
        }
    }

    private UriBuilder withApiKey(UriBuilder uriBuilder) {
        if (!bearerAuth) {
            uriBuilder.queryParam("api_key", apiKey);
        }
        return uriBuilder;
    }

    record SearchResponse(List<TmdbTitle> results) {
    }

    record TvDetails(List<TmdbSeason> seasons) {
    }
}
