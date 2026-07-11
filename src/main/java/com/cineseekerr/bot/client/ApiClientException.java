package com.cineseekerr.bot.client;

/**
 * Raised when a call to an external service (TMDB, Prowlarr, qBittorrent) fails.
 * The message is safe to surface to the Telegram user; the cause carries the details
 * for the logs.
 */
public class ApiClientException extends RuntimeException {

    public ApiClientException(String message) {
        super(message);
    }

    public ApiClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
