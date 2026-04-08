package com.anthropic.claudecode.model;

import java.util.*;

/**
 * Content replacement state for tool result storage.
 * Translated from src/utils/toolResultStorage.ts
 *
 * Tracks which tool results have been persisted to disk and their replacements.
 */
public class ContentReplacementState {

    private final Set<String> seenIds;
    private final Map<String, String> replacements;

    public ContentReplacementState() {
        this.seenIds = new HashSet<>();
        this.replacements = new LinkedHashMap<>();
    }

    private ContentReplacementState(Set<String> seenIds, Map<String, String> replacements) {
        this.seenIds = seenIds;
        this.replacements = replacements;
    }

    /**
     * Create a new empty state.
     * Translated from createContentReplacementState() in toolResultStorage.ts
     */
    public static ContentReplacementState create() {
        return new ContentReplacementState();
    }

    /**
     * Clone the state for a cache-sharing fork.
     * Translated from cloneContentReplacementState() in toolResultStorage.ts
     */
    public ContentReplacementState clone() {
        return new ContentReplacementState(
            new HashSet<>(seenIds),
            new LinkedHashMap<>(replacements)
        );
    }

    public Set<String> getSeenIds() { return seenIds; }
    public Map<String, String> getReplacements() { return replacements; }

    public boolean hasSeenId(String id) { return seenIds.contains(id); }
    public void addSeenId(String id) { seenIds.add(id); }
    public void addReplacement(String id, String replacement) { replacements.put(id, replacement); }
    public String getReplacement(String id) { return replacements.get(id); }
}
