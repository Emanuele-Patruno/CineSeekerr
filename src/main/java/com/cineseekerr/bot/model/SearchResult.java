package com.cineseekerr.bot.model;

/**
 * A Prowlarr release paired with the attributes parsed from its name — the unit the bot
 * filters, ranks and finally sends to qBittorrent.
 */
public record SearchResult(ProwlarrRelease release, ParsedRelease parsed) {

    public int seeders() {
        return release.seedersOrZero();
    }
}
