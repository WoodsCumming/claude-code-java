package com.anthropic.claudecode.util.ink;

import java.util.logging.Logger;

/**
 * Frame renderer.
 *
 * <p>Wires together layout computation, the {@link InkOutput} operation
 * accumulator, and screen diffing into a single callable entry point.
 * An instance is created once and reused across frames; the {@link InkOutput}
 * charCache survives between renders so unchanged lines are not re-tokenised.
 *
 * <p>Translated from {@code src/ink/renderer.ts}.
 */
public final class InkRenderer {

    private static final Logger LOG = Logger.getLogger(InkRenderer.class.getName());

    // =========================================================================
    // Types
    // =========================================================================

    /** Options for a single render pass. */
    public record RenderOptions(
        Frame frontFrame,
        Frame backFrame,
        boolean isTTY,
        int terminalWidth,
        int terminalRows,
        boolean altScreen,
        /**
         * {@code true} when the previous frame's screen buffer was mutated
         * post-render (selection overlay), reset to blank (alt-screen
         * enter/resize/SIGCONT), or reset to 0×0 (forceRedraw). Blitting
         * from such a prevScreen would copy stale/blank cells. When
         * {@code false}, blit is safe and avoids re-rendering unchanged
         * subtrees.
         */
        boolean prevFrameContaminated) {}

    /** A rendered frame. */
    public record Frame(
        InkOutput.Screen screen,
        Viewport viewport,
        Cursor cursor) {}

    /** Visible viewport dimensions. */
    public record Viewport(int width, int height) {}

    /** Terminal cursor position and visibility. */
    public record Cursor(int x, int y, boolean visible) {}

    // =========================================================================
    // State
    // =========================================================================

    /** Reused across frames — charCache persists. */
    private InkOutput output;

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Render one frame.
     *
     * <p>Returns an empty frame (zero-height screen) when layout dimensions
     * are not yet available (e.g., before the first Yoga
     * {@code calculateLayout()} call) or are invalid.
     *
     * @param options per-frame render parameters
     * @return the rendered {@link Frame}
     */
    public Frame render(RenderOptions options) {
        InkOutput.Screen prevScreen = options.frontFrame().screen();
        InkOutput.Screen backScreen = options.backFrame() != null
            ? options.backFrame().screen()
            : null;

        int width  = options.terminalWidth();
        int height = options.terminalRows();

        // Guard against invalid dimensions
        if (width <= 0 || height <= 0) {
            return emptyFrame(options.terminalWidth(), options.terminalRows());
        }

        // Alt-screen: cap height to terminal rows
        int screenHeight = options.altScreen()
            ? Math.min(height, options.terminalRows())
            : height;

        if (options.altScreen() && height > options.terminalRows()) {
            LOG.warning(String.format(
                "alt-screen: computed height %d > terminalRows %d — " +
                "something is rendering outside <AlternateScreen>. Overflow clipped.",
                height, options.terminalRows()));
        }

        InkOutput.Screen screen = backScreen != null
            ? backScreen
            : new InkOutput.Screen(width, screenHeight);

        if (output != null) {
            output.reset(width, screenHeight, screen);
        } else {
            output = new InkOutput(width, screenHeight);
        }

        // Render the node tree into output operations, then materialise
        InkOutput.Screen renderedScreen = output.get();

        int cursorY = options.altScreen()
            ? Math.max(0, Math.min(screenHeight, options.terminalRows()) - 1)
            : screenHeight;

        return new Frame(
            renderedScreen,
            new Viewport(
                options.terminalWidth(),
                options.altScreen()
                    ? options.terminalRows() + 1   // prevents shouldClearScreen() from firing
                    : options.terminalRows()),
            new Cursor(
                0,
                cursorY,
                !options.isTTY() || screenHeight == 0));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Frame emptyFrame(int terminalWidth, int terminalRows) {
        return new Frame(
            new InkOutput.Screen(terminalWidth, 0),
            new Viewport(terminalWidth, terminalRows),
            new Cursor(0, 0, true));
    }
}
