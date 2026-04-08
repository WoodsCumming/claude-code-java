package com.anthropic.claudecode.util.ink.events;

import lombok.Builder;
import lombok.With;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Java equivalent of Ink's event-handlers.ts (src/ink/events/event-handlers.ts).
 *
 * <p>Holds event-handler props for Box and other host components, using the React/DOM naming
 * convention (onEventName for bubble phase, onEventNameCapture for capture phase).
 *
 * <p>{@link #HANDLER_FOR_EVENT} provides an O(1) reverse lookup from event-type string to the
 * handler-prop names, mirroring the TypeScript {@code HANDLER_FOR_EVENT} constant.
 *
 * <p>{@link #EVENT_HANDLER_PROPS} is the set of all prop names that carry event handlers, used by
 * the reconciler to store them in {@code _eventHandlers} rather than in attributes.
 */
@Builder(toBuilder = true)
@With
public record EventHandlers(
        Consumer<TerminalEvent> onKeyDown,
        Consumer<TerminalEvent> onKeyDownCapture,

        Consumer<TerminalEvent> onFocus,
        Consumer<TerminalEvent> onFocusCapture,
        Consumer<TerminalEvent> onBlur,
        Consumer<TerminalEvent> onBlurCapture,

        Consumer<TerminalEvent> onPaste,
        Consumer<TerminalEvent> onPasteCapture,

        Consumer<TerminalEvent> onResize,

        Consumer<TerminalEvent> onClick,
        Runnable onMouseEnter,
        Runnable onMouseLeave
) {

    // -------------------------------------------------------------------------
    // Reverse lookup: event-type → handler-prop descriptor
    // -------------------------------------------------------------------------

    /**
     * Describes the bubble- and capture-phase handler prop names for a given event type. One or both
     * may be absent (e.g. {@code resize} has no capture prop).
     */
    public record HandlerMapping(Optional<String> bubble, Optional<String> capture) {

        /** Convenience factory for an event that has both phases. */
        public static HandlerMapping both(String bubble, String capture) {
            return new HandlerMapping(Optional.of(bubble), Optional.of(capture));
        }

        /** Convenience factory for an event that only has a bubble handler. */
        public static HandlerMapping bubbleOnly(String bubble) {
            return new HandlerMapping(Optional.of(bubble), Optional.empty());
        }
    }

    /**
     * Reverse lookup: event-type string → {@link HandlerMapping}.
     *
     * <p>Used by the dispatcher for O(1) handler lookup per node.
     */
    public static final Map<String, HandlerMapping> HANDLER_FOR_EVENT = Map.of(
            "keydown", HandlerMapping.both("onKeyDown", "onKeyDownCapture"),
            "focus",   HandlerMapping.both("onFocus",   "onFocusCapture"),
            "blur",    HandlerMapping.both("onBlur",    "onBlurCapture"),
            "paste",   HandlerMapping.both("onPaste",   "onPasteCapture"),
            "resize",  HandlerMapping.bubbleOnly("onResize"),
            "click",   HandlerMapping.bubbleOnly("onClick")
    );

    /**
     * Set of all event-handler prop names, for the reconciler to detect event props and store them in
     * {@code _eventHandlers} instead of attributes.
     */
    public static final Set<String> EVENT_HANDLER_PROPS = Set.of(
            "onKeyDown", "onKeyDownCapture",
            "onFocus",   "onFocusCapture",
            "onBlur",    "onBlurCapture",
            "onPaste",   "onPasteCapture",
            "onResize",
            "onClick",
            "onMouseEnter",
            "onMouseLeave"
    );

    // -------------------------------------------------------------------------
    // Dynamic handler lookup (used by EventDispatcher)
    // -------------------------------------------------------------------------

    /**
     * Retrieve the handler named {@code propName} from this {@code EventHandlers} instance.
     * Returns {@link Optional#empty()} when the prop is not set.
     */
    @SuppressWarnings("unchecked")
    public Optional<Consumer<TerminalEvent>> getHandler(String propName) {
        return Optional.ofNullable(switch (propName) {
            case "onKeyDown"        -> onKeyDown;
            case "onKeyDownCapture" -> onKeyDownCapture;
            case "onFocus"          -> onFocus;
            case "onFocusCapture"   -> onFocusCapture;
            case "onBlur"           -> onBlur;
            case "onBlurCapture"    -> onBlurCapture;
            case "onPaste"          -> onPaste;
            case "onPasteCapture"   -> onPasteCapture;
            case "onResize"         -> onResize;
            case "onClick"          -> onClick;
            default                 -> null;
        });
    }

    /** Returns an empty (all-null) {@link EventHandlers} instance. */
    public static EventHandlers empty() {
        return EventHandlers.builder().build();
    }
}
