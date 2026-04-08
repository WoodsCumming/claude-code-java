package com.anthropic.claudecode.util.bash;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Utilities for computing shell-command "prefixes" used by the permission system.
 *
 * Translated from src/utils/bash/prefix.ts
 *
 * NOTE: This class has stubs for {@code parseCommand}, {@code getCommandSpec},
 * and {@code buildPrefix} because those depend on the tree-sitter native module
 * and a spec registry that are not available in the JVM. The public API is
 * preserved so callers can compile and the stubs can be replaced later.
 */
@Slf4j
public final class BashPrefixUtils {



    // -----------------------------------------------------------------------
    // Patterns
    // -----------------------------------------------------------------------

    private static final Pattern NUMERIC = Pattern.compile("^\\d+$");
    private static final Pattern ENV_VAR = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=");

    /**
     * Wrapper commands with complex option handling that can't be expressed in
     * specs. For these, the sub-command position varies based on options.
     */
    private static final java.util.Set<String> WRAPPER_COMMANDS =
            java.util.Set.of("nice");

    // -----------------------------------------------------------------------
    // Stub types – replace with real implementations once the spec registry
    // and parser bridge are available.
    // -----------------------------------------------------------------------

    /**
     * Minimal representation of a parsed command returned by the bash parser.
     * The real implementation would come from tree-sitter analysis.
     */
    public record ParsedCommandResult(
            List<String> envVars,
            String commandNode,
            List<String> cmdArgs) {}

    /**
     * Minimal representation of a command spec entry from the registry.
     */
    public record CommandSpec(
            List<ArgSpec> args,
            List<SubcommandSpec> subcommands) {}

    public record ArgSpec(boolean isCommand) {}

    public record SubcommandSpec(Object name) {} // name is String or List<String>

    // -----------------------------------------------------------------------
    // Stub collaborators — replace with real implementations
    // -----------------------------------------------------------------------

    /**
     * Stub for {@code parseCommand(command)} from parser.ts.
     * Returns a best-effort parse using simple whitespace splitting.
     */
    private static CompletableFuture<Optional<ParsedCommandResult>> parseCommand(
            String command) {
        if (command == null || command.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        List<String> envVars = new ArrayList<>();
        List<String> cmdArgs = new ArrayList<>();

        List<String> tokens = Arrays.stream(command.strip().split("\\s+"))
                .filter(t -> !t.isEmpty())
                .toList();

        int i = 0;
        // Collect leading VAR=value tokens
        while (i < tokens.size() && ENV_VAR.matcher(tokens.get(i)).find()) {
            envVars.add(tokens.get(i++));
        }

        if (i >= tokens.size()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        cmdArgs = tokens.subList(i, tokens.size());
        String commandNode = cmdArgs.isEmpty() ? null : cmdArgs.get(0);

        return CompletableFuture.completedFuture(
                Optional.of(new ParsedCommandResult(envVars, commandNode, cmdArgs)));
    }

    /**
     * Stub for {@code getCommandSpec(cmd)} from registry.ts.
     * Always returns empty (no spec known) in this stub.
     */
    private static CompletableFuture<Optional<CommandSpec>> getCommandSpec(String cmd) {
        // In a real implementation this would look up the spec registry.
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Stub for {@code buildPrefix(cmd, args, spec)} from specPrefix.ts.
     * Returns the command alone (no sub-command prefix detected) in this stub.
     */
    private static CompletableFuture<Optional<String>> buildPrefix(
            String cmd, List<String> args, Optional<CommandSpec> spec) {
        // In a real implementation this would walk the spec to identify
        // the longest recognized prefix for the given args.
        return CompletableFuture.completedFuture(Optional.of(cmd));
    }

    // -----------------------------------------------------------------------
    // isKnownSubcommand
    // -----------------------------------------------------------------------

    private static boolean isKnownSubcommand(String arg, Optional<CommandSpec> spec) {
        if (spec.isEmpty() || spec.get().subcommands() == null
                || spec.get().subcommands().isEmpty()) {
            return false;
        }
        for (SubcommandSpec sub : spec.get().subcommands()) {
            Object name = sub.name();
            if (name instanceof String s && s.equals(arg)) return true;
            if (name instanceof List<?> list) {
                for (Object n : list) {
                    if (arg.equals(n)) return true;
                }
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // getCommandPrefixStatic
    // -----------------------------------------------------------------------

    /**
     * Computes the prefix for a single (possibly wrapper) command.
     *
     * @param command        the full command string
     * @param recursionDepth current recursion depth (max 10)
     * @param wrapperCount   how many wrapper levels have been traversed (max 2)
     * @return a future resolving to a record with commandPrefix (null if none),
     *         or null if the command cannot be parsed
     */
    public static CompletableFuture<CommandPrefixResult> getCommandPrefixStatic(
            String command,
            int recursionDepth,
            int wrapperCount) {

        if (wrapperCount > 2 || recursionDepth > 10) {
            return CompletableFuture.completedFuture(null);
        }

        return parseCommand(command).thenCompose(parsedOpt -> {
            if (parsedOpt.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            ParsedCommandResult parsed = parsedOpt.get();
            if (parsed.commandNode() == null) {
                return CompletableFuture.completedFuture(
                        new CommandPrefixResult(null));
            }

            List<String> cmdArgs = parsed.cmdArgs();
            String cmd = cmdArgs.isEmpty() ? null : cmdArgs.get(0);
            if (cmd == null) {
                return CompletableFuture.completedFuture(
                        new CommandPrefixResult(null));
            }
            List<String> args = cmdArgs.subList(1, cmdArgs.size());

            return getCommandSpec(cmd).thenCompose(spec -> {
                boolean isWrapper = WRAPPER_COMMANDS.contains(cmd)
                        || (spec.isPresent()
                            && spec.get().args() != null
                            && spec.get().args().stream().anyMatch(ArgSpec::isCommand));

                // Special case: first arg matches a known subcommand → not a wrapper
                if (isWrapper && !args.isEmpty()
                        && isKnownSubcommand(args.get(0), spec)) {
                    isWrapper = false;
                }

                final boolean isWrapperFinal = isWrapper;
                CompletableFuture<Optional<String>> prefixFuture = isWrapper
                        ? handleWrapper(cmd, args, recursionDepth, wrapperCount)
                        : buildPrefix(cmd, args, spec);

                return prefixFuture.thenApply(prefix -> {
                    if (prefix.isEmpty() && recursionDepth == 0 && isWrapperFinal) {
                        return null;
                    }
                    String envPrefix = parsed.envVars().isEmpty()
                            ? ""
                            : String.join(" ", parsed.envVars()) + " ";
                    String commandPrefix = prefix.map(p -> envPrefix + p).orElse(null);
                    return new CommandPrefixResult(commandPrefix);
                });
            });
        });
    }

    /**
     * Computes the prefix with default recursion/wrapper counters.
     */
    public static CompletableFuture<CommandPrefixResult> getCommandPrefixStatic(
            String command) {
        return getCommandPrefixStatic(command, 0, 0);
    }

    // -----------------------------------------------------------------------
    // CommandPrefixResult
    // -----------------------------------------------------------------------

    public record CommandPrefixResult(String commandPrefix) {}

    // -----------------------------------------------------------------------
    // handleWrapper
    // -----------------------------------------------------------------------

    private static CompletableFuture<Optional<String>> handleWrapper(
            String command,
            List<String> args,
            int recursionDepth,
            int wrapperCount) {

        return getCommandSpec(command).thenCompose(specOpt -> {
            if (specOpt.isPresent() && specOpt.get().args() != null) {
                List<ArgSpec> specArgs = specOpt.get().args();
                int commandArgIndex = -1;
                for (int i = 0; i < specArgs.size(); i++) {
                    if (specArgs.get(i).isCommand()) {
                        commandArgIndex = i;
                        break;
                    }
                }

                if (commandArgIndex != -1) {
                    List<String> parts = new ArrayList<>();
                    parts.add(command);

                    for (int i = 0; i < args.size() && i <= commandArgIndex; i++) {
                        if (i == commandArgIndex) {
                            List<String> subArgs = args.subList(i, args.size());
                            String subCmd = String.join(" ", subArgs);
                            final List<String> partsCopy = new ArrayList<>(parts);
                            return getCommandPrefixStatic(subCmd,
                                    recursionDepth + 1, wrapperCount + 1)
                                    .thenApply(result -> {
                                        if (result != null && result.commandPrefix() != null) {
                                            partsCopy.addAll(
                                                    Arrays.asList(
                                                            result.commandPrefix().split(" ")));
                                            return Optional.of(
                                                    String.join(" ", partsCopy));
                                        }
                                        return Optional.<String>empty();
                                    });
                        } else {
                            String arg = args.get(i);
                            if (arg != null && !arg.startsWith("-")
                                    && !ENV_VAR.matcher(arg).find()) {
                                parts.add(arg);
                            }
                        }
                    }
                }
            }

            // Fall back: find first non-flag, non-numeric, non-env-var arg
            Optional<String> wrapped = args.stream()
                    .filter(arg -> !arg.startsWith("-")
                            && !NUMERIC.matcher(arg).matches()
                            && !ENV_VAR.matcher(arg).find())
                    .findFirst();

            if (wrapped.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.of(command));
            }

            int idx = args.indexOf(wrapped.get());
            String subCmd = String.join(" ", args.subList(idx, args.size()));
            return getCommandPrefixStatic(subCmd, recursionDepth + 1, wrapperCount + 1)
                    .thenApply(result -> {
                        if (result == null || result.commandPrefix() == null) {
                            return Optional.<String>empty();
                        }
                        return Optional.of(command + " " + result.commandPrefix());
                    });
        });
    }

    // -----------------------------------------------------------------------
    // getCompoundCommandPrefixesStatic
    // -----------------------------------------------------------------------

    /**
     * Computes prefixes for a compound command (with {@code &&} / {@code ||} / {@code ;}).
     *
     * For single commands, returns a single-element list with the prefix.
     * For compound commands, per-subcommand prefixes are computed and collapsed
     * via word-aligned longest common prefix.
     *
     * @param command          the full compound command string
     * @param excludeSubcommand optional predicate; if it returns true for a
     *                          subcommand, that subcommand is excluded from the
     *                          prefix suggestion
     */
    public static CompletableFuture<List<String>> getCompoundCommandPrefixesStatic(
            String command,
            Predicate<String> excludeSubcommand) {

        List<String> subcommands = splitCommandDeprecated(command);

        if (subcommands.size() <= 1) {
            return getCommandPrefixStatic(command).thenApply(result -> {
                if (result != null && result.commandPrefix() != null) {
                    return List.of(result.commandPrefix());
                }
                return List.of();
            });
        }

        List<CompletableFuture<Optional<String>>> futures = new ArrayList<>();
        for (String subcmd : subcommands) {
            String trimmed = subcmd.strip();
            if (excludeSubcommand != null && excludeSubcommand.test(trimmed)) {
                continue;
            }
            futures.add(getCommandPrefixStatic(trimmed).thenApply(result ->
                    result != null && result.commandPrefix() != null
                            ? Optional.of(result.commandPrefix())
                            : Optional.<String>empty()));
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(__ -> {
                    List<String> prefixes = new ArrayList<>();
                    for (CompletableFuture<Optional<String>> f : futures) {
                        f.join().ifPresent(prefixes::add);
                    }

                    if (prefixes.isEmpty()) return List.<String>of();

                    // Group by root command (first word)
                    Map<String, List<String>> groups = new HashMap<>();
                    for (String prefix : prefixes) {
                        String root = prefix.split(" ")[0];
                        groups.computeIfAbsent(root, k -> new ArrayList<>()).add(prefix);
                    }

                    List<String> collapsed = new ArrayList<>();
                    for (List<String> group : groups.values()) {
                        collapsed.add(longestCommonPrefix(group));
                    }
                    return collapsed;
                });
    }

    /**
     * Computes compound command prefixes without an exclusion predicate.
     */
    public static CompletableFuture<List<String>> getCompoundCommandPrefixesStatic(
            String command) {
        return getCompoundCommandPrefixesStatic(command, null);
    }

    // -----------------------------------------------------------------------
    // splitCommandDeprecated
    // -----------------------------------------------------------------------

    /**
     * Splits a compound command by {@code &&}, {@code ||}, and {@code ;} operators.
     * Mirrors splitCommand_DEPRECATED from commands.ts.
     *
     * @deprecated Regex-based splitter, not quote-aware. Use tree-sitter path when possible.
     */
    @Deprecated
    static List<String> splitCommandDeprecated(String command) {
        if (command == null || command.isBlank()) return List.of();
        // Split on &&, ||, ;  while keeping simple quoting awareness
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (c == '\\' && !inSingle) {
                current.append(c);
                if (i + 1 < command.length()) current.append(command.charAt(++i));
                continue;
            }
            if (c == '\'' && !inDouble) { inSingle = !inSingle; current.append(c); continue; }
            if (c == '"'  && !inSingle) { inDouble = !inDouble; current.append(c); continue; }

            if (!inSingle && !inDouble) {
                if (c == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') {
                    parts.add(current.toString()); current.setLength(0); i++; continue;
                }
                if (c == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') {
                    parts.add(current.toString()); current.setLength(0); i++; continue;
                }
                if (c == ';') {
                    parts.add(current.toString()); current.setLength(0); continue;
                }
            }
            current.append(c);
        }
        if (!current.isEmpty()) parts.add(current.toString());
        return parts;
    }

    // -----------------------------------------------------------------------
    // longestCommonPrefix
    // -----------------------------------------------------------------------

    /**
     * Computes the longest common prefix of strings, aligned to word boundaries.
     *
     * <pre>
     * ["git fetch", "git worktree"] → "git"
     * ["npm run test", "npm run lint"] → "npm run"
     * </pre>
     */
    static String longestCommonPrefix(List<String> strings) {
        if (strings.isEmpty()) return "";
        if (strings.size() == 1) return strings.get(0);

        String first = strings.get(0);
        String[] words = first.split(" ");
        int commonWords = words.length;

        for (int i = 1; i < strings.size(); i++) {
            String[] otherWords = strings.get(i).split(" ");
            int shared = 0;
            while (shared < commonWords
                    && shared < otherWords.length
                    && words[shared].equals(otherWords[shared])) {
                shared++;
            }
            commonWords = shared;
        }

        return String.join(" ",
                Arrays.copyOfRange(words, 0, Math.max(1, commonWords)));
    }

    private BashPrefixUtils() {}
}
