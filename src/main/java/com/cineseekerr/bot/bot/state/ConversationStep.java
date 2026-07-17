package com.cineseekerr.bot.bot.state;

/** The step a chat's conversation is currently waiting on. */
public enum ConversationStep {
    AWAITING_MOVIE_CHOICE,
    AWAITING_SEASON_CHOICE,
    AWAITING_MANUAL_SEASON,
    AWAITING_EPISODE_CHOICE,
    AWAITING_MANUAL_EPISODE,
    AWAITING_AUDIO,
    AWAITING_SUBTITLES,
    AWAITING_QUALITY,
    AWAITING_RELEASE_CHOICE
}
