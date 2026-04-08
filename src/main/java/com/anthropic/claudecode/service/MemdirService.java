package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.anthropic.claudecode.util.MemdirPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Memory directory (memdir) service.
 * Translated from src/memdir/memdir.ts
 *
 * Provides the memory system behavioral instructions and prompt building.
 * Path utilities are in {@link MemdirPaths}.
 */
@Slf4j
@Service
public class MemdirService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MemdirService.class);


    public static final String ENTRYPOINT_NAME = "MEMORY.md";
    public static final int MAX_ENTRYPOINT_LINES = 200;
    /**
     * ~125 chars/line at 200 lines. Catches long-line indexes that slip past
     * the line cap (p100 observed: 197KB under 200 lines).
     */
    public static final int MAX_ENTRYPOINT_BYTES = 25_000;

    private static final String AUTO_MEM_DISPLAY_NAME = "auto memory";

    /**
     * Shared guidance text appended to each memory directory prompt line.
     * Translated from DIR_EXISTS_GUIDANCE in memdir.ts
     */
    public static final String DIR_EXISTS_GUIDANCE =
            "This directory already exists — write to it directly with the Write tool " +
            "(do not run mkdir or check for its existence).";

    public static final String DIRS_EXIST_GUIDANCE =
            "Both directories already exist — write to them directly with the Write tool " +
            "(do not run mkdir or check for their existence).";

    /**
     * Result of truncating the MEMORY.md entrypoint content.
     * Translated from EntrypointTruncation in memdir.ts
     */
    public record EntrypointTruncation(
            String content,
            int lineCount,
            int byteCount,
            boolean wasLineTruncated,
            boolean wasByteTruncated
    ) {}

    /**
     * Truncate MEMORY.md content to the line AND byte caps, appending a warning
     * that names which cap fired. Line-truncates first (natural boundary), then
     * byte-truncates at the last newline before the cap so we don't cut mid-line.
     *
     * Shared by buildMemoryPrompt and loadMemoryPrompt.
     * Translated from truncateEntrypointContent() in memdir.ts
     */
    public static EntrypointTruncation truncateEntrypointContent(String raw) {
        String trimmed = raw.trim();
        String[] contentLines = trimmed.split("\n", -1);
        int lineCount = contentLines.length;
        int byteCount = trimmed.length(); // approximation in UTF-16; TS uses byte length

        boolean wasLineTruncated = lineCount > MAX_ENTRYPOINT_LINES;
        // Check original byte count — long lines are the failure mode the byte cap targets
        boolean wasByteTruncated = byteCount > MAX_ENTRYPOINT_BYTES;

        if (!wasLineTruncated && !wasByteTruncated) {
            return new EntrypointTruncation(trimmed, lineCount, byteCount, false, false);
        }

        String truncated = wasLineTruncated
                ? String.join("\n", List.of(contentLines).subList(0, MAX_ENTRYPOINT_LINES))
                : trimmed;

        if (truncated.length() > MAX_ENTRYPOINT_BYTES) {
            int cutAt = truncated.lastIndexOf('\n', MAX_ENTRYPOINT_BYTES);
            truncated = truncated.substring(0, cutAt > 0 ? cutAt : MAX_ENTRYPOINT_BYTES);
        }

        String reason;
        if (wasByteTruncated && !wasLineTruncated) {
            reason = formatFileSize(byteCount) + " (limit: " + formatFileSize(MAX_ENTRYPOINT_BYTES) +
                    ") — index entries are too long";
        } else if (wasLineTruncated && !wasByteTruncated) {
            reason = lineCount + " lines (limit: " + MAX_ENTRYPOINT_LINES + ")";
        } else {
            reason = lineCount + " lines and " + formatFileSize(byteCount);
        }

        String warningMsg = "\n\n> WARNING: " + ENTRYPOINT_NAME + " is " + reason +
                ". Only part of it was loaded. " +
                "Keep index entries to one line under ~200 chars; move detail into topic files.";

        return new EntrypointTruncation(truncated + warningMsg, lineCount, byteCount,
                wasLineTruncated, wasByteTruncated);
    }

    private static String formatFileSize(int bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /**
     * Build the typed-memory behavioral instructions (without MEMORY.md content).
     * Constrains memories to a closed four-type taxonomy (user / feedback / project / reference).
     * Translated from buildMemoryLines() in memdir.ts
     *
     * @param displayName      display name for the memory section heading
     * @param memoryDir        path to the memory directory
     * @param extraGuidelines  optional additional guidelines to append
     * @param skipIndex        if true, omit the two-step index update instruction
     */
    public List<String> buildMemoryLines(
            String displayName,
            String memoryDir,
            List<String> extraGuidelines,
            boolean skipIndex) {

        List<String> howToSave;
        if (skipIndex) {
            howToSave = List.of(
                    "## How to save memories",
                    "",
                    "Write each memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) using this frontmatter format:",
                    "",
                    "```",
                    "---",
                    "name: <short name>",
                    "description: <one-line description>",
                    "type: <user|feedback|project|reference>",
                    "---",
                    "",
                    "<memory content>",
                    "```",
                    "",
                    "- Keep the name, description, and type fields in memory files up-to-date with the content",
                    "- Organize memory semantically by topic, not chronologically",
                    "- Update or remove memories that turn out to be wrong or outdated",
                    "- Do not write duplicate memories. First check if there is an existing memory you can update before writing a new one."
            );
        } else {
            howToSave = List.of(
                    "## How to save memories",
                    "",
                    "Saving a memory is a two-step process:",
                    "",
                    "**Step 1** — write the memory to its own file (e.g., `user_role.md`, `feedback_testing.md`) using this frontmatter format:",
                    "",
                    "```",
                    "---",
                    "name: <short name>",
                    "description: <one-line description>",
                    "type: <user|feedback|project|reference>",
                    "---",
                    "",
                    "<memory content>",
                    "```",
                    "",
                    "**Step 2** — add a pointer to that file in `" + ENTRYPOINT_NAME + "`. " +
                    "`" + ENTRYPOINT_NAME + "` is an index, not a memory — each entry should be one line, " +
                    "under ~150 characters: `- [Title](file.md) — one-line hook`. " +
                    "It has no frontmatter. Never write memory content directly into `" + ENTRYPOINT_NAME + "`.",
                    "",
                    "- `" + ENTRYPOINT_NAME + "` is always loaded into your conversation context — lines after " +
                    MAX_ENTRYPOINT_LINES + " will be truncated, so keep the index concise",
                    "- Keep the name, description, and type fields in memory files up-to-date with the content",
                    "- Organize memory semantically by topic, not chronologically",
                    "- Update or remove memories that turn out to be wrong or outdated",
                    "- Do not write duplicate memories. First check if there is an existing memory you can update before writing a new one."
            );
        }

        List<String> lines = new ArrayList<>();
        lines.add("# " + displayName);
        lines.add("");
        lines.add("You have a persistent, file-based memory system at `" + memoryDir + "`. " + DIR_EXISTS_GUIDANCE);
        lines.add("");
        lines.add("You should build up this memory system over time so that future conversations can have a complete picture of who the user is, how they'd like to collaborate with you, what behaviors to avoid or repeat, and the context behind the work the user gives you.");
        lines.add("");
        lines.add("If the user explicitly asks you to remember something, save it immediately as whichever type fits best. If they ask you to forget something, find and remove the relevant entry.");
        lines.add("");
        // Types section (individual variant: no scope tags)
        lines.addAll(List.of(
                "## Memory types",
                "",
                "Memories are organized into four types:",
                "- **user** — Facts about the user: role, preferences, working style, communication preferences",
                "- **feedback** — Patterns of feedback: what the user likes/dislikes about your behavior",
                "- **project** — Project context not derivable from the codebase: goals, stakeholders, decisions and rationale",
                "- **reference** — Stable reference material: tool docs, API patterns, known gotchas",
                ""
        ));
        // What not to save section
        lines.addAll(List.of(
                "## What NOT to save",
                "",
                "Do NOT save information that is derivable from the current project state:",
                "- Code patterns, architecture, file structure — read from the codebase instead",
                "- Git history — query with git commands instead",
                "- Content that changes frequently and would go stale immediately",
                ""
        ));
        lines.add("");
        lines.addAll(howToSave);
        lines.add("");
        // When to access section
        lines.addAll(List.of(
                "## When to access memory",
                "",
                "- At the start of a conversation: load `" + ENTRYPOINT_NAME + "` to orient yourself",
                "- When the user references past context: search topic files for relevant memories",
                "- When you learn something worth remembering: save it immediately",
                ""
        ));
        lines.add("");
        // Trusting recall section
        lines.addAll(List.of(
                "## Trusting your memory",
                "",
                "Memories are point-in-time observations. Before asserting a memory as current fact, " +
                "consider when it was written and whether it could have changed.",
                ""
        ));
        lines.add("");
        lines.add("## Memory and other forms of persistence");
        lines.add("Memory is one of several persistence mechanisms available to you as you assist the user in a given conversation. The distinction is often that memory can be recalled in future conversations and should not be used for persisting information that is only useful within the scope of the current conversation.");
        lines.add("- When to use or update a plan instead of memory: If you are about to start a non-trivial implementation task and would like to reach alignment with the user on your approach you should use a Plan rather than saving this information to memory. Similarly, if you already have a plan within the conversation and you have changed your approach persist that change by updating the plan rather than saving a memory.");
        lines.add("- When to use or update tasks instead of memory: When you need to break your work in current conversation into discrete steps or keep track of your progress use tasks instead of saving to memory. Tasks are great for persisting information about the work that needs to be done in the current conversation, but memory should be reserved for information that will be useful in future conversations.");
        lines.add("");
        if (extraGuidelines != null && !extraGuidelines.isEmpty()) {
            lines.addAll(extraGuidelines);
            lines.add("");
        }

        return lines;
    }

    /**
     * Build the typed-memory prompt with MEMORY.md content included.
     * Used by agent memory (which has no getClaudeMds() equivalent).
     * Translated from buildMemoryPrompt() in memdir.ts
     */
    public String buildMemoryPrompt(String displayName, String memoryDir, List<String> extraGuidelines) {
        Path entrypoint = Path.of(memoryDir, ENTRYPOINT_NAME);
        String entrypointContent = "";
        try {
            entrypointContent = Files.readString(entrypoint);
        } catch (IOException e) {
            // No memory file yet — that's fine
        }

        List<String> lines = buildMemoryLines(displayName, memoryDir, extraGuidelines, false);

        if (!entrypointContent.isBlank()) {
            EntrypointTruncation t = truncateEntrypointContent(entrypointContent);
            lines.add("## " + ENTRYPOINT_NAME);
            lines.add("");
            lines.add(t.content());
        } else {
            lines.add("## " + ENTRYPOINT_NAME);
            lines.add("");
            lines.add("Your " + ENTRYPOINT_NAME + " is currently empty. When you save new memories, they will appear here.");
        }

        return String.join("\n", lines);
    }

    /**
     * Ensure a memory directory exists. Idempotent.
     * Translated from ensureMemoryDirExists() in memdir.ts
     */
    public CompletableFuture<Void> ensureMemoryDirExists(String memoryDir) {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(Path.of(memoryDir));
            } catch (IOException e) {
                // EEXIST is already handled by createDirectories.
                // Anything else (EACCES/EPERM/EROFS) is a real problem — log it.
                log.debug("ensureMemoryDirExists failed for {}: {}", memoryDir, e.getMessage());
            }
        });
    }

    /**
     * Load the unified memory prompt for inclusion in the system prompt.
     * Returns null when auto memory is disabled.
     * Translated from loadMemoryPrompt() in memdir.ts
     *
     * @param projectRoot the canonical project root for path resolution
     */
    public CompletableFuture<String> loadMemoryPrompt(String projectRoot) {
        return CompletableFuture.supplyAsync(() -> {
            boolean autoEnabled = MemdirPaths.isAutoMemoryEnabled();

            if (!autoEnabled) {
                log.debug("[memdir] Auto memory disabled");
                return null;
            }

            String autoDir = MemdirPaths.getAutoMemPath(projectRoot);

            // Ensure the directory exists before the model tries to write
            try {
                Files.createDirectories(Path.of(autoDir));
            } catch (IOException e) {
                log.debug("[memdir] Could not create memory dir {}: {}", autoDir, e.getMessage());
            }

            // Cowork injects memory-policy text via env var
            String coworkExtra = System.getenv("CLAUDE_COWORK_MEMORY_EXTRA_GUIDELINES");
            List<String> extraGuidelines = (coworkExtra != null && !coworkExtra.isBlank())
                    ? List.of(coworkExtra)
                    : null;

            return String.join("\n",
                    buildMemoryLines(AUTO_MEM_DISPLAY_NAME, autoDir, extraGuidelines, false));
        });
    }

    /**
     * Check if auto-memory is enabled.
     * Delegates to {@link MemdirPaths#isAutoMemoryEnabled()}.
     * Translated from isAutoMemoryEnabled() in paths.ts
     */
    public boolean isAutoMemoryEnabled() {
        return MemdirPaths.isAutoMemoryEnabled();
    }

    /**
     * Get the auto-memory path for a project.
     * Delegates to {@link MemdirPaths#getAutoMemPath(String)}.
     */
    public String getAutoMemPath(String projectRoot) {
        return MemdirPaths.getAutoMemPath(projectRoot);
    }

    /** Get auto-memory path using current working directory as project root. */
    public String getAutoMemPath() {
        return MemdirPaths.getAutoMemPath(System.getProperty("user.dir", ""));
    }

    /**
     * Get the entrypoint for auto-memory (MEMORY.md inside the auto-memory dir).
     */
    public String getAutoMemEntrypoint(String projectRoot) {
        return MemdirPaths.getAutoMemEntrypoint(projectRoot);
    }

    /**
     * Check if a path is an auto-memory path.
     */
    public boolean isAutoMemPath(String path, String projectRoot) {
        return MemdirPaths.isAutoMemPath(path, projectRoot);
    }
}
