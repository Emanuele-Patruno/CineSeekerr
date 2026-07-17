package com.cineseekerr.bot.bot;

import com.cineseekerr.bot.config.CineSeekerrProperties;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Resolves the bot's user-facing texts from {@code messages*.properties} in the language
 * configured via {@code BOT_LANGUAGE}. English ({@code messages.properties}) is the base
 * bundle and the fallback for untranslated languages.
 */
@Component
public class Messages {

    private final ResourceBundleMessageSource source;
    private final Locale locale;

    public Messages(CineSeekerrProperties properties) {
        this.source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        // never fall back to the JVM's locale: BOT_LANGUAGE is the only selector
        source.setFallbackToSystemLocale(false);
        this.locale = Locale.forLanguageTag(properties.language());
    }

    /**
     * The text for {@code key}, with {@code {0}}-style placeholders replaced by
     * {@code args}. Keys are static and kept in sync with the bundles, so a missing key is
     * a programming error and throws.
     */
    public String get(String key, Object... args) {
        return source.getMessage(key, args, locale);
    }
}
