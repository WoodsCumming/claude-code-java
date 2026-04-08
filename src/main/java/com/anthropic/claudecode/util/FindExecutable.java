package com.anthropic.claudecode.util;

import java.util.List;
import java.util.Optional;

/**
 * Find executable utilities, similar to {@code which}.
 * Replaces spawn-rx's findActualExecutable to avoid pulling in rxjs.
 * Translated from src/utils/findExecutable.ts
 */
public class FindExecutable {

    private FindExecutable() {}

    /**
     * Find an executable by searching PATH, similar to {@code which}.
     *
     * Returns an {@link ExecutableResult} whose {@code cmd} is the resolved path
     * if found, or the original {@code exe} name if not. {@code args} is always
     * the pass-through of the input args — matching the spawn-rx API shape.
     * Translated from findExecutable() in findExecutable.ts
     *
     * @param exe  Executable name or path to look up
     * @param args Arguments to pass through unchanged
     * @return An {@link ExecutableResult} with the resolved command and args
     */
    public static ExecutableResult findExecutable(String exe, List<String> args) {
        Optional<String> resolved = WhichUtils.whichSync(exe);
        return new ExecutableResult(resolved.orElse(exe), args);
    }

    /**
     * Result record mirroring the {@code { cmd, args }} shape from the TS source.
     */
    public record ExecutableResult(String cmd, List<String> args) {}
}
