package com.cineseekerr.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A single indexer configured in Prowlarr, as returned by {@code /api/v1/indexer}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProwlarrIndexer(int id, String name, boolean enable) {
}
