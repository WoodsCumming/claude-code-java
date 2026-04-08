package com.anthropic.claudecode.util;

/**
 * Consolidation prompt builder for the Auto Dream feature.
 * Translated from src/services/autoDream/consolidationPrompt.ts
 *
 * Extracted from dream.ts so auto-dream ships independently of KAIROS
 * feature flags.
 */
public final class AutoDreamPrompts {

    // Mirror constants from memdir.js
    public static final String DIR_EXISTS_GUIDANCE =
        "If this directory does not exist, you can create it. " +
        "It will be initialized on first write.";
    public static final String ENTRYPOINT_NAME = "CLAUDE.md";
    public static final int MAX_ENTRYPOINT_LINES = 200;

    private AutoDreamPrompts() {
        // Utility class - no instantiation
    }

    /**
     * Build the memory consolidation prompt for the dream subagent.
     * Translated from buildConsolidationPrompt() in consolidationPrompt.ts
     *
     * @param memoryRoot   path to the memory root directory
     * @param transcriptDir path to session transcripts directory
     * @param extra        additional context appended at the end (may be empty)
     * @return the full prompt string
     */
    public static String buildConsolidationPrompt(
            String memoryRoot,
            String transcriptDir,
            String extra) {

        String extraSection = (extra != null && !extra.isBlank())
            ? "\n\n## Additional context\n\n" + extra
            : "";

        return "# Dream: Memory Consolidation\n\n" +
            "You are performing a dream — a reflective pass over your memory files. " +
            "Synthesize what you've learned recently into durable, well-organized memories " +
            "so that future sessions can orient quickly.\n\n" +
            "Memory directory: `" + memoryRoot + "`\n" +
            DIR_EXISTS_GUIDANCE + "\n\n" +
            "Session transcripts: `" + transcriptDir + "` " +
            "(large JSONL files — grep narrowly, don't read whole files)\n\n" +
            "---\n\n" +
            "## Phase 1 — Orient\n\n" +
            "- `ls` the memory directory to see what already exists\n" +
            "- Read `" + ENTRYPOINT_NAME + "` to understand the current index\n" +
            "- Skim existing topic files so you improve them rather than creating duplicates\n" +
            "- If `logs/` or `sessions/` subdirectories exist (assistant-mode layout), " +
            "review recent entries there\n\n" +
            "## Phase 2 — Gather recent signal\n\n" +
            "Look for new information worth persisting. Sources in rough priority order:\n\n" +
            "1. **Daily logs** (`logs/YYYY/MM/YYYY-MM-DD.md`) if present — " +
            "these are the append-only stream\n" +
            "2. **Existing memories that drifted** — facts that contradict something " +
            "you see in the codebase now\n" +
            "3. **Transcript search** — if you need specific context (e.g., \"what was the " +
            "error message from yesterday's build failure?\"), grep the JSONL transcripts " +
            "for narrow terms:\n" +
            "   `grep -rn \"<narrow term>\" " + transcriptDir + "/ --include=\"*.jsonl\" | tail -50`\n\n" +
            "Don't exhaustively read transcripts. Look only for things you already suspect matter.\n\n" +
            "## Phase 3 — Consolidate\n\n" +
            "For each thing worth remembering, write or update a memory file at the top level " +
            "of the memory directory. Use the memory file format and type conventions from your " +
            "system prompt's auto-memory section — it's the source of truth for what to save, " +
            "how to structure it, and what NOT to save.\n\n" +
            "Focus on:\n" +
            "- Merging new signal into existing topic files rather than creating near-duplicates\n" +
            "- Converting relative dates (\"yesterday\", \"last week\") to absolute dates so " +
            "they remain interpretable after time passes\n" +
            "- Deleting contradicted facts — if today's investigation disproves an old memory, " +
            "fix it at the source\n\n" +
            "## Phase 4 — Prune and index\n\n" +
            "Update `" + ENTRYPOINT_NAME + "` so it stays under " + MAX_ENTRYPOINT_LINES +
            " lines AND under ~25KB. It's an **index**, not a dump — each entry should be " +
            "one line under ~150 characters: `- [Title](file.md) — one-line hook`. " +
            "Never write memory content directly into it.\n\n" +
            "- Remove pointers to memories that are now stale, wrong, or superseded\n" +
            "- Demote verbose entries: if an index line is over ~200 chars, it's carrying " +
            "content that belongs in the topic file — shorten the line, move the detail\n" +
            "- Add pointers to newly important memories\n" +
            "- Resolve contradictions — if two files disagree, fix the wrong one\n\n" +
            "---\n\n" +
            "Return a brief summary of what you consolidated, updated, or pruned. " +
            "If nothing changed (memories are already tight), say so." +
            extraSection;
    }
}
