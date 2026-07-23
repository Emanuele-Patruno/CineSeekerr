package com.cineseekerr.bot.bot;

import com.cineseekerr.bot.bot.state.ConversationState;
import com.cineseekerr.bot.model.Language;
import com.cineseekerr.bot.model.ParsedRelease;
import com.cineseekerr.bot.model.QbtTorrent;
import com.cineseekerr.bot.model.ReleaseSource;
import com.cineseekerr.bot.model.Resolution;
import com.cineseekerr.bot.model.SearchResult;
import com.cineseekerr.bot.model.TmdbTitle;
import com.cineseekerr.bot.model.VideoCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Builds the HTML texts the bot sends, in the language configured via
 * {@link Messages}. All user-controlled content goes through {@link #esc}.
 */
@Component
public class MessageFormatter {

    static final String[] NUMBER_EMOJI = {"1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣"};

    private final Messages messages;

    public MessageFormatter(Messages messages) {
        this.messages = messages;
    }

    public static String esc(String text) {
        return text == null ? "" : text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    static String titleLabel(TmdbTitle title) {
        return title.year() == null ? title.title() : title.title() + " (" + title.year() + ")";
    }

    /** 📺 for TV series, 🎬 for movies. */
    static String icon(TmdbTitle title) {
        return title.isTv() ? "📺" : "🎬";
    }

    String candidatesText(List<TmdbTitle> candidates) {
        StringBuilder sb = new StringBuilder(messages.get("candidates.header")).append("\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            TmdbTitle title = candidates.get(i);
            sb.append(NUMBER_EMOJI[i]).append(' ').append(icon(title)).append(' ')
                    .append("<b>").append(esc(titleLabel(title))).append("</b>");
            if (title.posterUrl() != null) {
                sb.append(" <a href=\"").append(title.posterUrl()).append("\">🖼</a>");
            }
            String overview = title.overview();
            if (overview != null && !overview.isBlank()) {
                sb.append('\n').append("<i>").append(esc(truncate(overview, 120))).append("</i>");
            }
            sb.append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    String qualityLabel(Resolution resolution) {
        return resolution == Resolution.UNKNOWN ? messages.get("quality.other") : resolution.label();
    }

    String codecLabel(VideoCodec codec) {
        return codec == VideoCodec.UNKNOWN ? messages.get("format.other") : codec.label();
    }

    static String subtitleLabel(Language language) {
        return "SUB " + language.label();
    }

    /** e.g. {@code "ITA · SUB ITA · 2160p"} (same order as the filter steps) or {@code "no filters"}. */
    String filterRecap(ConversationState state) {
        List<String> parts = new ArrayList<>();
        if (state.audioFilter() != null) {
            parts.add(state.audioFilter().label());
        }
        if (state.subtitleFilter() != null) {
            parts.add(subtitleLabel(state.subtitleFilter()));
        }
        if (state.qualityFilter() != null) {
            parts.add(qualityLabel(state.qualityFilter()));
        }
        if (state.formatFilter() != null) {
            parts.add(codecLabel(state.formatFilter()));
        }
        return parts.isEmpty() ? messages.get("recap.none") : String.join(" · ", parts);
    }

    String stepHeader(ConversationState state) {
        String label = titleLabel(state.title());
        if (state.season() != null) {
            label += " — " + messages.get("recap.season", state.season());
        }
        if (state.episode() != null) {
            label += " — " + messages.get("recap.episode", state.episode());
        }
        return icon(state.title()) + " <b>" + esc(label) + "</b> — "
                + messages.get("step.header.releases", state.filtered().size(), filterRecap(state))
                + "\n\n";
    }

    String releaseSummary(SearchResult result) {
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
                ? messages.get("release.audio.unknown")
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

    String shortlistText(ConversationState state) {
        StringBuilder sb = new StringBuilder(stepHeader(state))
                .append(messages.get("shortlist.header")).append("\n\n");
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
        sb.append(messages.get("shortlist.prompt"));
        return sb.toString();
    }

    String torrentStatusLine(QbtTorrent torrent) {
        String name = truncate(torrent.name(), 60);
        if (torrent.isComplete()) {
            return messages.get("status.line.done", esc(name));
        }
        int percent = (int) Math.floor(torrent.progress() * 100);
        long speed = torrent.dlspeed() == null ? 0 : torrent.dlspeed();
        return messages.get("status.line.progress", esc(name), String.valueOf(percent),
                humanSize(speed), humanEta(torrent.eta()));
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

    /** e.g. {@code "2h 15m"} or {@code "40m"}. */
    public static String humanDuration(Duration duration) {
        long totalMinutes = Math.max(0, duration.toMinutes());
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m";
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
