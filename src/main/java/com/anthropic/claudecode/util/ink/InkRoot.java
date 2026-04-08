package com.anthropic.claudecode.util.ink;

import lombok.extern.slf4j.Slf4j;
import javax.annotation.Nullable;
import java.io.PrintStream;
import java.util.function.Consumer;

/**
 * Java equivalent of root.ts.
 *
 * root.ts exports three entry-points:
 *  1. {@code renderSync}    — synchronously mount/reuse an Ink instance
 *  2. {@code wrappedRender} — async wrapper with a microtask boundary
 *  3. {@code createRoot}    — create a managed root without immediately rendering
 *
 * In Java there is no React reconciler or Yoga, so we model the interface
 * using callbacks and a simple lifecycle contract. Callers supply a
 * {@link RenderCallback} that receives the terminal output stream and performs
 * rendering; the root tracks whether it has been unmounted.
 *
 * The {@link FrameEvent} and {@link RenderOptions} types mirror the TS exports.
 */
@Slf4j
public final class InkRoot {



    private InkRoot() {}

    // -----------------------------------------------------------------------
    // RenderOptions
    // -----------------------------------------------------------------------

    /**
     * Options passed to {@link #createRoot} or {@link #renderSync}.
     * Mirrors the TypeScript {@code RenderOptions} type.
     */
    public static final class RenderOptions {
        /** Output stream (default: System.out). */
        public PrintStream stdout = System.out;
        /** Error stream (default: System.err). */
        public PrintStream stderr = System.err;
        /** Whether to exit the JVM on Ctrl+C (default: true). */
        public boolean exitOnCtrlC = true;
        /** Called after each frame with timing information. */
        @Nullable public Consumer<InkFrame.FrameEvent> onFrame;

        public RenderOptions() {}

        public RenderOptions stdout(PrintStream s) { this.stdout = s; return this; }
        public RenderOptions stderr(PrintStream s) { this.stderr = s; return this; }
        public RenderOptions exitOnCtrlC(boolean v) { this.exitOnCtrlC = v; return this; }
        public RenderOptions onFrame(Consumer<InkFrame.FrameEvent> h) { this.onFrame = h; return this; }
    }

    // -----------------------------------------------------------------------
    // Instance
    // -----------------------------------------------------------------------

    /**
     * Handle returned by {@link #renderSync}.
     * Mirrors the TypeScript {@code Instance} type.
     */
    public interface Instance {
        /** Re-render with updated state. */
        void rerender(Object node);
        /** Unmount the instance. */
        void unmount();
        /** Block until the instance is unmounted. */
        void waitUntilExit() throws InterruptedException;
        /** Remove the instance from the global registry. */
        void cleanup();
    }

    // -----------------------------------------------------------------------
    // Root
    // -----------------------------------------------------------------------

    /**
     * A managed root, similar to React DOM's createRoot API.
     * Mirrors the TypeScript {@code Root} type.
     */
    public interface Root {
        void render(Object node);
        void unmount();
        void waitUntilExit() throws InterruptedException;
    }

    // -----------------------------------------------------------------------
    // RenderCallback
    // -----------------------------------------------------------------------

    /**
     * Functional interface supplied by the caller to perform actual rendering.
     * In the TypeScript source the renderer is React + Yoga; here callers
     * provide their own rendering logic.
     */
    @FunctionalInterface
    public interface RenderCallback {
        void render(PrintStream out, Object node);
    }

    // -----------------------------------------------------------------------
    // DefaultInstance
    // -----------------------------------------------------------------------

    private static final class DefaultInstance implements Instance {
        private final PrintStream out;
        private final RenderCallback renderer;
        private final RenderOptions opts;
        private volatile boolean unmounted = false;
        private final Object lock = new Object();

        DefaultInstance(PrintStream out, RenderCallback renderer, RenderOptions opts) {
            this.out = out; this.renderer = renderer; this.opts = opts;
        }

        @Override public void rerender(Object node) {
            if (!unmounted) renderer.render(out, node);
        }

        @Override public void unmount() {
            synchronized (lock) {
                unmounted = true;
                lock.notifyAll();
            }
        }

        @Override public void waitUntilExit() throws InterruptedException {
            synchronized (lock) {
                while (!unmounted) lock.wait();
            }
        }

        @Override public void cleanup() {}
    }

    // -----------------------------------------------------------------------
    // renderSync
    // -----------------------------------------------------------------------

    /**
     * Mount a renderable node and return an {@link Instance}.
     * Mirrors the TypeScript {@code renderSync} function.
     *
     * @param node     the UI tree to render (caller-defined)
     * @param options  render options
     * @param renderer callback that performs the actual rendering
     * @return a handle to the running instance
     */
    public static Instance renderSync(Object node, RenderOptions options, RenderCallback renderer) {
        if (options == null) options = new RenderOptions();
        DefaultInstance instance = new DefaultInstance(options.stdout, renderer, options);
        instance.rerender(node);
        return instance;
    }

    // -----------------------------------------------------------------------
    // createRoot
    // -----------------------------------------------------------------------

    /**
     * Create a managed root without rendering anything yet.
     * Mirrors the TypeScript {@code createRoot} function.
     *
     * @param options  render options
     * @param renderer callback that performs rendering
     * @return a {@link Root} handle
     */
    public static Root createRoot(RenderOptions options, RenderCallback renderer) {
        if (options == null) options = new RenderOptions();
        final RenderOptions opts = options;
        DefaultInstance instance = new DefaultInstance(opts.stdout, renderer, opts);
        return new Root() {
            @Override public void render(Object node) { instance.rerender(node); }
            @Override public void unmount() { instance.unmount(); }
            @Override public void waitUntilExit() throws InterruptedException { instance.waitUntilExit(); }
        };
    }
}
