package com.anthropic.claudecode.util.ink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Text-node squashing for the Ink render tree.
 * Translated from squash-text-nodes.ts.
 *
 * <p>Walks an {@link InkDomNode} tree, collecting leaf text content into
 * {@link StyledSegment} objects that carry the accumulated style from ancestor
 * {@code ink-text} / {@code ink-link} nodes.  Used by the renderer to produce
 * styled terminal output without repeated ANSI string transforms.
 */
public final class SquashTextNodes {

    private SquashTextNodes() {}

    // =========================================================================
    // Domain types
    // =========================================================================

    /**
     * A contiguous run of text together with the resolved styles that should
     * be applied to it and an optional OSC-8 hyperlink URL.
     * Mirrors {@code StyledSegment} from squash-text-nodes.ts.
     */
    public record StyledSegment(String text, Map<String, Object> styles, String hyperlink) {
        public StyledSegment(String text, Map<String, Object> styles) {
            this(text, styles, null);
        }
    }

    /**
     * Minimal DOM node representation.  The renderer builds a tree of these;
     * this interface exposes only what the squash algorithm needs.
     */
    public interface InkDomNode {
        /** Node type: {@code "#text"}, {@code "ink-text"}, {@code "ink-virtual-text"},
         *  {@code "ink-link"}, {@code "ink-box"}, etc. */
        String getNodeName();

        /** Raw text value – only meaningful when {@link #getNodeName()} is {@code "#text"}. */
        String getNodeValue();

        /** Ordered child nodes. */
        List<InkDomNode> getChildNodes();

        /** Inline text styles declared on this node (may be {@code null}). */
        Map<String, Object> getTextStyles();

        /** Element attributes (used for e.g. the {@code href} on {@code ink-link}). */
        Map<String, Object> getAttributes();
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Recursively collect text segments from {@code node}, merging inherited styles
     * at each level.
     *
     * <p>Mirrors the exported {@code squashTextNodesToSegments} function.
     *
     * @param node              root of the subtree to walk
     * @param inheritedStyles   styles accumulated from ancestor nodes
     * @param inheritedHyperlink OSC-8 URL inherited from an ancestor {@code ink-link}
     * @param out               accumulator list (mutated in place; also returned)
     * @return {@code out} (for convenience)
     */
    public static List<StyledSegment> squashTextNodesToSegments(
            InkDomNode node,
            Map<String, Object> inheritedStyles,
            String inheritedHyperlink,
            List<StyledSegment> out) {

        // Merge this node's text styles on top of inherited ones
        Map<String, Object> mergedStyles;
        Map<String, Object> nodeStyles = node.getTextStyles();
        if (nodeStyles != null && !nodeStyles.isEmpty()) {
            mergedStyles = new HashMap<>(inheritedStyles != null ? inheritedStyles : Collections.emptyMap());
            mergedStyles.putAll(nodeStyles);
        } else {
            mergedStyles = inheritedStyles != null ? inheritedStyles : Collections.emptyMap();
        }

        for (InkDomNode child : node.getChildNodes()) {
            if (child == null) continue;

            switch (child.getNodeName()) {
                case "#text" -> {
                    String value = child.getNodeValue();
                    if (value != null && !value.isEmpty()) {
                        out.add(new StyledSegment(value, mergedStyles, inheritedHyperlink));
                    }
                }
                case "ink-text", "ink-virtual-text" ->
                        squashTextNodesToSegments(child, mergedStyles, inheritedHyperlink, out);
                case "ink-link" -> {
                    Object hrefAttr = child.getAttributes() != null
                            ? child.getAttributes().get("href")
                            : null;
                    String href = hrefAttr instanceof String s ? s : null;
                    String effectiveHref = href != null ? href : inheritedHyperlink;
                    squashTextNodesToSegments(child, mergedStyles, effectiveHref, out);
                }
                // Non-text nodes (ink-box, etc.) are silently skipped.
                default -> {}
            }
        }

        return out;
    }

    /** Overload with empty initial state. */
    public static List<StyledSegment> squashTextNodesToSegments(InkDomNode node) {
        return squashTextNodesToSegments(node, null, null, new ArrayList<>());
    }

    /**
     * Squash the subtree into a single plain-text string (no style information).
     * Mirrors the default-exported {@code squashTextNodes} function.
     *
     * <p>Used for text measurement during layout calculations.
     */
    public static String squashTextNodes(InkDomNode node) {
        StringBuilder sb = new StringBuilder();
        squashTextNodesImpl(node, sb);
        return sb.toString();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static void squashTextNodesImpl(InkDomNode node, StringBuilder sb) {
        for (InkDomNode child : node.getChildNodes()) {
            if (child == null) continue;
            switch (child.getNodeName()) {
                case "#text" -> {
                    String value = child.getNodeValue();
                    if (value != null) sb.append(value);
                }
                case "ink-text", "ink-virtual-text", "ink-link" ->
                        squashTextNodesImpl(child, sb);
                default -> {} // non-text nodes ignored
            }
        }
    }
}
