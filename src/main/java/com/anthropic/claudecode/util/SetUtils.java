package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Set utility functions.
 * Translated from src/utils/set.ts
 */
public class SetUtils {

    /**
     * Return the set difference (elements in a but not in b).
     * Translated from difference() in set.ts
     */
    public static <A> Set<A> difference(Set<A> a, Set<A> b) {
        Set<A> result = new HashSet<>();
        for (A item : a) {
            if (!b.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Check if two sets have any common elements.
     * Translated from intersects() in set.ts
     */
    public static <A> boolean intersects(Set<A> a, Set<A> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        for (A item : a) {
            if (b.contains(item)) return true;
        }
        return false;
    }

    /**
     * Return the union of two sets.
     */
    public static <A> Set<A> union(Set<A> a, Set<A> b) {
        Set<A> result = new HashSet<>(a);
        result.addAll(b);
        return result;
    }

    /**
     * Return the intersection of two sets.
     */
    public static <A> Set<A> intersection(Set<A> a, Set<A> b) {
        Set<A> result = new HashSet<>();
        for (A item : a) {
            if (b.contains(item)) result.add(item);
        }
        return result;
    }

    private SetUtils() {}
}
