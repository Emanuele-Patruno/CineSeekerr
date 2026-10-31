package com.cineseekerr.bot.bot.download;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers, per TMDB title, the release author the user last downloaded from, so the
 * shortlist can offer it again for the next episode or season. Mirrored to a JSON file next
 * to the pending-downloads file (same volume, no extra setting) and reloaded on startup.
 */
@Component
public class PreferredAuthors {

    private static final Logger log = LoggerFactory.getLogger(PreferredAuthors.class);
    private static final String FILE_NAME = "preferred-authors.json";

    private final Map<Integer, String> byTitle = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path file;

    public PreferredAuthors(ObjectMapper objectMapper,
                            @Value("${cineseekerr.download.state-file:/data/pending-downloads.json}") String stateFile) {
        this.objectMapper = objectMapper;
        this.file = Path.of(stateFile).resolveSibling(FILE_NAME);
        load();
    }

    public Optional<String> find(int tmdbId) {
        return Optional.ofNullable(byTitle.get(tmdbId));
    }

    public void remember(int tmdbId, String author) {
        if (author == null || author.isBlank() || author.strip().equals(byTitle.get(tmdbId))) {
            return;
        }
        byTitle.put(tmdbId, author.strip());
        persist();
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            byTitle.putAll(objectMapper.readValue(file.toFile(), new TypeReference<Map<Integer, String>>() {
            }));
        } catch (IOException e) {
            log.warn("Could not read preferred authors from {}, starting empty: {}", file, e.getMessage());
        }
    }

    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(file.toFile(), Map.copyOf(byTitle));
        } catch (IOException e) {
            log.warn("Could not persist preferred authors to {}: {}", file, e.getMessage());
        }
    }
}
