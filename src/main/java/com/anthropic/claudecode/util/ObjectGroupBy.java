package com.anthropic.claudecode.util;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Object groupBy utility.
 * Translated from src/utils/objectGroupBy.ts
 */
public class ObjectGroupBy {

    /**
     * Group items by a key selector.
     * Translated from objectGroupBy() in objectGroupBy.ts
     */
    public static <T, K> Map<K, List<T>> groupBy(
            Iterable<T> items,
            Function<T, K> keySelector) {

        Map<K, List<T>> result = new LinkedHashMap<>();
        for (T item : items) {
            K key = keySelector.apply(item);
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        return result;
    }

    /**
     * Group items by a key selector with index.
     * Translated from objectGroupBy() with index in objectGroupBy.ts
     */
    public static <T, K> Map<K, List<T>> groupByWithIndex(
            Iterable<T> items,
            BiFunction<T, Integer, K> keySelector) {

        Map<K, List<T>> result = new LinkedHashMap<>();
        int index = 0;
        for (T item : items) {
            K key = keySelector.apply(item, index++);
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        return result;
    }

    private ObjectGroupBy() {}
}
