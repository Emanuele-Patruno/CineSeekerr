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
import com.cineseekerr.bot.model.QbtTorrent;
import com.cineseekerr.bot.model.Resolution;
import com.cineseekerr.bot.model.SearchResult;
import com.cineseekerr.bot.model.TmdbMovie;
import com.cineseekerr.bot.parser.ReleaseNameParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.cineseekerr.bot.bot.MessageFormatter.NUMBER_EMOJI;
import static com.cineseekerr.bot.bot.MessageFormatter.esc;

/**
 * The conversation state machine, one instance shared by all chats.
 *
 * <p>Flow: free text (or {@code /cerca}) → TMDB candidates → Prowlarr search → dynamic
 * quality/audio/subtitle filters (each computed from the actual result set, steps with a
 * single option are skipped) → top-5 by seeders → send to qBittorrent.
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

    public ConversationHandler(TmdbClient tmdbClient,
                               ProwlarrClient prowlarrClient,
                               QbittorrentClient qbittorrentClient,
                               ReleaseNameParser parser,
                               TelegramMessenger messenger,
                               ConversationStateStore stateStore,
                               DownloadTracker downloadTracker,
                               CineSeekerrProperties properties) {
        this.tmdbClient = tmdbClient;
        this.prowlarrClient = prowlarrClient;
        this.qbittorrentClient = qbittorrentClient;
        this.parser = parser;
        this.messenger = messenger;
        this.stateStore = stateStore;
        this.downloadTracker = downloadTracker;
        this.properties = properties;
    }

    public void onTextMessage(long chatId, String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        try {
            if (trimmed.startsWith("/")) {
                handleCommand(chatId, trimmed);
            } else {
                startSearch(chatId, trimmed);
            }
        } catch (ApiClientException e) {
            log.warn("External service error for chat {}: {}", chatId, e.getMessage(), e);
            messenger.sendHtml(chatId, "⚠️ " + esc(e.getMessage()) + "\nRiprova più tardi.", null);
        }
    }

    public void onCallback(long chatId, int messageId, String callbackQueryId, String data) {
        messenger.answerCallback(callbackQueryId);
        if (data == null || data.isBlank()) {
            return;
        }
        if ("cancel".equals(data)) {
            stateStore.clear(chatId);
            messenger.editHtml(chatId, messageId, "❌ Operazione annullata. Scrivimi un titolo quando vuoi.", null);
            return;
        }

        String[] parts = data.split(":", 2);
        String action = parts[0];
        String value = parts.length > 1 ? parts[1] : "";

        // /stato's "stop download" buttons act on qBittorrent directly and don't belong
        // to the movie-search conversation, so they work with no state on file.
        if ("stopdl".equals(action)) {
            onStopDownload(chatId, messageId, value);
            return;
        }

        ConversationState state = stateStore.find(chatId).orElse(null);
        if (state == null) {
            messenger.editHtml(chatId, messageId,
                    "⌛ Sessione scaduta — scrivimi un nuovo titolo per ricominciare.", null);
            return;
        }

        try {
            switch (action) {
                case "movie" -> whenStep(state, ConversationStep.AWAITING_MOVIE_CHOICE,
                        () -> onMovieChosen(chatId, state, parseIndex(value, state.candidates().size())));
                case "quality" -> whenStep(state, ConversationStep.AWAITING_QUALITY,
                        () -> onQualityChosen(chatId, state, value));
                case "audio" -> whenStep(state, ConversationStep.AWAITING_AUDIO,
                        () -> onAudioChosen(chatId, state, value));
                case "subs" -> whenStep(state, ConversationStep.AWAITING_SUBTITLES,
                        () -> onSubtitlesChosen(chatId, state, value));
                case "release" -> whenStep(state, ConversationStep.AWAITING_RELEASE_CHOICE,
                        () -> onReleaseChosen(chatId, state, parseIndex(value, state.shortlist().size())));
                default -> log.debug("Unknown callback action '{}' from chat {}", action, chatId);
            }
        } catch (ApiClientException e) {
            log.warn("External service error for chat {}: {}", chatId, e.getMessage(), e);
            stateStore.clear(chatId);
            messenger.editHtml(chatId, messageId, "⚠️ " + esc(e.getMessage()) + "\nRiprova più tardi.", null);
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
            case "/start", "/help" -> messenger.sendHtml(chatId, helpText(), null);
            case "/cerca" -> {
                if (args.isEmpty()) {
                    messenger.sendHtml(chatId, "Uso: <code>/cerca titolo del film</code>", null);
                } else {
                    startSearch(chatId, args);
                }
            }
            case "/annulla" -> {
                stateStore.clear(chatId);
                messenger.sendHtml(chatId, "❌ Operazione annullata. Scrivimi un titolo quando vuoi.", null);
            }
            case "/stato" -> sendDownloadStatus(chatId);
            default -> messenger.sendHtml(chatId,
                    "Comando sconosciuto. Usa /help per l'elenco dei comandi.", null);
        }
    }

    private String helpText() {
        return """
                🍿 <b>CineSeekerr</b>
                Scrivimi il titolo di un film e te lo metto su Plex.

                <b>Comandi:</b>
                /cerca &lt;titolo&gt; — cerca un film (o scrivi solo il titolo)
                /stato — download in corso
                /annulla — annulla l'operazione corrente
                /help — questo messaggio""";
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
            messenger.sendHtml(chatId, "Nessun download in corso.", null);
            return;
        }
        StringBuilder sb = new StringBuilder("📥 <b>Download in corso:</b>\n\n");
        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (QbtTorrent torrent : active) {
            sb.append(MessageFormatter.torrentStatusLine(torrent)).append('\n');
            rows.add(new InlineKeyboardRow(button(
                    "❌ Ferma " + MessageFormatter.truncate(torrent.name(), 30), "stopdl:" + torrent.hash())));
        }
        messenger.sendHtml(chatId, sb.toString(), keyboard(rows));
    }

    private void onStopDownload(long chatId, int messageId, String hash) {
        try {
            qbittorrentClient.deleteTorrent(hash, true);
            messenger.editHtml(chatId, messageId, "🗑 Download fermato e rimosso.", null);
            log.info("Chat {} stopped download {}", chatId, hash);
        } catch (ApiClientException e) {
            log.warn("Failed to stop download {} for chat {}: {}", hash, chatId, e.getMessage());
            messenger.editHtml(chatId, messageId, "⚠️ " + esc(e.getMessage()) + "\nRiprova più tardi.", null);
        }
    }

    // ------------------------------------------------------------------ search flow

    private void startSearch(long chatId, String query) {
        stateStore.clear(chatId);
        List<TmdbMovie> candidates = tmdbClient.searchMovies(query).stream()
                .limit(MAX_CANDIDATES)
                .toList();
        if (candidates.isEmpty()) {
            messenger.sendHtml(chatId,
                    "😕 Nessun film trovato per «" + esc(query) + "». Prova con un altro titolo.", null);
            return;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            rows.add(new InlineKeyboardRow(button(
                    NUMBER_EMOJI[i] + " " + MessageFormatter.truncate(MessageFormatter.movieLabel(candidates.get(i)), 34),
                    "movie:" + i)));
        }
        rows.add(cancelRow());

        Integer messageId = messenger.sendHtml(chatId,
                MessageFormatter.candidatesText(candidates), keyboard(rows));

        ConversationState state = new ConversationState();
        state.setStep(ConversationStep.AWAITING_MOVIE_CHOICE);
        state.setCandidates(candidates);
        state.setMessageId(messageId);
        stateStore.save(chatId, state);
    }

    private void onMovieChosen(long chatId, ConversationState state, int index) {
        if (index < 0) {
            return;
        }
        TmdbMovie movie = state.candidates().get(index);
        state.setMovie(movie);
        editFlowMessage(chatId, state,
                "⏳ Cerco release di <b>" + esc(MessageFormatter.movieLabel(movie)) + "</b> sugli indexer…", null);

        List<SearchResult> results = prowlarrClient.search(prowlarrQuery(movie)).stream()
                .map(release -> new SearchResult(release, parser.parse(release.title())))
                .sorted(Comparator.comparingInt(SearchResult::seeders).reversed())
                .toList();

        if (results.isEmpty()) {
            stateStore.clear(chatId);
            editFlowMessage(chatId, state, "😕 Nessuna release trovata per <b>"
                    + esc(MessageFormatter.movieLabel(movie)) + "</b>.", null);
            return;
        }
        state.setFiltered(results);
        showQualityStep(chatId, state);
    }

    /**
     * Indexers respond far better to the original (usually English) title than to the
     * localized TMDB one, so that is what we search for — the localized title is only
     * used for display and for the final Plex folder name.
     */
    private String prowlarrQuery(TmdbMovie movie) {
        String title = movie.originalTitle() != null && !movie.originalTitle().isBlank()
                ? movie.originalTitle()
                : movie.title();
        return movie.year() == null ? title : title + " " + movie.year();
    }

    // ------------------------------------------------------------------ filter steps

    private void showQualityStep(long chatId, ConversationState state) {
        Map<Resolution, Long> buckets = ReleaseFilters.qualityBuckets(state.filtered());
        if (buckets.size() <= 1) {
            state.setQualityFilter(null);
            showAudioStep(chatId, state);
            return;
        }
        List<InlineKeyboardRow> rows = bucketRows(buckets.entrySet().stream()
                .map(e -> button(MessageFormatter.qualityLabel(e.getKey()) + " (" + e.getValue() + ")",
                        "quality:" + e.getKey().name()))
                .toList());
        rows.add(new InlineKeyboardRow(button("Qualsiasi (" + state.filtered().size() + ")", "quality:" + ANY)));
        rows.add(cancelRow());

        state.setStep(ConversationStep.AWAITING_QUALITY);
        saveAndEdit(chatId, state,
                MessageFormatter.stepHeader(state) + "📺 <b>Scegli la qualità:</b>", keyboard(rows));
    }

    private void onQualityChosen(long chatId, ConversationState state, String value) {
        Resolution resolution = ANY.equals(value) ? null : Resolution.valueOf(value);
        state.setQualityFilter(resolution);
        state.setFiltered(ReleaseFilters.byQuality(state.filtered(), resolution));
        showAudioStep(chatId, state);
    }

    private void showAudioStep(long chatId, ConversationState state) {
        Map<Language, Long> buckets = ReleaseFilters.audioBuckets(state.filtered());
        if (buckets.size() <= 1) {
            state.setAudioFilter(null);
            showSubtitleStep(chatId, state);
            return;
        }
        List<InlineKeyboardRow> rows = bucketRows(buckets.entrySet().stream()
                .map(e -> button(e.getKey().label() + " (" + e.getValue() + ")",
                        "audio:" + e.getKey().name()))
                .toList());
        rows.add(new InlineKeyboardRow(button("Qualsiasi (" + state.filtered().size() + ")", "audio:" + ANY)));
        rows.add(cancelRow());

        state.setStep(ConversationStep.AWAITING_AUDIO);
        saveAndEdit(chatId, state,
                MessageFormatter.stepHeader(state) + "🔊 <b>Scegli l'audio:</b>\n"
                        + "<i>ITA include anche le release MULTI/DUAL</i>", keyboard(rows));
    }

    private void onAudioChosen(long chatId, ConversationState state, String value) {
        Language language = ANY.equals(value) ? null : Language.valueOf(value);
        state.setAudioFilter(language);
        state.setFiltered(ReleaseFilters.byAudio(state.filtered(), language));
        showSubtitleStep(chatId, state);
    }

    private void showSubtitleStep(long chatId, ConversationState state) {
        Map<Language, Long> buckets = ReleaseFilters.subtitleBuckets(state.filtered());
        if (buckets.isEmpty()) {
            state.setSubtitleFilter(null);
            showShortlist(chatId, state);
            return;
        }
        List<InlineKeyboardRow> rows = bucketRows(buckets.entrySet().stream()
                .map(e -> button(MessageFormatter.subtitleLabel(e.getKey()) + " (" + e.getValue() + ")",
                        "subs:" + e.getKey().name()))
                .toList());
        rows.add(new InlineKeyboardRow(button("Indifferente (" + state.filtered().size() + ")", "subs:" + ANY)));
        rows.add(cancelRow());

        state.setStep(ConversationStep.AWAITING_SUBTITLES);
        saveAndEdit(chatId, state,
                MessageFormatter.stepHeader(state) + "💬 <b>Sottotitoli:</b>", keyboard(rows));
    }

    private void onSubtitlesChosen(long chatId, ConversationState state, String value) {
        Language language = ANY.equals(value) ? null : Language.valueOf(value);
        state.setSubtitleFilter(language);
        state.setFiltered(ReleaseFilters.bySubtitle(state.filtered(), language));
        showShortlist(chatId, state);
    }

    // ------------------------------------------------------------------ shortlist & download

    private void showShortlist(long chatId, ConversationState state) {
        List<SearchResult> shortlist = state.filtered().stream()
                .limit(MAX_SHORTLIST)
                .toList();
        state.setShortlist(shortlist);

        InlineKeyboardRow numberRow = new InlineKeyboardRow();
        for (int i = 0; i < shortlist.size(); i++) {
            numberRow.add(button(NUMBER_EMOJI[i], "release:" + i));
        }
        List<InlineKeyboardRow> rows = new ArrayList<>(List.of(numberRow));
        rows.add(cancelRow());

        state.setStep(ConversationStep.AWAITING_RELEASE_CHOICE);
        saveAndEdit(chatId, state, MessageFormatter.shortlistText(state), keyboard(rows));
    }

    private void onReleaseChosen(long chatId, ConversationState state, int index) {
        if (index < 0) {
            return;
        }
        SearchResult chosen = state.shortlist().get(index);
        String plexName = PlexRenameService.plexName(state.movie().title(), state.movie().year());

        qbittorrentClient.addTorrent(
                chosen.release().preferredDownloadUrl(),
                properties.media().rootFolder(),
                DownloadTracker.QBT_CATEGORY);
        downloadTracker.track(chatId, chosen.release().title(), plexName);
        stateStore.clear(chatId);

        editFlowMessage(chatId, state,
                "✅ Torrent inviato a qBittorrent!\n\n"
                        + "🎬 <b>" + esc(plexName) + "</b>\n"
                        + "<code>" + esc(MessageFormatter.truncate(chosen.release().title(), 80)) + "</code>\n\n"
                        + "Ti avviso appena è pronto su Plex. Usa /stato per seguire il download.", null);
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

    private static InlineKeyboardRow cancelRow() {
        return new InlineKeyboardRow(button("❌ Annulla", "cancel"));
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
