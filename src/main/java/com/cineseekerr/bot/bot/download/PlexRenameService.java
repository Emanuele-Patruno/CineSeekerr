package com.cineseekerr.bot.bot.download;

import com.cineseekerr.bot.client.ApiClientException;
import com.cineseekerr.bot.client.QbittorrentClient;
import com.cineseekerr.bot.model.QbtTorrentFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Renames a completed download to Plex's expected {@code Title (Year)} layout.
 *
 * <p>The rename goes through the qBittorrent API (not the filesystem) so qBittorrent
 * keeps tracking the files and seeding continues — and the bot container needs no access
 * to the media volume at all.
 */
@Component
public class PlexRenameService {

    private static final Logger log = LoggerFactory.getLogger(PlexRenameService.class);

    private final QbittorrentClient qbittorrentClient;

    public PlexRenameService(QbittorrentClient qbittorrentClient) {
        this.qbittorrentClient = qbittorrentClient;
    }

    /**
     * Renames the torrent's root folder (or its single file) to {@code plexName}.
     *
     * @return {@code true} if the layout now matches what Plex expects
     */
    public boolean renameForPlex(String hash, String plexName) {
        try {
            List<QbtTorrentFile> files = qbittorrentClient.listFiles(hash);
            if (files.isEmpty()) {
                log.warn("Torrent {} reports no files, skipping rename", hash);
                return false;
            }

            String firstPath = files.getFirst().name();
            int slash = firstPath.indexOf('/');
            if (slash > 0) {
                String root = firstPath.substring(0, slash);
                boolean commonRoot = files.stream().allMatch(f -> f.name().startsWith(root + "/"));
                if (!commonRoot) {
                    log.warn("Torrent {} has no single root folder, skipping rename", hash);
                    return false;
                }
                if (root.equals(plexName)) {
                    return true;
                }
                qbittorrentClient.renameFolder(hash, root, plexName);
                log.info("Renamed torrent {} folder '{}' -> '{}'", hash, root, plexName);
                return true;
            }

            if (files.size() == 1) {
                String newName = plexName + extensionOf(firstPath);
                if (firstPath.equals(newName)) {
                    return true;
                }
                qbittorrentClient.renameFile(hash, firstPath, newName);
                log.info("Renamed torrent {} file '{}' -> '{}'", hash, firstPath, newName);
                return true;
            }

            log.warn("Torrent {} has {} loose files without a root folder, skipping rename",
                    hash, files.size());
            return false;
        } catch (ApiClientException e) {
            log.warn("Rename of torrent {} failed: {}", hash, e.getMessage());
            return false;
        }
    }

    /** Builds the Plex folder name {@code Title (Year)}, stripped of illegal filename chars. */
    public static String plexName(String title, Integer year) {
        String sanitized = title
                .replaceAll("[\\\\/:*?\"<>|]", "")
                .replaceAll("\\s+", " ")
                .strip();
        return year == null ? sanitized : sanitized + " (" + year + ")";
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot) : "";
    }
}
