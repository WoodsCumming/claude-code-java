package com.anthropic.claudecode.util.ink.hooks;

import java.util.function.Consumer;

/**
 * Java equivalent of Ink's useApp hook (src/ink/hooks/use-app.ts).
 *
 * <p>In the React world {@code useApp()} retrieves the {@code AppContext} which exposes a single
 * method — {@code exit()} — to programmatically unmount the Ink application.
 *
 * <p>In Java there is no React context. Instead, this class models the context value itself: an
 * {@link AppContext} record that carries an {@code exit} callback. Collaborators that need to
 * trigger an app exit obtain an {@code AppContext} instance (typically injected) and call
 * {@link AppContext#exit(Throwable)}.
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * AppContext appCtx = UseApp.AppContext.of(err -> {
 *     // shut down the Ink rendering loop
 * });
 * appCtx.exit(null);   // clean exit
 * appCtx.exit(error);  // exit with error
 * }</pre>
 */
public final class UseApp {

    private UseApp() {}

    // -------------------------------------------------------------------------
    // AppContext value type
    // -------------------------------------------------------------------------

    /**
     * Mirrors the TypeScript {@code Props} interface from {@code AppContext.ts}.
     *
     * <p>Holds the {@code exit} callback that was registered when the application started. Passing a
     * non-null {@link Throwable} signals an error exit; {@code null} is a clean exit.
     */
    public record AppContext(Consumer<Throwable> exitCallback) {

        /**
         * Factory method that mirrors the TypeScript default context value (no-op exit).
         */
        public static AppContext noOp() {
            return new AppContext(ignored -> {});
        }

        /**
         * Factory with an explicit exit handler.
         */
        public static AppContext of(Consumer<Throwable> exitCallback) {
            return new AppContext(exitCallback);
        }

        /**
         * Exit the application cleanly (no error).
         */
        public void exit() {
            exitCallback.accept(null);
        }

        /**
         * Exit the application, optionally with an error.
         *
         * @param error the error to propagate, or {@code null} for a clean exit
         */
        public void exit(Throwable error) {
            exitCallback.accept(error);
        }
    }
}
