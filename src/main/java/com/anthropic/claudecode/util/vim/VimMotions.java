package com.anthropic.claudecode.util.vim;

/**
 * Vim Motion Functions.
 *
 * <p>Pure functions for resolving vim motions to cursor positions.
 * No state is mutated — every method is a pure calculation.
 *
 * <p>Translated from: src/vim/motions.ts
 */
public final class VimMotions {

    private VimMotions() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Resolve a motion to a target cursor position.
     * Does not modify anything — pure calculation.
     *
     * <p>The motion is applied {@code count} times, stopping early if the cursor
     * does not move (i.e., it is already at a boundary).
     *
     * @param key    the motion key (e.g., "h", "j", "w", "G")
     * @param cursor the current cursor (represented as a generic {@link Cursor} abstraction)
     * @param count  how many times to apply the motion
     * @return the resulting cursor position
     */
    public static Cursor resolveMotion(String key, Cursor cursor, int count) {
        Cursor result = cursor;
        for (int i = 0; i < count; i++) {
            Cursor next = applySingleMotion(key, result);
            if (next.equals(result)) break;
            result = next;
        }
        return result;
    }

    /**
     * Returns {@code true} when the motion is <em>inclusive</em>, meaning the
     * character at the destination is included in an operator's range.
     *
     * @param key the motion key
     */
    public static boolean isInclusiveMotion(String key) {
        return "eE$".contains(key);
    }

    /**
     * Returns {@code true} when the motion is <em>linewise</em>, meaning operators
     * act on full lines rather than a character range.
     *
     * <p>Note: {@code gj} / {@code gk} are characterwise exclusive per {@code :help gj},
     * not linewise.
     *
     * @param key the motion key
     */
    public static boolean isLinewiseMotion(String key) {
        return "jkG".contains(key) || "gg".equals(key);
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Apply a single step of the given motion to {@code cursor}.
     */
    private static Cursor applySingleMotion(String key, Cursor cursor) {
        return switch (key) {
            case "h"  -> cursor.left();
            case "l"  -> cursor.right();
            case "j"  -> cursor.downLogicalLine();
            case "k"  -> cursor.upLogicalLine();
            case "gj" -> cursor.down();
            case "gk" -> cursor.up();
            case "w"  -> cursor.nextVimWord();
            case "b"  -> cursor.prevVimWord();
            case "e"  -> cursor.endOfVimWord();
            case "W"  -> cursor.nextWORD();
            case "B"  -> cursor.prevWORD();
            case "E"  -> cursor.endOfWORD();
            case "0"  -> cursor.startOfLogicalLine();
            case "^"  -> cursor.firstNonBlankInLogicalLine();
            case "$"  -> cursor.endOfLogicalLine();
            case "G"  -> cursor.startOfLastLine();
            default   -> cursor;
        };
    }

    // =========================================================================
    // Cursor abstraction
    // =========================================================================

    /**
     * Minimal cursor interface required by the motion functions.
     *
     * <p>Implementations are expected to be value-like (immutable), returning
     * new instances from each movement method.  The {@link #equals(Object)}
     * contract must be correctly implemented so that the "stop early on no
     * movement" optimisation in {@link #resolveMotion} works correctly.
     */
    public interface Cursor {
        /** Current byte / code-unit offset in the underlying text. */
        int offset();

        Cursor left();
        Cursor right();
        Cursor down();
        Cursor up();
        Cursor downLogicalLine();
        Cursor upLogicalLine();
        Cursor nextVimWord();
        Cursor prevVimWord();
        Cursor endOfVimWord();
        Cursor nextWORD();
        Cursor prevWORD();
        Cursor endOfWORD();
        Cursor startOfLogicalLine();
        Cursor firstNonBlankInLogicalLine();
        Cursor endOfLogicalLine();
        Cursor startOfLastLine();
        Cursor startOfFirstLine();
        Cursor goToLine(int lineNumber);

        boolean isAtEnd();

        /**
         * Find the nth occurrence of {@code character} in the given {@link VimTypes.FindType}
         * direction from the current cursor. Returns the absolute offset, or null if not found.
         */
        default Integer findCharacter(String character, VimTypes.FindType findType, int count) {
            return null;
        }

        /**
         * Return a new cursor positioned at the given absolute offset.
         */
        default Cursor atOffset(int offset) {
            return this;
        }

        /**
         * Return a position record for this cursor.
         * Default returns a CursorPosition; subinterfaces may override with a covariant type.
         */
        default Object getPositionObj() {
            return new CursorPosition(0, 0);
        }

        /**
         * Return the offset of the next code point after {@code offset}.
         */
        default int nextOffset(int offset) {
            return offset + 1;
        }

        /**
         * Ensure that {@code offset} does not land inside a special reference span
         * (e.g. an inline image). Returns the adjusted offset.
         */
        default int snapOutOfImageRef(int offset, String side) {
            return offset;
        }

        @Override
        boolean equals(Object other);
    }

    /** Lightweight (line, col) pair used by {@link Cursor#getPosition()}. */
    public record CursorPosition(int line, int col) {}
}
