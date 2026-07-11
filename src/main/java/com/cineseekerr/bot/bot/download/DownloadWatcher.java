package com.cineseekerr.bot.bot.download;

import com.cineseekerr.bot.bot.telegram.TelegramMessenger;
import com.cineseekerr.bot.client.ApiClientException;
import com.cineseekerr.bot.client.QbittorrentClient;
import com.cineseekerr.bot.model.QbtTorrent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.cineseekerr.bot.bot.MessageFormatter.esc;

/**
 * Polls qBittorrent for the downloads queued by the bot. When one completes it is renamed
 * for Plex and the user is notified. Entries that never show up in qBittorrent (e.g. the
 * torrent was rejected after the fact) are expired with a warning to the user.
 */
@Component
public class DownloadWatcher {

    private static final Logger log = LoggerFactory.getLogger(DownloadWatcher.class);

    /** How long a queued download may stay invisible in qBittorrent before we give up. */
    static final Duration UNMATCHED_TIMEOUT = Duration.ofMinutes(15);

    private final DownloadTracker tracker;
    private final QbittorrentClient qbittorrentClient;
    private final PlexRenameService renameService;
    private final TelegramMessenger messenger;

    public DownloadWatcher(DownloadTracker tracker,
                           QbittorrentClient qbittorrentClient,
                           PlexRenameService renameService,
                           TelegramMessenger messenger) {
        this.tracker = tracker;
        this.qbittorrentClient = qbittorrentClient;
        this.renameService = renameService;
        this.messenger = messenger;
    }

    @Scheduled(fixedDelayString = "${cineseekerr.download.poll-interval:PT30S}")
    public void poll() {
        if (tracker.isEmpty()) {
            return;
        }
        List<QbtTorrent> torrents;
        try {
            torrents = qbittorrentClient.listTorrents(DownloadTracker.QBT_CATEGORY);
        } catch (ApiClientException e) {
            log.warn("Skipping download poll, qBittorrent unavailable: {}", e.getMessage());
            return;
        }

        tracker.snapshot().forEach((key, download) -> {
            Optional<QbtTorrent> match = torrents.stream()
                    .filter(t -> matches(key, t.name()))
                    .findFirst();
            if (match.isPresent()) {
                if (match.get().isComplete()) {
                    complete(key, download, match.get());
                }
            } else if (Duration.between(download.addedAt(), Instant.now()).compareTo(UNMATCHED_TIMEOUT) > 0) {
                log.warn("Download '{}' never appeared in qBittorrent, giving up", download.releaseTitle());
                messenger.sendHtml(download.chatId(),
                        "⚠️ Non trovo <b>" + esc(download.plexName())
                                + "</b> in qBittorrent — controlla manualmente.", null);
                tracker.remove(key);
            }
        });
    }

    private void complete(String key, DownloadTracker.PendingDownload download, QbtTorrent torrent) {
        boolean renamed = renameService.renameForPlex(torrent.hash(), download.plexName());
        String text = renamed
                ? "🍿 <b>" + esc(download.plexName()) + "</b> è pronto! Disponibile a breve su Plex."
                : "🍿 <b>" + esc(download.plexName()) + "</b> è stato scaricato, ma non sono riuscito"
                        + " a rinominarlo per Plex — potrebbe servire una sistemata manuale.";
        messenger.sendHtml(download.chatId(), text, null);
        tracker.remove(key);
        log.info("Download '{}' completed (renamed={})", download.plexName(), renamed);
    }

    /**
     * Prowlarr's release title and qBittorrent's torrent name usually match, but indexers
     * sometimes add or drop decorations — so containment in either direction counts too.
     */
    private boolean matches(String pendingKey, String torrentName) {
        String normalized = DownloadTracker.normalize(torrentName);
        if (normalized.isBlank() || pendingKey.isBlank()) {
            return false;
        }
        return normalized.equals(pendingKey)
                || normalized.contains(pendingKey)
                || pendingKey.contains(normalized);
    }
}
