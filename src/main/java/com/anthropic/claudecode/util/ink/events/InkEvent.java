package com.anthropic.claudecode.util.ink.events;

/**
 * Java equivalent of Ink's base Event class (src/ink/events/event.ts).
 *
 * <p>Tracks whether {@link #stopImmediatePropagation()} has been called so that
 * {@link EventEmitter#emit} can respect it and halt listener iteration.
 */
public class InkEvent {

    private boolean didStopImmediatePropagation = false;

    public boolean didStopImmediatePropagation() {
        return didStopImmediatePropagation;
    }

    public void stopImmediatePropagation() {
        this.didStopImmediatePropagation = true;
    }
}
