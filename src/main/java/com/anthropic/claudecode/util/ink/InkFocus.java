package com.anthropic.claudecode.util.ink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Java equivalent of Ink's focus.ts (src/ink/focus.ts).
 *
 * <p>DOM-like focus manager for the Ink terminal UI. Tracks the currently active element and a
 * bounded focus stack. Has no direct reference to the tree; callers pass the root node when tree
 * walks are needed.
 *
 * <p>Stored on the root {@link DomElement} so any node can reach it by walking
 * {@code parentNode} — analogous to how a browser node reaches its {@code ownerDocument}.
 *
 * <h3>Interfaces</h3>
 *
 * <ul>
 *   <li>{@link DomElement} — the minimal node interface required by the focus manager</li>
 *   <li>{@link FocusEvent} — carries the event type ("focus" / "blur") and the related element</li>
 * </ul>
 */
public class InkFocus {

    private static final int MAX_FOCUS_STACK = 32;

    // -------------------------------------------------------------------------
    // Minimal DOM node interface
    // -------------------------------------------------------------------------

    /**
     * Minimal interface that a DOM node must implement to participate in focus management.
     * Mirrors the fields of the TypeScript {@code DOMElement} that focus.ts depends on.
     */
    public interface DomElement {
        DomElement getParentNode();
        List<? extends DomElement> getChildNodes();
        String getNodeName();

        /**
         * Returns the tab index of this element, or {@code -1} / {@code null} if not tabbable.
         * Any non-negative value makes the node tabbable.
         */
        Integer getTabIndex();

        /**
         * The {@link FocusManager} attached to this node (only set on the root node). Mirrors
         * the TypeScript {@code focusManager?: FocusManager} field on {@code DOMElement}.
         */
        FocusManager getFocusManager();
    }

    // -------------------------------------------------------------------------
    // FocusEvent (replaces src/ink/events/focus-event.ts)
    // -------------------------------------------------------------------------

    /**
     * Simple focus event carrying the event type and the related (previously- or next-focused)
     * element. Mirrors the TypeScript {@code FocusEvent} constructor
     * {@code new FocusEvent('blur'|'focus', relatedTarget)}.
     */
    public record FocusEvent(String type, DomElement relatedTarget) {}

    // -------------------------------------------------------------------------
    // FocusManager
    // -------------------------------------------------------------------------

    /**
     * Tracks the active element and a bounded focus stack. Dispatches focus/blur events via the
     * injected {@code dispatchFocusEvent} callback.
     *
     * <p>Mirrors the TypeScript {@code FocusManager} class.
     */
    public static class FocusManager {

        private DomElement activeElement = null;
        private boolean enabled = true;
        private final Deque<DomElement> focusStack = new ArrayDeque<>();
        private final BiFunction<DomElement, FocusEvent, Boolean> dispatchFocusEvent;

        /**
         * Construct a new {@code FocusManager}.
         *
         * @param dispatchFocusEvent callback that dispatches a {@link FocusEvent} on the given node;
         *                           returns {@code true} if {@code preventDefault()} was NOT called
         */
        public FocusManager(BiFunction<DomElement, FocusEvent, Boolean> dispatchFocusEvent) {
            this.dispatchFocusEvent = dispatchFocusEvent;
        }

        // ------------------------------------------------------------------
        // Public API
        // ------------------------------------------------------------------

        public DomElement getActiveElement() {
            return activeElement;
        }

        /**
         * Move focus to {@code node}. Dispatches blur on the previous element and focus on
         * {@code node}. No-op if {@code node} is already focused or if the manager is disabled.
         */
        public void focus(DomElement node) {
            if (node == activeElement) return;
            if (!enabled) return;

            DomElement previous = activeElement;
            if (previous != null) {
                // Deduplicate before pushing to prevent unbounded growth from Tab cycling
                boolean removed = focusStack.remove(previous);
                focusStack.addLast(previous);
                if (focusStack.size() > MAX_FOCUS_STACK) {
                    focusStack.removeFirst();
                }
                dispatchFocusEvent.apply(previous, new FocusEvent("blur", node));
            }
            activeElement = node;
            dispatchFocusEvent.apply(node, new FocusEvent("focus", previous));
        }

        /**
         * Remove focus from the current element without transferring it anywhere. Dispatches a blur
         * event. No-op if nothing is focused.
         */
        public void blur() {
            if (activeElement == null) return;
            DomElement previous = activeElement;
            activeElement = null;
            dispatchFocusEvent.apply(previous, new FocusEvent("blur", null));
        }

        /**
         * Called by the reconciler when a node is removed from the tree. Handles both the exact node
         * and any focused descendant within the removed subtree. Dispatches blur and attempts to
         * restore focus from the stack.
         *
         * @param node the removed node
         * @param root the tree root (used to verify whether a candidate is still mounted)
         */
        public void handleNodeRemoved(DomElement node, DomElement root) {
            // Remove the node and any stale entries from the stack
            focusStack.removeIf(n -> n == node || !isInTree(n, root));

            if (activeElement == null) return;
            if (activeElement != node && isInTree(activeElement, root)) return;

            DomElement removed = activeElement;
            activeElement = null;
            dispatchFocusEvent.apply(removed, new FocusEvent("blur", null));

            // Restore focus to the most recent still-mounted element
            while (!focusStack.isEmpty()) {
                DomElement candidate = focusStack.removeLast();
                if (isInTree(candidate, root)) {
                    activeElement = candidate;
                    dispatchFocusEvent.apply(candidate, new FocusEvent("focus", removed));
                    return;
                }
            }
        }

        /**
         * Focus {@code node} immediately (for {@code autoFocus} reconciler handling).
         */
        public void handleAutoFocus(DomElement node) {
            focus(node);
        }

        /**
         * Focus {@code node} if it has a non-negative {@code tabIndex} (for click-to-focus).
         */
        public void handleClickFocus(DomElement node) {
            Integer tabIndex = node.getTabIndex();
            if (tabIndex == null || tabIndex < 0) return;
            focus(node);
        }

        public void enable() {
            enabled = true;
        }

        public void disable() {
            enabled = false;
        }

        /** Move focus to the next tabbable element (Tab key). */
        public void focusNext(DomElement root) {
            moveFocus(1, root);
        }

        /** Move focus to the previous tabbable element (Shift+Tab). */
        public void focusPrevious(DomElement root) {
            moveFocus(-1, root);
        }

        // ------------------------------------------------------------------
        // Private helpers
        // ------------------------------------------------------------------

        private void moveFocus(int direction, DomElement root) {
            if (!enabled) return;

            List<DomElement> tabbable = collectTabbable(root);
            if (tabbable.isEmpty()) return;

            int currentIndex = activeElement == null ? -1 : tabbable.indexOf(activeElement);
            int nextIndex;
            if (currentIndex == -1) {
                nextIndex = direction == 1 ? 0 : tabbable.size() - 1;
            } else {
                nextIndex = Math.floorMod(currentIndex + direction, tabbable.size());
            }

            DomElement next = tabbable.get(nextIndex);
            if (next != null) {
                focus(next);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tree utilities
    // -------------------------------------------------------------------------

    private static List<DomElement> collectTabbable(DomElement root) {
        List<DomElement> result = new ArrayList<>();
        walkTree(root, result);
        return result;
    }

    private static void walkTree(DomElement node, List<DomElement> result) {
        Integer tabIndex = node.getTabIndex();
        if (tabIndex != null && tabIndex >= 0) {
            result.add(node);
        }
        for (DomElement child : node.getChildNodes()) {
            if (!"#text".equals(child.getNodeName())) {
                walkTree(child, result);
            }
        }
    }

    private static boolean isInTree(DomElement node, DomElement root) {
        DomElement current = node;
        while (current != null) {
            if (current == root) return true;
            current = current.getParentNode();
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Static helpers (mirrors exported functions in focus.ts)
    // -------------------------------------------------------------------------

    /**
     * Walk up to the root and return it. The root is the node that holds the {@link FocusManager} —
     * like {@code node.getRootNode()} in the browser.
     *
     * @throws IllegalStateException if the node is not in a tree with a {@link FocusManager}
     */
    public static DomElement getRootNode(DomElement node) {
        DomElement current = node;
        while (current != null) {
            if (current.getFocusManager() != null) return current;
            current = current.getParentNode();
        }
        throw new IllegalStateException("Node is not in a tree with a FocusManager");
    }

    /**
     * Walk up to the root and return its {@link FocusManager}. Like {@code node.ownerDocument} in
     * the browser.
     */
    public static FocusManager getFocusManager(DomElement node) {
        return getRootNode(node).getFocusManager();
    }
}
