package com.cineseekerr.bot.model;

/**
 * Audio/subtitle language tag extracted from a release name.
 *
 * <p>{@link #MULTI} and {@link #DUAL} are not real languages but common scene tags meaning
 * "multiple audio tracks" / "two audio tracks". They are modelled as peer values because the
 * bot presents them as filter buckets alongside actual languages
 * (e.g. {@code Audio: [ITA (8)] [ENG (10)] [MULTI (5)]}). They are only ever used for audio,
 * never for subtitles.
 */
public enum Language {
    ITA("ITA"),
    ENG("ENG"),
    FRE("FRE"),
    GER("GER"),
    SPA("SPA"),
    MULTI("MULTI"),
    DUAL("DUAL");

    private final String label;

    Language(String label) {
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
