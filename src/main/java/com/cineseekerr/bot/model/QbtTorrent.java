package com.cineseekerr.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A torrent as reported by the qBittorrent {@code /api/v2/torrents/info} endpoint.
 *
 * @param progress    download progress between 0.0 and 1.0
 * @param contentPath absolute path (inside the qBittorrent container) of the torrent's
 *                    file or root folder; used for the post-download rename
 * @param numSeeds    connected seeders; used to detect a stalled download
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QbtTorrent(
        String hash,
        String name,
        String state,
        double progress,
        @JsonProperty("save_path") String savePath,
        @JsonProperty("content_path") String contentPath,
        Long eta,
        Long dlspeed,
        @JsonProperty("num_seeds") Integer numSeeds) {

    public boolean isComplete() {
        return progress >= 1.0d;
    }
}
