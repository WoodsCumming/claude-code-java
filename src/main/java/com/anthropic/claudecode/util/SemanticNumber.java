package com.anthropic.claudecode.util;

import java.util.regex.Pattern;

/**
 * Semantic number parsing utilities.
 * Translated from src/utils/semanticNumber.ts
 *
 * Parses numbers that may be represented as strings in JSON tool inputs.
 */
public class SemanticNumber {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    /**
     * Parse a value that may be a number or a numeric string.
     * Translated from semanticNumber() in semanticNumber.ts
     */
    public static Double parse(Object value) {
        if (value == null) return null;

        if (value instanceof Number n) {
            double d = n.doubleValue();
            return Double.isFinite(d) ? d : null;
        }

        if (value instanceof String s) {
            if (NUMBER_PATTERN.matcher(s).matches()) {
                try {
                    double n = Double.parseDouble(s);
                    return Double.isFinite(n) ? n : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }

        return null;
    }

    /**
     * Parse to integer.
     */
    public static Integer parseInt(Object value) {
        Double d = parse(value);
        return d != null ? (int) Math.round(d) : null;
    }

    private SemanticNumber() {}
}
