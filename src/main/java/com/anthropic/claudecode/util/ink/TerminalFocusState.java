package com.anthropic.claudecode.util.ink;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Java equivalent of Ink's terminal-focus-state.ts (src/ink/terminal-focus-state.ts).
 *
 * <p>Tracks whether the terminal window itself is focused, using DECSET 1004 focus events. The
 * state starts as {@link State#UNKNOWN} for terminals that do not support focus reporting.
 * Consumers should treat {@link State#UNKNOWN} identically to {@link State#FOCUSED} (no
 * throttling).
 *
 * <p>This class is a singleton module — all state is held in static fields, mirroring the
 * module-level variables in the TypeScript source.
 *
 * <h3>States</h3>
 *
 * <ul>
 *   <li>{@link State#FOCUSED} — terminal reports that it has focus</li>
 *   <li>{@link State#BLURRED} — terminal reports that it has lost focus</li>
 *   <li>{@link State#UNKNOWN} — terminal does not support DECSET 1004 (default)</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 *
 * <p>Subscriber notifications are dispatched synchronously on the calling thread (matching the
 * TypeScript source's synchronous callback invocation). Subscribers are held in a
 * {@link CopyOnWriteArraySet} so additions/removals during notification are safe.
 */
public final class TerminalFocusState {

    // -------------------------------------------------------------------------
    // State enum (mirrors TS union 'focused' | 'blurred' | 'unknown')
    // -------------------------------------------------------------------------

    /**
     * Sealed representation of the terminal focus state. Mirrors the TypeScript type
     * {@code TerminalFocusState = 'focused' | 'blurred' | 'unknown'}.
     */
    public enum State {
        FOCUSED, BLURRED, UNKNOWN
    }

    // -------------------------------------------------------------------------
    // Module-level state (static, mirroring TS module variables)
    // -------------------------------------------------------------------------

    private static volatile State focusState = State.UNKNOWN;

    /**
     * Callbacks registered via {@link #subscribe}. Notified synchronously on every state change.
     * (Mirrors {@code subscribers: Set<() => void>} in the TypeScript source.)
     */
    private static final CopyOnWriteArraySet<Runnable> subscribers = new CopyOnWriteArraySet<>();

    /**
     * One-shot resolvers that are called — and then cleared — when the terminal loses focus.
     * (Mirrors {@code resolvers: Set<() => void>} in the TypeScript source.)
     */
    private static final CopyOnWriteArraySet<Runnable> resolvers = new CopyOnWriteArraySet<>();

    private TerminalFocusState() {}

    // -------------------------------------------------------------------------
    // Mutators
    // -------------------------------------------------------------------------

    /**
     * Update the focus state and notify all subscribers synchronously.
     *
     * <p>When {@code focused} is {@code false} (blur), one-shot resolvers are also drained.
     * Mirrors {@code setTerminalFocused(v: boolean)} in the TypeScript source.
     */
    public static void setTerminalFocused(boolean focused) {
        focusState = focused ? State.FOCUSED : State.BLURRED;
        for (Runnable cb : subscribers) {
            cb.run();
        }
        if (!focused) {
            for (Runnable resolve : resolvers) {
                resolve.run();
            }
            resolvers.clear();
        }
    }

    /**
     * Reset the state back to {@link State#UNKNOWN} and notify all subscribers. Used during
     * cleanup (e.g. when the Ink instance exits).
     * Mirrors {@code resetTerminalFocusState()} in the TypeScript source.
     */
    public static void resetTerminalFocusState() {
        focusState = State.UNKNOWN;
        for (Runnable cb : subscribers) {
            cb.run();
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} unless the terminal is known to be blurred.
     * Mirrors {@code getTerminalFocused()}: {@code focusState !== 'blurred'}.
     */
    public static boolean getTerminalFocused() {
        return focusState != State.BLURRED;
    }

    /**
     * Returns the full {@link State} value.
     * Mirrors {@code getTerminalFocusState()}.
     */
    public static State getTerminalFocusState() {
        return focusState;
    }

    // -------------------------------------------------------------------------
    // Subscription (mirrors useSyncExternalStore pattern)
    // -------------------------------------------------------------------------

    /**
     * Subscribe to focus-state changes. The supplied {@link Runnable} is called synchronously
     * whenever the state changes.
     *
     * <p>Returns an unsubscribe {@link Runnable} — call it to stop receiving notifications.
     * Mirrors {@code subscribeTerminalFocus(cb: () => void): () => void}.
     *
     * @param cb callback invoked on every state change
     * @return unsubscribe handle
     */
    public static Runnable subscribe(Runnable cb) {
        subscribers.add(cb);
        return () -> subscribers.remove(cb);
    }

    // -------------------------------------------------------------------------
    // One-shot resolver registration
    // -------------------------------------------------------------------------

    /**
     * Register a one-shot {@link Runnable} that will be invoked the next time the terminal
     * transitions to {@link State#BLURRED}. The resolver is removed after it fires.
     *
     * <p>Mirrors the {@code resolvers} set used internally by the TypeScript module for
     * Promise-based "wait for blur" patterns.
     */
    public static void addBlurResolver(Runnable resolver) {
        resolvers.add(resolver);
    }
}
