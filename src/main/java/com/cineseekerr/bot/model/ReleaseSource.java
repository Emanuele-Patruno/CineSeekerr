package com.cineseekerr.bot.model;

/**
 * Source medium of a release, extracted from its name.
 *
 * <p>Italian scene variants are mapped onto the closest standard value:
 * {@code BDMux} → {@link #BDRIP}, {@code DLMux} → {@link #WEB_DL},
 * {@code WEBMux} → {@link #WEBRIP}. A bare {@code REMUX} tag implies {@link #BLURAY}.
 */
public enum ReleaseSource {
    BLURAY("BluRay"),
    BDRIP("BDRip"),
    WEB_DL("WEB-DL"),
    WEBRIP("WEBRip"),
    HDTV("HDTV"),
    DVDRIP("DVDRip"),
    CAM("CAM"),
    TELESYNC("TS"),
    UNKNOWN("?");

    private final String label;

    ReleaseSource(String label) {
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
