package com.cineseekerr.bot.bot.state;

import com.cineseekerr.bot.model.Language;
import com.cineseekerr.bot.model.Resolution;
import com.cineseekerr.bot.model.SearchResult;
import com.cineseekerr.bot.model.TmdbMovie;

import java.util.List;

/**
 * Mutable per-chat conversation state. Instances are only ever touched by the single
 * update-consumer thread, so no internal synchronization is needed; the store that holds
 * them is what must be thread-safe.
 */
public final class ConversationState {

    private ConversationStep step = ConversationStep.AWAITING_MOVIE_CHOICE;
    private List<TmdbMovie> candidates = List.of();
    private TmdbMovie movie;
    /** Working set of releases, progressively narrowed by each filter step. */
    private List<SearchResult> filtered = List.of();
    private Resolution qualityFilter;
    private Language audioFilter;
    private Language subtitleFilter;
    private List<SearchResult> shortlist = List.of();
    /** Telegram message the bot keeps editing instead of sending new ones. */
    private Integer messageId;

    public ConversationStep step() {
        return step;
    }

    public void setStep(ConversationStep step) {
        this.step = step;
    }

    public List<TmdbMovie> candidates() {
        return candidates;
    }

    public void setCandidates(List<TmdbMovie> candidates) {
        this.candidates = List.copyOf(candidates);
    }

    public TmdbMovie movie() {
        return movie;
    }

    public void setMovie(TmdbMovie movie) {
        this.movie = movie;
    }

    public List<SearchResult> filtered() {
        return filtered;
    }

    public void setFiltered(List<SearchResult> filtered) {
        this.filtered = List.copyOf(filtered);
    }

    public Resolution qualityFilter() {
        return qualityFilter;
    }

    public void setQualityFilter(Resolution qualityFilter) {
        this.qualityFilter = qualityFilter;
    }

    public Language audioFilter() {
        return audioFilter;
    }

    public void setAudioFilter(Language audioFilter) {
        this.audioFilter = audioFilter;
    }

    public Language subtitleFilter() {
        return subtitleFilter;
    }

    public void setSubtitleFilter(Language subtitleFilter) {
        this.subtitleFilter = subtitleFilter;
    }

    public List<SearchResult> shortlist() {
        return shortlist;
    }

    public void setShortlist(List<SearchResult> shortlist) {
        this.shortlist = List.copyOf(shortlist);
    }

    public Integer messageId() {
        return messageId;
    }

    public void setMessageId(Integer messageId) {
        this.messageId = messageId;
    }
}
