package com.cineseekerr.bot.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A movie or TV series candidate returned by the TMDB {@code /search/multi} API, used to
 * disambiguate the user's query before searching indexers.
 *
 * <p>TMDB names the fields differently for the two media types ({@code title} vs
 * {@code name}, {@code release_date} vs {@code first_air_date}); the aliases fold both
 * shapes into one record.
 *
 * <p>{@code media_type} is kept as a raw string rather than an enum: {@code /search/multi}
 * can return kinds we don't otherwise handle (e.g. {@code collection}), and a hard failure
 * to deserialize one of them would break the whole response instead of just letting that
 * one result be filtered out.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TmdbTitle(
        int id,
        @JsonProperty("media_type") String mediaType,
        @JsonAlias("name") String title,
        @JsonProperty("original_title") @JsonAlias("original_name") String originalTitle,
        @JsonProperty("release_date") @JsonAlias("first_air_date") String releaseDate,
        @JsonProperty("poster_path") String posterPath,
        String overview) {

    private static final String POSTER_BASE_URL = "https://image.tmdb.org/t/p/w342";

    public boolean isTv() {
        return "tv".equals(mediaType);
    }

    public boolean isMovie() {
        return "movie".equals(mediaType);
    }

    /**
     * TMDB's dedicated {@code /search/movie} and {@code /search/tv} endpoints (unlike
     * {@code /search/multi}) don't include a {@code media_type} field since the type is
     * implicit in which endpoint was called — this fills it in after the fact.
     */
    public TmdbTitle withMediaType(String mediaType) {
        return new TmdbTitle(id, mediaType, title, originalTitle, releaseDate, posterPath, overview);
    }

    /** Release (or first-air) year, or {@code null} when TMDB has no date. */
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
