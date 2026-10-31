package com.cineseekerr.bot.bot;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the release group from a release title when it follows the anime convention of a
 * bracketed name at the very start ({@code [Group] Show - 01 [1080p]...}). Prowlarr exposes no
 * uploader field, and scene-style titles put the group in too many places to guess, so this
 * deliberately recognises only the one layout that is reliable.
 */
public final class ReleaseGroups {

    private static final Pattern LEADING_BRACKETS = Pattern.compile("^\\s*\\[([^\\[\\]]{2,40})]");
    private static final Pattern RESOLUTION = Pattern.compile("(?i)\\d{3,4}[pi]|\\d{3,4}x\\d{3,4}|[248]k");
    private static final Pattern CRC32 = Pattern.compile("(?i)[0-9a-f]{8}");
    /** Bracketed technical tags that can open a title but are not a group. */
    private static final Set<String> TECHNICAL_TAGS = Set.of(
            "web", "webdl", "webrip", "bd", "bdrip", "bluray", "hdtv", "dvd", "dvdrip", "remux",
            "hevc", "x264", "x265", "h264", "h265", "av1", "aac", "flac", "multi", "dual",
            "sub", "subs", "batch", "complete");

    private ReleaseGroups() {
    }

    /** The bracketed group at the start of {@code title}, or empty when there isn't a plausible one. */
    public static Optional<String> leadingGroup(String title) {
        if (title == null) {
            return Optional.empty();
        }
        Matcher matcher = LEADING_BRACKETS.matcher(title);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String candidate = matcher.group(1).strip();
        return isGroupName(candidate) ? Optional.of(candidate) : Optional.empty();
    }

    private static boolean isGroupName(String candidate) {
        if (candidate.chars().noneMatch(Character::isLetter)) {
            return false;
        }
        if (RESOLUTION.matcher(candidate).matches() || CRC32.matcher(candidate).matches()) {
            return false;
        }
        String normalized = candidate.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return !normalized.isEmpty() && !TECHNICAL_TAGS.contains(normalized);
    }
}
