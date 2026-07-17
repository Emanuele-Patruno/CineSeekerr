package com.cineseekerr.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * Typed view of all {@code cineseekerr.*} configuration. Every value is ultimately sourced
 * from environment variables (see {@code application.yml} and the README).
 */
@ConfigurationProperties(prefix = "cineseekerr")
public record CineSeekerrProperties(
        Telegram telegram,
        Tmdb tmdb,
        Prowlarr prowlarr,
        Qbittorrent qbittorrent,
        Media media,
        /** UI language of the bot's Telegram messages ({@code en} or {@code it}). */
        String language) {

    public CineSeekerrProperties {
        if (language == null || language.isBlank()) {
            language = "en";
        }
    }

    /**
     * @param botToken       token issued by BotFather
     * @param allowedChatIds whitelist of Telegram chat IDs; updates from any other chat
     *                       are silently ignored
     */
    public record Telegram(String botToken, Set<Long> allowedChatIds) {
        public Telegram {
            allowedChatIds = allowedChatIds == null ? Set.of() : Set.copyOf(allowedChatIds);
        }
    }

    /**
     * @param apiKey   either a TMDB v3 API key or a v4 API Read Access Token (JWT);
     *                 the client auto-detects which one it is
     * @param language language for titles and overviews, e.g. {@code it-IT}
     */
    public record Tmdb(String apiKey, String baseUrl, String language) {
        public Tmdb {
            if (language == null || language.isBlank()) {
                language = "it-IT";
            }
        }
    }

    public record Prowlarr(String baseUrl, String apiKey) {
    }

    public record Qbittorrent(String baseUrl, String username, String password) {
    }

    /**
     * @param rootFolder   save path handed to qBittorrent for movies; completed downloads
     *                     are renamed to {@code Title (Year)} inside it so Plex matches them
     * @param tvRootFolder save path for TV series; each season pack is saved under
     *                     {@code Show (Year)/} and renamed to {@code Season NN} inside it
     */
    public record Media(String rootFolder, String tvRootFolder) {
    }
}
