package com.anthropic.claudecode.util.ink.hooks;

import com.anthropic.claudecode.util.ink.events.EventEmitter;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Java equivalent of Ink's useInput hook (src/ink/hooks/use-input.ts).
 *
 * <p>In the React world, {@code useInput} registers a callback that is called for each character
 * the user types (or once for a pasted multi-character string). It also toggles stdin raw mode so
 * key presses are delivered without line-buffering.
 *
 * <p>In Java there is no React hook lifecycle. This class provides an equivalent lifecycle via
 * {@link #register} / {@link #unregister} (or the try-with-resources {@link Registration} it
 * returns). Call {@link #register} when the component is "mounted" and {@link Registration#close()}
 * (or {@link #unregister}) when it is "unmounted".
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * UseInput useInput = new UseInput(stdinContext);
 *
 * // Register an active handler (enables raw mode automatically)
 * UseInput.Registration reg = useInput.register((input, key) -> {
 *     if ("q".equals(input)) appCtx.exit();
 *     if (key.isLeftArrow()) { ... }
 * });
 *
 * // Later — when the component unmounts:
 * reg.close();
 * }</pre>
 *
 * <h3>Ordering note</h3>
 *
 * <p>Just as in the TypeScript source, the listener is registered once at mount time so its
 * position in the emitter's listener array remains stable. The {@code isActive} flag is checked
 * lazily inside the listener rather than re-registering on every toggle, preserving
 * {@code stopImmediatePropagation()} ordering.
 */
public class UseInput {

    // -------------------------------------------------------------------------
    // Key descriptor (mirrors TypeScript Key type)
    // -------------------------------------------------------------------------

    /**
     * Parsed key modifiers and navigation flags. Mirrors the TypeScript {@code Key} type from
     * {@code input-event.ts}.
     */
    public record Key(
            boolean upArrow,
            boolean downArrow,
            boolean leftArrow,
            boolean rightArrow,
            boolean pageDown,
            boolean pageUp,
            boolean wheelUp,
            boolean wheelDown,
            boolean home,
            boolean end,
            boolean returnKey,
            boolean escape,
            boolean ctrl,
            boolean shift,
            boolean fn,
            boolean tab,
            boolean backspace,
            boolean delete,
            boolean meta,
            boolean superKey
    ) {
        /** A Key with all flags {@code false}. */
        public static Key empty() {
            return new Key(false, false, false, false, false, false, false, false,
                    false, false, false, false, false, false, false, false, false,
                    false, false, false);
        }
    }

    // -------------------------------------------------------------------------
    // Handler functional interface
    // -------------------------------------------------------------------------

    /**
     * Callback invoked for each user input event. Mirrors the TypeScript {@code Handler} type.
     *
     * @param input the typed character(s); may be empty for special keys
     * @param key   parsed key descriptor with modifier and navigation flags
     */
    @FunctionalInterface
    public interface InputHandler {
        void handle(String input, Key key);
    }

    // -------------------------------------------------------------------------
    // Registration (lifecycle handle)
    // -------------------------------------------------------------------------

    /**
     * Returned by {@link #register}. Implements {@link AutoCloseable} so it can be used in
     * try-with-resources blocks. Calling {@link #close()} disables raw mode (if this was the last
     * active registration) and removes the listener from the emitter.
     */
    public final class Registration implements AutoCloseable {

        private final Consumer<Object> listener;
        private final AtomicBoolean active;
        private volatile boolean closed = false;

        private Registration(Consumer<Object> listener, AtomicBoolean active) {
            this.listener = listener;
            this.active   = active;
        }

        /**
         * Temporarily disable this registration without removing it from the emitter. The listener
         * stays registered (maintaining its position for stopImmediatePropagation ordering) but
         * silently ignores events.
         */
        public void setActive(boolean isActive) {
            boolean wasActive = active.getAndSet(isActive);
            if (wasActive && !isActive) {
                stdinContext.disableRawMode();
            } else if (!wasActive && isActive) {
                stdinContext.enableRawMode();
            }
        }

        /**
         * Permanently unregister this handler and restore cooked mode.
         */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (active.getAndSet(false)) {
                stdinContext.disableRawMode();
            }
            stdinContext.eventEmitter().removeListener("input", listener);
        }
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final UseStdin.StdinContext stdinContext;

    public UseInput(UseStdin.StdinContext stdinContext) {
        this.stdinContext = stdinContext;
    }

    // -------------------------------------------------------------------------
    // register / unregister
    // -------------------------------------------------------------------------

    /**
     * Register an active input handler (equivalent to mounting a React component that calls
     * {@code useInput(handler)}). Raw mode is enabled immediately.
     *
     * @param handler callback to invoke on each input event
     * @return a {@link Registration} that must be {@link Registration#close() closed} on unmount
     */
    public Registration register(InputHandler handler) {
        return register(handler, true);
    }

    /**
     * Register an input handler with an explicit initial {@code isActive} state. When
     * {@code isActive} is {@code false} the listener is registered but raw mode is not enabled and
     * events are silently dropped until {@link Registration#setActive(boolean)} is called with
     * {@code true}.
     */
    public Registration register(InputHandler handler, boolean isActive) {
        AtomicBoolean active = new AtomicBoolean(isActive);

        Consumer<Object> listener = rawEvent -> {
            if (!active.get()) return;

            // The event object is expected to carry input + key information.
            // The actual type depends on the runtime event model; we support two shapes:
            //   1. An object with getInput()/getKey() methods (e.g. a future InputEvent class)
            //   2. A plain Object[] / Map used for testing
            // For now we delegate to the helper below.
            InputEventData data = extractData(rawEvent);
            if (data == null) return;

            // Mirror TypeScript: if app should exit on Ctrl+C, don't forward Ctrl+C to handler
            boolean isCtrlC = "c".equals(data.input()) && data.key().ctrl();
            if (isCtrlC && stdinContext.exitOnCtrlC()) return;

            handler.handle(data.input(), data.key());
        };

        stdinContext.eventEmitter().on("input", listener);

        if (isActive) {
            stdinContext.enableRawMode();
        }

        return new Registration(listener, active);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts {@link InputEventData} from the raw object emitted on the {@code "input"} channel.
     * Returns {@code null} when the object shape is not recognised.
     */
    private static InputEventData extractData(Object rawEvent) {
        if (rawEvent instanceof InputEventData data) {
            return data;
        }
        // Fallback: unknown shape — drop the event.
        return null;
    }

    /**
     * Thin value type carrying the parsed input string and key descriptor. Produced by the
     * keypress parser and emitted on the {@code "input"} channel.
     */
    public record InputEventData(String input, Key key) {}
}
