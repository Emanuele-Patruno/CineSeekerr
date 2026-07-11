package com.cineseekerr.bot.model;

/**
 * Video resolution extracted from a release name.
 *
 * <p>Interlaced variants (e.g. {@code 1080i}) are mapped to the same bucket as their
 * progressive counterpart, since for filtering purposes the distinction is irrelevant.
 */
public enum Resolution {
    R720P("720p"),
    R1080P("1080p"),
    R2160P("2160p"),
    UNKNOWN("?");

    private final String label;

    Resolution(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
