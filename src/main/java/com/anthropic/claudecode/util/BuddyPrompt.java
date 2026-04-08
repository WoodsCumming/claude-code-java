package com.anthropic.claudecode.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Buddy companion prompt utilities.
 * Translated from src/buddy/prompt.ts
 *
 * Provides system-prompt text and attachment helpers for introducing a
 * companion in a conversation context.
 */
public class BuddyPrompt {

    /**
     * Returns the companion introduction text injected into the system prompt.
     * Translated from companionIntroText() in prompt.ts
     *
     * @param name    the companion's name (e.g. "Gravy")
     * @param species the companion's species (e.g. "rabbit")
     * @return formatted markdown introduction text
     */
    public static String companionIntroText(String name, String species) {
        return """
                # Companion

                A small %s named %s sits beside the user's input box and occasionally comments in a speech bubble. You're not %s — it's a separate watcher.

                When the user addresses %s directly (by name), its bubble will answer. Your job in that moment is to stay out of the way: respond in ONE line or less, or just answer any part of the message meant for you. Don't explain that you're not %s — they know. Don't narrate what %s might say — the bubble handles that."""
                .formatted(species, name, name, name, name, name);
    }

    /**
     * Companion intro attachment used to signal the companion's introduction
     * in a message stream.
     *
     * @param name    companion name
     * @param species companion species value
     */
    public record CompanionIntroAttachment(String type, String name, String species) {
        public CompanionIntroAttachment(String name, String species) {
            this("companion_intro", name, species);
        }
    }

    /**
     * Returns companion intro attachments to prepend to the next API call, or
     * an empty list when:
     * <ul>
     *   <li>The BUDDY feature is disabled</li>
     *   <li>No companion exists in config</li>
     *   <li>Companion is muted</li>
     *   <li>The companion was already introduced in the current message history</li>
     * </ul>
     *
     * Translated from getCompanionIntroAttachment() in prompt.ts
     *
     * @param buddyEnabled      whether the BUDDY feature flag is on
     * @param companionName     companion name from config (null if no companion)
     * @param companionSpecies  companion species from config (null if no companion)
     * @param companionMuted    whether the companion is muted
     * @param alreadyIntroduced true when a companion_intro attachment for this
     *                          companion name already exists in the message history
     * @return list containing a single intro attachment, or an empty list
     */
    public static List<CompanionIntroAttachment> getCompanionIntroAttachment(
            boolean buddyEnabled,
            String companionName,
            String companionSpecies,
            boolean companionMuted,
            boolean alreadyIntroduced) {

        if (!buddyEnabled) return List.of();
        if (companionName == null || companionSpecies == null) return List.of();
        if (companionMuted) return List.of();
        if (alreadyIntroduced) return List.of();

        List<CompanionIntroAttachment> result = new ArrayList<>();
        result.add(new CompanionIntroAttachment(companionName, companionSpecies));
        return result;
    }

    private BuddyPrompt() {}
}
