package com.anthropic.claudecode.util;

import java.util.*;

/**
 * Fixed-size circular buffer.
 * Translated from src/utils/CircularBuffer.ts
 *
 * Automatically evicts the oldest items when full.
 */
public class CircularBuffer<T> {

    private final Object[] buffer;
    private final int capacity;
    private int head = 0;
    private int size = 0;

    public CircularBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }

    /**
     * Add an item to the buffer.
     * Translated from CircularBuffer.add() in CircularBuffer.ts
     */
    public void add(T item) {
        buffer[head] = item;
        head = (head + 1) % capacity;
        if (size < capacity) size++;
    }

    /**
     * Add multiple items.
     * Translated from CircularBuffer.addAll() in CircularBuffer.ts
     */
    public void addAll(List<T> items) {
        for (T item : items) add(item);
    }

    /**
     * Get the most recent N items.
     * Translated from CircularBuffer.getRecent() in CircularBuffer.ts
     */
    @SuppressWarnings("unchecked")
    public List<T> getRecent(int count) {
        List<T> result = new ArrayList<>();
        int start = size < capacity ? 0 : head;
        int available = Math.min(count, size);

        for (int i = 0; i < available; i++) {
            int index = (start + size - available + i) % capacity;
            result.add((T) buffer[index]);
        }

        return result;
    }

    /**
     * Get all items in order (oldest to newest).
     * Translated from CircularBuffer.toArray() in CircularBuffer.ts
     */
    @SuppressWarnings("unchecked")
    public List<T> toList() {
        if (size == 0) return List.of();

        List<T> result = new ArrayList<>(size);
        int start = size < capacity ? 0 : head;

        for (int i = 0; i < size; i++) {
            int index = (start + i) % capacity;
            result.add((T) buffer[index]);
        }

        return result;
    }

    /**
     * Clear the buffer.
     * Translated from CircularBuffer.clear() in CircularBuffer.ts
     */
    public void clear() {
        Arrays.fill(buffer, null);
        head = 0;
        size = 0;
    }

    /**
     * Get the current size.
     */
    public int length() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }
}
