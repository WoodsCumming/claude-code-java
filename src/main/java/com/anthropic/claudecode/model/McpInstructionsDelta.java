package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * MCP instructions delta for tracking server instruction changes.
 * Translated from src/utils/mcpInstructionsDelta.ts
 */
@Data
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class McpInstructionsDelta {

    /** Server names that were added */
    private List<String> addedNames;

    /** Rendered instruction blocks for added servers */
    private List<String> addedBlocks;

    /** Server names that were removed */
    private List<String> removedNames;

    /**
     * Check if MCP instructions delta is enabled.
     * Translated from isMcpInstructionsDeltaEnabled() in mcpInstructionsDelta.ts
     */
    public static boolean isEnabled() {
        String envVal = System.getenv("CLAUDE_CODE_MCP_INSTR_DELTA");
        if ("true".equalsIgnoreCase(envVal) || "1".equals(envVal)) return true;
        if ("false".equalsIgnoreCase(envVal) || "0".equals(envVal)) return false;
        return false; // Default disabled
    }

        public List<String> getAddedNames() { return addedNames; }
        public void setAddedNames(List<String> v) { addedNames = v; }
        public List<String> getAddedBlocks() { return addedBlocks; }
        public void setAddedBlocks(List<String> v) { addedBlocks = v; }
        public List<String> getRemovedNames() { return removedNames; }
        public void setRemovedNames(List<String> v) { removedNames = v; }
}
