package com.anthropic.claudecode.util.ink.events;

/**
 * Java equivalent of Ink's TerminalEvent class (src/ink/events/terminal-event.ts).
 *
 * <p>Base class for all terminal events with DOM-style capture/bubble propagation. Mirrors the
 * browser's {@code Event} API: target, currentTarget, eventPhase, stopPropagation(),
 * preventDefault(), timeStamp.
 *
 * <p>Also serves as the {@code EventTarget} — any node that participates in the dispatcher tree must
 * expose {@link #getParentNode()} and {@link #getEventHandlers()}.
 */
public class TerminalEvent extends InkEvent {

    // -------------------------------------------------------------------------
    // EventTarget interface (inner)
    // -------------------------------------------------------------------------

    /**
     * Represents a node in the dispatcher tree. Any DOM-like node that participates in capture/bubble
     * dispatch must implement this interface.
     */
    public interface EventTarget {
        EventTarget getParentNode();

        /**
         * Returns the event-handler props attached to this node by the reconciler, or {@code null} if
         * none have been set.
         */
        EventHandlers getEventHandlers();
    }

    // -------------------------------------------------------------------------
    // EventPhase sealed type
    // -------------------------------------------------------------------------

    /** Mirrors the TypeScript {@code EventPhase} union type. */
    public enum EventPhase {
        NONE, CAPTURING, AT_TARGET, BUBBLING
    }

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final String type;
    private final long timeStamp;
    private final boolean bubbles;
    private final boolean cancelable;

    private EventTarget target;
    private EventTarget currentTarget;
    private EventPhase eventPhase = EventPhase.NONE;
    private boolean propagationStopped = false;
    private boolean defaultPrevented = false;

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    public TerminalEvent(String type, boolean bubbles, boolean cancelable) {
        this.type = type;
        this.timeStamp = System.nanoTime() / 1_000_000; // millis, approximating performance.now()
        this.bubbles = bubbles;
        this.cancelable = cancelable;
    }

    /** Creates a bubbling, cancelable event — the most common case. */
    public TerminalEvent(String type) {
        this(type, true, true);
    }

    // -------------------------------------------------------------------------
    // Public API (mirrors browser Event)
    // -------------------------------------------------------------------------

    public String getType() {
        return type;
    }

    public long getTimeStamp() {
        return timeStamp;
    }

    public boolean isBubbles() {
        return bubbles;
    }

    public boolean isCancelable() {
        return cancelable;
    }

    public EventTarget getTarget() {
        return target;
    }

    public EventTarget getCurrentTarget() {
        return currentTarget;
    }

    public EventPhase getEventPhase() {
        return eventPhase;
    }

    public boolean isDefaultPrevented() {
        return defaultPrevented;
    }

    public void stopPropagation() {
        this.propagationStopped = true;
    }

    @Override
    public void stopImmediatePropagation() {
        super.stopImmediatePropagation();
        this.propagationStopped = true;
    }

    public void preventDefault() {
        if (cancelable) {
            this.defaultPrevented = true;
        }
    }

    // -------------------------------------------------------------------------
    // Internal setters — used by EventDispatcher
    // -------------------------------------------------------------------------

    /** @internal */
    public void setTarget(EventTarget target) {
        this.target = target;
    }

    /** @internal */
    public void setCurrentTarget(EventTarget currentTarget) {
        this.currentTarget = currentTarget;
    }

    /** @internal */
    public void setEventPhase(EventPhase phase) {
        this.eventPhase = phase;
    }

    /** @internal */
    public boolean isPropagationStopped() {
        return propagationStopped;
    }

    /**
     * Hook for subclasses to do per-node setup before each handler fires. Default is a no-op.
     *
     * @internal
     */
    public void prepareForTarget(EventTarget target) {
        // no-op by default
    }
}
