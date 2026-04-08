package com.anthropic.claudecode.util.bash;

/**
 * Bash command spec for the {@code nohup} utility.
 * Translated from src/utils/bash/specs/nohup.ts
 *
 * {@code nohup} runs a command immune to hangup signals (SIGHUP), allowing
 * the process to keep running after the controlling terminal is closed.
 */
public final class BashSpecNohup {

    private BashSpecNohup() {}

    /** Command name. */
    public static final String NAME = "nohup";

    /** Short description. */
    public static final String DESCRIPTION = "Run a command immune to hangups";

    /**
     * Argument specification for {@code nohup}.
     * Translated from the {@code args} field in nohup.ts
     *
     * The single required argument is the command to run — marked
     * {@code isCommand = true} so the registry knows to provide sub-command
     * completions and security analysis for the delegated command.
     */
    public static final ArgSpec ARGS = new ArgSpec(
        "command",
        "Command to run with nohup",
        /*isOptional=*/ false,
        /*isVariadic=*/ false,
        /*isCommand=*/  true
    );

    /**
     * Full {@link CommandSpec} for {@code nohup}.
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
     * Specification for a shell command.
     * Translated from CommandSpec in registry.ts
     */
    public record CommandSpec(String name, String description, ArgSpec args) {}
}
