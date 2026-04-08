package com.anthropic.claudecode.util;

import java.util.Optional;
import java.util.UUID;

/**
 * Agent ID utilities.
 * Translated from src/utils/agentId.ts
 *
 * Provides helpers for formatting and parsing agent IDs.
 */
public class AgentIdUtils {

    /**
     * Format an agent ID as agentName@teamName.
     * Translated from formatAgentId() in agentId.ts
     */
    public static String formatAgentId(String agentName, String teamName) {
        return agentName + "@" + teamName;
    }

    /**
     * Parse an agent ID into its components.
     * Translated from parseAgentId() in agentId.ts
     */
    public static Optional<AgentIdComponents> parseAgentId(String agentId) {
        if (agentId == null) return Optional.empty();
        int atIndex = agentId.indexOf('@');
        if (atIndex == -1) return Optional.empty();
        return Optional.of(new AgentIdComponents(
            agentId.substring(0, atIndex),
            agentId.substring(atIndex + 1)
        ));
    }

    /**
     * Generate a request ID.
     * Translated from generateRequestId() in agentId.ts
     */
    public static String generateRequestId(String requestType, String agentId) {
        long timestamp = System.currentTimeMillis();
        return requestType + "-" + timestamp + "@" + agentId;
    }

    /**
     * Parse a request ID.
     * Translated from parseRequestId() in agentId.ts
     */
    public static Optional<RequestIdComponents> parseRequestId(String requestId) {
        if (requestId == null) return Optional.empty();
        int atIndex = requestId.indexOf('@');
        if (atIndex == -1) return Optional.empty();

        String prefix = requestId.substring(0, atIndex);
        String agentId = requestId.substring(atIndex + 1);

        int lastDash = prefix.lastIndexOf('-');
        if (lastDash == -1) return Optional.empty();

        String requestType = prefix.substring(0, lastDash);
        String timestampStr = prefix.substring(lastDash + 1);

        try {
            long timestamp = Long.parseLong(timestampStr);
            return Optional.of(new RequestIdComponents(requestType, timestamp, agentId));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Create a new agent ID with 16 hex chars.
     * Translated from createAgentId() in uuid.ts
     */
    public static String createAgentId() {
        return "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public record AgentIdComponents(String agentName, String teamName) {}
    public record RequestIdComponents(String requestType, long timestamp, String agentId) {}

    private AgentIdUtils() {}
}
