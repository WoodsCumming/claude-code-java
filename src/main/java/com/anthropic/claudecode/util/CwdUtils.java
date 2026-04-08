package com.anthropic.claudecode.util;

import java.util.concurrent.Callable;

/**
 * Current working directory utilities for concurrent agent contexts.
 * Translated from src/utils/cwd.ts
 *
 * <p>Uses InheritableThreadLocal so that async children of an agent thread
 * automatically inherit the CWD override — matching the AsyncLocalStorage
 * semantics of the TypeScript original. Each concurrent agent can see its
 * own working directory without affecting others.</p>
 */
public class CwdUtils {

    /**
     * Per-async-context CWD override.
     * InheritableThreadLocal is the closest Java equivalent to AsyncLocalStorage:
     * child threads inherit the parent's value, and changes in the child don't
     * propagate back to the parent.
     */
    private static final InheritableThreadLocal<String> cwdOverrideStorage =
            new InheritableThreadLocal<>();

    /**
     * Global CWD state (set by bootstrap / shell cd).
     * Defaults to JVM startup directory if not explicitly set.
     */
    private static volatile String globalCwdState = System.getProperty("user.dir", "");

    /**
     * Run a callable with an overridden working directory for the current
     * thread and all threads it spawns.
     * Translated from runWithCwdOverride() in cwd.ts
     */
    public static <T> T runWithCwdOverride(String cwd, Callable<T> fn) throws Exception {
        String previous = cwdOverrideStorage.get();
        cwdOverrideStorage.set(cwd);
        try {
            return fn.call();
        } finally {
            if (previous != null) {
                cwdOverrideStorage.set(previous);
            } else {
                cwdOverrideStorage.remove();
            }
        }
    }

    /**
     * Run a runnable with an overridden working directory (no return value).
     */
    public static void runWithCwdOverride(String cwd, Runnable fn) {
        String previous = cwdOverrideStorage.get();
        cwdOverrideStorage.set(cwd);
        try {
            fn.run();
        } finally {
            if (previous != null) {
                cwdOverrideStorage.set(previous);
            } else {
                cwdOverrideStorage.remove();
            }
        }
    }

    /**
     * Get the current working directory for this async context.
     * Returns the context-local override if set, otherwise the global CWD state.
     * Translated from pwd() in cwd.ts
     */
    public static String pwd() {
        String override = cwdOverrideStorage.get();
        return override != null ? override : globalCwdState;
    }

    /**
     * Get the current working directory, falling back to the original CWD if
     * {@link #pwd()} throws.
     * Translated from getCwd() in cwd.ts
     */
    public static String getCwd() {
        try {
            return pwd();
        } catch (Exception e) {
            return getOriginalCwd();
        }
    }

    /**
     * Get the original working directory from which the JVM was launched.
     * Equivalent to getOriginalCwd() / process.cwd() at startup.
     */
    public static String getOriginalCwd() {
        return System.getProperty("user.dir", "");
    }

    /**
     * Update the global CWD state (e.g. after a successful {@code cd} command).
     * This does not affect context-local overrides.
     */
    public static void setGlobalCwd(String cwd) {
        if (cwd != null && !cwd.isBlank()) {
            globalCwdState = cwd;
        }
    }

    private CwdUtils() {}
}
