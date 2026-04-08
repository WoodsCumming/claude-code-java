package com.anthropic.claudecode.service.skill;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * VerifyContentSkill — provides the embedded documentation registry for the
 * {@code verify} bundled skill.
 *
 * <p>In the TypeScript source ({@code verifyContent.ts}) each .md file is
 * bundled at build time as a string constant.  In this Java translation we
 * store empty placeholder strings for each path so that the registry keys
 * remain identical; real content would be loaded from classpath resources.
 *
 * <p>Translated from: src/skills/bundled/verifyContent.ts
 */
@Service
public class VerifyContentSkill {

    // -------------------------------------------------------------------------
    // Skill prompt  (maps to SKILL.md in the verify sub-directory)
    // -------------------------------------------------------------------------

    /**
     * The top-level skill prompt.  In production this should be loaded from
     * {@code src/skills/bundled/verify/SKILL.md} via the classpath.
     */
    public static final String SKILL_MD = "";  // populated from classpath resource

    // -------------------------------------------------------------------------
    // Skill file registry
    // -------------------------------------------------------------------------

    /**
     * Registry of all embedded documentation paths to their markdown content.
     * Keys match the TypeScript {@code SKILL_FILES} map exactly.
     *
     * <p>Values are intentionally left empty here; a production implementation
     * would load each entry from a classpath resource under
     * {@code skills/bundled/verify/}.
     */
    public static final Map<String, String> SKILL_FILES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("examples/cli.md",    "");  // cli.md bundled content
        m.put("examples/server.md", "");  // server.md bundled content
        SKILL_FILES = Map.copyOf(m);
    }
}
