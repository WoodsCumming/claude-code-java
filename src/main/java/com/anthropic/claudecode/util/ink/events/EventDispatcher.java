package com.anthropic.claudecode.util.ink.events;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Java equivalent of Ink's Dispatcher class (src/ink/events/dispatcher.ts).
 *
 * <p>Owns event-dispatch state and the capture/bubble dispatch loop. Mirrors how react-dom's host
 * config reads {@code ReactDOMSharedInternals} and {@code window.event}.
 *
 * <p>{@link #discreteUpdates} is injected after construction (by the reconciler) to break the
 * import cycle — set it before dispatching discrete events.
 *
 * <h3>Event priorities</h3>
 *
 * Rather than importing React reconciler constants, priority levels are represented as plain
 * {@code int}s using the same numeric values React uses internally:
 * <ul>
 *   <li>{@link #DISCRETE_PRIORITY} — keyboard, click, focus, paste</li>
 *   <li>{@link #CONTINUOUS_PRIORITY} — resize, scroll, mousemove</li>
 *   <li>{@link #DEFAULT_PRIORITY} — everything else</li>
 *   <li>{@link #NO_PRIORITY} — unset / sentinel</li>
 * </ul>
 */
@Slf4j
public class EventDispatcher {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EventDispatcher.class);


    // -------------------------------------------------------------------------
    // Priority constants (mirror react-reconciler/constants values)
    // -------------------------------------------------------------------------

    public static final int NO_PRIORITY         = 0;
    public static final int DISCRETE_PRIORITY   = 1;
    public static final int CONTINUOUS_PRIORITY = 4;
    public static final int DEFAULT_PRIORITY    = 16;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** The event currently being dispatched, or {@code null} between dispatches. */
    public TerminalEvent currentEvent = null;

    /** Active scheduling priority; defaults to {@link #DEFAULT_PRIORITY}. */
    public int currentUpdatePriority = DEFAULT_PRIORITY;

    /**
     * Injected by the reconciler to run a dispatch with discrete (synchronous) priority. When
     * {@code null}, {@link #dispatchDiscrete} falls through to the plain {@link #dispatch}.
     */
    public DiscreteUpdates discreteUpdates = null;

    // -------------------------------------------------------------------------
    // Functional interface for discreteUpdates
    // -------------------------------------------------------------------------

    /**
     * Mirrors the TypeScript {@code DiscreteUpdates} type: wraps a two-argument dispatch call in a
     * synchronous high-priority update.
     */
    @FunctionalInterface
    public interface DiscreteUpdates {
        boolean run(TerminalEvent.EventTarget target, TerminalEvent event);
    }

    // -------------------------------------------------------------------------
    // Priority inference
    // -------------------------------------------------------------------------

    /**
     * Infer event priority from the currently-dispatching event. Called by the reconciler host
     * config's {@code resolveUpdatePriority} when no explicit priority has been set.
     */
    public int resolveEventPriority() {
        if (currentUpdatePriority != NO_PRIORITY) {
            return currentUpdatePriority;
        }
        if (currentEvent != null) {
            return getEventPriority(currentEvent.getType());
        }
        return DEFAULT_PRIORITY;
    }

    private static int getEventPriority(String eventType) {
        return switch (eventType) {
            case "keydown", "keyup", "click", "focus", "blur", "paste" -> DISCRETE_PRIORITY;
            case "resize", "scroll", "mousemove"                        -> CONTINUOUS_PRIORITY;
            default                                                      -> DEFAULT_PRIORITY;
        };
    }

    // -------------------------------------------------------------------------
    // Core dispatch
    // -------------------------------------------------------------------------

    /**
     * Dispatch an event through the capture and bubble phases.
     *
     * @return {@code true} if {@code preventDefault()} was NOT called
     */
    public boolean dispatch(TerminalEvent.EventTarget target, TerminalEvent event) {
        TerminalEvent previousEvent = this.currentEvent;
        this.currentEvent = event;
        try {
            event.setTarget(target);

            List<DispatchListener> listeners = collectListeners(target, event);
            processDispatchQueue(listeners, event);

            event.setEventPhase(TerminalEvent.EventPhase.NONE);
            event.setCurrentTarget(null);

            return !event.isDefaultPrevented();
        } finally {
            this.currentEvent = previousEvent;
        }
    }

    /**
     * Dispatch with discrete (synchronous) priority. For user-initiated events: keyboard, click,
     * focus, paste.
     */
    public boolean dispatchDiscrete(TerminalEvent.EventTarget target, TerminalEvent event) {
        if (discreteUpdates == null) {
            return dispatch(target, event);
        }
        return discreteUpdates.run(target, event);
    }

    /**
     * Dispatch with continuous priority. For high-frequency events: resize, scroll, mouse move.
     */
    public boolean dispatchContinuous(TerminalEvent.EventTarget target, TerminalEvent event) {
        int previousPriority = this.currentUpdatePriority;
        try {
            this.currentUpdatePriority = CONTINUOUS_PRIORITY;
            return dispatch(target, event);
        } finally {
            this.currentUpdatePriority = previousPriority;
        }
    }

    // -------------------------------------------------------------------------
    // Listener collection
    // -------------------------------------------------------------------------

    /**
     * Collect all listeners for an event in dispatch order.
     *
     * <p>Uses react-dom's two-phase accumulation pattern:
     * <ul>
     *   <li>Walk from target to root.</li>
     *   <li>Capture handlers are prepended → root-first.</li>
     *   <li>Bubble handlers are appended → target-first.</li>
     * </ul>
     *
     * Result order: {@code [root-cap, ..., parent-cap, target-cap, target-bub, parent-bub, ...,
     * root-bub]}
     */
    private List<DispatchListener> collectListeners(
            TerminalEvent.EventTarget target, TerminalEvent event) {

        List<DispatchListener> list = new ArrayList<>();
        TerminalEvent.EventTarget node = target;

        while (node != null) {
            boolean isTarget = (node == target);

            Optional<Consumer<TerminalEvent>> captureHandler = getHandler(node, event.getType(), true);
            Optional<Consumer<TerminalEvent>> bubbleHandler  = getHandler(node, event.getType(), false);

            TerminalEvent.EventPhase targetPhase =
                    isTarget ? TerminalEvent.EventPhase.AT_TARGET : TerminalEvent.EventPhase.CAPTURING;

            final TerminalEvent.EventTarget finalNode = node;
            final TerminalEvent.EventPhase finalTargetPhase = targetPhase;
            captureHandler.ifPresent(h ->
                    list.add(0, new DispatchListener(finalNode, h, finalTargetPhase)));

            if (bubbleHandler.isPresent() && (event.isBubbles() || isTarget)) {
                TerminalEvent.EventPhase bubblePhase =
                        isTarget ? TerminalEvent.EventPhase.AT_TARGET : TerminalEvent.EventPhase.BUBBLING;
                list.add(new DispatchListener(node, bubbleHandler.get(), bubblePhase));
            }

            node = node.getParentNode();
        }

        return list;
    }

    private static Optional<Consumer<TerminalEvent>> getHandler(
            TerminalEvent.EventTarget node, String eventType, boolean capture) {

        EventHandlers handlers = node.getEventHandlers();
        if (handlers == null) return Optional.empty();

        EventHandlers.HandlerMapping mapping = EventHandlers.HANDLER_FOR_EVENT.get(eventType);
        if (mapping == null) return Optional.empty();

        Optional<String> propName = capture ? mapping.capture() : mapping.bubble();
        return propName.flatMap(handlers::getHandler);
    }

    // -------------------------------------------------------------------------
    // Listener execution
    // -------------------------------------------------------------------------

    /**
     * Execute collected listeners with propagation control. Before each handler,
     * {@link TerminalEvent#prepareForTarget} is called so subclasses can do per-node setup.
     */
    private static void processDispatchQueue(
            List<DispatchListener> listeners, TerminalEvent event) {

        TerminalEvent.EventTarget previousNode = null;

        for (DispatchListener dl : listeners) {
            if (event.didStopImmediatePropagation()) break;
            if (event.isPropagationStopped() && dl.node() != previousNode) break;

            event.setEventPhase(dl.phase());
            event.setCurrentTarget(dl.node());
            event.prepareForTarget(dl.node());

            try {
                dl.handler().accept(event);
            } catch (Exception e) {
                log.error("Error in event handler for event type '{}'", event.getType(), e);
            }

            previousNode = dl.node();
        }
    }

    // -------------------------------------------------------------------------
    // Internal record
    // -------------------------------------------------------------------------

    private record DispatchListener(
            TerminalEvent.EventTarget node,
            Consumer<TerminalEvent> handler,
            TerminalEvent.EventPhase phase) {}
}
