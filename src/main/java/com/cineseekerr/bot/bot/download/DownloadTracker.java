package com.cineseekerr.bot.bot.download;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of downloads the bot is waiting on. qBittorrent's add endpoint does
 * not return the torrent hash, so downloads are matched back to qBittorrent torrents by
 * normalized release name (see {@link #normalize}). Lost on restart — an acceptable
 * trade-off for a home bot (a Redis-backed version is on the roadmap).
 */
@Component
public class DownloadTracker {

    /** qBittorrent category used for everything this bot downloads. */
    public static final String QBT_CATEGORY = "film";

    /**
     * @param chatId       chat to notify on completion
     * @param releaseTitle release name as reported by Prowlarr
     * @param plexName     final "Title (Year)" name for Plex
     * @param addedAt      when the download was queued, used to expire unmatched entries
     */
    public record PendingDownload(long chatId, String releaseTitle, String plexName, Instant addedAt) {
    }

    private final Map<String, PendingDownload> pending = new ConcurrentHashMap<>();

    public void track(long chatId, String releaseTitle, String plexName) {
        track(chatId, releaseTitle, plexName, Instant.now());
    }

    public void track(long chatId, String releaseTitle, String plexName, Instant addedAt) {
        pending.put(normalize(releaseTitle), new PendingDownload(chatId, releaseTitle, plexName, addedAt));
    }

    public Map<String, PendingDownload> snapshot() {
        return Map.copyOf(pending);
    }

    public void remove(String key) {
        pending.remove(key);
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    /**
     * Collapses a release/torrent name to lowercase alphanumerics so that
     * {@code Dune.Part.Two.2024} and {@code Dune Part Two 2024} compare equal.
     */
    public static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
