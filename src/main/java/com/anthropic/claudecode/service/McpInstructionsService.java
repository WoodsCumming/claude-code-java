package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * MCP instructions delta service.
 * Translated from src/utils/mcpInstructionsDelta.ts
 *
 * Diffs the current set of connected MCP servers that have instructions against
 * what has already been announced in this conversation and returns a delta object.
 * Returns null when nothing has changed.
 *
 * Instructions are immutable for the life of a connection (set once at handshake),
 * so the scan diffs on server NAME, not on content.
 *
 * The delta mechanism is gated by isMcpInstructionsDeltaEnabled() (GrowthBook
 * feature "tengu_basalt_3kr" or USER_TYPE=ant or env override).
 * When disabled, the system prompt is rebuilt every turn instead.
 */
@Slf4j
@Service
public class McpInstructionsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpInstructionsService.class);


    // ---------------------------------------------------------------------------
    // Types
    // ---------------------------------------------------------------------------

    /**
     * Delta describing which MCP server instruction blocks were added/removed.
     * Translated from McpInstructionsDelta in mcpInstructionsDelta.ts
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class McpInstructionsDelta {
        /** Server names added in this delta — for stateless-scan reconstruction. */
        private final List<String> addedNames;
        /** Rendered "## {name}\n{instructions}" blocks for addedNames (same order). */
        private final List<String> addedBlocks;
        /** Server names that were previously announced but are no longer connected. */
        private final List<String> removedNames;

        public List<String> getAddedNames() { return addedNames; }
        public List<String> getAddedBlocks() { return addedBlocks; }
        public List<String> getRemovedNames() { return removedNames; }
    
    }

    /**
     * Client-authored instruction block to announce when a server connects,
     * in addition to (or instead of) the server's own InitializeResult.instructions.
     * Translated from ClientSideInstruction in mcpInstructionsDelta.ts
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class ClientSideInstruction {
        private final String serverName;
        private final String block;

        public String getServerName() { return serverName; }
        public String getBlock() { return block; }
    }

    /**
     * Represents a connected MCP server with optional instructions.
     * Translated from the ConnectedMCPServer shape used in getMcpInstructionsDelta().
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class ConnectedMcpServer {
        private final String name;
        /** May be null if the server provided no InitializeResult.instructions. */
        private final String instructions;

        public String getName() { return name; }
        public String getInstructions() { return instructions; }
    }

    // ---------------------------------------------------------------------------
    // Feature gate
    // ---------------------------------------------------------------------------

    /**
     * Determine whether the MCP instructions delta feature is enabled.
     * Translated from isMcpInstructionsDeltaEnabled() in mcpInstructionsDelta.ts
     *
     * Priority:
     *  1. CLAUDE_CODE_MCP_INSTR_DELTA=true/false env override
     *  2. USER_TYPE=ant bypass
     *  3. GrowthBook feature flag "tengu_basalt_3kr"
     */
    public boolean isMcpInstructionsDeltaEnabled() {
        String envOverride = System.getenv("CLAUDE_CODE_MCP_INSTR_DELTA");
        if ("true".equalsIgnoreCase(envOverride)) return true;
        if (envOverride != null && !envOverride.isBlank() && !"true".equalsIgnoreCase(envOverride)) return false;

        String userType = System.getenv("USER_TYPE");
        if ("ant".equals(userType)) return true;

        // GrowthBook gate "tengu_basalt_3kr" — delegate to analytics service in full impl
        return false;
    }

    // ---------------------------------------------------------------------------
    // Core algorithm
    // ---------------------------------------------------------------------------

    /**
     * Diff the current set of connected MCP servers against what has already been
     * announced in this conversation.  Returns null if nothing changed.
     * Translated from getMcpInstructionsDelta() in mcpInstructionsDelta.ts
     *
     * @param connectedServers      Live list of currently-connected MCP servers
     * @param announcedServerNames  Names already announced in the current conversation
     *                              (derived by scanning mcp_instructions_delta attachments)
     * @param clientSideInstructions Extra client-authored instruction blocks
     * @return Delta, or null if nothing changed
     */
    public McpInstructionsDelta getMcpInstructionsDelta(
            List<ConnectedMcpServer> connectedServers,
            Set<String> announcedServerNames,
            List<ClientSideInstruction> clientSideInstructions) {

        Set<String> connectedNames = new LinkedHashSet<>();
        for (ConnectedMcpServer s : connectedServers) {
            connectedNames.add(s.getName());
        }

        // Build the blocks map: server-name → rendered instruction block
        // A server can have both: server-authored instructions + a client-side block appended.
        Map<String, String> blocks = new LinkedHashMap<>();
        for (ConnectedMcpServer s : connectedServers) {
            if (s.getInstructions() != null && !s.getInstructions().isBlank()) {
                blocks.put(s.getName(), "## " + s.getName() + "\n" + s.getInstructions());
            }
        }
        for (ClientSideInstruction ci : clientSideInstructions) {
            if (!connectedNames.contains(ci.getServerName())) continue;
            String existing = blocks.get(ci.getServerName());
            blocks.put(ci.getServerName(),
                existing != null
                    ? existing + "\n\n" + ci.getBlock()
                    : "## " + ci.getServerName() + "\n" + ci.getBlock());
        }

        // Find servers with instruction blocks that have not yet been announced
        List<Map.Entry<String, String>> added = new ArrayList<>();
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            if (!announcedServerNames.contains(entry.getKey())) {
                added.add(entry);
            }
        }

        // Find previously announced servers that are no longer connected
        List<String> removed = new ArrayList<>();
        for (String name : announcedServerNames) {
            if (!connectedNames.contains(name)) {
                removed.add(name);
            }
        }

        if (added.isEmpty() && removed.isEmpty()) {
            return null;
        }

        // Sort added by name (mirrors added.sort((a, b) => a.name.localeCompare(b.name)))
        added.sort(Map.Entry.comparingByKey());
        Collections.sort(removed);

        List<String> addedNames = added.stream().map(Map.Entry::getKey).toList();
        List<String> addedBlocks = added.stream().map(Map.Entry::getValue).toList();

        log.debug("[McpInstructions] Delta: +{} -{}",
            addedNames.size(), removed.size());

        return new McpInstructionsDelta(addedNames, addedBlocks, removed);
    }

    /**
     * Reconstruct the set of currently-announced server names by scanning
     * mcp_instructions_delta attachments in the conversation messages.
     *
     * Mirrors the announced set construction loop in getMcpInstructionsDelta() (TS):
     *   for (const msg of messages) {
     *     if (msg.type !== 'attachment') continue;
     *     if (msg.attachment.type !== 'mcp_instructions_delta') continue;
     *     for (const n of msg.attachment.addedNames) announced.add(n);
     *     for (const n of msg.attachment.removedNames) announced.delete(n);
     *   }
     *
     * @param deltaAttachments Ordered list of McpInstructionsDelta objects already
     *                         persisted as attachments in the conversation
     * @return The reconstructed announced-names set
     */
    public Set<String> reconstructAnnouncedNames(List<McpInstructionsDelta> deltaAttachments) {
        Set<String> announced = new LinkedHashSet<>();
        for (McpInstructionsDelta delta : deltaAttachments) {
            announced.addAll(delta.getAddedNames());
            announced.removeAll(delta.getRemovedNames());
        }
        return announced;
    }
}
