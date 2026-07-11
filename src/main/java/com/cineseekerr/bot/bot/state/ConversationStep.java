package com.cineseekerr.bot.bot.state;

/** The step a chat's conversation is currently waiting on. */
public enum ConversationStep {
    AWAITING_MOVIE_CHOICE,
    AWAITING_QUALITY,
    AWAITING_AUDIO,
    AWAITING_SUBTITLES,
    AWAITING_RELEASE_CHOICE
}
