package com.anthropic.claudecode.util.bash;

/**
 * Bash command spec for the {@code alias} built-in.
 * Translated from src/utils/bash/specs/alias.ts
 *
 * Provides metadata (name, description, argument spec) used by the bash
 * command registry to build completions and help text.
 */
public final class BashSpecAlias {

    private BashSpecAlias() {}

    /** Command name. */
    public static final String NAME = "alias";

    /** Short description. */
    public static final String DESCRIPTION = "Create or list command aliases";

    /**
     * Argument specification for {@code alias}.
     * Translated from the {@code args} field in alias.ts
     *
     * The argument is optional and variadic — zero or more {@code name=value}
     * pairs can be supplied. When omitted, the shell lists all current aliases.
     */
    public static final ArgSpec ARGS = new ArgSpec(
        "definition",
        "Alias definition in the form name=value",
        /*isOptional=*/ true,
        /*isVariadic=*/ true,
        /*isCommand=*/  false
    );

    /**
     * Full {@link CommandSpec} for {@code alias}.
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
