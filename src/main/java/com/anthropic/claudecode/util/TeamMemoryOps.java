package com.anthropic.claudecode.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Team memory operations utilities.
 * Translated from src/utils/teamMemoryOps.ts
 *
 * Utilities for detecting and handling team memory file operations,
 * and building summary text for team memory activity.
 */
public final class TeamMemoryOps {

    // Tool name constants (mirrors FILE_WRITE_TOOL_NAME / FILE_EDIT_TOOL_NAME)
    public static final String FILE_WRITE_TOOL_NAME = "Write";
    public static final String FILE_EDIT_TOOL_NAME  = "Edit";

    // -------------------------------------------------------------------------
    // Path detection
    // -------------------------------------------------------------------------

    /**
     * Check if a file path refers to a team memory file.
     * Translated from isTeamMemFile() in teamMemPaths.ts (re-exported via teamMemoryOps.ts)
     */
    public static boolean isTeamMemFile(String path) {
        if (path == null) return false;
        return path.contains(".claude/team-memory") || path.contains("team-memory");
    }

    // -------------------------------------------------------------------------
    // Tool-use detection
    // -------------------------------------------------------------------------

    /**
     * Check if a search tool use targets team memory files by examining its path input.
     * Translated from isTeamMemorySearch() in teamMemoryOps.ts
     */
    public static boolean isTeamMemorySearch(Map<String, Object> toolInput) {
        if (toolInput == null) return false;

        String path = (String) toolInput.get("path");
        if (path != null && isTeamMemFile(path)) return true;

        // The TypeScript also checks "glob" but not "pattern" for search
        String glob = (String) toolInput.get("glob");
        if (glob != null && isTeamMemFile(glob)) return true;

        return false;
    }

    /**
     * Check if a Write or Edit tool use targets a team memory file.
     * Translated from isTeamMemoryWriteOrEdit() in teamMemoryOps.ts
     */
    public static boolean isTeamMemoryWriteOrEdit(String toolName,
                                                   Map<String, Object> toolInput) {
        if (!FILE_WRITE_TOOL_NAME.equals(toolName) && !FILE_EDIT_TOOL_NAME.equals(toolName)) {
            return false;
        }
        if (toolInput == null) return false;

        // file_path takes precedence, then path (matches TS: input?.file_path ?? input?.path)
        String filePath = (String) toolInput.get("file_path");
        if (filePath == null) filePath = (String) toolInput.get("path");

        return filePath != null && isTeamMemFile(filePath);
    }

    // -------------------------------------------------------------------------
    // Summary text helpers
    // -------------------------------------------------------------------------

    /**
     * Counts container for team memory operation tallies.
     */
    public record MemoryCounts(
        int teamMemoryReadCount,
        int teamMemorySearchCount,
        int teamMemoryWriteCount
    ) {}

    /**
     * Append team memory summary parts to the given list.
     * Encapsulates all team memory verb/string logic for summary text generation.
     * Translated from appendTeamMemorySummaryParts() in teamMemoryOps.ts
     *
     * @param counts   operation tallies
     * @param isActive true if the action is still in progress (uses present continuous)
     * @param parts    mutable list to append summary strings to
     */
    public static void appendTeamMemorySummaryParts(MemoryCounts counts,
                                                      boolean isActive,
                                                      List<String> parts) {
        int readCount   = counts.teamMemoryReadCount();
        int searchCount = counts.teamMemorySearchCount();
        int writeCount  = counts.teamMemoryWriteCount();

        if (readCount > 0) {
            String verb = pickVerb(isActive, parts.isEmpty(), "Recalling", "recalling",
                "Recalled", "recalled");
            parts.add(verb + " " + readCount + " team "
                + (readCount == 1 ? "memory" : "memories"));
        }

        if (searchCount > 0) {
            String verb = pickVerb(isActive, parts.isEmpty(), "Searching", "searching",
                "Searched", "searched");
            parts.add(verb + " team memories");
        }

        if (writeCount > 0) {
            String verb = pickVerb(isActive, parts.isEmpty(), "Writing", "writing",
                "Wrote", "wrote");
            parts.add(verb + " " + writeCount + " team "
                + (writeCount == 1 ? "memory" : "memories"));
        }
    }

    /** Pick the appropriate verb based on tense (active/past) and position (lead/continuation). */
    private static String pickVerb(boolean isActive, boolean isFirst,
                                    String activeFirst, String activeCont,
                                    String pastFirst,   String pastCont) {
        if (isActive) return isFirst ? activeFirst : activeCont;
        return isFirst ? pastFirst : pastCont;
    }

    private TeamMemoryOps() {}
}
