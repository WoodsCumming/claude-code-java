package com.anthropic.claudecode.util;

import lombok.Builder;
import lombok.Data;

import java.util.*;

/**
 * Groups tool uses by message ID and tool name for consolidated UI rendering.
 *
 * Only groups 2+ tool uses of the same type that originated from the same API
 * response (same messageId). When verbose mode is on, no grouping is applied.
 *
 * Translated from src/utils/groupToolUses.ts
 */
public class GroupToolUsesUtils {

    // -------------------------------------------------------------------------
    // Message type constants (mirrors the TypeScript discriminated unions)
    // -------------------------------------------------------------------------

    public static final String TYPE_ASSISTANT   = "assistant";
    public static final String TYPE_USER        = "user";
    public static final String TYPE_PROGRESS    = "progress";
    public static final String TYPE_GROUPED     = "grouped_tool_use";
    public static final String TYPE_TOOL_USE    = "tool_use";
    public static final String TYPE_TOOL_RESULT = "tool_result";

    // -------------------------------------------------------------------------
    // Minimal message interfaces
    // -------------------------------------------------------------------------

    /**
     * Minimal representation of any normalized message.
     * Mirrors the NormalizedMessage union in the TypeScript sources.
     */
    public interface NormalizedMessage {
        String getType();
        String getUuid();
        long getTimestamp();
    }

    /**
     * A message that can be rendered (assistant, user, progress, grouped_tool_use).
     * Mirrors RenderableMessage in the TypeScript sources.
     */
    public interface RenderableMessage extends NormalizedMessage {}

    /**
     * An assistant message whose first content block is a tool_use.
     */
    public interface AssistantToolUseMessage extends RenderableMessage {
        String getMessageId();   // API response ID
        String getToolUseId();
        String getToolName();
    }

    /**
     * A user message that may contain tool_result content blocks.
     */
    public interface UserMessage extends RenderableMessage {
        List<ToolResultBlock> getToolResults();
    }

    /**
     * Minimal tool result content block.
     */
    public interface ToolResultBlock {
        String getType();        // "tool_result"
        String getToolUseId();
    }

    // -------------------------------------------------------------------------
    // GroupedToolUseMessage
    // -------------------------------------------------------------------------

    /**
     * A synthetic grouped message produced by applyGrouping().
     * Translated from GroupedToolUseMessage in types/message.ts
     */
    @Data
    @Builder
    public static class GroupedToolUseMessage implements RenderableMessage {
        @Builder.Default private final String type        = TYPE_GROUPED;
        private final String toolName;
        private final List<AssistantToolUseMessage> messages;
        private final List<UserMessage> results;
        private final AssistantToolUseMessage displayMessage;
        private final String uuid;
        private final long timestamp;
        private final String messageId;

        @Override public String getType()      { return type; }
        @Override public String getUuid()      { return uuid; }
        @Override public long getTimestamp()   { return timestamp; }
    }

    // -------------------------------------------------------------------------
    // GroupingResult
    // -------------------------------------------------------------------------

    /**
     * Result of applyGrouping().
     * Translated from GroupingResult in groupToolUses.ts
     */
    public record GroupingResult(List<RenderableMessage> messages) {}

    // -------------------------------------------------------------------------
    // Tool grouping registry
    // -------------------------------------------------------------------------

    /**
     * Marker interface for tools that support grouped rendering.
     * In the TS impl, tools have a renderGroupedToolUse property.
     */
    public interface GroupRenderableTool {
        String getName();
    }

    // -------------------------------------------------------------------------
    // applyGrouping
    // -------------------------------------------------------------------------

    /**
     * Groups tool uses by messageId + toolName if the tool supports grouped rendering.
     * Only groups 2+ tools of the same type from the same message.
     * Also collects corresponding tool_results and attaches them to the grouped message.
     * When verbose is true, skips grouping so messages render at original positions.
     *
     * Translated from applyGrouping() in groupToolUses.ts
     *
     * @param messages          input messages (without progress messages)
     * @param groupableToolNames set of tool names that support grouped rendering
     * @param verbose           when true, skip grouping
     * @return GroupingResult containing the (possibly grouped) renderable messages
     */
    public static GroupingResult applyGrouping(
            List<? extends NormalizedMessage> messages,
            Set<String> groupableToolNames,
            boolean verbose) {

        // In verbose mode, return messages as-is
        if (verbose) {
            List<RenderableMessage> out = new ArrayList<>();
            for (NormalizedMessage m : messages) {
                if (m instanceof RenderableMessage rm) out.add(rm);
            }
            return new GroupingResult(out);
        }

        // ----------------------------------------------------------------
        // Pass 1: group assistant tool-use messages by (messageId, toolName)
        // ----------------------------------------------------------------
        Map<String, List<AssistantToolUseMessage>> groups = new LinkedHashMap<>();

        for (NormalizedMessage msg : messages) {
            if (!(msg instanceof AssistantToolUseMessage atm)) continue;
            if (!groupableToolNames.contains(atm.getToolName())) continue;

            String key = atm.getMessageId() + ":" + atm.getToolName();
            groups.computeIfAbsent(key, _k -> new ArrayList<>()).add(atm);
        }

        // Identify valid groups (2+ items) and collect their tool-use IDs
        Map<String, List<AssistantToolUseMessage>> validGroups = new LinkedHashMap<>();
        Set<String> groupedToolUseIds = new HashSet<>();

        for (Map.Entry<String, List<AssistantToolUseMessage>> entry : groups.entrySet()) {
            if (entry.getValue().size() >= 2) {
                validGroups.put(entry.getKey(), entry.getValue());
                for (AssistantToolUseMessage atm : entry.getValue()) {
                    groupedToolUseIds.add(atm.getToolUseId());
                }
            }
        }

        // ----------------------------------------------------------------
        // Collect result messages for grouped tool_uses
        // ----------------------------------------------------------------
        Map<String, UserMessage> resultsByToolUseId = new HashMap<>();
        for (NormalizedMessage msg : messages) {
            if (!(msg instanceof UserMessage um)) continue;
            for (ToolResultBlock block : um.getToolResults()) {
                if (groupedToolUseIds.contains(block.getToolUseId())) {
                    resultsByToolUseId.put(block.getToolUseId(), um);
                }
            }
        }

        // ----------------------------------------------------------------
        // Pass 2: build output, emitting each group only once
        // ----------------------------------------------------------------
        List<RenderableMessage> result = new ArrayList<>();
        Set<String> emittedGroups = new HashSet<>();

        for (NormalizedMessage msg : messages) {
            if (msg instanceof AssistantToolUseMessage atm) {
                String key   = atm.getMessageId() + ":" + atm.getToolName();
                List<AssistantToolUseMessage> group = validGroups.get(key);

                if (group != null) {
                    if (!emittedGroups.contains(key)) {
                        emittedGroups.add(key);
                        AssistantToolUseMessage first = group.get(0);

                        // Collect results for this group
                        List<UserMessage> groupResults = new ArrayList<>();
                        for (AssistantToolUseMessage a : group) {
                            UserMessage r = resultsByToolUseId.get(a.getToolUseId());
                            if (r != null) groupResults.add(r);
                        }

                        result.add(GroupedToolUseMessage.builder()
                                .toolName(atm.getToolName())
                                .messages(group)
                                .results(groupResults)
                                .displayMessage(first)
                                .uuid("grouped-" + first.getUuid())
                                .timestamp(first.getTimestamp())
                                .messageId(atm.getMessageId())
                                .build());
                    }
                    continue; // skip individual message — already in group
                }
            }

            // Skip user messages whose tool_results are ALL grouped
            if (msg instanceof UserMessage um) {
                List<ToolResultBlock> toolResults = um.getToolResults();
                if (!toolResults.isEmpty()) {
                    boolean allGrouped = toolResults.stream()
                            .allMatch(tr -> groupedToolUseIds.contains(tr.getToolUseId()));
                    if (allGrouped) continue;
                }
            }

            if (msg instanceof RenderableMessage rm) {
                result.add(rm);
            }
        }

        return new GroupingResult(result);
    }

    private GroupToolUsesUtils() {}
}
