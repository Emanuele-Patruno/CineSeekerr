package com.cineseekerr.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A season entry from the TMDB {@code /tv/{id}} details endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbSeason(
        @JsonProperty("season_number") int seasonNumber,
        @JsonProperty("episode_count") int episodeCount) {
}
