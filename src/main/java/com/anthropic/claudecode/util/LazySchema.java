package com.anthropic.claudecode.util;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Lazy schema factory.
 * Translated from src/utils/lazySchema.ts
 *
 * Returns a memoized factory that constructs the value on first call.
 */
public class LazySchema {

    /**
     * Create a lazy schema factory.
     * Translated from lazySchema() in lazySchema.ts
     */
    public static <T> Supplier<T> lazy(Supplier<T> factory) {
        AtomicReference<T> cached = new AtomicReference<>();
        return () -> {
            T value = cached.get();
            if (value == null) {
                value = factory.get();
                cached.set(value);
            }
            return value;
        };
    }

    private LazySchema() {}
}
