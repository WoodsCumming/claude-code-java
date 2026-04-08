package com.anthropic.claudecode.util.ink;

import com.anthropic.claudecode.util.ink.events.EventHandlers;
import com.anthropic.claudecode.util.ink.events.TerminalEvent;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Java equivalent of Ink's dom.ts (src/ink/dom.ts).
 *
 * <p>Defines the Ink virtual DOM node model: {@link DomElement} and {@link TextNode}, along with
 * the mutating operations the reconciler uses ({@link #appendChildNode}, {@link #insertBeforeNode},
 * {@link #removeChildNode}, {@link #setAttribute}, {@link #markDirty}, etc.).
 *
 * <p>The node names mirror the TypeScript {@code ElementNames} union:
 * {@code ink-root}, {@code ink-box}, {@code ink-text}, {@code ink-virtual-text},
 * {@code ink-link}, {@code ink-progress}, {@code ink-raw-ansi}.
 *
 * <h3>Node hierarchy</h3>
 *
 * <pre>
 * DomNode (interface)
 *  ├── DomElement  (nodeName ≠ "#text")
 *  └── TextNode    (nodeName = "#text")
 * </pre>
 */
public final class InkDom {

    private InkDom() {}

    // =========================================================================
    // Node name constants
    // =========================================================================

    public static final String NODE_ROOT         = "ink-root";
    public static final String NODE_BOX          = "ink-box";
    public static final String NODE_TEXT         = "ink-text";
    public static final String NODE_VIRTUAL_TEXT = "ink-virtual-text";
    public static final String NODE_LINK         = "ink-link";
    public static final String NODE_PROGRESS     = "ink-progress";
    public static final String NODE_RAW_ANSI     = "ink-raw-ansi";
    public static final String NODE_TEXT_NAME    = "#text";

    // =========================================================================
    // DomNode interface
    // =========================================================================

    /**
     * Base interface for all Ink DOM nodes, mirroring the TypeScript {@code InkNode} shared fields
     * plus the discriminated {@code nodeName}.
     */
    public interface DomNode {
        String getNodeName();
        DomElement getParentNode();
        void setParentNode(DomElement parent);
        Map<String, Object> getStyle();
        void setStyle(Map<String, Object> style);
    }

    // =========================================================================
    // DomElement
    // =========================================================================

    /**
     * Mutable element node for all non-text node names. Mirrors the TypeScript {@code DOMElement}
     * type.
     */
    @Getter
    @Setter
    public static class DomElement implements DomNode, TerminalEvent.EventTarget {

        private final String nodeName;
        private DomElement parentNode;
        private final List<DomNode> childNodes = new ArrayList<>();
        private final Map<String, Object> attributes = new HashMap<>();
        private Map<String, Object> style = new HashMap<>();

        // Text styles (used by ink-text nodes)
        private Map<String, Object> textStyles;

        // Dirty flag — when true this node needs re-rendering
        private boolean dirty = false;

        // Set by the reconciler's hideInstance/unhideInstance
        private boolean hidden = false;

        // Event handlers stored separately from attributes (so identity changes don't trigger dirty)
        private EventHandlers eventHandlers;

        // Focus manager — only set on ink-root
        private InkFocus.FocusManager focusManager;

        // Debug owner chain (populated only when CLAUDE_CODE_DEBUG_REPAINTS is set)
        private List<String> debugOwnerChain;

        // Scroll state
        private int scrollTop = 0;
        private int pendingScrollDelta = 0;
        private Integer scrollClampMin;
        private Integer scrollClampMax;
        private Integer scrollHeight;
        private Integer scrollViewportHeight;
        private Integer scrollViewportTop;
        private boolean stickyScroll = false;

        // Anchor for scrollToElement
        private ScrollAnchor scrollAnchor;

        // Lifecycle callbacks (set by Ink internals)
        private Runnable onComputeLayout;
        private Runnable onRender;
        private Runnable onImmediateRender;

        // Skip-empty-render guard for React 19 test-mode double-invoke
        private boolean hasRenderedContent = false;

        public DomElement(String nodeName) {
            this.nodeName = nodeName;
        }

        // ------------------------------------------------------------------
        // TerminalEvent.EventTarget implementation
        // ------------------------------------------------------------------

        @Override
        public DomElement getParentNode() {
            return parentNode;
        }

        @Override
        public EventHandlers getEventHandlers() {
            return eventHandlers;
        }

        // ------------------------------------------------------------------
        // InkFocus.DomElement implementation
        // ------------------------------------------------------------------

        public List<DomNode> getChildNodes() {
            return childNodes;
        }

        public Integer getTabIndex() {
            Object v = attributes.get("tabIndex");
            return v instanceof Number n ? n.intValue() : null;
        }

        // ------------------------------------------------------------------
        // Convenience
        // ------------------------------------------------------------------

        public boolean isInkRoot() {
            return NODE_ROOT.equals(nodeName);
        }
    }

    // =========================================================================
    // TextNode
    // =========================================================================

    /**
     * Leaf text node. Mirrors the TypeScript {@code TextNode} type
     * ({@code nodeName = "#text"}).
     */
    @Getter
    @Setter
    public static class TextNode implements DomNode {

        private final String nodeName = NODE_TEXT_NAME;
        private DomElement parentNode;
        private Map<String, Object> style = new HashMap<>();
        private String nodeValue = "";

        public TextNode(String nodeValue) {
            setNodeValue(nodeValue);
        }

        public void setNodeValue(String text) {
            this.nodeValue = text == null ? "" : text;
        }
    }

    // =========================================================================
    // ScrollAnchor helper record
    // =========================================================================

    /**
     * Mirrors the TypeScript {@code scrollAnchor?: { el: DOMElement; offset: number }} field.
     */
    public record ScrollAnchor(DomElement element, int offset) {}

    // =========================================================================
    // Factory methods
    // =========================================================================

    /**
     * Create a new {@link DomElement} for the given node name.
     * Mirrors {@code createNode(nodeName: ElementNames): DOMElement}.
     */
    public static DomElement createNode(String nodeName) {
        return new DomElement(nodeName);
    }

    /**
     * Create a new {@link TextNode}.
     * Mirrors {@code createTextNode(text: string): TextNode}.
     */
    public static TextNode createTextNode(String text) {
        return new TextNode(text == null ? "" : text);
    }

    // =========================================================================
    // Tree mutation operations
    // =========================================================================

    /**
     * Append {@code childNode} to {@code node}'s child list. If {@code childNode} already has a
     * parent, it is removed from that parent first.
     * Mirrors {@code appendChildNode}.
     */
    public static void appendChildNode(DomElement node, DomElement childNode) {
        if (childNode.getParentNode() != null) {
            removeChildNode(childNode.getParentNode(), childNode);
        }
        childNode.setParentNode(node);
        node.getChildNodes().add(childNode);
        markDirty(node);
    }

    /**
     * Insert {@code newChildNode} before {@code beforeChildNode} in {@code node}'s child list. If
     * {@code beforeChildNode} is not found, appends at the end.
     * Mirrors {@code insertBeforeNode}.
     */
    public static void insertBeforeNode(DomElement node, DomNode newChildNode, DomNode beforeChildNode) {
        if (newChildNode instanceof DomElement de && de.getParentNode() != null) {
            removeChildNode(de.getParentNode(), de);
        }
        if (newChildNode instanceof DomElement de) {
            de.setParentNode(node);
        } else if (newChildNode instanceof TextNode tn) {
            tn.setParentNode(node);
        }

        int index = node.getChildNodes().indexOf(beforeChildNode);
        if (index >= 0) {
            node.getChildNodes().add(index, newChildNode);
        } else {
            node.getChildNodes().add(newChildNode);
        }
        markDirty(node);
    }

    /**
     * Remove {@code removeNode} from {@code node}'s child list.
     * Mirrors {@code removeChildNode}.
     */
    public static void removeChildNode(DomElement node, DomNode removeNode) {
        if (removeNode instanceof DomElement de) {
            de.setParentNode(null);
        } else if (removeNode instanceof TextNode tn) {
            tn.setParentNode(null);
        }
        node.getChildNodes().remove(removeNode);
        markDirty(node);
    }

    // =========================================================================
    // Attribute / style mutation
    // =========================================================================

    /**
     * Set an attribute on {@code node}, skipping the {@code "children"} key and skipping unchanged
     * values. Marks the node dirty on change.
     * Mirrors {@code setAttribute}.
     */
    public static void setAttribute(DomElement node, String key, Object value) {
        if ("children".equals(key)) return;
        if (Objects.equals(node.getAttributes().get(key), value)) return;
        node.getAttributes().put(key, value);
        markDirty(node);
    }

    /**
     * Set the style map on {@code node}, skipping unchanged maps (shallow compare).
     * Marks dirty on change. Mirrors {@code setStyle}.
     */
    public static void setStyle(DomNode node, Map<String, Object> style) {
        if (shallowEqual(node.getStyle(), style)) return;
        node.setStyle(style);
        markDirty(node);
    }

    /**
     * Set the text styles map on a {@link DomElement}, skipping unchanged maps.
     * Mirrors {@code setTextStyles}.
     */
    public static void setTextStyles(DomElement node, Map<String, Object> textStyles) {
        if (shallowEqual(node.getTextStyles(), textStyles)) return;
        node.setTextStyles(textStyles);
        markDirty(node);
    }

    /**
     * Update a {@link TextNode}'s value, skipping if unchanged.
     * Mirrors {@code setTextNodeValue}.
     */
    public static void setTextNodeValue(TextNode node, String text) {
        String normalized = text == null ? "" : text;
        if (Objects.equals(node.getNodeValue(), normalized)) return;
        node.setNodeValue(normalized);
        markDirty(node);
    }

    // =========================================================================
    // Dirty marking
    // =========================================================================

    /**
     * Mark {@code node} and all its ancestors as dirty for re-rendering.
     * Mirrors {@code markDirty}.
     */
    public static void markDirty(DomNode node) {
        DomNode current = node;
        while (current != null) {
            if (!(current instanceof TextNode)) {
                ((DomElement) current).setDirty(true);
            }
            current = current.getParentNode();
        }
    }

    /**
     * Walk to the root and call its {@code onRender} callback. Used for DOM-level mutations (e.g.
     * scrollTop changes) that should trigger an Ink frame without going through the reconciler.
     * Pair with {@link #markDirty}.
     * Mirrors {@code scheduleRenderFrom}.
     */
    public static void scheduleRenderFrom(DomNode node) {
        DomNode cur = node;
        while (cur != null && cur.getParentNode() != null) {
            cur = cur.getParentNode();
        }
        if (cur instanceof DomElement root && root.getOnRender() != null) {
            root.getOnRender().run();
        }
    }

    // =========================================================================
    // Tree search
    // =========================================================================

    /**
     * Find the React component stack responsible for content at screen row {@code y}.
     *
     * <p>DFS the DOM tree accumulating layout offsets. Returns the {@code debugOwnerChain} of the
     * deepest node whose bounding box contains {@code y}. Returns an empty list when no chain data
     * is available (i.e. {@code CLAUDE_CODE_DEBUG_REPAINTS} is not set).
     * Mirrors {@code findOwnerChainAtRow}.
     */
    public static List<String> findOwnerChainAtRow(DomElement root, int y) {
        // Layout node access is not modelled here (it lives in the layout engine).
        // This stub preserves the method signature for API completeness; a full
        // implementation would delegate to the layout engine to get computed top/height.
        return List.of();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean shallowEqual(Map<String, Object> a, Map<String, Object> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, Object> entry : a.entrySet()) {
            if (!Objects.equals(entry.getValue(), b.get(entry.getKey()))) return false;
        }
        return true;
    }
}
