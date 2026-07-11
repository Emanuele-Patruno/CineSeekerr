package com.cineseekerr.bot.bot.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Thin wrapper around {@link TelegramClient} so the state machine can be unit-tested
 * without touching the Telegram API. Failures are logged, never propagated: a lost
 * message must not corrupt the conversation state.
 */
@Component
public class TelegramMessenger {

    private static final Logger log = LoggerFactory.getLogger(TelegramMessenger.class);

    private final TelegramClient telegramClient;

    public TelegramMessenger(TelegramClient telegramClient) {
        this.telegramClient = telegramClient;
    }

    /** Sends an HTML message and returns its id, or {@code null} if sending failed. */
    public Integer sendHtml(long chatId, String html, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(html)
                .parseMode(ParseMode.HTML)
                .replyMarkup(keyboard)
                .build();
        try {
            Message sent = telegramClient.execute(message);
            return sent.getMessageId();
        } catch (TelegramApiException e) {
            log.error("Failed to send message to chat {}", chatId, e);
            return null;
        }
    }

    /** Edits an existing message in place — the bot never spams new messages per step. */
    public void editHtml(long chatId, int messageId, String html, InlineKeyboardMarkup keyboard) {
        EditMessageText edit = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(html)
                .parseMode(ParseMode.HTML)
                .replyMarkup(keyboard)
                .build();
        try {
            telegramClient.execute(edit);
        } catch (TelegramApiException e) {
            log.warn("Failed to edit message {} in chat {}: {}", messageId, chatId, e.getMessage());
        }
    }

    /** Always answer callback queries, or the user's client shows a spinner forever. */
    public void answerCallback(String callbackQueryId) {
        try {
            telegramClient.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to answer callback query {}: {}", callbackQueryId, e.getMessage());
        }
    }
}
