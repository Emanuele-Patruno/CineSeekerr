package com.cineseekerr.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A file inside a torrent, as reported by {@code /api/v2/torrents/files}.
 *
 * @param name path relative to the torrent's save path, e.g. {@code Folder/movie.mkv}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QbtTorrentFile(String name, Long size) {
}
