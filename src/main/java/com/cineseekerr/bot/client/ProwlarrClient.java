package com.cineseekerr.bot.client;

import com.cineseekerr.bot.config.CineSeekerrProperties;
import com.cineseekerr.bot.model.MediaType;
import com.cineseekerr.bot.model.ProwlarrIndexer;
import com.cineseekerr.bot.model.ProwlarrRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Set;

/**
 * Client for the Prowlarr v1 search API. Queries all configured indexers at once;
 * with FlareSolverr-backed indexers a search can take tens of seconds, hence the generous
 * read timeout configured in {@code application.yml}.
 */
@Component
public class ProwlarrClient {

    private static final Logger log = LoggerFactory.getLogger(ProwlarrClient.class);

    /** Newznab/Torznab top-level categories. */
    private static final String MOVIES_CATEGORY = "2000";
    private static final String TV_CATEGORY = "5000";
    private static final int RESULT_LIMIT = 100;

    private final RestClient restClient;

    public ProwlarrClient(RestClient.Builder restClientBuilder, CineSeekerrProperties properties) {
        CineSeekerrProperties.Prowlarr prowlarr = properties.prowlarr();
        this.restClient = restClientBuilder
                .baseUrl(prowlarr.baseUrl())
                .defaultHeader("X-Api-Key", prowlarr.apiKey())
                .build();
    }

    /**
     * Searches all indexers for {@code query} in the category matching {@code mediaType}
     * (movies or TV). Only torrent results that can actually be downloaded are returned;
     * usenet results are dropped because the download side of this bot is qBittorrent.
     */
    public List<ProwlarrRelease> search(String query, MediaType mediaType) {
        return search(query, mediaType, Set.of());
    }

    /**
     * Same as {@link #search(String, MediaType)}, restricted to the given indexer IDs; an
     * empty set searches every configured indexer, same as the two-argument overload.
     */
    public List<ProwlarrRelease> search(String query, MediaType mediaType, Set<Integer> indexerIds) {
        String category = mediaType == MediaType.TV ? TV_CATEGORY : MOVIES_CATEGORY;
        List<ProwlarrRelease> results;
        try {
            results = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/api/v1/search")
                                .queryParam("query", query)
                                .queryParam("categories", category)
                                .queryParam("type", "search")
                                .queryParam("limit", RESULT_LIMIT);
                        if (indexerIds != null && !indexerIds.isEmpty()) {
                            uriBuilder.queryParam("indexerIds", indexerIds.toArray());
                        }
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ProwlarrRelease>>() {
                    });
        } catch (RestClientException e) {
            throw new ApiClientException("Prowlarr search failed for '" + query + "'", e);
        }

        if (results == null) {
            return List.of();
        }
        List<ProwlarrRelease> downloadable = results.stream()
                .filter(ProwlarrRelease::isTorrent)
                .filter(ProwlarrRelease::isDownloadable)
                .toList();
        log.debug("Prowlarr search for '{}' returned {} results ({} downloadable torrents)",
                query, results.size(), downloadable.size());
        return downloadable;
    }

    /** Enabled indexers configured in Prowlarr, used to let the user restrict a search. */
    public List<ProwlarrIndexer> listIndexers() {
        List<ProwlarrIndexer> indexers;
        try {
            indexers = restClient.get()
                    .uri("/api/v1/indexer")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<ProwlarrIndexer>>() {
                    });
        } catch (RestClientException e) {
            throw new ApiClientException("Failed to list Prowlarr indexers", e);
        }
        if (indexers == null) {
            return List.of();
        }
        return indexers.stream().filter(ProwlarrIndexer::enable).toList();
    }
}
