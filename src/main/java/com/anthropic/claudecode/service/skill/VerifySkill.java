package com.anthropic.claudecode.service.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * VerifySkill — verify a code change does what it should by running the app.
 *
 * <p>This skill is ANT-ONLY (requires USER_TYPE=ant in the environment).
 *
 * <p>Translated from: src/skills/bundled/verify.ts
 */
@Slf4j
@Service
public class VerifySkill {



    // -------------------------------------------------------------------------
    // Constants (mirroring verifyContent.ts values)
    // -------------------------------------------------------------------------

    /**
     * The body of the verify skill prompt.  In the original TypeScript source
     * this is loaded from an embedded markdown file (verifyContent.ts) whose
     * front-matter carries the description.  Here we inline a representative
     * version of that content.
     */
    private static final String SKILL_BODY = """
            ## Goal
            Verify that the code change you just made actually works end-to-end, not just that it compiles or passes unit tests.

            ## Steps

            ### 1. Understand what changed
            Run `git diff HEAD~1` (or `git diff` for unstaged changes) to see exactly what was modified. Identify the affected feature or behaviour.

            ### 2. Identify the verification method
            Choose the most appropriate method based on the change:
            - **UI change** → use a browser or screenshot tool to visually confirm the change
            - **API / server change** → start the dev server and curl the affected endpoints
            - **CLI change** → run the CLI with appropriate arguments and observe output
            - **Library / utility change** → run the relevant unit or integration tests

            ### 3. Execute the verification
            Carry out the verification steps you identified. Capture the output or screenshot.

            ### 4. Report results
            Summarise what you verified and whether the change behaves as expected.  If something is wrong, diagnose it and suggest a fix.
            """;

    private static final String DESCRIPTION =
            "Verify a code change does what it should by running the app.";

    // -------------------------------------------------------------------------
    // Skill metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "verify";

    // -------------------------------------------------------------------------
    // Guard
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} only when the skill is available for the current user.
     * Mirrors the {@code if (process.env.USER_TYPE !== 'ant') return} guard in TS.
     */
    public boolean isEnabled() {
        return "ant".equals(System.getenv("USER_TYPE"));
    }

    /** Returns the skill description (sourced from front-matter in the TS original). */
    public String getDescription() {
        return DESCRIPTION;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the prompt for the /verify command.
     *
     * @param args optional user request / additional context
     * @return prompt parts to send to the model
     */
    public CompletableFuture<List<PromptPart>> getPromptForCommand(String args) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> parts = new ArrayList<>();
            parts.add(SKILL_BODY.stripLeading());

            if (args != null && !args.isBlank()) {
                parts.add("## User Request\n\n" + args);
            }

            String combined = String.join("\n\n", parts);
            return List.of(new PromptPart("text", combined));
        });
    }

    /** Simple record representing a single prompt part. */
    public record PromptPart(String type, String text) {}
}
