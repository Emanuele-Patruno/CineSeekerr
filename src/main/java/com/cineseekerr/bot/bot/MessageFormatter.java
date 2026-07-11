package com.cineseekerr.bot.bot;

import com.cineseekerr.bot.bot.state.ConversationState;
import com.cineseekerr.bot.model.Language;
import com.cineseekerr.bot.model.ParsedRelease;
import com.cineseekerr.bot.model.QbtTorrent;
import com.cineseekerr.bot.model.ReleaseSource;
import com.cineseekerr.bot.model.Resolution;
import com.cineseekerr.bot.model.SearchResult;
import com.cineseekerr.bot.model.TmdbMovie;
import com.cineseekerr.bot.model.VideoCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Builds the HTML texts the bot sends. All user-controlled content goes through {@link #esc}. */
public final class MessageFormatter {

    static final String[] NUMBER_EMOJI = {"1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣"};

    private MessageFormatter() {
    }

    public static String esc(String text) {
        return text == null ? "" : text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    static String movieLabel(TmdbMovie movie) {
        return movie.year() == null ? movie.title() : movie.title() + " (" + movie.year() + ")";
    }

    static String candidatesText(List<TmdbMovie> candidates) {
        StringBuilder sb = new StringBuilder("🎬 <b>Quale film intendi?</b>\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            TmdbMovie movie = candidates.get(i);
            sb.append(NUMBER_EMOJI[i]).append(' ').append("<b>").append(esc(movieLabel(movie))).append("</b>");
            if (movie.posterUrl() != null) {
                sb.append(" <a href=\"").append(movie.posterUrl()).append("\">🖼</a>");
            }
            String overview = movie.overview();
            if (overview != null && !overview.isBlank()) {
                sb.append('\n').append("<i>").append(esc(truncate(overview, 120))).append("</i>");
            }
            sb.append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    static String qualityLabel(Resolution resolution) {
        return resolution == Resolution.UNKNOWN ? "Altro" : resolution.label();
    }

    static String subtitleLabel(Language language) {
        return "SUB " + language.label();
    }

    /** e.g. {@code "2160p · ITA · SUB ITA"} or {@code "nessun filtro"}. */
    static String filterRecap(ConversationState state) {
        List<String> parts = new ArrayList<>();
        if (state.qualityFilter() != null) {
            parts.add(qualityLabel(state.qualityFilter()));
        }
        if (state.audioFilter() != null) {
            parts.add(state.audioFilter().label());
        }
        if (state.subtitleFilter() != null) {
            parts.add(subtitleLabel(state.subtitleFilter()));
        }
        return parts.isEmpty() ? "nessun filtro" : String.join(" · ", parts);
    }

    static String stepHeader(ConversationState state) {
        return "🎬 <b>" + esc(movieLabel(state.movie())) + "</b> — "
                + state.filtered().size() + " release (" + filterRecap(state) + ")\n\n";
    }

    static String releaseSummary(SearchResult result) {
        ParsedRelease parsed = result.parsed();
        List<String> tech = new ArrayList<>();
        if (parsed.resolution() != Resolution.UNKNOWN) {
            tech.add(parsed.resolution().label());
        }
        if (parsed.source() != ReleaseSource.UNKNOWN) {
            tech.add(parsed.source().label());
        }
        if (parsed.codec() != VideoCodec.UNKNOWN) {
            tech.add(parsed.codec().label());
        }
        String audio = parsed.audioLanguages().isEmpty()
                ? "audio ?"
                : parsed.audioLanguages().stream().map(Language::label).collect(Collectors.joining(" "));
        tech.add("🔊 " + audio);
        if (parsed.subtitled()) {
            String subs = parsed.subtitleLanguages().isEmpty()
                    ? "SUB"
                    : parsed.subtitleLanguages().stream()
                            .map(MessageFormatter::subtitleLabel)
                            .collect(Collectors.joining(" "));
            tech.add("💬 " + subs);
        }
        return String.join(" · ", tech);
    }

    static String shortlistText(ConversationState state) {
        StringBuilder sb = new StringBuilder(stepHeader(state))
                .append("🎯 <b>Migliori release per seeders:</b>\n\n");
        List<SearchResult> shortlist = state.shortlist();
        for (int i = 0; i < shortlist.size(); i++) {
            SearchResult result = shortlist.get(i);
            sb.append(NUMBER_EMOJI[i]).append(' ')
                    .append("👤 ").append(result.seeders())
                    .append(" · 📦 ").append(humanSize(result.release().sizeOrZero()))
                    .append(" · 🏷 ").append(esc(result.release().indexer()))
                    .append('\n')
                    .append(releaseSummary(result))
                    .append('\n')
                    .append("<code>").append(esc(truncate(result.release().title(), 80))).append("</code>")
                    .append("\n\n");
        }
        sb.append("Quale scarico?");
        return sb.toString();
    }

    static String torrentStatusLine(QbtTorrent torrent) {
        String name = truncate(torrent.name(), 60);
        if (torrent.isComplete()) {
            return "✅ <b>" + esc(name) + "</b> — completato";
        }
        int percent = (int) Math.floor(torrent.progress() * 100);
        long speed = torrent.dlspeed() == null ? 0 : torrent.dlspeed();
        return "⬇️ <b>" + esc(name) + "</b> — " + percent + "% ("
                + humanSize(speed) + "/s, ETA " + humanEta(torrent.eta()) + ")";
    }

    static String humanSize(long bytes) {
        if (bytes >= 1L << 30) {
            return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        }
        if (bytes >= 1L << 20) {
            return String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
        }
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
    }

    /** qBittorrent reports 8640000 seconds when the ETA is unknown. */
    static String humanEta(Long etaSeconds) {
        if (etaSeconds == null || etaSeconds >= 8_640_000L || etaSeconds < 0) {
            return "?";
        }
        long hours = etaSeconds / 3600;
        long minutes = (etaSeconds % 3600) / 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes > 0 ? minutes + "m" : etaSeconds + "s";
    }

    static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength - 1) + "…";
    }
}
