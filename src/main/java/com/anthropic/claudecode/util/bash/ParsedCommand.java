package com.anthropic.claudecode.util.bash;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides methods for working with shell commands.
 * Uses tree-sitter when available for quote-aware parsing,
 * falls back to regex-based parsing otherwise.
 *
 * Translated from src/utils/bash/ParsedCommand.ts
 */
@Slf4j
public class ParsedCommand {



    // -----------------------------------------------------------------------
    // OutputRedirection
    // -----------------------------------------------------------------------

    /**
     * Represents a shell output redirection (> or >>).
     */
    public record OutputRedirection(String target, String operator) {
        /**
         * @param target   the redirection target (file path)
         * @param operator either ">" or ">>"
         */
        public OutputRedirection {
            if (!">".equals(operator) && !">>".equals(operator)) {
                throw new IllegalArgumentException("operator must be '>' or '>>'");
            }
        }
    }

    // -----------------------------------------------------------------------
    // IParsedCommand interface
    // -----------------------------------------------------------------------

    /**
     * Interface for parsed command implementations.
     * Both tree-sitter and regex fallback implementations conform to this.
     */
    public interface IParsedCommand {
        String getOriginalCommand();

        String toString();

        List<String> getPipeSegments();

        String withoutOutputRedirections();

        List<OutputRedirection> getOutputRedirections();

        /**
         * Returns tree-sitter analysis data if available.
         * Returns null for the regex fallback implementation.
         */
        TreeSitterAnalysisStub getTreeSitterAnalysis();
    }

    // -----------------------------------------------------------------------
    // TreeSitterAnalysisStub  (placeholder – native module not available in JVM)
    // -----------------------------------------------------------------------

    /**
     * Stub for TreeSitterAnalysis. The native tree-sitter module is not
     * available in the JVM. This placeholder preserves the shape of the type.
     */
    public record TreeSitterAnalysisStub() {}

    // -----------------------------------------------------------------------
    // RedirectionNode  (internal)
    // -----------------------------------------------------------------------

    private record RedirectionNode(String target, String operator,
                                   int startIndex, int endIndex) {}

    // -----------------------------------------------------------------------
    // RegexParsedCommand_DEPRECATED
    // -----------------------------------------------------------------------

    /**
     * Legacy regex/shell-quote path. Only used when tree-sitter is unavailable.
     * Exported for testing purposes.
     *
     * @deprecated Regex-based fallback implementation. Use tree-sitter path when possible.
     */
    @Deprecated
    public static class RegexParsedCommand_DEPRECATED implements IParsedCommand {

        private final String originalCommand;

        public RegexParsedCommand_DEPRECATED(String command) {
            this.originalCommand = command;
        }

        @Override
        public String getOriginalCommand() {
            return originalCommand;
        }

        @Override
        public String toString() {
            return originalCommand;
        }

        @Override
        public List<String> getPipeSegments() {
            try {
                List<String> parts = BashCommandUtils.splitCommandWithOperators(originalCommand);
                List<String> segments = new ArrayList<>();
                List<String> currentSegment = new ArrayList<>();

                for (String part : parts) {
                    if ("|".equals(part)) {
                        if (!currentSegment.isEmpty()) {
                            segments.add(String.join(" ", currentSegment));
                            currentSegment = new ArrayList<>();
                        }
                    } else {
                        currentSegment.add(part);
                    }
                }

                if (!currentSegment.isEmpty()) {
                    segments.add(String.join(" ", currentSegment));
                }

                return segments.isEmpty() ? List.of(originalCommand) : segments;
            } catch (Exception e) {
                return List.of(originalCommand);
            }
        }

        @Override
        public String withoutOutputRedirections() {
            if (!originalCommand.contains(">")) {
                return originalCommand;
            }
            BashCommandUtils.RedirectionExtractionResult result =
                    BashCommandUtils.extractOutputRedirections(originalCommand);
            return result.redirections().isEmpty()
                    ? originalCommand
                    : result.commandWithoutRedirections();
        }

        @Override
        public List<OutputRedirection> getOutputRedirections() {
            BashCommandUtils.RedirectionExtractionResult result =
                    BashCommandUtils.extractOutputRedirections(originalCommand);
            return result.redirections();
        }

        @Override
        public TreeSitterAnalysisStub getTreeSitterAnalysis() {
            return null;
        }
    }

    // -----------------------------------------------------------------------
    // TreeSitterParsedCommand  (stub – native module not available in JVM)
    // -----------------------------------------------------------------------

    /**
     * Parsed command backed by tree-sitter AST analysis.
     *
     * NOTE: The native tree-sitter parser is not available in the JVM. This
     * implementation stores the pre-computed pipe positions and redirection
     * nodes that would normally come from tree-sitter, so that callers who
     * already have AST data (e.g. from a stub/bridge) can still use this class.
     * When constructed without AST data the implementation falls back to the
     * regex path.
     */
    public static class TreeSitterParsedCommand implements IParsedCommand {

        private final String originalCommand;
        // Tree-sitter's startIndex/endIndex are byte offsets (UTF-8).
        // We mirror the TS implementation by working on the raw UTF-8 bytes so
        // that multi-byte code-points are handled correctly.
        private final byte[] commandBytes;
        private final List<Integer> pipePositions;
        private final List<RedirectionNode> redirectionNodes;
        private final TreeSitterAnalysisStub treeSitterAnalysis;

        public TreeSitterParsedCommand(
                String command,
                List<Integer> pipePositions,
                List<RedirectionNode> redirectionNodes,
                TreeSitterAnalysisStub treeSitterAnalysis) {
            this.originalCommand = command;
            this.commandBytes = command.getBytes(StandardCharsets.UTF_8);
            this.pipePositions = pipePositions;
            this.redirectionNodes = redirectionNodes;
            this.treeSitterAnalysis = treeSitterAnalysis;
        }

        @Override
        public String getOriginalCommand() {
            return originalCommand;
        }

        @Override
        public String toString() {
            return originalCommand;
        }

        @Override
        public List<String> getPipeSegments() {
            if (pipePositions.isEmpty()) {
                return List.of(originalCommand);
            }

            List<String> segments = new ArrayList<>();
            int currentStart = 0;

            for (int pipePos : pipePositions) {
                String segment = new String(
                        Arrays.copyOfRange(commandBytes, currentStart, pipePos),
                        StandardCharsets.UTF_8).strip();
                if (!segment.isEmpty()) {
                    segments.add(segment);
                }
                currentStart = pipePos + 1;
            }

            String lastSegment = new String(
                    Arrays.copyOfRange(commandBytes, currentStart, commandBytes.length),
                    StandardCharsets.UTF_8).strip();
            if (!lastSegment.isEmpty()) {
                segments.add(lastSegment);
            }

            return segments;
        }

        @Override
        public String withoutOutputRedirections() {
            if (redirectionNodes.isEmpty()) {
                return originalCommand;
            }

            // Sort descending by startIndex so we replace from end to start
            List<RedirectionNode> sorted = redirectionNodes.stream()
                    .sorted(Comparator.comparingInt(RedirectionNode::startIndex).reversed())
                    .toList();

            byte[] result = commandBytes;
            for (RedirectionNode redir : sorted) {
                byte[] before = Arrays.copyOfRange(result, 0, redir.startIndex());
                byte[] after  = Arrays.copyOfRange(result, redir.endIndex(), result.length);
                byte[] merged = new byte[before.length + after.length];
                System.arraycopy(before, 0, merged, 0, before.length);
                System.arraycopy(after,  0, merged, before.length, after.length);
                result = merged;
            }

            return new String(result, StandardCharsets.UTF_8)
                    .strip()
                    .replaceAll("\\s+", " ");
        }

        @Override
        public List<OutputRedirection> getOutputRedirections() {
            return redirectionNodes.stream()
                    .map(n -> new OutputRedirection(n.target(), n.operator()))
                    .toList();
        }

        @Override
        public TreeSitterAnalysisStub getTreeSitterAnalysis() {
            return treeSitterAnalysis;
        }
    }

    // -----------------------------------------------------------------------
    // Single-entry cache  (mirrors the TS size-1 memoize)
    // -----------------------------------------------------------------------

    private static final AtomicReference<String> lastCmd = new AtomicReference<>();
    private static final AtomicReference<CompletableFuture<IParsedCommand>> lastResult =
            new AtomicReference<>();

    /**
     * Availability flag – memoized once (mirrors getTreeSitterAvailable in TS).
     * Always false in the JVM port because the native tree-sitter module is not
     * available.
     */
    private static final AtomicBoolean treeSitterAvailableChecked = new AtomicBoolean(false);
    private static volatile boolean treeSitterAvailable = false;

    private static CompletableFuture<Boolean> getTreeSitterAvailable() {
        // Native tree-sitter is not available in the JVM – always return false.
        return CompletableFuture.completedFuture(false);
    }

    // -----------------------------------------------------------------------
    // parse()
    // -----------------------------------------------------------------------

    /**
     * Parse a command string and return a ParsedCommand instance.
     * Returns a failed future whose result is null if parsing fails completely.
     */
    public static CompletableFuture<IParsedCommand> parse(String command) {
        String cached = lastCmd.get();
        CompletableFuture<IParsedCommand> cachedResult = lastResult.get();

        if (command != null && command.equals(cached) && cachedResult != null) {
            return cachedResult;
        }

        CompletableFuture<IParsedCommand> future = doParse(command);
        lastCmd.set(command);
        lastResult.set(future);
        return future;
    }

    private static CompletableFuture<IParsedCommand> doParse(String command) {
        if (command == null || command.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        return getTreeSitterAvailable().thenApply(available -> {
            if (available) {
                // Tree-sitter not available in JVM – this branch is never reached.
                // A future bridge implementation would call the native parser here.
                log.warn("Tree-sitter reported available but no JVM bridge is installed; falling back to regex.");
            }
            // Fallback to regex implementation
            return (IParsedCommand) new RegexParsedCommand_DEPRECATED(command);
        });
    }

    private ParsedCommand() {}
}
