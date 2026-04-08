package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;
import java.util.*;

/**
 * Settings JSON structure.
 * Translated from SettingsJson type in src/utils/settings/types.ts
 */
@Data
@lombok.Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
public class SettingsJson {

    /** API key helper command */
    @JsonProperty("apiKeyHelper")
    private String apiKeyHelper;

    /** Environment variables */
    private Map<String, String> env;

    /** Permission settings */
    private PermissionsSettings permissions;

    /** Hooks configuration */
    private Map<String, Object> hooks;

    /** Default model */
    private String model;

    /** Output style */
    private String outputStyle;

    /** Whether to include co-authored-by trailer in commits */
    @JsonProperty("includeCoAuthoredBy")
    private Boolean includeCoAuthoredBy;

    /** Verbose mode */
    private Boolean verbose;

    /** MCP servers configuration */
    private Map<String, Object> mcpServers;

    /** Allowed tools */
    private List<String> allowedTools;

    /** Disabled tools */
    private List<String> disabledTools;

    @Data
    @lombok.Builder
    
    public static class PermissionsSettings {
        private List<String> allow;
        private List<String> deny;
        private List<String> ask;
        private String defaultMode;
        private String disableBypassPermissionsMode;
        private List<String> additionalDirectories;
    }


}
