package com.anthropic.claudecode.util.ink.layout;

import java.util.function.BiFunction;

/**
 * Adapter interface for the layout engine (mirrors Yoga's API surface).
 * Translated from node.ts.
 *
 * <p>All enum constants use the same string values as the TypeScript originals
 * so serialisation / debugging remain consistent.
 */
public interface LayoutNode {

    // =========================================================================
    // Nested enums  (TypeScript const-objects → Java enums)
    // =========================================================================

    enum LayoutEdge {
        ALL("all"),
        HORIZONTAL("horizontal"),
        VERTICAL("vertical"),
        LEFT("left"),
        RIGHT("right"),
        TOP("top"),
        BOTTOM("bottom"),
        START("start"),
        END("end");

        public final String value;
        LayoutEdge(String value) { this.value = value; }
    }

    enum LayoutGutter {
        ALL("all"),
        COLUMN("column"),
        ROW("row");

        public final String value;
        LayoutGutter(String value) { this.value = value; }
    }

    enum LayoutDisplay {
        FLEX("flex"),
        NONE("none");

        public final String value;
        LayoutDisplay(String value) { this.value = value; }
    }

    enum LayoutFlexDirection {
        ROW("row"),
        ROW_REVERSE("row-reverse"),
        COLUMN("column"),
        COLUMN_REVERSE("column-reverse");

        public final String value;
        LayoutFlexDirection(String value) { this.value = value; }
    }

    enum LayoutAlign {
        AUTO("auto"),
        STRETCH("stretch"),
        FLEX_START("flex-start"),
        CENTER("center"),
        FLEX_END("flex-end");

        public final String value;
        LayoutAlign(String value) { this.value = value; }
    }

    enum LayoutJustify {
        FLEX_START("flex-start"),
        CENTER("center"),
        FLEX_END("flex-end"),
        SPACE_BETWEEN("space-between"),
        SPACE_AROUND("space-around"),
        SPACE_EVENLY("space-evenly");

        public final String value;
        LayoutJustify(String value) { this.value = value; }
    }

    enum LayoutWrap {
        NO_WRAP("nowrap"),
        WRAP("wrap"),
        WRAP_REVERSE("wrap-reverse");

        public final String value;
        LayoutWrap(String value) { this.value = value; }
    }

    enum LayoutPositionType {
        RELATIVE("relative"),
        ABSOLUTE("absolute");

        public final String value;
        LayoutPositionType(String value) { this.value = value; }
    }

    enum LayoutOverflow {
        VISIBLE("visible"),
        HIDDEN("hidden"),
        SCROLL("scroll");

        public final String value;
        LayoutOverflow(String value) { this.value = value; }
    }

    enum LayoutMeasureMode {
        UNDEFINED("undefined"),
        EXACTLY("exactly"),
        AT_MOST("at-most");

        public final String value;
        LayoutMeasureMode(String value) { this.value = value; }
    }

    // =========================================================================
    // Functional type for measure callbacks
    // =========================================================================

    /** Mirrors {@code LayoutMeasureFunc}: given available width + mode, returns
     *  the natural width × height of the node's content. */
    @FunctionalInterface
    interface MeasureFunc {
        Geometry.Size measure(float width, LayoutMeasureMode widthMode);
    }

    // =========================================================================
    // Tree management
    // =========================================================================

    void insertChild(LayoutNode child, int index);

    void removeChild(LayoutNode child);

    int getChildCount();

    LayoutNode getParent();

    // =========================================================================
    // Layout computation
    // =========================================================================

    void calculateLayout(float width, float height);

    default void calculateLayout() {
        calculateLayout(Float.NaN, Float.NaN);
    }

    void setMeasureFunc(MeasureFunc fn);

    void unsetMeasureFunc();

    void markDirty();

    // =========================================================================
    // Layout reading  (post-layout)
    // =========================================================================

    float getComputedLeft();

    float getComputedTop();

    float getComputedWidth();

    float getComputedHeight();

    float getComputedBorder(LayoutEdge edge);

    float getComputedPadding(LayoutEdge edge);

    // =========================================================================
    // Style setters – dimensions
    // =========================================================================

    void setWidth(float value);
    void setWidthPercent(float value);
    void setWidthAuto();

    void setHeight(float value);
    void setHeightPercent(float value);
    void setHeightAuto();

    void setMinWidth(float value);
    void setMinWidthPercent(float value);

    void setMinHeight(float value);
    void setMinHeightPercent(float value);

    void setMaxWidth(float value);
    void setMaxWidthPercent(float value);

    void setMaxHeight(float value);
    void setMaxHeightPercent(float value);

    // =========================================================================
    // Style setters – flex / alignment
    // =========================================================================

    void setFlexDirection(LayoutFlexDirection dir);
    void setFlexGrow(float value);
    void setFlexShrink(float value);
    void setFlexBasis(float value);
    void setFlexBasisPercent(float value);
    void setFlexWrap(LayoutWrap wrap);
    void setAlignItems(LayoutAlign align);
    void setAlignSelf(LayoutAlign align);
    void setJustifyContent(LayoutJustify justify);

    // =========================================================================
    // Style setters – display / position / overflow
    // =========================================================================

    void setDisplay(LayoutDisplay display);
    LayoutDisplay getDisplay();

    void setPositionType(LayoutPositionType type);
    void setPosition(LayoutEdge edge, float value);
    void setPositionPercent(LayoutEdge edge, float value);

    void setOverflow(LayoutOverflow overflow);

    // =========================================================================
    // Style setters – spacing
    // =========================================================================

    void setMargin(LayoutEdge edge, float value);
    void setPadding(LayoutEdge edge, float value);
    void setBorder(LayoutEdge edge, float value);
    void setGap(LayoutGutter gutter, float value);

    // =========================================================================
    // Lifecycle
    // =========================================================================

    void free();

    void freeRecursive();
}
