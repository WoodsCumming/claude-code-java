package com.anthropic.claudecode.util.ink.layout;

import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutAlign;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutDisplay;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutEdge;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutFlexDirection;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutGutter;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutJustify;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutMeasureMode;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutOverflow;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutPositionType;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.LayoutWrap;
import com.anthropic.claudecode.util.ink.layout.LayoutNode.MeasureFunc;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory and default implementation for the layout engine.
 * Translated from engine.ts (which delegated to yoga.js).
 *
 * <p>This class provides a simple Java box-model implementation of {@link LayoutNode}
 * that can be used when a real Yoga binding is not available.  All style setters
 * accumulate state; {@link BoxLayoutNode#calculateLayout} performs a single-pass
 * flexbox-style layout pass sufficient for the Ink terminal renderer.
 */
public final class LayoutEngine {

    private LayoutEngine() {}

    /** Create a new {@link LayoutNode} backed by the built-in box-model engine. */
    public static LayoutNode createLayoutNode() {
        return new BoxLayoutNode();
    }

    // =========================================================================
    // Built-in box-model LayoutNode implementation
    // =========================================================================

    /**
     * A self-contained, lightweight {@link LayoutNode} that implements a subset of
     * the Yoga flexbox model sufficient for Ink terminal UI rendering.
     *
     * <p>Computed layout values are populated after {@link #calculateLayout} is called
     * on the root node.
     */
    public static final class BoxLayoutNode implements LayoutNode {

        // -----------------------------------------------------------------
        // Style state
        // -----------------------------------------------------------------

        private Float width;
        private Float height;
        private Float minWidth;
        private Float minHeight;
        private Float maxWidth;
        private Float maxHeight;
        private boolean widthAuto;
        private boolean heightAuto;

        private LayoutFlexDirection flexDirection = LayoutFlexDirection.COLUMN;
        private float flexGrow = 0f;
        private float flexShrink = 1f;
        private float flexBasis = Float.NaN;
        private LayoutWrap flexWrap = LayoutWrap.NO_WRAP;
        private LayoutAlign alignItems = LayoutAlign.STRETCH;
        private LayoutAlign alignSelf = LayoutAlign.AUTO;
        private LayoutJustify justifyContent = LayoutJustify.FLEX_START;
        private LayoutDisplay display = LayoutDisplay.FLEX;
        private LayoutPositionType positionType = LayoutPositionType.RELATIVE;
        private LayoutOverflow overflow = LayoutOverflow.VISIBLE;

        // Edges: top / right / bottom / left
        private final float[] margin = new float[4];
        private final float[] padding = new float[4];
        private final float[] border = new float[4];
        private final float[] position = new float[]{Float.NaN, Float.NaN, Float.NaN, Float.NaN};

        private float gapColumn = 0f;
        private float gapRow = 0f;

        private MeasureFunc measureFunc;

        // -----------------------------------------------------------------
        // Tree
        // -----------------------------------------------------------------

        private BoxLayoutNode parent;
        private final List<BoxLayoutNode> children = new ArrayList<>();

        // -----------------------------------------------------------------
        // Computed layout (filled after calculateLayout)
        // -----------------------------------------------------------------

        private float computedLeft;
        private float computedTop;
        private float computedWidth;
        private float computedHeight;

        // -----------------------------------------------------------------
        // LayoutNode – tree
        // -----------------------------------------------------------------

        @Override
        public void insertChild(LayoutNode child, int index) {
            BoxLayoutNode c = (BoxLayoutNode) child;
            c.parent = this;
            children.add(index, c);
        }

        @Override
        public void removeChild(LayoutNode child) {
            BoxLayoutNode c = (BoxLayoutNode) child;
            if (children.remove(c)) {
                c.parent = null;
            }
        }

        @Override
        public int getChildCount() {
            return children.size();
        }

        @Override
        public LayoutNode getParent() {
            return parent;
        }

        // -----------------------------------------------------------------
        // LayoutNode – layout computation
        // -----------------------------------------------------------------

        @Override
        public void calculateLayout(float availableWidth, float availableHeight) {
            float aw = Float.isNaN(availableWidth) ? 0f : availableWidth;
            float ah = Float.isNaN(availableHeight) ? 0f : availableHeight;
            // Root node: position at origin
            computedLeft = 0;
            computedTop = 0;
            resolveSize(aw, ah);
            layoutChildren(computedWidth, computedHeight);
        }

        @Override
        public void setMeasureFunc(MeasureFunc fn) {
            this.measureFunc = fn;
        }

        @Override
        public void unsetMeasureFunc() {
            this.measureFunc = null;
        }

        @Override
        public void markDirty() {
            // In a full Yoga implementation this would invalidate cached layout.
            // For this simplified engine we are stateless between calculateLayout calls.
        }

        // -----------------------------------------------------------------
        // LayoutNode – computed values
        // -----------------------------------------------------------------

        @Override public float getComputedLeft()   { return computedLeft; }
        @Override public float getComputedTop()    { return computedTop; }
        @Override public float getComputedWidth()  { return computedWidth; }
        @Override public float getComputedHeight() { return computedHeight; }

        @Override
        public float getComputedBorder(LayoutEdge edge) {
            return border[edgeIndex(edge)];
        }

        @Override
        public float getComputedPadding(LayoutEdge edge) {
            return padding[edgeIndex(edge)];
        }

        // -----------------------------------------------------------------
        // LayoutNode – style setters
        // -----------------------------------------------------------------

        @Override public void setWidth(float v)          { width = v; widthAuto = false; }
        @Override public void setWidthPercent(float v)   { width = v; widthAuto = false; } // percent approximated
        @Override public void setWidthAuto()             { widthAuto = true; width = null; }

        @Override public void setHeight(float v)         { height = v; heightAuto = false; }
        @Override public void setHeightPercent(float v)  { height = v; heightAuto = false; }
        @Override public void setHeightAuto()            { heightAuto = true; height = null; }

        @Override public void setMinWidth(float v)       { minWidth = v; }
        @Override public void setMinWidthPercent(float v){ minWidth = v; }
        @Override public void setMinHeight(float v)      { minHeight = v; }
        @Override public void setMinHeightPercent(float v){ minHeight = v; }
        @Override public void setMaxWidth(float v)       { maxWidth = v; }
        @Override public void setMaxWidthPercent(float v){ maxWidth = v; }
        @Override public void setMaxHeight(float v)      { maxHeight = v; }
        @Override public void setMaxHeightPercent(float v){ maxHeight = v; }

        @Override public void setFlexDirection(LayoutFlexDirection dir)   { flexDirection = dir; }
        @Override public void setFlexGrow(float v)                        { flexGrow = v; }
        @Override public void setFlexShrink(float v)                      { flexShrink = v; }
        @Override public void setFlexBasis(float v)                       { flexBasis = v; }
        @Override public void setFlexBasisPercent(float v)                { flexBasis = v; }
        @Override public void setFlexWrap(LayoutWrap wrap)                { flexWrap = wrap; }
        @Override public void setAlignItems(LayoutAlign align)            { alignItems = align; }
        @Override public void setAlignSelf(LayoutAlign align)             { alignSelf = align; }
        @Override public void setJustifyContent(LayoutJustify justify)    { justifyContent = justify; }

        @Override public void setDisplay(LayoutDisplay d)      { display = d; }
        @Override public LayoutDisplay getDisplay()            { return display; }

        @Override public void setPositionType(LayoutPositionType t)       { positionType = t; }

        @Override
        public void setPosition(LayoutEdge edge, float value) {
            position[edgeIndex(edge)] = value;
        }

        @Override
        public void setPositionPercent(LayoutEdge edge, float value) {
            position[edgeIndex(edge)] = value;
        }

        @Override public void setOverflow(LayoutOverflow o) { overflow = o; }

        @Override
        public void setMargin(LayoutEdge edge, float value) {
            applyEdge(margin, edge, value);
        }

        @Override
        public void setPadding(LayoutEdge edge, float value) {
            applyEdge(padding, edge, value);
        }

        @Override
        public void setBorder(LayoutEdge edge, float value) {
            applyEdge(border, edge, value);
        }

        @Override
        public void setGap(LayoutGutter gutter, float value) {
            switch (gutter) {
                case ALL -> { gapColumn = value; gapRow = value; }
                case COLUMN -> gapColumn = value;
                case ROW -> gapRow = value;
            }
        }

        // -----------------------------------------------------------------
        // LayoutNode – lifecycle
        // -----------------------------------------------------------------

        @Override
        public void free() {
            if (parent != null) parent.children.remove(this);
            parent = null;
        }

        @Override
        public void freeRecursive() {
            for (BoxLayoutNode child : new ArrayList<>(children)) {
                child.freeRecursive();
            }
            children.clear();
            free();
        }

        // -----------------------------------------------------------------
        // Internal layout engine
        // -----------------------------------------------------------------

        /**
         * Resolve the node's own width / height from style constraints and
         * available space, taking into account the measure function when present.
         */
        private void resolveSize(float availableWidth, float availableHeight) {
            float innerW;
            float innerH;

            float borderH = border[edgeIndex(LayoutEdge.LEFT)] + border[edgeIndex(LayoutEdge.RIGHT)];
            float borderV = border[edgeIndex(LayoutEdge.TOP)] + border[edgeIndex(LayoutEdge.BOTTOM)];
            float paddingH = padding[edgeIndex(LayoutEdge.LEFT)] + padding[edgeIndex(LayoutEdge.RIGHT)];
            float paddingV = padding[edgeIndex(LayoutEdge.TOP)] + padding[edgeIndex(LayoutEdge.BOTTOM)];

            if (width != null) {
                innerW = width;
            } else {
                innerW = Math.max(0f, availableWidth - margin[edgeIndex(LayoutEdge.LEFT)] - margin[edgeIndex(LayoutEdge.RIGHT)]);
            }
            if (height != null) {
                innerH = height;
            } else {
                innerH = Math.max(0f, availableHeight - margin[edgeIndex(LayoutEdge.TOP)] - margin[edgeIndex(LayoutEdge.BOTTOM)]);
            }

            // Apply measure function (leaf text nodes)
            if (measureFunc != null && children.isEmpty()) {
                float contentW = Math.max(0f, innerW - paddingH - borderH);
                Geometry.Size measured = measureFunc.measure(contentW, LayoutMeasureMode.AT_MOST);
                if (widthAuto || width == null) {
                    innerW = measured.width() + paddingH + borderH;
                }
                if (heightAuto || height == null) {
                    innerH = measured.height() + paddingV + borderV;
                }
            }

            // Clamp to min/max
            if (minWidth != null)  innerW = Math.max(innerW, minWidth);
            if (maxWidth != null)  innerW = Math.min(innerW, maxWidth);
            if (minHeight != null) innerH = Math.max(innerH, minHeight);
            if (maxHeight != null) innerH = Math.min(innerH, maxHeight);

            computedWidth  = Math.max(0f, innerW);
            computedHeight = Math.max(0f, innerH);
        }

        /**
         * Single-pass flexbox child layout. Supports row / column directions and
         * basic justification / alignment sufficient for the Ink terminal renderer.
         */
        private void layoutChildren(float parentWidth, float parentHeight) {
            if (children.isEmpty()) return;

            float paddingLeft   = padding[edgeIndex(LayoutEdge.LEFT)];
            float paddingTop    = padding[edgeIndex(LayoutEdge.TOP)];
            float paddingRight  = padding[edgeIndex(LayoutEdge.RIGHT)];
            float paddingBottom = padding[edgeIndex(LayoutEdge.BOTTOM)];
            float borderLeft    = border[edgeIndex(LayoutEdge.LEFT)];
            float borderTop     = border[edgeIndex(LayoutEdge.TOP)];
            float borderRight   = border[edgeIndex(LayoutEdge.RIGHT)];
            float borderBottom  = border[edgeIndex(LayoutEdge.BOTTOM)];

            float contentX = borderLeft + paddingLeft;
            float contentY = borderTop + paddingTop;
            float contentW = parentWidth  - borderLeft - borderRight  - paddingLeft - paddingRight;
            float contentH = parentHeight - borderTop  - borderBottom - paddingTop  - paddingBottom;

            boolean isRow = flexDirection == LayoutFlexDirection.ROW
                    || flexDirection == LayoutFlexDirection.ROW_REVERSE;
            float gap = isRow ? gapColumn : gapRow;

            // First pass: resolve each child's size
            for (BoxLayoutNode child : children) {
                if (child.display == LayoutDisplay.NONE) continue;
                float childAvailW = isRow ? contentW : contentW;
                float childAvailH = isRow ? contentH : contentH;
                child.resolveSize(childAvailW, childAvailH);
                child.layoutChildren(child.computedWidth, child.computedHeight);
            }

            // Collect visible children
            List<BoxLayoutNode> visible = children.stream()
                    .filter(c -> c.display != LayoutDisplay.NONE)
                    .toList();

            if (visible.isEmpty()) return;

            // Total main-axis size used by children (including gaps)
            float totalMain = 0f;
            for (BoxLayoutNode child : visible) {
                float marginMain = isRow
                        ? (child.margin[edgeIndex(LayoutEdge.LEFT)] + child.margin[edgeIndex(LayoutEdge.RIGHT)])
                        : (child.margin[edgeIndex(LayoutEdge.TOP)] + child.margin[edgeIndex(LayoutEdge.BOTTOM)]);
                totalMain += (isRow ? child.computedWidth : child.computedHeight) + marginMain;
            }
            totalMain += gap * Math.max(0, visible.size() - 1);

            // Available space for justify-content distribution
            float mainSize = isRow ? contentW : contentH;
            float freeSpace = mainSize - totalMain;

            // Compute starting offset based on justifyContent
            float startOffset = 0f;
            float between = 0f;
            switch (justifyContent) {
                case FLEX_START -> startOffset = 0f;
                case FLEX_END   -> startOffset = Math.max(0f, freeSpace);
                case CENTER     -> startOffset = Math.max(0f, freeSpace / 2f);
                case SPACE_BETWEEN -> {
                    startOffset = 0f;
                    between = visible.size() > 1 ? Math.max(0f, freeSpace / (visible.size() - 1)) : 0f;
                }
                case SPACE_AROUND -> {
                    float unit = visible.size() > 0 ? freeSpace / visible.size() : 0f;
                    startOffset = unit / 2f;
                    between = unit;
                }
                case SPACE_EVENLY -> {
                    float unit = visible.size() > 0 ? freeSpace / (visible.size() + 1) : 0f;
                    startOffset = unit;
                    between = unit;
                }
            }

            // Position each child
            float cursor = startOffset;
            boolean reverseOrder = flexDirection == LayoutFlexDirection.ROW_REVERSE
                    || flexDirection == LayoutFlexDirection.COLUMN_REVERSE;
            List<BoxLayoutNode> ordered = reverseOrder
                    ? visible.reversed()
                    : visible;

            for (int i = 0; i < ordered.size(); i++) {
                BoxLayoutNode child = ordered.get(i);

                float marginLeft   = child.margin[edgeIndex(LayoutEdge.LEFT)];
                float marginTop    = child.margin[edgeIndex(LayoutEdge.TOP)];
                float marginRight  = child.margin[edgeIndex(LayoutEdge.RIGHT)];
                float marginBottom = child.margin[edgeIndex(LayoutEdge.BOTTOM)];

                if (isRow) {
                    child.computedLeft = contentX + cursor + marginLeft;
                    // Cross-axis alignment
                    float crossFree = contentH - child.computedHeight - marginTop - marginBottom;
                    child.computedTop = contentY + marginTop + crossAlignOffset(child.alignSelf, alignItems, crossFree);
                    cursor += child.computedWidth + marginLeft + marginRight;
                } else {
                    child.computedTop = contentY + cursor + marginTop;
                    // Cross-axis alignment
                    float crossFree = contentW - child.computedWidth - marginLeft - marginRight;
                    child.computedLeft = contentX + marginLeft + crossAlignOffset(child.alignSelf, alignItems, crossFree);
                    cursor += child.computedHeight + marginTop + marginBottom;
                }
                cursor += gap + (i < ordered.size() - 1 ? between : 0f);

                // Handle position: absolute nodes
                if (child.positionType == LayoutPositionType.ABSOLUTE) {
                    if (!Float.isNaN(child.position[edgeIndex(LayoutEdge.LEFT)])) {
                        child.computedLeft = borderLeft + paddingLeft + child.position[edgeIndex(LayoutEdge.LEFT)];
                    }
                    if (!Float.isNaN(child.position[edgeIndex(LayoutEdge.TOP)])) {
                        child.computedTop = borderTop + paddingTop + child.position[edgeIndex(LayoutEdge.TOP)];
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // Helpers
        // -----------------------------------------------------------------

        private static float crossAlignOffset(LayoutAlign self, LayoutAlign items, float free) {
            LayoutAlign effective = (self == LayoutAlign.AUTO) ? items : self;
            return switch (effective) {
                case CENTER   -> Math.max(0f, free / 2f);
                case FLEX_END -> Math.max(0f, free);
                default       -> 0f; // FLEX_START, STRETCH, AUTO
            };
        }

        /** Map a {@link LayoutEdge} to an index in a 4-element array [top, right, bottom, left]. */
        private static int edgeIndex(LayoutEdge edge) {
            return switch (edge) {
                case TOP, VERTICAL, ALL -> 0;
                case RIGHT, HORIZONTAL  -> 1;
                case BOTTOM             -> 2;
                case LEFT, START, END   -> 3;
            };
        }

        /** Apply a value to the appropriate indices based on edge type. */
        private static void applyEdge(float[] arr, LayoutEdge edge, float value) {
            switch (edge) {
                case ALL -> { arr[0] = value; arr[1] = value; arr[2] = value; arr[3] = value; }
                case VERTICAL   -> { arr[0] = value; arr[2] = value; }
                case HORIZONTAL -> { arr[1] = value; arr[3] = value; }
                case TOP    -> arr[0] = value;
                case RIGHT  -> arr[1] = value;
                case BOTTOM -> arr[2] = value;
                case LEFT, START, END -> arr[3] = value;
            }
        }
    }
}
