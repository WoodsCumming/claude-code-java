package com.anthropic.claudecode.util.bash;

import java.util.List;

/**
 * Command completion specs for common bash utilities.
 *
 * Each spec describes a command's name, description, options, and positional
 * arguments so the shell-completion engine can surface correct suggestions.
 *
 * Translated from src/utils/bash/specs/index.ts and the individual spec files:
 *   alias.ts, nohup.ts, pyright.ts, sleep.ts, srun.ts, time.ts, timeout.ts
 */
public final class BashSpecs {

    private BashSpecs() {}

    // =========================================================================
    // Domain types (CommandSpec in registry.ts)
    // =========================================================================

    /**
     * Describes a command-line argument (positional or variadic).
     * Translated from the args shape in CommandSpec / registry.ts
     */
    public record ArgSpec(
            String name,
            String description,
            boolean isOptional,
            boolean isVariadic,
            boolean isCommand    // true when the arg is itself a sub-command
    ) {
        /** Convenience constructor for simple required args. */
        public static ArgSpec required(String name, String description) {
            return new ArgSpec(name, description, false, false, false);
        }

        /** Convenience constructor for optional args. */
        public static ArgSpec optional(String name, String description) {
            return new ArgSpec(name, description, true, false, false);
        }

        /** Convenience constructor for variadic args. */
        public static ArgSpec variadic(String name, String description, boolean optional) {
            return new ArgSpec(name, description, optional, true, false);
        }

        /** Convenience constructor for sub-command args (e.g. nohup, time, timeout). */
        public static ArgSpec command(String name, String description) {
            return new ArgSpec(name, description, false, false, true);
        }
    }

    /**
     * Describes a command-line option (flag or option with value).
     * Translated from the options shape in CommandSpec / registry.ts
     */
    public record OptionSpec(
            List<String> names,      // e.g. ["--help", "-h"]
            String description,
            ArgSpec arg              // null if the option takes no value
    ) {
        /** Convenience constructor for flags (no value). */
        public static OptionSpec flag(List<String> names, String description) {
            return new OptionSpec(names, description, null);
        }

        /** Convenience constructor for options that take a value. */
        public static OptionSpec withArg(List<String> names, String description,
                                          String argName) {
            return new OptionSpec(names, description, ArgSpec.required(argName, ""));
        }

        public static OptionSpec withArg(List<String> names, String description,
                                          String argName, boolean optional) {
            return new OptionSpec(names, description,
                    optional ? ArgSpec.optional(argName, "") : ArgSpec.required(argName, ""));
        }
    }

    /**
     * Full command specification.
     * Translated from CommandSpec in registry.ts
     */
    public record CommandSpec(
            String name,
            String description,
            List<OptionSpec> options,   // may be empty
            List<ArgSpec> args          // positional args; may be empty
    ) {}

    // =========================================================================
    // alias  (alias.ts)
    // =========================================================================

    /**
     * alias — Create or list command aliases.
     * Translated from alias.ts
     */
    public static final CommandSpec ALIAS = new CommandSpec(
            "alias",
            "Create or list command aliases",
            List.of(),
            List.of(ArgSpec.variadic("definition",
                    "Alias definition in the form name=value", true))
    );

    // =========================================================================
    // nohup  (nohup.ts)
    // =========================================================================

    /**
     * nohup — Run a command immune to hangups.
     * Translated from nohup.ts
     */
    public static final CommandSpec NOHUP = new CommandSpec(
            "nohup",
            "Run a command immune to hangups",
            List.of(),
            List.of(ArgSpec.command("command", "Command to run with nohup"))
    );

    // =========================================================================
    // pyright  (pyright.ts)
    // =========================================================================

    /**
     * pyright — Type checker for Python.
     * Translated from pyright.ts
     */
    public static final CommandSpec PYRIGHT = new CommandSpec(
            "pyright",
            "Type checker for Python",
            List.of(
                    OptionSpec.flag(List.of("--help", "-h"), "Show help message"),
                    OptionSpec.flag(List.of("--version"), "Print pyright version and exit"),
                    OptionSpec.flag(List.of("--watch", "-w"),
                            "Continue to run and watch for changes"),
                    OptionSpec.withArg(List.of("--project", "-p"),
                            "Use the configuration file at this location",
                            "FILE OR DIRECTORY"),
                    OptionSpec.flag(List.of("-"),
                            "Read file or directory list from stdin"),
                    OptionSpec.withArg(List.of("--createstub"),
                            "Create type stub file(s) for import", "IMPORT"),
                    OptionSpec.withArg(List.of("--typeshedpath", "-t"),
                            "Use typeshed type stubs at this location", "DIRECTORY"),
                    OptionSpec.withArg(List.of("--verifytypes"),
                            "Verify completeness of types in py.typed package", "IMPORT"),
                    OptionSpec.flag(List.of("--ignoreexternal"),
                            "Ignore external imports for --verifytypes"),
                    OptionSpec.withArg(List.of("--pythonpath"),
                            "Path to the Python interpreter", "FILE"),
                    OptionSpec.withArg(List.of("--pythonplatform"),
                            "Analyze for platform", "PLATFORM"),
                    OptionSpec.withArg(List.of("--pythonversion"),
                            "Analyze for Python version", "VERSION"),
                    OptionSpec.withArg(List.of("--venvpath", "-v"),
                            "Directory that contains virtual environments", "DIRECTORY"),
                    OptionSpec.flag(List.of("--outputjson"),
                            "Output results in JSON format"),
                    OptionSpec.flag(List.of("--verbose"), "Emit verbose diagnostics"),
                    OptionSpec.flag(List.of("--stats"), "Print detailed performance stats"),
                    OptionSpec.flag(List.of("--dependencies"),
                            "Emit import dependency information"),
                    OptionSpec.withArg(List.of("--level"),
                            "Minimum diagnostic level", "LEVEL"),
                    OptionSpec.flag(List.of("--skipunannotated"),
                            "Skip type analysis of unannotated functions"),
                    OptionSpec.flag(List.of("--warnings"),
                            "Use exit code of 1 if warnings are reported"),
                    new OptionSpec(List.of("--threads"),
                            "Use up to N threads to parallelize type checking",
                            new ArgSpec("N", "", true, false, false))
            ),
            List.of(ArgSpec.variadic("files",
                    "Specify files or directories to analyze (overrides config file)", true))
    );

    // =========================================================================
    // sleep  (sleep.ts)
    // =========================================================================

    /**
     * sleep — Delay for a specified amount of time.
     * Translated from sleep.ts
     */
    public static final CommandSpec SLEEP = new CommandSpec(
            "sleep",
            "Delay for a specified amount of time",
            List.of(),
            List.of(ArgSpec.required("duration",
                    "Duration to sleep (seconds or with suffix like 5s, 2m, 1h)"))
    );

    // =========================================================================
    // srun  (srun.ts)
    // =========================================================================

    /**
     * srun — Run a command on SLURM cluster nodes.
     * Translated from srun.ts
     */
    public static final CommandSpec SRUN = new CommandSpec(
            "srun",
            "Run a command on SLURM cluster nodes",
            List.of(
                    OptionSpec.withArg(List.of("-n", "--ntasks"),
                            "Number of tasks", "count"),
                    OptionSpec.withArg(List.of("-N", "--nodes"),
                            "Number of nodes", "count")
            ),
            List.of(ArgSpec.command("command", "Command to run on the cluster"))
    );

    // =========================================================================
    // time  (time.ts)
    // =========================================================================

    /**
     * time — Time a command.
     * Translated from time.ts
     */
    public static final CommandSpec TIME = new CommandSpec(
            "time",
            "Time a command",
            List.of(),
            List.of(ArgSpec.command("command", "Command to time"))
    );

    // =========================================================================
    // timeout  (timeout.ts)
    // =========================================================================

    /**
     * timeout — Run a command with a time limit.
     * Translated from timeout.ts
     */
    public static final CommandSpec TIMEOUT = new CommandSpec(
            "timeout",
            "Run a command with a time limit",
            List.of(),
            List.of(
                    ArgSpec.required("duration",
                            "Duration to wait before timing out (e.g., 10, 5s, 2m)"),
                    ArgSpec.command("command", "Command to run")
            )
    );

    // =========================================================================
    // Registry (index.ts)
    // =========================================================================

    /**
     * All registered bash command specs.
     * Translated from the default export array in index.ts
     */
    public static final List<CommandSpec> ALL = List.of(
            PYRIGHT,
            TIMEOUT,
            SLEEP,
            ALIAS,
            NOHUP,
            TIME,
            SRUN
    );

    /**
     * Look up a spec by command name.
     *
     * @param name command name (e.g. "pyright")
     * @return matching spec or null
     */
    public static CommandSpec find(String name) {
        if (name == null) return null;
        return ALL.stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
