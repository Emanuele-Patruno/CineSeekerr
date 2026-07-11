package com.cineseekerr.bot.bot.state;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Default {@link ConversationStateStore}: a plain concurrent map, lost on restart. */
@Component
public class InMemoryConversationStateStore implements ConversationStateStore {

    private final Map<Long, ConversationState> states = new ConcurrentHashMap<>();

    @Override
    public Optional<ConversationState> find(long chatId) {
        return Optional.ofNullable(states.get(chatId));
    }

    @Override
    public void save(long chatId, ConversationState state) {
        states.put(chatId, state);
    }

    @Override
    public void clear(long chatId) {
        states.remove(chatId);
    }
}
