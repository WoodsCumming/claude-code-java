package com.anthropic.claudecode.util.bash;

import java.util.List;

/**
 * Bash command spec for the {@code timeout} utility.
 * Translated from src/utils/bash/specs/timeout.ts
 *
 * {@code timeout} runs a command with a time limit, sending SIGTERM
 * (or another signal) when the duration expires.
 */
public final class BashSpecTimeout {

    private BashSpecTimeout() {}

    /** Command name. */
    public static final String NAME = "timeout";

    /** Short description. */
    public static final String DESCRIPTION = "Run a command with a time limit";

    /**
     * Argument specifications for {@code timeout}.
     * Translated from the {@code args} array in timeout.ts
     *
     * Two positional arguments:
     *  1. {@code duration} — required, non-command: the time limit
     *     (e.g., "10", "5s", "2m").
     *  2. {@code command} — required, isCommand=true: the command to run.
     */
    public static final List<ArgSpec> ARGS = List.of(
        new ArgSpec(
            "duration",
            "Duration to wait before timing out (e.g., 10, 5s, 2m)",
            /*isOptional=*/ false,
            /*isVariadic=*/ false,
            /*isCommand=*/  false
        ),
        new ArgSpec(
            "command",
            "Command to run",
            /*isOptional=*/ false,
            /*isVariadic=*/ false,
            /*isCommand=*/  true
        )
    );

    /**
     * Full {@link CommandSpec} for {@code timeout}.
     */
    public static final CommandSpec SPEC = new CommandSpec(NAME, DESCRIPTION, ARGS);

    // -------------------------------------------------------------------------
    // Shared spec types (mirrors CommandSpec / ArgSpec in registry.ts)
    // -------------------------------------------------------------------------

    /**
     * Specification for a single argument.
     * Translated from the inline arg-object type in registry.ts
     */
    public record ArgSpec(
        String name,
        String description,
        boolean isOptional,
        boolean isVariadic,
        boolean isCommand
    ) {}

    /**
     * Specification for a shell command with multiple positional arguments.
     * Translated from CommandSpec in registry.ts
     */
    public record CommandSpec(String name, String description, List<ArgSpec> args) {}
}
