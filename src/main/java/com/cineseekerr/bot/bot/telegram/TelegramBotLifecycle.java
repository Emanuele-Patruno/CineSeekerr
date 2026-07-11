package com.cineseekerr.bot.bot.telegram;

import com.cineseekerr.bot.config.CineSeekerrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Starts/stops the Telegram long-polling session with the Spring context. When
 * {@code TELEGRAM_BOT_TOKEN} is missing the application still boots (with a loud warning)
 * — handy for local development and tests.
 */
@Component
public class TelegramBotLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotLifecycle.class);

    private final CineSeekerrProperties properties;
    private final CineSeekerrBot bot;

    private TelegramBotsLongPollingApplication botsApplication;
    private volatile boolean running;

    public TelegramBotLifecycle(CineSeekerrProperties properties, CineSeekerrBot bot) {
        this.properties = properties;
        this.bot = bot;
    }

    @Override
    public void start() {
        String token = properties.telegram().botToken();
        if (token == null || token.isBlank()) {
            log.warn("TELEGRAM_BOT_TOKEN is not set — Telegram polling is DISABLED");
            return;
        }
        if (properties.telegram().allowedChatIds().isEmpty()) {
            log.warn("TELEGRAM_ALLOWED_CHAT_IDS is empty — the bot will ignore every message");
        }
        try {
            botsApplication = new TelegramBotsLongPollingApplication();
            botsApplication.registerBot(token, bot);
            running = true;
            log.info("Telegram long polling started");
        } catch (TelegramApiException e) {
            throw new IllegalStateException("Could not start the Telegram bot", e);
        }
    }

    @Override
    public void stop() {
        if (botsApplication != null) {
            try {
                botsApplication.close();
            } catch (Exception e) {
                log.warn("Error while closing the Telegram polling session", e);
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
