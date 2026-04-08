package com.anthropic.claudecode.util.ink.hooks;

import com.anthropic.claudecode.util.ink.events.EventEmitter;

import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Java equivalent of Ink's useStdin hook (src/ink/hooks/use-stdin.ts).
 *
 * <p>In React, {@code useStdin()} retrieves the {@code StdinContext} value. The context exposes the
 * raw stdin stream, a {@code setRawMode} toggle, and the internal event emitter used to deliver
 * parsed input events to registered {@code useInput} hooks.
 *
 * <p>In Java there is no React context. Instead, this class models the context value as
 * {@link StdinContext} — a plain record that can be constructed once and passed to collaborators
 * (e.g. via constructor injection or a DI framework).
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * UseStdin.StdinContext ctx = UseStdin.StdinContext.of(
 *     System.in,
 *     rawMode -> { /* toggle raw mode on the terminal *\/ },
 *     true,
 *     true,
 *     emitter
 * );
 * }</pre>
 */
public final class UseStdin {

    private UseStdin() {}

    // -------------------------------------------------------------------------
    // StdinContext value type
    // -------------------------------------------------------------------------

    /**
     * Mirrors the TypeScript {@code Props} interface from {@code StdinContext.ts}.
     *
     * <ul>
     *   <li>{@code stdin} — the underlying input stream (usually {@code System.in})</li>
     *   <li>{@code setRawMode} — toggle raw terminal mode</li>
     *   <li>{@code isRawModeSupported} — whether the terminal supports raw mode</li>
     *   <li>{@code exitOnCtrlC} — whether Ctrl+C should exit the application</li>
     *   <li>{@code eventEmitter} — shared emitter for parsed input events; use
     *       {@link #listenToInput} to subscribe</li>
     * </ul>
     */
    public record StdinContext(
            InputStream stdin,
            Consumer<Boolean> setRawMode,
            boolean isRawModeSupported,
            boolean exitOnCtrlC,
            EventEmitter eventEmitter
    ) {

        /**
         * Default context with no-op setRawMode and a fresh {@link EventEmitter}. Matches the
         * TypeScript default context value used as a fallback when no provider is mounted.
         */
        public static StdinContext defaultContext() {
            return new StdinContext(
                    System.in,
                    ignored -> {},
                    false,
                    true,
                    new EventEmitter()
            );
        }

        /**
         * Full factory with explicit values for all fields.
         */
        public static StdinContext of(
                InputStream stdin,
                Consumer<Boolean> setRawMode,
                boolean isRawModeSupported,
                boolean exitOnCtrlC,
                EventEmitter eventEmitter) {
            return new StdinContext(stdin, setRawMode, isRawModeSupported, exitOnCtrlC, eventEmitter);
        }

        // ------------------------------------------------------------------
        // Convenience delegation methods
        // ------------------------------------------------------------------

        /**
         * Enable raw mode on the underlying terminal.
         */
        public void enableRawMode() {
            setRawMode.accept(true);
        }

        /**
         * Disable raw mode on the underlying terminal.
         */
        public void disableRawMode() {
            setRawMode.accept(false);
        }

        /**
         * Subscribe {@code listener} to {@code "input"} events emitted by the event emitter.
         * Returns a {@link Runnable} that removes the subscription when called — analogous to the
         * cleanup returned by React's {@code useEffect}.
         *
         * @param listener receives the raw event object (typically a parsed {@code InputEvent})
         * @return cleanup runnable that calls {@link EventEmitter#removeListener}
         */
        public Runnable listenToInput(java.util.function.Consumer<Object> listener) {
            eventEmitter.on("input", listener);
            return () -> eventEmitter.removeListener("input", listener);
        }
    }
}
