package com.cineseekerr.bot.bot.download;

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
     * @param plexName     display name shown in notifications (e.g. "Title (Year)"); also
     *                     the rename target
     * @param addedAt      when the download was queued, used to expire unmatched entries
     */
    public record PendingDownload(long chatId, String releaseTitle, String plexName, Instant addedAt) {
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
        track(chatId, releaseTitle, plexName, Instant.now());
    }

    public void track(long chatId, String releaseTitle, String plexName, Instant addedAt) {
        pending.put(normalize(releaseTitle), new PendingDownload(chatId, releaseTitle, plexName, addedAt));
        persist();
    }

    public Map<String, PendingDownload> snapshot() {
        return Map.copyOf(pending);
    }

    public void remove(String key) {
        pending.remove(key);
        persist();
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
