package com.cineseekerr.bot.bot.download;

import com.cineseekerr.bot.model.TmdbTitle;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of downloads the bot is waiting on. qBittorrent's add endpoint does not return
 * the torrent hash, so downloads are matched back to qBittorrent torrents by normalized
 * release name (see {@link #normalize}).
 *
 * <p>The registry is mirrored to a JSON file on every change and reloaded on startup, so a
 * container restart while a download is in flight doesn't silently drop the Plex rename
 * that's still owed to the user.
 */
@Component
public class DownloadTracker {

    private static final Logger log = LoggerFactory.getLogger(DownloadTracker.class);

    /** qBittorrent category used for everything this bot downloads. */
    public static final String QBT_CATEGORY = "film";

    /**
     * @param chatId       chat to notify on completion
     * @param releaseTitle release name as reported by Prowlarr
     * @param plexName     display name shown in notifications (e.g. "Title (Year)" or
     *                     "Show (Year) — Stagione 2"); also the rename target for movies
     * @param tmdbTitle    the TMDB title this download is for, used to search again if the
     *                     download stalls; {@code null} for entries tracked without one
     * @param season       season number for a TV download; {@code null} for movies
     * @param episode      episode number for a single-episode download; {@code null} for
     *                     whole-season packs and movies
     * @param addedAt      when the download was queued, used to expire unmatched entries
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PendingDownload(long chatId, String releaseTitle, String plexName, TmdbTitle tmdbTitle,
                                   Integer season, Integer episode, Instant addedAt) {
    }

    private final Map<String, PendingDownload> pending = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path stateFile;

    public DownloadTracker(ObjectMapper objectMapper,
                            @Value("${cineseekerr.download.state-file:/data/pending-downloads.json}") String stateFile) {
        this.objectMapper = objectMapper;
        this.stateFile = Path.of(stateFile);
        load();
    }

    public void track(long chatId, String releaseTitle, String plexName) {
        track(chatId, releaseTitle, plexName, null, null, null, Instant.now());
    }

    public void track(long chatId, String releaseTitle, String plexName, Instant addedAt) {
        track(chatId, releaseTitle, plexName, null, null, null, addedAt);
    }

    public void track(long chatId, String releaseTitle, String plexName, TmdbTitle tmdbTitle, Integer season,
                      Integer episode) {
        track(chatId, releaseTitle, plexName, tmdbTitle, season, episode, Instant.now());
    }

    public void track(long chatId, String releaseTitle, String plexName, TmdbTitle tmdbTitle, Integer season,
                      Integer episode, Instant addedAt) {
        pending.put(normalize(releaseTitle),
                new PendingDownload(chatId, releaseTitle, plexName, tmdbTitle, season, episode, addedAt));
        persist();
    }

    public Map<String, PendingDownload> snapshot() {
        return Map.copyOf(pending);
    }

    public void remove(String key) {
        pending.remove(key);
        persist();
    }

    /**
     * Finds the pending download whose release title matches a qBittorrent torrent name —
     * used to resolve the "retry search" button on a stalled-download notification, where
     * only the torrent hash is available.
     */
    public Optional<Map.Entry<String, PendingDownload>> findByTorrentName(String torrentName) {
        String normalized = normalize(torrentName);
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return pending.entrySet().stream()
                .filter(e -> normalized.equals(e.getKey())
                        || normalized.contains(e.getKey())
                        || e.getKey().contains(normalized))
                .findFirst();
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

    private void load() {
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            List<PendingDownload> restored = objectMapper.readValue(stateFile.toFile(),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, PendingDownload.class));
            restored.forEach(download -> pending.put(normalize(download.releaseTitle()), download));
            if (!restored.isEmpty()) {
                log.info("Restored {} pending download(s) from {}", restored.size(), stateFile);
            }
        } catch (IOException e) {
            log.warn("Could not read pending downloads from {}, starting empty: {}", stateFile, e.getMessage());
        }
    }

    /** Rewrites the whole file after every change; the pending set is tiny (a handful of entries at most). */
    private void persist() {
        try {
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(stateFile.toFile(), List.copyOf(pending.values()));
        } catch (IOException e) {
            log.warn("Could not persist pending downloads to {}: {}", stateFile, e.getMessage());
        }
    }
}
