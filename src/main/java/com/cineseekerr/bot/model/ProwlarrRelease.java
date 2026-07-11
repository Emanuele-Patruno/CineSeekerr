package com.cineseekerr.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single search result from the Prowlarr {@code /api/v1/search} endpoint.
 * Field names match Prowlarr's camelCase JSON.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProwlarrRelease(
        String guid,
        String title,
        Long size,
        Integer seeders,
        Integer leechers,
        String indexer,
        Integer indexerId,
        String downloadUrl,
        String magnetUrl,
        String infoUrl,
        String protocol) {

    public boolean isTorrent() {
        return "torrent".equalsIgnoreCase(protocol);
    }

    public boolean isDownloadable() {
        return downloadUrl != null || magnetUrl != null;
    }

    /**
     * URL to hand to qBittorrent. Prefers Prowlarr's proxied {@code downloadUrl} (which
     * works for indexers behind authentication or FlareSolverr) and falls back to the
     * bare magnet link.
     */
    public String preferredDownloadUrl() {
        return downloadUrl != null ? downloadUrl : magnetUrl;
    }

    /** Seeders as a primitive, treating missing data as 0 so sorting never NPEs. */
    public int seedersOrZero() {
        return seeders == null ? 0 : seeders;
    }

    public long sizeOrZero() {
        return size == null ? 0 : size;
    }
}
