package com.cineseekerr.bot.bot.telegram;

import com.cineseekerr.bot.bot.ConversationHandler;
import com.cineseekerr.bot.bot.Messages;
import com.cineseekerr.bot.config.CineSeekerrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.Set;

/**
 * Entry point for Telegram updates: enforces the chat whitelist and dispatches to the
 * {@link ConversationHandler}. Updates from chats outside
 * {@code TELEGRAM_ALLOWED_CHAT_IDS} are ignored without any reply, so the bot is
 * invisible to strangers.
 */
@Component
public class CineSeekerrBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(CineSeekerrBot.class);

    private final ConversationHandler handler;
    private final TelegramMessenger messenger;
    private final Messages messages;
    private final Set<Long> allowedChatIds;

    public CineSeekerrBot(ConversationHandler handler,
                        TelegramMessenger messenger,
                        CineSeekerrProperties properties,
                        Messages messages) {
        this.handler = handler;
        this.messenger = messenger;
        this.messages = messages;
        this.allowedChatIds = properties.telegram().allowedChatIds();
    }

    @Override
    public void consume(Update update) {
        Long chatId = chatIdOf(update);
        if (chatId == null) {
            return;
        }
        if (!allowedChatIds.contains(chatId)) {
            log.info("Ignoring update from unauthorized chat {}", chatId);
            return;
        }
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                handler.onTextMessage(chatId, update.getMessage().getText());
            } else if (update.hasCallbackQuery()) {
                CallbackQuery callback = update.getCallbackQuery();
                handler.onCallback(chatId, callback.getMessage().getMessageId(),
                        callback.getId(), callback.getData());
            }
        } catch (RuntimeException e) {
            log.error("Unexpected error handling update for chat {}", chatId, e);
            messenger.sendHtml(chatId, messages.get("error.unexpected"), null);
        }
    }

    private Long chatIdOf(Update update) {
        if (update.hasMessage()) {
            return update.getMessage().getChatId();
        }
        if (update.hasCallbackQuery()) {
            return update.getCallbackQuery().getMessage().getChatId();
        }
        return null;
    }
}
