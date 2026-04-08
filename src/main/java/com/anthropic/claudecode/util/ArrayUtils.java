package com.anthropic.claudecode.util;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * Array utility functions.
 * Translated from src/utils/array.ts
 */
public class ArrayUtils {

    /**
     * Intersperse a separator between elements.
     * Translated from intersperse() in array.ts
     */
    public static <A> List<A> intersperse(List<A> list, Function<Integer, A> separator) {
        if (list == null || list.isEmpty()) return List.of();
        List<A> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) result.add(separator.apply(i));
            result.add(list.get(i));
        }
        return result;
    }

    /**
     * Count elements matching a predicate.
     * Translated from count() in array.ts
     */
    public static <T> int count(List<T> list, Predicate<T> pred) {
        if (list == null) return 0;
        int n = 0;
        for (T x : list) {
            if (pred.test(x)) n++;
        }
        return n;
    }

    /**
     * Get unique elements.
     * Translated from uniq() in array.ts
     */
    public static <T> List<T> uniq(Iterable<T> iterable) {
        if (iterable == null) return List.of();
        Set<T> seen = new LinkedHashSet<>();
        for (T item : iterable) {
            seen.add(item);
        }
        return new ArrayList<>(seen);
    }

    private ArrayUtils() {}
}
