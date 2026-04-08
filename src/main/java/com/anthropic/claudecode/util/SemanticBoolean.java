package com.anthropic.claudecode.util;

/**
 * Semantic boolean parsing utilities.
 * Translated from src/utils/semanticBoolean.ts
 *
 * Parses booleans that may be represented as strings in JSON tool inputs.
 */
public class SemanticBoolean {

    /**
     * Parse a value that may be a boolean or a boolean string.
     * Translated from semanticBoolean() in semanticBoolean.ts
     */
    public static Boolean parse(Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if ("true".equals(s)) return true;
            if ("false".equals(s)) return false;
        }
        return null;
    }

    /**
     * Parse with default value.
     */
    public static boolean parseWithDefault(Object value, boolean defaultValue) {
        Boolean result = parse(value);
        return result != null ? result : defaultValue;
    }

    private SemanticBoolean() {}
}
