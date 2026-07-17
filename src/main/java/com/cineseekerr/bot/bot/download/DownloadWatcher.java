package com.cineseekerr.bot.bot.download;

import com.cineseekerr.bot.bot.MessageFormatter;
import com.cineseekerr.bot.bot.Messages;
import com.cineseekerr.bot.bot.telegram.TelegramMessenger;
import com.cineseekerr.bot.client.ApiClientException;
import com.cineseekerr.bot.client.QbittorrentClient;
import com.cineseekerr.bot.model.QbtTorrent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.cineseekerr.bot.bot.MessageFormatter.esc;

/**
 * Polls qBittorrent for the downloads queued by the bot. When one completes it is renamed
 * for Plex and the user is notified. Entries that never show up in qBittorrent (e.g. the
 * torrent was rejected after the fact) are expired with a warning to the user. Downloads
 * that sit at 0 seeders for too long are flagged as stalled, once, with a button to stop
 * them and search for another release.
 */
@Component
public class DownloadWatcher {

    private static final Logger log = LoggerFactory.getLogger(DownloadWatcher.class);

    /** How long a queued download may stay invisible in qBittorrent before we give up. */
    static final Duration UNMATCHED_TIMEOUT = Duration.ofMinutes(15);

    /** How long a download may sit at 0 seeders before we flag it as stalled. */
    static final Duration STALL_TIMEOUT = Duration.ofHours(2);

    private final DownloadTracker tracker;
    private final QbittorrentClient qbittorrentClient;
    private final PlexRenameService renameService;
    private final TelegramMessenger messenger;
    private final Messages messages;
    private final Clock clock;

    /** Torrent hash -> when it was first seen at 0 seeders; cleared once it recovers. */
    private final Map<String, Instant> zeroSeedsSince = new ConcurrentHashMap<>();
    /** Torrent hashes already notified as stalled, so we don't repeat it every poll. */
    private final Set<String> stalledNotified = ConcurrentHashMap.newKeySet();

    @Autowired
    public DownloadWatcher(DownloadTracker tracker,
                           QbittorrentClient qbittorrentClient,
                           PlexRenameService renameService,
                           TelegramMessenger messenger,
                           Messages messages) {
        this(tracker, qbittorrentClient, renameService, messenger, messages, Clock.systemUTC());
    }

    /** Package-visible so tests can fast-forward the stall clock without sleeping. */
    DownloadWatcher(DownloadTracker tracker,
                    QbittorrentClient qbittorrentClient,
                    PlexRenameService renameService,
                    TelegramMessenger messenger,
                    Messages messages,
                    Clock clock) {
        this.tracker = tracker;
        this.qbittorrentClient = qbittorrentClient;
        this.renameService = renameService;
        this.messenger = messenger;
        this.messages = messages;
        this.clock = clock;
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
                QbtTorrent torrent = match.get();
                if (torrent.isComplete()) {
                    forgetStallTracking(torrent.hash());
                    complete(key, download, torrent);
                } else {
                    checkStalled(download, torrent);
                }
            } else if (Duration.between(download.addedAt(), Instant.now(clock)).compareTo(UNMATCHED_TIMEOUT) > 0) {
                log.warn("Download '{}' never appeared in qBittorrent, giving up", download.releaseTitle());
                messenger.sendHtml(download.chatId(),
                        messages.get("watcher.lost", esc(download.plexName())), null);
                tracker.remove(key);
            }
        });
    }

    private void complete(String key, DownloadTracker.PendingDownload download, QbtTorrent torrent) {
        boolean renamed;
        if (download.episode() != null) {
            // single episodes are saved straight into ".../Show (Year)/Season NN" and keep
            // their scene name, whose SxxEyy tag is what Plex matches episodes with
            renamed = true;
        } else if (download.season() != null) {
            renamed = renameService.renameSeasonForPlex(torrent.hash(), download.season());
        } else {
            renamed = renameService.renameForPlex(torrent.hash(), download.plexName());
        }
        String text = messages.get(renamed ? "watcher.ready" : "watcher.rename.failed",
                esc(download.plexName()));
        messenger.sendHtml(download.chatId(), text, null);
        tracker.remove(key);
        log.info("Download '{}' completed (renamed={})", download.plexName(), renamed);
    }

    private void checkStalled(DownloadTracker.PendingDownload download, QbtTorrent torrent) {
        Integer seeds = torrent.numSeeds();
        if (seeds == null || seeds > 0) {
            forgetStallTracking(torrent.hash());
            return;
        }
        Instant since = zeroSeedsSince.computeIfAbsent(torrent.hash(), h -> Instant.now(clock));
        if (stalledNotified.contains(torrent.hash())) {
            return;
        }
        Duration stalledFor = Duration.between(since, Instant.now(clock));
        if (stalledFor.compareTo(STALL_TIMEOUT) >= 0) {
            stalledNotified.add(torrent.hash());
            notifyStalled(download, torrent, stalledFor);
        }
    }

    private void notifyStalled(DownloadTracker.PendingDownload download, QbtTorrent torrent, Duration stalledFor) {
        String text = messages.get("stalled.notice",
                esc(download.plexName()), MessageFormatter.humanDuration(stalledFor));
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(InlineKeyboardButton.builder()
                        .text(messages.get("stalled.retry.button"))
                        .callbackData("retrystalled:" + torrent.hash())
                        .build()))
                .build();
        messenger.sendHtml(download.chatId(), text, keyboard);
        log.info("Download '{}' has been stalled for {}, notified chat {}",
                download.plexName(), stalledFor, download.chatId());
    }

    private void forgetStallTracking(String hash) {
        zeroSeedsSince.remove(hash);
        stalledNotified.remove(hash);
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
