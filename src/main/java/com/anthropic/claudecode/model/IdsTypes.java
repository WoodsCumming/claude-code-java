package com.anthropic.claudecode.model;

import java.util.regex.Pattern;

/**
 * Branded types for session and agent IDs.
 * Translated from src/types/ids.ts
 *
 * TypeScript uses structural branded types (string & { __brand }) to prevent
 * accidentally mixing session IDs and agent IDs at compile time. In Java we
 * use value-type wrappers (records) that achieve the same semantic separation.
 */
public final class IdsTypes {

    private static final Pattern AGENT_ID_PATTERN = Pattern.compile("^a(?:.+-)?[0-9a-f]{16}$");

    /**
     * A session ID uniquely identifies a Claude Code session.
     * Corresponds to TypeScript: type SessionId = string & { readonly __brand: 'SessionId' }
     */
    public record SessionId(String value) {
        public SessionId {
            if (value == null) throw new IllegalArgumentException("SessionId value must not be null");
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * An agent ID uniquely identifies a subagent within a session.
     * When present, indicates the context is a subagent (not the main session).
     * Corresponds to TypeScript: type AgentId = string & { readonly __brand: 'AgentId' }
     */
    public record AgentId(String value) {
        public AgentId {
            if (value == null) throw new IllegalArgumentException("AgentId value must not be null");
        }

        @Override
        public String toString() {
            return value;
        }
    }

    /**
     * Cast a raw string to SessionId.
     * Use sparingly — prefer getSessionId() when possible.
     * Corresponds to TypeScript: function asSessionId(id: string): SessionId
     */
    public static SessionId asSessionId(String id) {
        return new SessionId(id);
    }

    /**
     * Cast a raw string to AgentId.
     * Use sparingly — prefer createAgentId() when possible.
     * Corresponds to TypeScript: function asAgentId(id: string): AgentId
     */
    public static AgentId asAgentId(String id) {
        return new AgentId(id);
    }

    /**
     * Validate and brand a string as AgentId.
     * Matches the format produced by createAgentId(): 'a' + optional '<label>-' + 16 hex chars.
     * Returns null if the string doesn't match (e.g. teammate names, team-addressing).
     * Corresponds to TypeScript: function toAgentId(s: string): AgentId | null
     */
    public static AgentId toAgentId(String s) {
        if (s == null) return null;
        return AGENT_ID_PATTERN.matcher(s).matches() ? new AgentId(s) : null;
    }

    private IdsTypes() {}
}
