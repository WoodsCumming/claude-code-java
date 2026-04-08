package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.IntStream;

/**
 * Service for handling large pasted text in the prompt input.
 * Translated from src/components/PromptInput/inputPaste.ts
 *
 * When pasted content exceeds the truncation threshold, this service
 * replaces the middle portion with a placeholder reference and stores
 * the truncated content separately (keyed by a sequential paste ID).
 */
@Slf4j
@Service
public class InputPasteService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InputPasteService.class);


    /** Characters before we truncate the pasted content. */
    private static final int TRUNCATION_THRESHOLD = 10_000;

    /** Characters to show at start and end when truncated. */
    private static final int PREVIEW_LENGTH = 1_000;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Determines whether the input text should be truncated.
     * If so, returns a truncated text placeholder and the hidden content;
     * otherwise returns the text unchanged.
     * Translated from maybeTruncateMessageForInput() in inputPaste.ts
     *
     * @param text       the input text to potentially truncate
     * @param nextPasteId the reference ID to embed in the placeholder
     * @return a {@link TruncatedMessage} with the display text and optional hidden content
     */
    public TruncatedMessage maybeTruncateMessageForInput(String text, int nextPasteId) {
        if (text == null || text.length() <= TRUNCATION_THRESHOLD) {
            return new TruncatedMessage(text != null ? text : "", "");
        }

        int startLength = PREVIEW_LENGTH / 2;
        int endLength = PREVIEW_LENGTH / 2;

        String startText = text.substring(0, startLength);
        String endText = text.substring(text.length() - endLength);

        // The hidden middle portion becomes the placeholder content
        String placeholderContent = text.substring(startLength, text.length() - endLength);
        int truncatedLines = countLines(placeholderContent);

        String placeholderRef = formatTruncatedTextRef(nextPasteId, truncatedLines);
        String truncatedText = startText + placeholderRef + endText;

        return new TruncatedMessage(truncatedText, placeholderContent);
    }

    /**
     * Applies truncation to the current prompt input, updating the pasted
     * contents map with the hidden text if a truncation occurred.
     * Translated from maybeTruncateInput() in inputPaste.ts
     *
     * @param input          the current prompt input string
     * @param pastedContents the current map of paste ID → PastedContent
     * @return the updated input string and pasted-contents map
     */
    public TruncateInputResult maybeTruncateInput(String input,
                                                   Map<Integer, PastedContent> pastedContents) {
        // Determine the next available paste ID
        OptionalInt maxId = pastedContents.keySet().stream()
                .mapToInt(Integer::intValue)
                .max();
        int nextPasteId = maxId.isPresent() ? maxId.getAsInt() + 1 : 1;

        TruncatedMessage result = maybeTruncateMessageForInput(input, nextPasteId);

        if (result.placeholderContent().isEmpty()) {
            return new TruncateInputResult(input, pastedContents);
        }

        Map<Integer, PastedContent> newPastedContents = new HashMap<>(pastedContents);
        newPastedContents.put(nextPasteId, new PastedContent(nextPasteId, "text", result.placeholderContent()));

        return new TruncateInputResult(result.truncatedText(), newPastedContents);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Counts the number of lines in a string (used for the placeholder label).
     * Mirrors getPastedTextRefNumLines() which counts '\n' characters.
     */
    private int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) text.chars().filter(c -> c == '\n').count() + 1;
    }

    /**
     * Formats the inline placeholder reference that replaces hidden content.
     * Translated from formatTruncatedTextRef() in inputPaste.ts
     */
    private String formatTruncatedTextRef(int id, int numLines) {
        return "[...Truncated text #%d +%d lines...]".formatted(id, numLines);
    }

    // -------------------------------------------------------------------------
    // Supporting records
    // -------------------------------------------------------------------------

    /**
     * Result of attempting to truncate a message.
     * Translated from TruncatedMessage in inputPaste.ts
     *
     * @param truncatedText      the text to display in the input box (may contain a placeholder ref)
     * @param placeholderContent the hidden content, or empty string if no truncation occurred
     */
    public record TruncatedMessage(String truncatedText, String placeholderContent) {}

    /**
     * Result of {@link #maybeTruncateInput}.
     *
     * @param newInput          the (possibly truncated) input string
     * @param newPastedContents the updated pasted-contents map (unchanged if no truncation)
     */
    public record TruncateInputResult(String newInput, Map<Integer, PastedContent> newPastedContents) {}

    /**
     * Represents a stored pasted content entry.
     * Translated from PastedContent in src/utils/config.ts (type: 'text' | 'image')
     */
    public record PastedContent(int id, String type, String content) {}
}
