package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Tree display utilities.
 * Translated from src/utils/treeify.ts
 *
 * Renders nested data structures as ASCII tree diagrams.
 */
public class TreeifyUtils {

    private static final String BRANCH = "\u251C";    // ├
    private static final String LAST_BRANCH = "\u2514"; // └
    private static final String LINE = "\u2502";       // │
    private static final String EMPTY = " ";

    /**
     * Render a nested map as a tree string.
     * Translated from treeify() in treeify.ts
     */
    public static String treeify(Map<String, Object> obj) {
        if (obj == null || obj.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        List<Map.Entry<String, Object>> entries = new ArrayList<>(obj.entrySet());

        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Object> entry = entries.get(i);
            boolean isLast = i == entries.size() - 1;
            growBranch(sb, entry.getKey(), entry.getValue(), "", isLast, 0);
        }

        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void growBranch(StringBuilder sb, String key, Object value,
                                    String prefix, boolean isLast, int depth) {
        String connector = isLast ? LAST_BRANCH : BRANCH;
        String linePrefix = prefix + connector + "\u2500\u2500 ";  // ─── connector

        if (value instanceof Map) {
            sb.append(linePrefix).append(key).append("\n");
            String childPrefix = prefix + (isLast ? "    " : LINE + "   ");
            Map<String, Object> childMap = (Map<String, Object>) value;
            List<Map.Entry<String, Object>> entries = new ArrayList<>(childMap.entrySet());
            for (int i = 0; i < entries.size(); i++) {
                Map.Entry<String, Object> entry = entries.get(i);
                boolean childIsLast = i == entries.size() - 1;
                growBranch(sb, entry.getKey(), entry.getValue(), childPrefix, childIsLast, depth + 1);
            }
        } else if (value != null) {
            sb.append(linePrefix).append(key).append(": ").append(value).append("\n");
        } else {
            sb.append(linePrefix).append(key).append("\n");
        }
    }

    private TreeifyUtils() {}
}
