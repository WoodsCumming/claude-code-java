package com.anthropic.claudecode.util;

/**
 * Embedded search tools utilities.
 * Translated from src/utils/embeddedTools.ts
 */
public class EmbeddedTools {

    /**
     * Check if embedded search tools are available.
     * Translated from hasEmbeddedSearchTools() in embeddedTools.ts
     */
    public static boolean hasEmbeddedSearchTools() {
        // External builds: never have embedded tools
        if (!EnvUtils.isEnvTruthy(System.getenv("EMBEDDED_SEARCH_TOOLS"))) return false;

        String entrypoint = System.getenv("CLAUDE_CODE_ENTRYPOINT");
        return !"sdk-ts".equals(entrypoint)
            && !"sdk-py".equals(entrypoint)
            && !"sdk-cli".equals(entrypoint)
            && !"local-agent".equals(entrypoint);
    }

    private EmbeddedTools() {}
}
