package com.anthropic.claudecode.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * LoremIpsumSkill — generates filler text for long-context testing.
 *
 * <p>This skill is ANT-ONLY (requires {@code USER_TYPE=ant} environment
 * variable).  It outputs approximately the requested number of tokens by
 * randomly assembling single-token English words.
 *
 * <p>Translated from: src/skills/bundled/loremIpsum.ts
 */
@Slf4j
@Service
public class LoremIpsumSkill {



    // -------------------------------------------------------------------------
    // Skill metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "lorem-ipsum";
    public static final String DESCRIPTION =
            "Generate filler text for long context testing. "
            + "Specify token count as argument (e.g., /lorem-ipsum 50000). "
            + "Outputs approximately the requested number of tokens. Ant-only.";
    public static final String ARGUMENT_HINT = "[token_count]";

    private static final int MAX_TOKENS = 500_000;

    // -------------------------------------------------------------------------
    // Single-token word list
    // (Verified 1-token words from the TypeScript source)
    // -------------------------------------------------------------------------

    private static final String[] ONE_TOKEN_WORDS = {
        // Articles & pronouns
        "the", "a", "an", "I", "you", "he", "she", "it", "we", "they",
        "me", "him", "her", "us", "them", "my", "your", "his", "its", "our",
        "this", "that", "what", "who",
        // Common verbs
        "is", "are", "was", "were", "be", "been", "have", "has", "had",
        "do", "does", "did", "will", "would", "can", "could", "may", "might",
        "must", "shall", "should", "make", "made", "get", "got", "go", "went",
        "come", "came", "see", "saw", "know", "take", "think", "look", "want",
        "use", "find", "give", "tell", "work", "call", "try", "ask", "need",
        "feel", "seem", "leave", "put",
        // Common nouns & adjectives
        "time", "year", "day", "way", "man", "thing", "life", "hand", "part",
        "place", "case", "point", "fact", "good", "new", "first", "last",
        "long", "great", "little", "own", "other", "old", "right", "big",
        "high", "small", "large", "next", "early", "young", "few", "public",
        "bad", "same", "able",
        // Prepositions & conjunctions
        "in", "on", "at", "to", "for", "of", "with", "from", "by", "about",
        "like", "through", "over", "before", "between", "under", "since",
        "without", "and", "or", "but", "if", "than", "because", "as",
        "until", "while", "so", "though", "both", "each", "when", "where",
        "why", "how",
        // Common adverbs
        "not", "now", "just", "more", "also", "here", "there", "then",
        "only", "very", "well", "back", "still", "even", "much", "too",
        "such", "never", "again", "most", "once", "off", "away", "down",
        "out", "up",
        // Tech / common words
        "test", "code", "data", "file", "line", "text", "word", "number",
        "system", "program", "set", "run", "value", "name", "type", "state",
        "end", "start"
    };

    // -------------------------------------------------------------------------
    // Guard
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} only when the current user type is "ant".
     * Mirrors the {@code if (process.env.USER_TYPE !== 'ant') return} guard in TS.
     */
    public boolean isEnabled() {
        return "ant".equals(System.getenv("USER_TYPE"));
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the lorem-ipsum text for the {@code /lorem-ipsum} command.
     *
     * @param args optional token count supplied by the user
     * @return a single-element list containing the generated text (or an error)
     */
    public CompletableFuture<List<PromptPart>> getPromptForCommand(String args) {
        return CompletableFuture.supplyAsync(() -> {
            String trimmed = args == null ? "" : args.strip();

            if (!trimmed.isEmpty()) {
                int parsed;
                try {
                    parsed = Integer.parseInt(trimmed);
                } catch (NumberFormatException e) {
                    return List.of(new PromptPart("text",
                            "Invalid token count. Please provide a positive number (e.g., /lorem-ipsum 10000)."));
                }
                if (parsed <= 0) {
                    return List.of(new PromptPart("text",
                            "Invalid token count. Please provide a positive number (e.g., /lorem-ipsum 10000)."));
                }

                int targetTokens = parsed;
                int cappedTokens = Math.min(targetTokens, MAX_TOKENS);

                if (cappedTokens < targetTokens) {
                    String text = "Requested " + targetTokens + " tokens, but capped at "
                            + MAX_TOKENS + " for safety.\n\n"
                            + generateLoremIpsum(cappedTokens);
                    return List.of(new PromptPart("text", text));
                }

                return List.of(new PromptPart("text", generateLoremIpsum(cappedTokens)));
            }

            // No argument supplied — default to 10 000 tokens.
            return List.of(new PromptPart("text", generateLoremIpsum(10_000)));
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Generates approximately {@code targetTokens} tokens by assembling random
     * sentences (10–20 words each) from the single-token word list.
     * Paragraph breaks are inserted randomly (~20 % chance per sentence).
     */
    String generateLoremIpsum(int targetTokens) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        StringBuilder result = new StringBuilder();
        int tokens = 0;

        while (tokens < targetTokens) {
            int sentenceLength = 10 + rng.nextInt(11);  // 10–20

            for (int i = 0; i < sentenceLength && tokens < targetTokens; i++) {
                String word = ONE_TOKEN_WORDS[rng.nextInt(ONE_TOKEN_WORDS.length)];
                result.append(word);
                tokens++;

                if (i == sentenceLength - 1 || tokens >= targetTokens) {
                    result.append(". ");
                } else {
                    result.append(' ');
                }
            }

            // Paragraph break roughly every 5–8 sentences (~20 % chance).
            if (tokens < targetTokens && rng.nextDouble() < 0.2) {
                result.append("\n\n");
            }
        }

        return result.toString().strip();
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /** Simple record representing a single prompt part sent to the model. */
    public record PromptPart(String type, String text) {}
}
