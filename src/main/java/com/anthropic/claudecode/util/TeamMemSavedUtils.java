package com.anthropic.claudecode.util;

/**
 * Team-memory saved message utilities.
 * Translated from src/components/messages/teamMemSaved.ts
 *
 * Provides the team-memory segment text for the memory-saved UI.
 * Only meaningful when the TEAMMEM feature flag is enabled.
 */
public class TeamMemSavedUtils {

    /**
     * Result of {@link #teamMemSavedPart}: the display segment and the count.
     * Corresponds to the anonymous return type {@code { segment: string; count: number }}
     * in TypeScript.
     */
    public record TeamMemSavedPart(String segment, int count) {}

    /**
     * Returns the team-memory display segment for the memory-saved UI, plus
     * the count so callers can derive the private count without needing the
     * raw teamCount field.
     *
     * Returns {@code null} when {@code teamCount} is zero or absent, matching
     * the TypeScript behaviour of returning {@code null} for an empty team.
     *
     * Translated from teamMemSavedPart() in teamMemSaved.ts
     *
     * @param teamCount the number of team memories saved; null is treated as 0
     * @return a {@link TeamMemSavedPart} with the formatted segment and count,
     *         or {@code null} when count is zero
     */
    public static TeamMemSavedPart teamMemSavedPart(Integer teamCount) {
        int count = teamCount != null ? teamCount : 0;
        if (count == 0) return null;
        String segment = count + " team " + (count == 1 ? "memory" : "memories");
        return new TeamMemSavedPart(segment, count);
    }

    private TeamMemSavedUtils() {}
}
