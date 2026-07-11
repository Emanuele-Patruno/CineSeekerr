package com.cineseekerr.bot.model;

/**
 * Video codec extracted from a release name. Synonyms are collapsed into a single value:
 * H.264/AVC → {@link #X264}, H.265/HEVC → {@link #X265}, DivX → {@link #XVID}.
 */
public enum VideoCodec {
    X264("x264"),
    X265("x265"),
    XVID("XviD"),
    AV1("AV1"),
    UNKNOWN("?");

    private final String label;

    VideoCodec(String label) {
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
