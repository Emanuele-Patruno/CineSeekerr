package com.cineseekerr.bot.config;

import com.cineseekerr.bot.parser.ReleaseNameParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration
@EnableScheduling
public class AppConfig {

    /** The parser is a pure class with no Spring dependency, registered here as a bean. */
    @Bean
    public ReleaseNameParser releaseNameParser() {
        return new ReleaseNameParser();
    }

    @Bean
    public TelegramClient telegramClient(CineSeekerrProperties properties) {
        String token = properties.telegram().botToken();
        // With no token the client is never used (polling is disabled), but the bean
        // must still exist for the context to wire up.
        return new OkHttpTelegramClient(token == null || token.isBlank() ? "unset" : token);
    }
}
