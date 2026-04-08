package com.anthropic.claudecode.util;

import java.util.*;
import lombok.Data;

/**
 * CLI argument parsing and headless-run utility helpers.
 *
 * Merges two TypeScript sources:
 * <ul>
 *   <li>{@code src/utils/cliArgs.ts} — early flag parsing</li>
 *   <li>{@code src/cli/print.ts} — exported utility functions used by the
 *       headless runner: {@code joinPromptValues}, {@code canBatchWith},
 *       UUID deduplication, and the {@code RunHeadlessOptions} descriptor</li>
 * </ul>
 *
 * Translated from src/cli/print.ts and src/utils/cliArgs.ts
 */
public class CliArgsUtils {

    // =========================================================================
    // Early CLI flag parsing  (src/utils/cliArgs.ts)
    // =========================================================================

    /**
     * Parse a CLI flag value early, before main argument processing.
     * Translated from eagerParseCliFlag() in cliArgs.ts.
     *
     * <p>Supports both space-separated ({@code --flag value}) and equals-separated
     * ({@code --flag=value}) syntax.
     */
    public static Optional<String> eagerParseCliFlag(String flagName, String[] argv) {
        if (argv == null || flagName == null) return Optional.empty();

        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (arg == null) continue;

            // Handle --flag=value syntax
            if (arg.startsWith(flagName + "=")) {
                return Optional.of(arg.substring(flagName.length() + 1));
            }

            // Handle --flag value syntax
            if (arg.equals(flagName) && i + 1 < argv.length) {
                return Optional.of(argv[i + 1]);
            }
        }

        return Optional.empty();
    }

    /**
     * Handle the standard Unix {@code --} separator in CLI arguments.
     * Translated from handleDoubleDashSeparator() in cliArgs.ts.
     */
    public static SplitArgs handleDoubleDashSeparator(String[] args) {
        if (args == null) return new SplitArgs(new String[0], new String[0]);

        for (int i = 0; i < args.length; i++) {
            if ("--".equals(args[i])) {
                String[] before = Arrays.copyOfRange(args, 0, i);
                String[] after  = Arrays.copyOfRange(args, i + 1, args.length);
                return new SplitArgs(before, after);
            }
        }

        return new SplitArgs(args, new String[0]);
    }

    /** Result of splitting args on the {@code --} separator. */
    public record SplitArgs(String[] before, String[] after) {}

    // =========================================================================
    // UUID deduplication  (src/cli/print.ts)
    // =========================================================================

    /**
     * Bounded LRU set of recently seen message UUIDs.
     * Translated from the {@code receivedMessageUuids} / {@code MAX_RECEIVED_UUIDS}
     * block in print.ts.
     */
    public static final class ReceivedUuidTracker {

        private final int maxSize;
        private final Set<String> uuids = new LinkedHashSet<>();
        private final Deque<String> order = new ArrayDeque<>();

        public ReceivedUuidTracker(int maxSize) {
            this.maxSize = maxSize;
        }

        /**
         * Register a UUID.
         *
         * @return {@code true} if the UUID is new; {@code false} if it is a duplicate
         */
        public synchronized boolean track(String uuid) {
            if (uuids.contains(uuid)) return false;
            uuids.add(uuid);
            order.addLast(uuid);
            // Evict oldest entries when at capacity
            while (order.size() > maxSize) {
                String oldest = order.removeFirst();
                uuids.remove(oldest);
            }
            return true;
        }
    }

    // =========================================================================
    // Prompt value helpers  (src/cli/print.ts)
    // =========================================================================

    /**
     * A prompt value is either a plain string or a list of content block
     * parameters (for multi-modal prompts).
     *
     * Mirrors the {@code PromptValue} union type in print.ts.
     */
    public sealed interface PromptValue permits PromptValue.Text, PromptValue.Blocks {
        record Text(String text) implements PromptValue {}
        record Blocks(List<Map<String, Object>> blocks) implements PromptValue {}
    }

    /**
     * Convert a {@link PromptValue} to a list of content block maps.
     * Translated from {@code toBlocks()} in print.ts.
     */
    public static List<Map<String, Object>> toBlocks(PromptValue v) {
        return switch (v) {
            case PromptValue.Text t ->
                List.of(Map.of("type", "text", "text", t.text()));
            case PromptValue.Blocks b ->
                b.blocks();
        };
    }

    /**
     * Join prompt values from multiple queued commands into one.
     * Strings are newline-joined; if any value is a block array, all values
     * are normalised to blocks and concatenated.
     *
     * Translated from {@code joinPromptValues()} in print.ts.
     */
    public static PromptValue joinPromptValues(List<PromptValue> values) {
        if (values == null || values.isEmpty()) return new PromptValue.Text("");
        if (values.size() == 1) return values.get(0);

        boolean allText = values.stream().allMatch(v -> v instanceof PromptValue.Text);
        if (allText) {
            String joined = values.stream()
                .map(v -> ((PromptValue.Text) v).text())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
            return new PromptValue.Text(joined);
        }

        List<Map<String, Object>> blocks = new ArrayList<>();
        for (PromptValue v : values) {
            blocks.addAll(toBlocks(v));
        }
        return new PromptValue.Blocks(blocks);
    }

    // =========================================================================
    // Queued command batch check  (src/cli/print.ts)
    // =========================================================================

    /**
     * Descriptor for a queued CLI command — mirrors {@code QueuedCommand} in
     * textInputTypes.ts, projected to the fields used by {@link #canBatchWith}.
     */
    public record QueuedCommand(
        String mode,       // "prompt" | "interrupt" | ...
        String workload,   // workload attribution tag
        boolean isMeta     // true for proactive / hidden turns
    ) {}

    /**
     * Whether {@code next} can be batched into the same {@code ask()} call as
     * {@code head}.  Only prompt-mode commands batch, and only when the workload
     * tag and isMeta flag both match.
     *
     * Translated from {@code canBatchWith()} in print.ts.
     */
    public static boolean canBatchWith(QueuedCommand head, QueuedCommand next) {
        if (next == null) return false;
        return "prompt".equals(next.mode())
            && Objects.equals(next.workload(), head.workload())
            && next.isMeta() == head.isMeta();
    }

    // =========================================================================
    // Run-headless option descriptor  (src/cli/print.ts)
    // =========================================================================

    /**
     * Options passed to the headless (non-interactive) session runner.
     *
     * Mirrors the {@code options} parameter shape of {@code runHeadless()} in
     * print.ts.  Fields map 1-to-1; camelCase is preserved.
     */
    @Data
    @lombok.Builder
    
    public static class RunHeadlessOptions {
        private Boolean continuePrevious;
        private Object resume;               // boolean | String session-id
        private String resumeSessionAt;
        private Boolean verbose;
        private String outputFormat;
        private Map<String, Object> jsonSchema;
        private String permissionPromptToolName;
        private List<String> allowedTools;
        private Map<String, Object> thinkingConfig;
        private Integer maxTurns;
        private Double maxBudgetUsd;
        private Map<String, Object> taskBudget;
        private String systemPrompt;
        private String appendSystemPrompt;
        private String userSpecifiedModel;
        private String fallbackModel;
        private Object teleport;             // String | Boolean | null
        private String sdkUrl;
        private Boolean replayUserMessages;
        private Boolean includePartialMessages;
        private Boolean forkSession;
        private String rewindFiles;
        private Boolean enableAuthStatus;
        private String agent;
        private String workload;
        private String setupTrigger;         // "init" | "maintenance"
    }

    private CliArgsUtils() {}
}
