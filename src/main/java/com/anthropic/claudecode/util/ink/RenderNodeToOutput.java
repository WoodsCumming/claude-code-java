package com.anthropic.claudecode.util.ink;

import com.anthropic.claudecode.util.ink.SquashTextNodes.InkDomNode;
import com.anthropic.claudecode.util.ink.SquashTextNodes.StyledSegment;
import com.anthropic.claudecode.util.ink.WrapText.TextWrapMode;
import com.anthropic.claudecode.util.ink.layout.LayoutNode;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutDisplay;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Post-layout tree renderer: writes each DOM node's content into an {@link InkOutputBuffer}.
 * Translated from render-node-to-output.ts.
 *
 * <p>The entry point is {@link #renderNodeToOutput}.  It recursively descends the
 * node tree, routing each node type to the appropriate rendering logic:
 * <ul>
 *   <li>{@code ink-text} – squash text-node children into styled segments,
 *       wrap/truncate to the node's layout width, write via the output buffer.</li>
 *   <li>{@code ink-box}  – render children recursively; handle scroll containers.</li>
 *   <li>{@code ink-raw-ansi} – pass pre-rendered ANSI content through unchanged.</li>
 * </ul>
 */
public final class RenderNodeToOutput {

    private RenderNodeToOutput() {}

    // =========================================================================
    // Scroll hint (mirrors the exported ScrollHint type)
    // =========================================================================

    /**
     * DECSTBM scroll optimisation hint.
     * {@code delta > 0} means content moved up (scrollTop increased).
     */
    public record ScrollHint(int top, int bottom, int delta) {}

    /**
     * At-bottom follow-scroll event.
     */
    public record FollowScroll(int delta, int viewportTop, int viewportBottom) {}

    // =========================================================================
    // Render context (passed through the recursive call)
    // =========================================================================

    public static final class RenderContext {
        public final InkOutputBuffer output;
        public float offsetX;
        public float offsetY;
        public boolean skipSelfBlit;
        public String inheritedBackgroundColor;

        public RenderContext(InkOutputBuffer output, float offsetX, float offsetY) {
            this.output = output;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }
    }

    // =========================================================================
    // Output buffer contract
    // =========================================================================

    /**
     * Abstract output buffer that accepts rendered content.
     * Mirrors the {@code Output} class interface.
     */
    public interface InkOutputBuffer {
        /** Write {@code text} at cell position (x, y). */
        void write(float x, float y, String text);

        /** Write {@code text} with soft-wrap metadata. */
        default void write(float x, float y, String text, boolean[] softWrap) {
            write(x, y, text);
        }

        /** Clear a rectangular region. */
        void clear(int x, int y, int width, int height);

        /** Mark a region as non-selectable. */
        default void noSelect(int x, int y, int width, int height) {}

        /** Output width in columns. */
        int getWidth();
    }

    // =========================================================================
    // Thin DOM abstraction used during rendering
    // =========================================================================

    /**
     * Rendering-time DOM element.  Extends {@link InkDomNode} with layout and
     * display-style information.
     */
    public interface RenderDomElement extends InkDomNode {
        /** The Yoga layout node attached to this element (may be {@code null}). */
        LayoutNode getYogaNode();

        /** Whether this node is dirty (content has changed since last render). */
        boolean isDirty();

        /** Inline display styles (for {@code overflowX}, {@code overflowY}, etc.). */
        Map<String, Object> getStyle();

        /** Scroll position for scroll containers. */
        default float getScrollTop() { return 0f; }

        /** Current pending scroll delta (null = no pending scroll). */
        default Float getPendingScrollDelta() { return null; }
    }

    // =========================================================================
    // OSC 8 hyperlink helper
    // =========================================================================

    private static final String OSC = "\u001B]";
    private static final String BEL = "\u0007";

    private static String wrapWithOsc8Link(String text, String url) {
        return OSC + "8;;" + url + BEL + text + OSC + "8;;" + BEL;
    }

    // =========================================================================
    // Public entry point
    // =========================================================================

    /**
     * Render {@code node} and its descendants into {@code ctx.output}.
     *
     * @param node  root of the subtree to render
     * @param ctx   rendering context (output buffer + coordinate offsets)
     */
    public static void renderNodeToOutput(RenderDomElement node, RenderContext ctx) {
        LayoutNode yogaNode = node.getYogaNode();
        if (yogaNode == null) {
            // No layout node: render children at parent offsets
            renderChildren(node, ctx);
            return;
        }

        if (yogaNode.getDisplay() == LayoutDisplay.NONE) {
            return;
        }

        float x      = ctx.offsetX + yogaNode.getComputedLeft();
        float y      = ctx.offsetY + yogaNode.getComputedTop();
        float width  = yogaNode.getComputedWidth();
        float height = yogaNode.getComputedHeight();

        // Clamp absolute-positioned nodes that extend above the viewport
        Map<String, Object> style = node.getStyle();
        if (y < 0 && "absolute".equals(style.get("position"))) {
            y = 0;
        }

        switch (node.getNodeName()) {
            case "ink-raw-ansi" -> {
                Object rawText = node.getAttributes() != null ? node.getAttributes().get("rawText") : null;
                if (rawText instanceof String s && !s.isEmpty()) {
                    ctx.output.write(x, y, s);
                }
            }

            case "ink-text" -> renderTextNode(node, ctx, x, y, width, yogaNode, style);

            case "ink-box"  -> renderBoxNode(node, ctx, x, y, width, height, yogaNode, style);

            default -> {
                // Unknown node type: recurse into children
                RenderContext childCtx = new RenderContext(ctx.output, x, y);
                childCtx.inheritedBackgroundColor = ctx.inheritedBackgroundColor;
                renderChildren(node, childCtx);
            }
        }
    }

    // =========================================================================
    // ink-text rendering
    // =========================================================================

    private static void renderTextNode(
            RenderDomElement node,
            RenderContext ctx,
            float x,
            float y,
            float width,
            LayoutNode yogaNode,
            Map<String, Object> style) {

        List<StyledSegment> segments = SquashTextNodes.squashTextNodesToSegments(
                node,
                ctx.inheritedBackgroundColor != null
                        ? Map.of("backgroundColor", ctx.inheritedBackgroundColor)
                        : null,
                null,
                new ArrayList<>());

        String plainText = segments.stream().map(StyledSegment::text).reduce("", String::concat);
        if (plainText.isEmpty()) return;

        int maxWidth = (int) Math.min(width, ctx.output.getWidth() - x);
        Object textWrapVal = style != null ? style.get("textWrap") : null;
        String textWrapStr = textWrapVal instanceof String s ? s : "wrap";
        TextWrapMode wrapMode = TextWrapMode.fromString(textWrapStr);

        boolean needsWrapping = widestLine(plainText) > maxWidth;

        String text;
        boolean[] softWrap = null;

        if (!needsWrapping) {
            // No wrapping: apply styles directly to each segment
            StringBuilder sb = new StringBuilder();
            for (StyledSegment seg : segments) {
                String styled = applyTextStyles(seg.text(), seg.styles());
                if (seg.hyperlink() != null) styled = wrapWithOsc8Link(styled, seg.hyperlink());
                sb.append(styled);
            }
            text = sb.toString();
        } else if (segments.size() == 1) {
            // Single segment: wrap plain, then apply styles per line
            StyledSegment seg = segments.get(0);
            WrapResult wr = wrapWithSoftWrap(plainText, maxWidth, wrapMode);
            softWrap = wr.softWrap();
            StringBuilder sb = new StringBuilder();
            for (String line : wr.wrapped().split("\n", -1)) {
                if (!sb.isEmpty()) sb.append('\n');
                String styled = applyTextStyles(line, seg.styles());
                if (seg.hyperlink() != null) styled = wrapWithOsc8Link(styled, seg.hyperlink());
                sb.append(styled);
            }
            text = sb.toString();
        } else {
            // Multiple segments with wrapping: wrap plain, re-map styles by char position
            WrapResult wr = wrapWithSoftWrap(plainText, maxWidth, wrapMode);
            softWrap = wr.softWrap();
            int[] charToSegment = buildCharToSegmentMap(segments);
            text = applyStylesToWrappedText(
                    wr.wrapped(), segments, charToSegment, plainText,
                    wrapMode == TextWrapMode.WRAP_TRIM);
        }

        // Apply padding (indent from first child's computed offset)
        if (!node.getChildNodes().isEmpty()) {
            InkDomNode firstChild = node.getChildNodes().get(0);
            if (firstChild instanceof RenderDomElement rde && rde.getYogaNode() != null) {
                float offsetX2 = rde.getYogaNode().getComputedLeft();
                float offsetY2 = rde.getYogaNode().getComputedTop();
                if (offsetY2 > 0 || offsetX2 > 0) {
                    text = "\n".repeat((int) offsetY2) + indent(text, (int) offsetX2);
                    if (softWrap != null && offsetY2 > 0) {
                        boolean[] padded = new boolean[(int) offsetY2 + softWrap.length];
                        System.arraycopy(softWrap, 0, padded, (int) offsetY2, softWrap.length);
                        softWrap = padded;
                    }
                }
            }
        }

        ctx.output.write(x, y, text, softWrap);
    }

    // =========================================================================
    // ink-box rendering
    // =========================================================================

    private static void renderBoxNode(
            RenderDomElement node,
            RenderContext ctx,
            float x,
            float y,
            float width,
            float height,
            LayoutNode yogaNode,
            Map<String, Object> style) {

        String bgColor = style != null && style.get("backgroundColor") instanceof String s
                ? s : ctx.inheritedBackgroundColor;

        // noSelect
        if (style != null && style.get("noSelect") != null) {
            boolean fromEdge = "from-left-edge".equals(style.get("noSelect"));
            int bx = (int) Math.floor(x);
            ctx.output.noSelect(
                    fromEdge ? 0 : bx,
                    (int) Math.floor(y),
                    fromEdge ? bx + (int) Math.floor(width) : (int) Math.floor(width),
                    (int) Math.floor(height));
        }

        // Render children with adjusted offsets
        RenderContext childCtx = new RenderContext(ctx.output, x, y);
        childCtx.inheritedBackgroundColor = bgColor;

        for (InkDomNode childNode : node.getChildNodes()) {
            if (childNode instanceof RenderDomElement child) {
                renderNodeToOutput(child, childCtx);
            }
        }
    }

    // =========================================================================
    // Children pass-through
    // =========================================================================

    private static void renderChildren(RenderDomElement node, RenderContext ctx) {
        for (InkDomNode child : node.getChildNodes()) {
            if (child instanceof RenderDomElement rde) {
                renderNodeToOutput(rde, ctx);
            }
        }
    }

    // =========================================================================
    // Text wrapping helpers
    // =========================================================================

    private record WrapResult(String wrapped, boolean[] softWrap) {}

    private static WrapResult wrapWithSoftWrap(String plainText, int maxWidth, TextWrapMode mode) {
        if (mode != TextWrapMode.WRAP && mode != TextWrapMode.WRAP_TRIM) {
            return new WrapResult(WrapText.wrapText(plainText, maxWidth, mode), null);
        }
        String[] origLines = plainText.split("\n", -1);
        List<String> outLines = new ArrayList<>();
        List<Boolean> softWrapList = new ArrayList<>();
        for (String orig : origLines) {
            String[] pieces = WrapText.wrapText(orig, maxWidth, mode).split("\n", -1);
            for (int i = 0; i < pieces.length; i++) {
                outLines.add(pieces[i]);
                softWrapList.add(i > 0);
            }
        }
        boolean[] softWrap = new boolean[softWrapList.size()];
        for (int i = 0; i < softWrapList.size(); i++) softWrap[i] = softWrapList.get(i);
        return new WrapResult(String.join("\n", outLines), softWrap);
    }

    private static int[] buildCharToSegmentMap(List<StyledSegment> segments) {
        int total = segments.stream().mapToInt(s -> s.text().length()).sum();
        int[] map = new int[total];
        int pos = 0;
        for (int i = 0; i < segments.size(); i++) {
            int len = segments.get(i).text().length();
            for (int j = 0; j < len; j++) map[pos++] = i;
        }
        return map;
    }

    private static String applyStylesToWrappedText(
            String wrappedPlain,
            List<StyledSegment> segments,
            int[] charToSegment,
            String originalPlain,
            boolean trimEnabled) {

        String[] lines = wrappedPlain.split("\n", -1);
        List<String> resultLines = new ArrayList<>();
        int charIndex = 0;

        for (int lineIdx = 0; lineIdx < lines.length; lineIdx++) {
            String line = lines[lineIdx];

            // In trim mode, skip leading whitespace that was trimmed
            if (trimEnabled && !line.isEmpty()) {
                boolean lineStartsWS = Character.isWhitespace(line.charAt(0));
                boolean origHasWS = charIndex < originalPlain.length()
                        && Character.isWhitespace(originalPlain.charAt(charIndex));
                if (origHasWS && !lineStartsWS) {
                    while (charIndex < originalPlain.length()
                            && Character.isWhitespace(originalPlain.charAt(charIndex))) {
                        charIndex++;
                    }
                }
            }

            StringBuilder styledLine = new StringBuilder();
            int runStart = 0;
            int runSegIdx = charIndex < charToSegment.length ? charToSegment[charIndex] : 0;

            for (int i = 0; i < line.length(); i++) {
                int curSeg = charIndex < charToSegment.length ? charToSegment[charIndex] : runSegIdx;
                if (curSeg != runSegIdx) {
                    // Flush run
                    String run = line.substring(runStart, i);
                    styledLine.append(styledRun(run, runSegIdx, segments));
                    runStart = i;
                    runSegIdx = curSeg;
                }
                charIndex++;
            }
            // Flush final run
            styledLine.append(styledRun(line.substring(runStart), runSegIdx, segments));
            resultLines.add(styledLine.toString());

            if (charIndex < originalPlain.length() && originalPlain.charAt(charIndex) == '\n') {
                charIndex++;
            }

            // Trim mode: skip whitespace that was replaced by wrapping newline
            if (trimEnabled && lineIdx < lines.length - 1) {
                String nextLine = lines[lineIdx + 1];
                Character nextFirst = nextLine.isEmpty() ? null : nextLine.charAt(0);
                while (charIndex < originalPlain.length()
                        && Character.isWhitespace(originalPlain.charAt(charIndex))) {
                    if (nextFirst != null && originalPlain.charAt(charIndex) == nextFirst) break;
                    charIndex++;
                }
            }
        }

        return String.join("\n", resultLines);
    }

    private static String styledRun(String text, int segIdx, List<StyledSegment> segments) {
        if (segIdx < 0 || segIdx >= segments.size()) return text;
        StyledSegment seg = segments.get(segIdx);
        String styled = applyTextStyles(text, seg.styles());
        if (seg.hyperlink() != null) styled = wrapWithOsc8Link(styled, seg.hyperlink());
        return styled;
    }

    // =========================================================================
    // Style application (minimal – production code delegates to AnsiUtils)
    // =========================================================================

    /**
     * Apply the given style map to {@code text}, returning ANSI-decorated output.
     * This is a minimal implementation; a production renderer would use the
     * project's full {@code applyTextStyles} utility.
     */
    private static String applyTextStyles(String text, Map<String, Object> styles) {
        if (styles == null || styles.isEmpty() || text.isEmpty()) return text;
        // Delegate to SgrUtils or a similar helper in the project.
        // For now, pass through unchanged.
        return text;
    }

    // =========================================================================
    // Misc helpers
    // =========================================================================

    /** Widest visual line in a multi-line string. */
    static int widestLine(String text) {
        int max = 0;
        for (String line : text.split("\n", -1)) {
            max = Math.max(max, WrapText.visibleWidth(line));
        }
        return max;
    }

    /** Indent all lines of {@code text} by {@code spaces} spaces. */
    private static String indent(String text, int spaces) {
        if (spaces <= 0) return text;
        String pad = " ".repeat(spaces);
        return pad + text.replace("\n", "\n" + pad);
    }
}
