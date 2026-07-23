package com.cineseekerr.bot.bot;

import com.cineseekerr.bot.bot.download.DownloadTracker;
import com.cineseekerr.bot.bot.download.PlexRenameService;
import com.cineseekerr.bot.bot.state.ConversationState;
import com.cineseekerr.bot.bot.state.ConversationStateStore;
import com.cineseekerr.bot.bot.state.ConversationStep;
import com.cineseekerr.bot.bot.telegram.TelegramMessenger;
import com.cineseekerr.bot.client.ApiClientException;
import com.cineseekerr.bot.client.ProwlarrClient;
import com.cineseekerr.bot.client.QbittorrentClient;
import com.cineseekerr.bot.client.TmdbClient;
import com.cineseekerr.bot.config.CineSeekerrProperties;
import com.cineseekerr.bot.model.Language;
import com.cineseekerr.bot.model.MediaType;
import com.cineseekerr.bot.model.ProwlarrIndexer;
import com.cineseekerr.bot.model.ProwlarrRelease;
import com.cineseekerr.bot.model.QbtTorrent;
import com.cineseekerr.bot.model.Resolution;
import com.cineseekerr.bot.model.SearchResult;
import com.cineseekerr.bot.model.TmdbSeason;
import com.cineseekerr.bot.model.TmdbTitle;
import com.cineseekerr.bot.model.VideoCodec;
import com.cineseekerr.bot.parser.ReleaseNameParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.cineseekerr.bot.bot.MessageFormatter.NUMBER_EMOJI;
import static com.cineseekerr.bot.bot.MessageFormatter.esc;

/**
 * The conversation state machine, one instance shared by all chats.
 *
 * <p>Flow: free text (or {@code /cerca}) → TMDB candidates (movies and TV series mixed) →
 * for TV, season choice → Prowlarr search → dynamic audio/subtitle/quality/format filters
 * (each computed from the actual result set, steps with a single option are skipped) →
 * top-5 by seeders → send to qBittorrent.
 *
 * <p>All interactive steps edit the same Telegram message; callback data carries only
 * short tokens (e.g. {@code quality:R1080P}), everything else lives in
 * {@link ConversationStateStore}.
 */
@Component
public class ConversationHandler {

    private static final Logger log = LoggerFactory.getLogger(ConversationHandler.class);

    private static final int MAX_CANDIDATES = 5;
    private static final int MAX_SHORTLIST = 5;
    private static final String ANY = "any";

    private final TmdbClient tmdbClient;
    private final ProwlarrClient prowlarrClient;
    private final QbittorrentClient qbittorrentClient;
    private final ReleaseNameParser parser;
    private final TelegramMessenger messenger;
    private final ConversationStateStore stateStore;
    private final DownloadTracker downloadTracker;
    private final CineSeekerrProperties properties;
    private final Messages messages;
    private final MessageFormatter formatter;

    public ConversationHandler(TmdbClient tmdbClient,
                               ProwlarrClient prowlarrClient,
                               QbittorrentClient qbittorrentClient,
                               ReleaseNameParser parser,
                               TelegramMessenger messenger,
                               ConversationStateStore stateStore,
                               DownloadTracker downloadTracker,
                               CineSeekerrProperties properties,
                               Messages messages,
                               MessageFormatter formatter) {
        this.tmdbClient = tmdbClient;
        this.prowlarrClient = prowlarrClient;
        this.qbittorrentClient = qbittorrentClient;
        this.parser = parser;
        this.messenger = messenger;
        this.stateStore = stateStore;
        this.downloadTracker = downloadTracker;
        this.properties = properties;
        this.messages = messages;
        this.formatter = formatter;
    }

    public void onTextMessage(long chatId, String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        try {
            if (trimmed.startsWith("/")) {
                handleCommand(chatId, trimmed);
                return;
            }
            ConversationState state = stateStore.find(chatId).orElse(null);
            if (state != null && state.step() == ConversationStep.AWAITING_MANUAL_SEASON) {
                onManualSeasonEntered(chatId, state, trimmed);
            } else if (state != null && state.step() == ConversationStep.AWAITING_MANUAL_EPISODE) {
                onManualEpisodeEntered(chatId, state, trimmed);
            } else {
                startSearch(chatId, trimmed);
            }
        } catch (ApiClientException e) {
            log.warn("External service error for chat {}: {}", chatId, e.getMessage(), e);
            messenger.sendHtml(chatId, messages.get("error.generic", esc(e.getMessage())), null);
        }
    }

    public void onCallback(long chatId, int messageId, String callbackQueryId, String data) {
        messenger.answerCallback(callbackQueryId);
        if (data == null || data.isBlank()) {
            return;
        }
        if ("cancel".equals(data)) {
            stateStore.clear(chatId);
            messenger.editHtml(chatId, messageId, messages.get("cancel.done"), null);
            return;
        }

        String[] parts = data.split(":", 2);
        String action = parts[0];
        String value = parts.length > 1 ? parts[1] : "";

        // /stato's "stop download" buttons and a stalled-download notification's "retry"
        // button act on qBittorrent directly and don't belong to the movie-search
        // conversation, so they work with no state on file.
        if ("stopdl".equals(action)) {
            onStopDownload(chatId, messageId, value);
            return;
        }
        if ("retrystalled".equals(action)) {
            onRetryStalledDownload(chatId, messageId, value);
            return;
        }

        ConversationState state = stateStore.find(chatId).orElse(null);
        if (state == null) {
            messenger.editHtml(chatId, messageId, messages.get("session.expired"), null);
            return;
        }

        try {
            switch (action) {
                case "movie" -> whenStep(state, ConversationStep.AWAITING_MOVIE_CHOICE,
                        () -> onTitleChosen(chatId, state, parseIndex(value, state.candidates().size())));
                case "season" -> whenStep(state, ConversationStep.AWAITING_SEASON_CHOICE,
                        () -> onSeasonChosen(chatId, state, value));
                case "episode" -> whenStep(state, ConversationStep.AWAITING_EPISODE_CHOICE,
                        () -> onEpisodeChosen(chatId, state, value));
                case "quality" -> whenStep(state, ConversationStep.AWAITING_QUALITY,
                        () -> onQualityChosen(chatId, state, value));
                case "format" -> whenStep(state, ConversationStep.AWAITING_FORMAT,
                        () -> onFormatChosen(chatId, state, value));
                case "audio" -> whenStep(state, ConversationStep.AWAITING_AUDIO,
                        () -> onAudioChosen(chatId, state, value));
                case "subs" -> whenStep(state, ConversationStep.AWAITING_SUBTITLES,
                        () -> onSubtitlesChosen(chatId, state, value));
                case "release" -> whenStep(state, ConversationStep.AWAITING_RELEASE_CHOICE,
                        () -> onReleaseChosen(chatId, state, parseIndex(value, state.shortlist().size())));
                case "idx" -> whenStep(state, ConversationStep.AWAITING_INDEXER_CHOICE,
                        () -> onIndexerToggled(chatId, state, value));
                case "idxsearch" -> whenStep(state, ConversationStep.AWAITING_INDEXER_CHOICE,
                        () -> onIndexerSearchConfirmed(chatId, state));
                case "back" -> onBack(state);
                default -> log.debug("Unknown callback action '{}' from chat {}", action, chatId);
            }
        } catch (ApiClientException e) {
            log.warn("External service error for chat {}: {}", chatId, e.getMessage(), e);
            stateStore.clear(chatId);
            messenger.editHtml(chatId, messageId, messages.get("error.generic", esc(e.getMessage())), null);
        }
    }

    // ------------------------------------------------------------------ commands

    private void handleCommand(long chatId, String text) {
        String[] parts = text.split("\\s+", 2);
        String command = parts[0].toLowerCase();
        int at = command.indexOf('@');
        if (at > 0) {
            command = command.substring(0, at);
        }
        String args = parts.length > 1 ? parts[1].strip() : "";

        switch (command) {
            case "/start", "/help" -> messenger.sendHtml(chatId, messages.get("help"), null);
            case "/cerca", "/search" -> searchOrUsage(chatId, args, "usage.cerca",
                    () -> startSearch(chatId, args));
            case "/film", "/movie" -> searchOrUsage(chatId, args, "usage.film",
                    () -> startSearch(chatId, args, tmdbClient.searchMovies(args), "search.none.movie"));
            case "/serie", "/tv" -> searchOrUsage(chatId, args, "usage.serie",
                    () -> startSearch(chatId, args, tmdbClient.searchTv(args), "search.none.tv"));
            case "/annulla", "/cancel" -> {
                stateStore.clear(chatId);
                messenger.sendHtml(chatId, messages.get("cancel.done"), null);
            }
            case "/stato", "/status" -> sendDownloadStatus(chatId);
            default -> messenger.sendHtml(chatId, messages.get("command.unknown"), null);
        }
    }

    private void searchOrUsage(long chatId, String args, String usageKey, Runnable search) {
        if (args.isEmpty()) {
            messenger.sendHtml(chatId, messages.get(usageKey), null);
        } else {
            search.run();
        }
    }

    /**
     * Shows only downloads still in progress: qBittorrent never drops completed torrents
     * from the category on its own, and completed ones already get a push notification
     * from {@link com.cineseekerr.bot.bot.download.DownloadWatcher}, so listing them here
     * too would just accumulate every movie ever downloaded.
     */
    private void sendDownloadStatus(long chatId) {
        List<QbtTorrent> active = qbittorrentClient.listTorrents(DownloadTracker.QBT_CATEGORY).stream()
                .filter(t -> !t.isComplete())
                .toList();
        if (active.isEmpty()) {
            messenger.sendHtml(chatId, messages.get("status.none"), null);
            return;
        }
        StringBuilder sb = new StringBuilder(messages.get("status.header")).append("\n\n");
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (QbtTorrent torrent : active) {
            sb.append(formatter.torrentStatusLine(torrent)).append('\n');
            rows.add(new InlineKeyboardRow(button(
                    messages.get("status.stop.button", MessageFormatter.truncate(torrent.name(), 30)),
                    "stopdl:" + torrent.hash())));
        }
        messenger.sendHtml(chatId, sb.toString(), keyboard(rows));
    }

    private void onStopDownload(long chatId, int messageId, String hash) {
        try {
            qbittorrentClient.deleteTorrent(hash, true);
            messenger.editHtml(chatId, messageId, messages.get("status.stopped"), null);
            log.info("Chat {} stopped download {}", chatId, hash);
        } catch (ApiClientException e) {
            log.warn("Failed to stop download {} for chat {}: {}", hash, chatId, e.getMessage());
            messenger.editHtml(chatId, messageId, messages.get("error.generic", esc(e.getMessage())), null);
        }
    }

    /**
     * Stops a download {@link com.cineseekerr.bot.bot.download.DownloadWatcher} flagged as
     * stalled and, if we still know which movie it was for, starts a fresh search so the
     * user can pick a different release.
     */
    private void onRetryStalledDownload(long chatId, int messageId, String hash) {
        try {
            Optional<Map.Entry<String, DownloadTracker.PendingDownload>> match = qbittorrentClient
                    .listTorrents(DownloadTracker.QBT_CATEGORY).stream()
                    .filter(t -> hash.equals(t.hash()))
                    .findFirst()
                    .flatMap(t -> downloadTracker.findByTorrentName(t.name()));
            if (match.isEmpty()) {
                messenger.editHtml(chatId, messageId, messages.get("stalled.gone"), null);
                return;
            }
            downloadTracker.remove(match.get().getKey());
            qbittorrentClient.deleteTorrent(hash, true);
            log.info("Chat {} stopped stalled download {}", chatId, hash);

            DownloadTracker.PendingDownload download = match.get().getValue();
            if (download.tmdbTitle() == null) {
                messenger.editHtml(chatId, messageId, messages.get("stalled.stopped"), null);
                return;
            }
            messenger.editHtml(chatId, messageId, messages.get("stalled.searching"), null);
            retrySearch(chatId, download.tmdbTitle(), download.season(), download.episode());
        } catch (ApiClientException e) {
            log.warn("Failed to retry stalled download {} for chat {}: {}", hash, chatId, e.getMessage());
            messenger.editHtml(chatId, messageId, messages.get("error.generic", esc(e.getMessage())), null);
        }
    }

    // ------------------------------------------------------------------ search flow

    private void startSearch(long chatId, String query) {
        startSearch(chatId, query, tmdbClient.searchTitles(query), "search.none.query");
    }

    private void startSearch(long chatId, String query, List<TmdbTitle> results, String noneKey) {
        stateStore.clear(chatId);
        List<TmdbTitle> candidates = results.stream()
                .limit(MAX_CANDIDATES)
                .toList();
        if (candidates.isEmpty()) {
            messenger.sendHtml(chatId, messages.get(noneKey, esc(query)), null);
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            TmdbTitle candidate = candidates.get(i);
            rows.add(new InlineKeyboardRow(button(
                    NUMBER_EMOJI[i] + " " + MessageFormatter.icon(candidate) + " "
                            + MessageFormatter.truncate(MessageFormatter.titleLabel(candidate), 32),
                    "movie:" + i)));
        }
        rows.add(cancelRow());

        Integer messageId = messenger.sendHtml(chatId,
                formatter.candidatesText(candidates), keyboard(rows));

        ConversationState state = new ConversationState();
        state.setStep(ConversationStep.AWAITING_MOVIE_CHOICE);
        state.setCandidates(candidates);
        state.setMessageId(messageId);
        stateStore.save(chatId, state);
    }

    private void onTitleChosen(long chatId, ConversationState state, int index) {
        if (index < 0) {
            return;
        }
        state.setTitle(state.candidates().get(index));
        if (state.title().isTv()) {
            showSeasonStep(chatId, state);
        } else {
            showIndexerStep(chatId, state);
        }
    }

    /**
     * Always shows the season list with a manual-override escape hatch, even when TMDB
     * reports just one season: TMDB's season grouping can be wrong for some shows (e.g.
     * multi-cour anime lumped into a single "Season 1"), so auto-picking the only season
     * TMDB knows about would silently hide the ones it doesn't.
     */
    private void showSeasonStep(long chatId, ConversationState state) {
        List<TmdbSeason> seasons = tmdbClient.seasons(state.title().id());
        if (seasons.isEmpty()) {
            stateStore.clear(chatId);
            editFlowMessage(chatId, state,
                    messages.get("seasons.none", esc(MessageFormatter.titleLabel(state.title()))), null);
            return;
        }
        state.setSeasons(seasons);

        List<InlineKeyboardRow> rows = bucketRows(seasons.stream()
                .map(s -> button(messages.get("seasons.button", s.seasonNumber(), s.episodeCount()),
                        "season:" + s.seasonNumber()))
                .toList());
        rows.add(new InlineKeyboardRow(button(messages.get("seasons.manual.button"), "season:manual")));
        rows.add(cancelRow());

        state.setStep(ConversationStep.AWAITING_SEASON_CHOICE);
        saveAndEdit(chatId, state,
                messages.get("seasons.prompt", esc(MessageFormatter.titleLabel(state.title()))), keyboard(rows));
    }

    private void onSeasonChosen(long chatId, ConversationState state, String value) {
        if ("manual".equals(value)) {
            state.setStep(ConversationStep.AWAITING_MANUAL_SEASON);
            saveAndEdit(chatId, state, messages.get("seasons.manual.prompt"), cancelOnlyKeyboard());
            return;
        }
        int season;
        try {
            season = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return;
        }
        state.setSeason(season);
        showEpisodeStep(chatId, state);
    }

    private void onManualSeasonEntered(long chatId, ConversationState state, String text) {
        Integer season = parsePositiveInt(text);
        if (season == null) {
            messenger.sendHtml(chatId, messages.get("seasons.manual.invalid"), null);
            return;
        }
        state.setSeason(season);
        showEpisodeStep(chatId, state);
    }

    /** Telegram inline keyboards allow at most 100 buttons; 96 episodes + the two fixed rows fit. */
    private static final int MAX_EPISODE_BUTTONS = 96;
    private static final int EPISODES_PER_ROW = 8;

    /**
     * Numbered episode buttons are only offered when TMDB actually knows the episode
     * count for this season; otherwise (including seasons entered manually, which TMDB
     * never counted) the choice is just "whole season" or a manually typed episode number.
     */
    private void showEpisodeStep(long chatId, ConversationState state) {
        int episodeCount = state.seasons().stream()
                .filter(s -> s.seasonNumber() == state.season())
                .findFirst()
                .map(TmdbSeason::episodeCount)
                .orElse(0);

        List<InlineKeyboardRow> rows = new ArrayList<>();
        rows.add(new InlineKeyboardRow(button(messages.get("episode.all.button"), "episode:all")));
        if (episodeCount > 1) {
            List<InlineKeyboardButton> episodeButtons = new ArrayList<>();
            for (int ep = 1; ep <= Math.min(episodeCount, MAX_EPISODE_BUTTONS); ep++) {
                episodeButtons.add(button(String.valueOf(ep), "episode:" + ep));
            }
            for (int i = 0; i < episodeButtons.size(); i += EPISODES_PER_ROW) {
                rows.add(new InlineKeyboardRow(
                        episodeButtons.subList(i, Math.min(i + EPISODES_PER_ROW, episodeButtons.size()))));
            }
        } else {
            rows.add(new InlineKeyboardRow(button(messages.get("episode.manual.button"), "episode:manual")));
        }
        rows.add(cancelRow());

        String label = MessageFormatter.titleLabel(state.title()) + seasonEpisodeSuffix(state);
        state.setStep(ConversationStep.AWAITING_EPISODE_CHOICE);
        saveAndEdit(chatId, state, messages.get("episode.prompt", esc(label)), keyboard(rows));
    }

    private void onEpisodeChosen(long chatId, ConversationState state, String value) {
        if ("manual".equals(value)) {
            state.setStep(ConversationStep.AWAITING_MANUAL_EPISODE);
            saveAndEdit(chatId, state, messages.get("episode.manual.prompt"), cancelOnlyKeyboard());
            return;
        }
        if ("all".equals(value)) {
            state.setEpisode(null);
        } else {
            Integer episode = parsePositiveInt(value);
            if (episode == null) {
                return;
            }
            state.setEpisode(episode);
        }
        showIndexerStep(chatId, state);
    }

    private void onManualEpisodeEntered(long chatId, ConversationState state, String text) {
        Integer episode = parsePositiveInt(text);
        if (episode == null) {
            messenger.sendHtml(chatId, messages.get("episode.manual.invalid"), null);
            return;
        }
        state.setEpisode(episode);
        showIndexerStep(chatId, state);
    }

    private static Integer parsePositiveInt(String text) {
        try {
            int value = Integer.parseInt(text.strip());
            return value >= 1 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code " — Season N — Episode M"} recap suffix; empty for movies or before a season is chosen. */
    private String seasonEpisodeSuffix(ConversationState state) {
        if (!state.title().isTv() || state.season() == null) {
            return "";
        }
        String suffix = " — " + messages.get("recap.season", state.season());
        if (state.episode() != null) {
            suffix += " — " + messages.get("recap.episode", state.episode());
        }
        return suffix;
    }

    /**
     * Lets the user restrict the search to a subset of the indexers configured in
     * Prowlarr, or search all of them (the default, nothing excluded). Skipped when
     * Prowlarr has fewer than two enabled indexers, since there is nothing to choose.
     */
    private void showIndexerStep(long chatId, ConversationState state) {
        List<ProwlarrIndexer> indexers = prowlarrClient.listIndexers();
        state.setIndexers(indexers);
        state.setSelectedIndexerIds(indexers.stream().map(ProwlarrIndexer::id).collect(Collectors.toSet()));
        if (indexers.size() < 2) {
            searchReleasesFor(chatId, state);
            return;
        }
        state.setStep(ConversationStep.AWAITING_INDEXER_CHOICE);
        renderIndexerStep(chatId, state);
    }

    private void renderIndexerStep(long chatId, ConversationState state) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (ProwlarrIndexer indexer : state.indexers()) {
            boolean selected = state.selectedIndexerIds().contains(indexer.id());
            rows.add(new InlineKeyboardRow(button(
                    (selected ? "✅ " : "⬜ ") + MessageFormatter.truncate(indexer.name(), 28),
                    "idx:" + indexer.id())));
        }
        rows.add(new InlineKeyboardRow(button(
                messages.get("indexer.search.button", state.selectedIndexerIds().size()), "idxsearch")));
        rows.add(cancelRow());

        String prompt = messages.get("indexer.prompt");
        if (state.selectedIndexerIds().isEmpty()) {
            prompt = messages.get("indexer.none.selected") + "\n\n" + prompt;
        }
        saveAndEdit(chatId, state, prompt, keyboard(rows));
    }

    private void onIndexerToggled(long chatId, ConversationState state, String value) {
        int indexerId;
        try {
            indexerId = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return;
        }
        Set<Integer> selected = new HashSet<>(state.selectedIndexerIds());
        if (!selected.remove(indexerId)) {
            selected.add(indexerId);
        }
        state.setSelectedIndexerIds(selected);
        renderIndexerStep(chatId, state);
    }

    private void onIndexerSearchConfirmed(long chatId, ConversationState state) {
        if (state.selectedIndexerIds().isEmpty()) {
            renderIndexerStep(chatId, state);
            return;
        }
        searchReleasesFor(chatId, state);
    }

    /**
     * Starts a brand new search for a title we already know unambiguously — used when a
     * stalled-download notification's "retry" button is pressed, long after the original
     * search conversation is gone.
     */
    public void retrySearch(long chatId, TmdbTitle title, Integer season, Integer episode) {
        stateStore.clear(chatId);
        ConversationState state = new ConversationState();
        state.setTitle(title);
        state.setSeason(season);
        state.setEpisode(episode);
        searchReleasesFor(chatId, state);
    }

    private void searchReleasesFor(long chatId, ConversationState state) {
        TmdbTitle title = state.title();
        boolean tv = title.isTv();
        String label = MessageFormatter.titleLabel(title) + seasonEpisodeSuffix(state);
        editFlowMessage(chatId, state, messages.get("search.searching", esc(label)), null);

        List<SearchResult> results = searchIndexers(title, state).stream()
                .map(release -> new SearchResult(release, parser.parse(release.title())))
                // for TV: either the exact episode, or only full packs of the chosen season
                .filter(result -> !tv || state.season() == null
                        || (state.episode() != null
                                ? result.parsed().isEpisode(state.season(), state.episode())
                                : result.parsed().isSeasonPack(state.season())))
                .sorted(Comparator.comparingInt(SearchResult::seeders).reversed())
                .toList();

        if (results.isEmpty()) {
            stateStore.clear(chatId);
            String noneKey = tv && state.season() != null && state.episode() == null
                    ? "search.none.pack"
                    : "search.none.releases";
            editFlowMessage(chatId, state, messages.get(noneKey, esc(label)), null);
            return;
        }
        state.setAllResults(results);
        recomputeFiltered(state);
        showAudioStep(chatId, state);
    }

    /** Rebuilds {@link ConversationState#filtered()} from scratch from {@code allResults}. */
    private void recomputeFiltered(ConversationState state) {
        List<SearchResult> results = state.allResults();
        results = ReleaseFilters.byAudio(results, state.audioFilter());
        results = ReleaseFilters.bySubtitle(results, state.subtitleFilter());
        results = ReleaseFilters.byQuality(results, state.qualityFilter());
        results = ReleaseFilters.byFormat(results, state.formatFilter());
        state.setFiltered(results);
    }

    /**
     * Most scene releases use the original (usually English) title, but Italian indexers
     * often name releases with the localized one ("La rivincita delle bionde" instead of
     * "Legally Blonde") — so when the two differ, both are searched and the results are
     * merged, deduplicated by guid.
     */
    private List<ProwlarrRelease> searchIndexers(TmdbTitle title, ConversationState state) {
        MediaType type = title.isTv() ? MediaType.TV : MediaType.MOVIE;
        Set<Integer> indexerIds = state.selectedIndexerIds();
        List<ProwlarrRelease> results = new ArrayList<>(
                prowlarrClient.search(prowlarrQuery(title, originalName(title)), type, indexerIds));
        if (!originalName(title).equalsIgnoreCase(title.title())) {
            Set<String> seen = results.stream()
                    .map(ConversationHandler::dedupKey)
                    .collect(Collectors.toCollection(HashSet::new));
            prowlarrClient.search(prowlarrQuery(title, title.title()), type, indexerIds).stream()
                    .filter(release -> seen.add(dedupKey(release)))
                    .forEach(results::add);
        }
        return results;
    }

    private static String originalName(TmdbTitle title) {
        return title.originalTitle() != null && !title.originalTitle().isBlank()
                ? title.originalTitle()
                : title.title();
    }

    /** The same release can come back from both queries; the guid identifies it. */
    private static String dedupKey(ProwlarrRelease release) {
        return release.guid() != null ? release.guid() : release.title() + "@" + release.indexer();
    }

    /**
     * For movies the year narrows the search; for TV it is omitted, season packs are
     * tagged with the season rather than the first-air year.
     */
    private String prowlarrQuery(TmdbTitle title, String name) {
        return title.isTv() || title.year() == null ? name : name + " " + title.year();
    }

    // ------------------------------------------------------------------ filter steps

    // Step order: audio first (the deciding factor for an Italian-speaking household),
    // then subtitles, then quality, then format last. All four steps share the same shape
    // (bucket buttons + "any" + cancel, skip when there is nothing to choose), so each one
    // just parameterizes showFilterStep.

    private void showAudioStep(long chatId, ConversationState state) {
        showFilterStep(chatId, state, ReleaseFilters.audioBuckets(state.filtered()), 2,
                Language::label, "audio", "option.any", "step.audio",
                ConversationStep.AWAITING_AUDIO,
                state::setAudioFilter, () -> showSubtitleStep(chatId, state, null), null);
    }

    private void onAudioChosen(long chatId, ConversationState state, String value) {
        Language language = parseFilter(value, Language.class);
        state.setAudioFilter(language);
        recomputeFiltered(state);
        showSubtitleStep(chatId, state, () -> backToAudio(chatId, state));
    }

    private void showSubtitleStep(long chatId, ConversationState state, Runnable backAction) {
        showFilterStep(chatId, state, ReleaseFilters.subtitleBuckets(state.filtered()), 1,
                MessageFormatter::subtitleLabel, "subs", "option.subs.any", "step.subtitles",
                ConversationStep.AWAITING_SUBTITLES,
                state::setSubtitleFilter, () -> showQualityStep(chatId, state, backAction), backAction);
    }

    private void onSubtitlesChosen(long chatId, ConversationState state, String value) {
        // whatever the subtitle step's own "back" currently does, re-used below so that
        // re-showing it later (from quality's back button) points to the same place
        Runnable subtitleBackAction = state.backAction();
        Language language = parseFilter(value, Language.class);
        state.setSubtitleFilter(language);
        recomputeFiltered(state);
        showQualityStep(chatId, state, () -> backToSubtitle(chatId, state, subtitleBackAction));
    }

    private void showQualityStep(long chatId, ConversationState state, Runnable backAction) {
        showFilterStep(chatId, state, ReleaseFilters.qualityBuckets(state.filtered()), 2,
                formatter::qualityLabel, "quality", "option.any", "step.quality",
                ConversationStep.AWAITING_QUALITY,
                state::setQualityFilter, () -> showFormatStep(chatId, state, backAction), backAction);
    }

    private void onQualityChosen(long chatId, ConversationState state, String value) {
        Runnable qualityBackAction = state.backAction();
        Resolution resolution = parseFilter(value, Resolution.class);
        state.setQualityFilter(resolution);
        recomputeFiltered(state);
        showFormatStep(chatId, state, () -> backToQuality(chatId, state, qualityBackAction));
    }

    private void showFormatStep(long chatId, ConversationState state, Runnable backAction) {
        showFilterStep(chatId, state, ReleaseFilters.formatBuckets(state.filtered()), 2,
                formatter::codecLabel, "format", "option.any", "step.format",
                ConversationStep.AWAITING_FORMAT,
                state::setFormatFilter, () -> showShortlist(chatId, state, backAction), backAction);
    }

    private void onFormatChosen(long chatId, ConversationState state, String value) {
        Runnable formatBackAction = state.backAction();
        VideoCodec codec = parseFilter(value, VideoCodec.class);
        state.setFormatFilter(codec);
        recomputeFiltered(state);
        showShortlist(chatId, state, () -> backToFormat(chatId, state, formatBackAction));
    }

    /** "Back" from the subtitle step: forget audio (and anything after) and ask again. */
    private void backToAudio(long chatId, ConversationState state) {
        state.setAudioFilter(null);
        state.setSubtitleFilter(null);
        state.setQualityFilter(null);
        state.setFormatFilter(null);
        recomputeFiltered(state);
        showAudioStep(chatId, state);
    }

    /**
     * "Back" from the quality step: forget subtitles (and anything after) and ask again,
     * re-using whatever back target the subtitle step itself had (so a chain of "back"
     * presses can walk all the way to audio without ever landing on a skipped, invisible
     * step).
     */
    private void backToSubtitle(long chatId, ConversationState state, Runnable subtitleBackAction) {
        state.setSubtitleFilter(null);
        state.setQualityFilter(null);
        state.setFormatFilter(null);
        recomputeFiltered(state);
        showSubtitleStep(chatId, state, subtitleBackAction);
    }

    /** "Back" from the format step: forget quality (and format) and ask again, same idea as above. */
    private void backToQuality(long chatId, ConversationState state, Runnable qualityBackAction) {
        state.setQualityFilter(null);
        state.setFormatFilter(null);
        recomputeFiltered(state);
        showQualityStep(chatId, state, qualityBackAction);
    }

    /** "Back" from the release shortlist: forget format and ask again, same idea as above. */
    private void backToFormat(long chatId, ConversationState state, Runnable formatBackAction) {
        state.setFormatFilter(null);
        recomputeFiltered(state);
        showFormatStep(chatId, state, formatBackAction);
    }

    private void onBack(ConversationState state) {
        Runnable action = state.backAction();
        if (action != null) {
            action.run();
        }
    }

    /**
     * Shows one filter step, or — when the buckets offer fewer than {@code minOptions}
     * real choices — clears that filter and jumps straight to {@code skipTo}. Audio and
     * quality need at least 2 buckets to be worth asking; subtitles are shown even with a
     * single bucket, because "none" (no subtitle tag) is itself a meaningful alternative.
     * {@code backAction}, when non-{@code null}, adds a "back" button that re-does the
     * previous filter step; {@code null} means this step has nothing to go back to.
     */
    private <T extends Enum<T>> void showFilterStep(long chatId, ConversationState state,
                                                    Map<T, Long> buckets, int minOptions,
                                                    Function<T, String> label, String action,
                                                    String anyKey, String promptKey,
                                                    ConversationStep step,
                                                    Consumer<T> setFilter, Runnable skipTo,
                                                    Runnable backAction) {
        if (buckets.size() < minOptions) {
            setFilter.accept(null);
            skipTo.run();
            return;
        }
        List<InlineKeyboardRow> rows = bucketRows(buckets.entrySet().stream()
                .map(e -> button(label.apply(e.getKey()) + " (" + e.getValue() + ")",
                        action + ":" + e.getKey().name()))
                .toList());
        rows.add(new InlineKeyboardRow(button(messages.get(anyKey, state.filtered().size()), action + ":" + ANY)));
        if (backAction != null) {
            rows.add(new InlineKeyboardRow(button(messages.get("filter.back.button"), "back")));
        }
        rows.add(cancelRow());

        state.setBackAction(backAction);
        state.setStep(step);
        saveAndEdit(chatId, state,
                formatter.stepHeader(state) + messages.get(promptKey), keyboard(rows));
    }

    private static <T extends Enum<T>> T parseFilter(String value, Class<T> type) {
        return ANY.equals(value) ? null : Enum.valueOf(type, value);
    }

    // ------------------------------------------------------------------ shortlist & download

    private void showShortlist(long chatId, ConversationState state, Runnable backAction) {
        List<SearchResult> shortlist = state.filtered().stream()
                .limit(MAX_SHORTLIST)
                .toList();
        state.setShortlist(shortlist);

        InlineKeyboardRow numberRow = new InlineKeyboardRow();
        for (int i = 0; i < shortlist.size(); i++) {
            numberRow.add(button(NUMBER_EMOJI[i], "release:" + i));
        }
        List<InlineKeyboardRow> rows = new ArrayList<>(List.of(numberRow));
        if (backAction != null) {
            rows.add(new InlineKeyboardRow(button(messages.get("filter.back.button"), "back")));
        }
        rows.add(cancelRow());

        state.setBackAction(backAction);
        state.setStep(ConversationStep.AWAITING_RELEASE_CHOICE);
        saveAndEdit(chatId, state, formatter.shortlistText(state), keyboard(rows));
    }

    private void onReleaseChosen(long chatId, ConversationState state, int index) {
        if (index < 0) {
            return;
        }
        SearchResult chosen = state.shortlist().get(index);
        TmdbTitle title = state.title();
        boolean tv = title.isTv();
        String plexFolder = PlexRenameService.plexName(title.title(), title.year());
        // movies land straight in the movie root; each show gets its own folder under the
        // TV root: season packs get a "Season NN" rename on completion, single episodes
        // are saved directly into the season folder and keep their scene name
        String savePath = properties.media().rootFolder();
        String plexName = plexFolder + seasonEpisodeSuffix(state);
        if (tv) {
            savePath = properties.media().tvRootFolder() + "/" + plexFolder;
            if (state.season() != null && state.episode() != null) {
                savePath += "/" + PlexRenameService.seasonFolder(state.season());
            }
        }

        qbittorrentClient.addTorrent(
                chosen.release().preferredDownloadUrl(),
                savePath,
                DownloadTracker.QBT_CATEGORY);
        downloadTracker.track(chatId, chosen.release().title(), plexName, title,
                tv ? state.season() : null, tv ? state.episode() : null);
        stateStore.clear(chatId);

        editFlowMessage(chatId, state,
                messages.get("download.sent.title") + "\n\n"
                        + MessageFormatter.icon(title) + " <b>" + esc(plexName) + "</b>\n"
                        + "<code>" + esc(MessageFormatter.truncate(chosen.release().title(), 80)) + "</code>\n\n"
                        + messages.get("download.sent.footer"), null);
        log.info("Chat {} queued download '{}' as '{}'", chatId, chosen.release().title(), plexName);
    }

    // ------------------------------------------------------------------ helpers

    private void whenStep(ConversationState state, ConversationStep expected, Runnable action) {
        if (state.step() == expected) {
            action.run();
        } else {
            log.debug("Ignoring stale callback: state is {}, expected {}", state.step(), expected);
        }
    }

    private int parseIndex(String value, int size) {
        try {
            int index = Integer.parseInt(value);
            return index >= 0 && index < size ? index : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void saveAndEdit(long chatId, ConversationState state, String html, InlineKeyboardMarkup keyboard) {
        stateStore.save(chatId, state);
        editFlowMessage(chatId, state, html, keyboard);
    }

    private void editFlowMessage(long chatId, ConversationState state, String html, InlineKeyboardMarkup keyboard) {
        if (state.messageId() != null) {
            messenger.editHtml(chatId, state.messageId(), html, keyboard);
        } else {
            state.setMessageId(messenger.sendHtml(chatId, html, keyboard));
        }
    }

    private static InlineKeyboardButton button(String text, String callbackData) {
        return InlineKeyboardButton.builder().text(text).callbackData(callbackData).build();
    }

    private InlineKeyboardRow cancelRow() {
        return new InlineKeyboardRow(button(messages.get("cancel.button"), "cancel"));
    }

    private InlineKeyboardMarkup cancelOnlyKeyboard() {
        return keyboard(List.of(cancelRow()));
    }

    /** Lays buttons out three per row. */
    private static List<InlineKeyboardRow> bucketRows(List<InlineKeyboardButton> buttons) {
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += 3) {
            rows.add(new InlineKeyboardRow(buttons.subList(i, Math.min(i + 3, buttons.size()))));
        }
        return rows;
    }

    private static InlineKeyboardMarkup keyboard(List<InlineKeyboardRow> rows) {
        return InlineKeyboardMarkup.builder().keyboard(rows).build();
    }
}
