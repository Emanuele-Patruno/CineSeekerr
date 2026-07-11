package com.cineseekerr.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A movie candidate returned by the TMDB search API, used to disambiguate the user's query
 * before searching indexers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbMovie(
        int id,
        String title,
        @JsonProperty("original_title") String originalTitle,
        @JsonProperty("release_date") String releaseDate,
        @JsonProperty("poster_path") String posterPath,
        String overview) {

    private static final String POSTER_BASE_URL = "https://image.tmdb.org/t/p/w342";

    /** Release year, or {@code null} when TMDB has no release date. */
    public Integer year() {
        if (releaseDate == null || releaseDate.length() < 4) {
            return null;
        }
        try {
            return Integer.parseInt(releaseDate.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Full poster URL, or {@code null} when TMDB has no poster. */
    public String posterUrl() {
        return posterPath == null || posterPath.isBlank() ? null : POSTER_BASE_URL + posterPath;
    }
}
