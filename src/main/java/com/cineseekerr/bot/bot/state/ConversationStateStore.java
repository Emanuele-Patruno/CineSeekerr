package com.cineseekerr.bot.bot.state;

import java.util.Optional;

/**
 * Storage for per-chat conversation state. The default implementation is in-memory;
 * the abstraction exists so a Redis-backed implementation can be dropped in without
 * touching the state machine.
 */
public interface ConversationStateStore {

    Optional<ConversationState> find(long chatId);

    void save(long chatId, ConversationState state);

    void clear(long chatId);
}
