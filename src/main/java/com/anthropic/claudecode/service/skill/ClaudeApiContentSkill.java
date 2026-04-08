package com.anthropic.claudecode.service.skill;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ClaudeApiContentSkill — provides model variable constants and skill-file
 * registry for the claude-api bundled skill.
 *
 * <p>In the TypeScript source ({@code claudeApiContent.ts}) each .md file is
 * bundled at build time as a string constant.  In this Java translation we
 * store empty placeholder strings for each path so that the registry keys
 * remain identical; real content would be loaded from classpath resources.
 *
 * <p>Translated from: src/skills/bundled/claudeApiContent.ts
 */
@Service
public class ClaudeApiContentSkill {

    // -------------------------------------------------------------------------
    // Model variable substitution constants
    // @[MODEL LAUNCH]: Update these when new model IDs are released.
    // -------------------------------------------------------------------------

    public static final String OPUS_ID        = "claude-opus-4-6";
    public static final String OPUS_NAME      = "Claude Opus 4.6";
    public static final String SONNET_ID      = "claude-sonnet-4-6";
    public static final String SONNET_NAME    = "Claude Sonnet 4.6";
    public static final String HAIKU_ID       = "claude-haiku-4-5";
    public static final String HAIKU_NAME     = "Claude Haiku 4.5";
    /** Previous Sonnet ID — used in "do not append date suffixes" example. */
    public static final String PREV_SONNET_ID = "claude-sonnet-4-5";

    /**
     * Immutable map of {@code {{VARIABLE}}} placeholder names to their values.
     * Keys match the mustache-style tokens used inside the .md files.
     */
    public static final Map<String, String> SKILL_MODEL_VARS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("OPUS_ID",        OPUS_ID);
        m.put("OPUS_NAME",      OPUS_NAME);
        m.put("SONNET_ID",      SONNET_ID);
        m.put("SONNET_NAME",    SONNET_NAME);
        m.put("HAIKU_ID",       HAIKU_ID);
        m.put("HAIKU_NAME",     HAIKU_NAME);
        m.put("PREV_SONNET_ID", PREV_SONNET_ID);
        SKILL_MODEL_VARS = Map.copyOf(m);
    }

    // -------------------------------------------------------------------------
    // SKILL.md prompt placeholder
    // -------------------------------------------------------------------------

    /**
     * The top-level skill prompt.  In production this should be loaded from
     * {@code src/skills/bundled/claude-api/SKILL.md} via the classpath.
     */
    public static final String SKILL_PROMPT = "";   // populated from classpath resource

    // -------------------------------------------------------------------------
    // Skill file registry
    // -------------------------------------------------------------------------

    /**
     * Registry of all embedded documentation paths to their markdown content.
     * Keys match the TypeScript {@code SKILL_FILES} map exactly so that
     * language-detection logic can index into this map by path prefix.
     *
     * <p>Values are intentionally left empty here; a production implementation
     * would load each entry from a classpath resource under
     * {@code skills/bundled/claude-api/}.
     */
    public static final Map<String, String> SKILL_FILES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("csharp/claude-api.md",                     "");
        m.put("curl/examples.md",                         "");
        m.put("go/claude-api.md",                         "");
        m.put("java/claude-api.md",                       "");
        m.put("php/claude-api.md",                        "");
        m.put("python/agent-sdk/README.md",               "");
        m.put("python/agent-sdk/patterns.md",             "");
        m.put("python/claude-api/README.md",              "");
        m.put("python/claude-api/batches.md",             "");
        m.put("python/claude-api/files-api.md",           "");
        m.put("python/claude-api/streaming.md",           "");
        m.put("python/claude-api/tool-use.md",            "");
        m.put("ruby/claude-api.md",                       "");
        m.put("shared/error-codes.md",                    "");
        m.put("shared/live-sources.md",                   "");
        m.put("shared/models.md",                         "");
        m.put("shared/prompt-caching.md",                 "");
        m.put("shared/tool-use-concepts.md",              "");
        m.put("typescript/agent-sdk/README.md",           "");
        m.put("typescript/agent-sdk/patterns.md",         "");
        m.put("typescript/claude-api/README.md",          "");
        m.put("typescript/claude-api/batches.md",         "");
        m.put("typescript/claude-api/files-api.md",       "");
        m.put("typescript/claude-api/streaming.md",       "");
        m.put("typescript/claude-api/tool-use.md",        "");
        SKILL_FILES = Map.copyOf(m);
    }
}
