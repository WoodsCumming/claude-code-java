package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Content array utilities.
 * Translated from src/utils/contentArray.ts
 *
 * Utilities for manipulating content block arrays.
 */
public class ContentArrayUtils {

    /**
     * Insert a block after the last tool_result block.
     * Translated from insertBlockAfterToolResults() in contentArray.ts
     *
     * @param content The content array to modify
     * @param block   The block to insert
     */
    public static void insertBlockAfterToolResults(List<Map<String, Object>> content, Map<String, Object> block) {
        if (content == null || block == null) return;

        // Find position after the last tool_result block
        int lastToolResultIndex = -1;
        for (int i = 0; i < content.size(); i++) {
            Map<String, Object> item = content.get(i);
            if (item != null && "tool_result".equals(item.get("type"))) {
                lastToolResultIndex = i;
            }
        }

        int insertPos;
        if (lastToolResultIndex >= 0) {
            insertPos = lastToolResultIndex + 1;
        } else {
            // Insert before the last block
            insertPos = Math.max(0, content.size() - 1);
        }

        content.add(insertPos, block);

        // If the inserted block is the final element, append a text continuation block
        if (insertPos == content.size() - 1) {
            Map<String, Object> continuation = new LinkedHashMap<>();
            continuation.put("type", "text");
            continuation.put("text", "");
            content.add(continuation);
        }
    }

    private ContentArrayUtils() {}
}
