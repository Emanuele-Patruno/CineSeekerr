package com.cineseekerr.bot.client;

import com.cineseekerr.bot.config.CineSeekerrProperties;
import com.cineseekerr.bot.model.TmdbMovie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Client for the TMDB v3 API, used to disambiguate the user's free-text query into a
 * concrete movie (title + year) before searching indexers.
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
     * Searches movies by free text. Results come back in TMDB relevance/popularity order;
     * capping them for display is the caller's concern.
     */
    public List<TmdbMovie> searchMovies(String query) {
        try {
            SearchResponse response = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/search/movie")
                                .queryParam("query", query)
                                .queryParam("language", language)
                                .queryParam("include_adult", "false");
                        if (!bearerAuth) {
                            uriBuilder.queryParam("api_key", apiKey);
                        }
                        return uriBuilder.build();
                    })
                    .headers(headers -> {
                        if (bearerAuth) {
                            headers.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(SearchResponse.class);

            List<TmdbMovie> results = response == null || response.results() == null
                    ? List.of()
                    : response.results();
            log.debug("TMDB search for '{}' returned {} candidates", query, results.size());
            return results;
        } catch (RestClientException e) {
            throw new ApiClientException("TMDB search failed for '" + query + "'", e);
        }
    }

    record SearchResponse(List<TmdbMovie> results) {
    }
}
