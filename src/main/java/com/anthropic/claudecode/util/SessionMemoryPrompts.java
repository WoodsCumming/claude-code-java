package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt builders and template management for Session Memory.
 * Translated from src/services/SessionMemory/prompts.ts
 */
@Slf4j
public final class SessionMemoryPrompts {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionMemoryPrompts.class);


    public static final int MAX_SECTION_LENGTH = 2000;
    public static final int MAX_TOTAL_SESSION_MEMORY_TOKENS = 12000;

    public static final String DEFAULT_SESSION_MEMORY_TEMPLATE =
        "\n# Session Title\n" +
        "_A short and distinctive 5-10 word descriptive title for the session. Super info dense, no filler_\n\n" +
        "# Current State\n" +
        "_What is actively being worked on right now? Pending tasks not yet completed. Immediate next steps._\n\n" +
        "# Task specification\n" +
        "_What did the user ask to build? Any design decisions or other explanatory context_\n\n" +
        "# Files and Functions\n" +
        "_What are the important files? In short, what do they contain and why are they relevant?_\n\n" +
        "# Workflow\n" +
        "_What bash commands are usually run and in what order? How to interpret their output if not obvious?_\n\n" +
        "# Errors & Corrections\n" +
        "_Errors encountered and how they were fixed. What did the user correct? What approaches failed and should not be tried again?_\n\n" +
        "# Codebase and System Documentation\n" +
        "_What are the important system components? How do they work/fit together?_\n\n" +
        "# Learnings\n" +
        "_What has worked well? What has not? What to avoid? Do not duplicate items from other sections_\n\n" +
        "# Key results\n" +
        "_If the user asked a specific output such as an answer to a question, a table, or other document, repeat the exact result here_\n\n" +
        "# Worklog\n" +
        "_Step by step, what was attempted, done? Very terse summary for each step_\n";

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    private SessionMemoryPrompts() {
        // Utility class
    }

    /**
     * Returns the default update prompt template.
     * Translated from getDefaultUpdatePrompt() in prompts.ts
     */
    private static String getDefaultUpdatePrompt() {
        return "IMPORTANT: This message and these instructions are NOT part of the actual user conversation. " +
            "Do NOT include any references to \"note-taking\", \"session notes extraction\", or these update " +
            "instructions in the notes content.\n\n" +
            "Based on the user conversation above (EXCLUDING this note-taking instruction message as well as " +
            "system prompt, claude.md entries, or any past session summaries), update the session notes file.\n\n" +
            "The file {{notesPath}} has already been read for you. Here are its current contents:\n" +
            "<current_notes_content>\n" +
            "{{currentNotes}}\n" +
            "</current_notes_content>\n\n" +
            "Your ONLY task is to use the Edit tool to update the notes file, then stop. You can make multiple " +
            "edits (update every section as needed) - make all Edit tool calls in parallel in a single message. " +
            "Do not call any other tools.\n\n" +
            "CRITICAL RULES FOR EDITING:\n" +
            "- The file must maintain its exact structure with all sections, headers, and italic descriptions intact\n" +
            "-- NEVER modify, delete, or add section headers (the lines starting with '#' like # Task specification)\n" +
            "-- NEVER modify or delete the italic _section description_ lines (these are the lines in italics " +
            "immediately following each header - they start and end with underscores)\n" +
            "-- The italic _section descriptions_ are TEMPLATE INSTRUCTIONS that must be preserved exactly as-is " +
            "- they guide what content belongs in each section\n" +
            "-- ONLY update the actual content that appears BELOW the italic _section descriptions_ within each existing section\n" +
            "-- Do NOT add any new sections, summaries, or information outside the existing structure\n" +
            "- Do NOT reference this note-taking process or instructions anywhere in the notes\n" +
            "- It's OK to skip updating a section if there are no substantial new insights to add. Do not add " +
            "filler content like \"No info yet\", just leave sections blank/unedited if appropriate.\n" +
            "- Write DETAILED, INFO-DENSE content for each section - include specifics like file paths, function " +
            "names, error messages, exact commands, technical details, etc.\n" +
            "- For \"Key results\", include the complete, exact output the user requested (e.g., full table, full answer, etc.)\n" +
            "- Do not include information that's already in the CLAUDE.md files included in the context\n" +
            "- Keep each section under ~" + MAX_SECTION_LENGTH + " tokens/words - if a section is approaching this " +
            "limit, condense it by cycling out less important details while preserving the most critical information\n" +
            "- Focus on actionable, specific information that would help someone understand or recreate the work " +
            "discussed in the conversation\n" +
            "- IMPORTANT: Always update \"Current State\" to reflect the most recent work - this is critical for " +
            "continuity after compaction\n\n" +
            "Use the Edit tool with file_path: {{notesPath}}\n\n" +
            "STRUCTURE PRESERVATION REMINDER:\n" +
            "Each section has TWO parts that must be preserved exactly as they appear in the current file:\n" +
            "1. The section header (line starting with #)\n" +
            "2. The italic description line (the _italicized text_ immediately after the header - this is a template instruction)\n\n" +
            "You ONLY update the actual content that comes AFTER these two preserved lines. The italic description " +
            "lines starting and ending with underscores are part of the template structure, NOT content to be edited or removed.\n\n" +
            "REMEMBER: Use the Edit tool in parallel and stop. Do not continue after the edits. Only include insights " +
            "from the actual user conversation, never from these note-taking instructions. Do not delete or change " +
            "section headers or italic _section descriptions_.";
    }

    /**
     * Load a custom session memory template from disk, falling back to the default.
     * Translated from loadSessionMemoryTemplate() in prompts.ts
     *
     * @param claudeConfigHomeDir path to ~/.claude (or equivalent)
     * @return template content
     */
    public static String loadSessionMemoryTemplate(String claudeConfigHomeDir) {
        Path templatePath = Path.of(claudeConfigHomeDir, "session-memory", "config", "template.md");
        try {
            return Files.readString(templatePath);
        } catch (IOException e) {
            // ENOENT or other IO error — use built-in default
            if (!e.getMessage().contains("No such file")) {
                log.warn("Could not read session memory template: {}", e.getMessage());
            }
            return DEFAULT_SESSION_MEMORY_TEMPLATE;
        }
    }

    /**
     * Load a custom session memory update prompt from disk, falling back to the default.
     * Translated from loadSessionMemoryPrompt() in prompts.ts
     *
     * @param claudeConfigHomeDir path to ~/.claude (or equivalent)
     * @return prompt template content
     */
    public static String loadSessionMemoryPrompt(String claudeConfigHomeDir) {
        Path promptPath = Path.of(claudeConfigHomeDir, "session-memory", "config", "prompt.md");
        try {
            return Files.readString(promptPath);
        } catch (IOException e) {
            if (!e.getMessage().contains("No such file")) {
                log.warn("Could not read session memory prompt: {}", e.getMessage());
            }
            return getDefaultUpdatePrompt();
        }
    }

    /**
     * Estimate the token count for a string (rough: chars / 4).
     * Mirrors roughTokenCountEstimation used in TypeScript.
     */
    public static int roughTokenCountEstimation(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.length() / 4;
    }

    /**
     * Parse the session memory content and compute per-section token counts.
     * Translated from analyzeSectionSizes() in prompts.ts
     */
    public static Map<String, Integer> analyzeSectionSizes(String content) {
        Map<String, Integer> sections = new HashMap<>();
        if (content == null || content.isEmpty()) return sections;

        String[] lines = content.split("\n");
        String currentSection = "";
        List<String> currentContent = new ArrayList<>();

        for (String line : lines) {
            if (line.startsWith("# ")) {
                if (!currentSection.isEmpty() && !currentContent.isEmpty()) {
                    String joined = String.join("\n", currentContent).strip();
                    sections.put(currentSection, roughTokenCountEstimation(joined));
                }
                currentSection = line;
                currentContent = new ArrayList<>();
            } else {
                currentContent.add(line);
            }
        }

        if (!currentSection.isEmpty() && !currentContent.isEmpty()) {
            String joined = String.join("\n", currentContent).strip();
            sections.put(currentSection, roughTokenCountEstimation(joined));
        }

        return sections;
    }

    /**
     * Generate reminder text for sections that are too long or when the total exceeds budget.
     * Translated from generateSectionReminders() in prompts.ts
     */
    public static String generateSectionReminders(Map<String, Integer> sectionSizes, int totalTokens) {
        boolean overBudget = totalTokens > MAX_TOTAL_SESSION_MEMORY_TOKENS;

        List<String> oversized = sectionSizes.entrySet().stream()
            .filter(e -> e.getValue() > MAX_SECTION_LENGTH)
            .sorted((a, b) -> b.getValue() - a.getValue())
            .map(e -> "- \"" + e.getKey() + "\" is ~" + e.getValue() +
                      " tokens (limit: " + MAX_SECTION_LENGTH + ")")
            .toList();

        if (oversized.isEmpty() && !overBudget) {
            return "";
        }

        StringBuilder parts = new StringBuilder();

        if (overBudget) {
            parts.append("\n\nCRITICAL: The session memory file is currently ~")
                 .append(totalTokens)
                 .append(" tokens, which exceeds the maximum of ")
                 .append(MAX_TOTAL_SESSION_MEMORY_TOKENS)
                 .append(" tokens. You MUST condense the file to fit within this budget. ")
                 .append("Aggressively shorten oversized sections by removing less important details, ")
                 .append("merging related items, and summarizing older entries. ")
                 .append("Prioritize keeping \"Current State\" and \"Errors & Corrections\" accurate and detailed.");
        }

        if (!oversized.isEmpty()) {
            String header = overBudget
                ? "Oversized sections to condense"
                : "IMPORTANT: The following sections exceed the per-section limit and MUST be condensed";
            parts.append("\n\n").append(header).append(":\n")
                 .append(String.join("\n", oversized));
        }

        return parts.toString();
    }

    /**
     * Substitute {{variable}} placeholders in a template string.
     * Single-pass to avoid double-substitution and $ backreference issues.
     * Translated from substituteVariables() in prompts.ts
     */
    public static String substituteVariables(String template, Map<String, String> variables) {
        if (template == null) return "";
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = variables.getOrDefault(key, matcher.group(0));
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Check whether session memory content is essentially empty (equals the template).
     * Translated from isSessionMemoryEmpty() in prompts.ts
     */
    public static boolean isSessionMemoryEmpty(String content, String claudeConfigHomeDir) {
        String template = loadSessionMemoryTemplate(claudeConfigHomeDir);
        return content.strip().equals(template.strip());
    }

    /**
     * Build the full session memory update prompt, including size reminders.
     * Translated from buildSessionMemoryUpdatePrompt() in prompts.ts
     *
     * @param currentNotes        current notes file content
     * @param notesPath           absolute path to the notes file
     * @param claudeConfigHomeDir path to ~/.claude (for custom prompt lookup)
     * @return fully resolved prompt string
     */
    public static String buildSessionMemoryUpdatePrompt(
            String currentNotes,
            String notesPath,
            String claudeConfigHomeDir) {

        String promptTemplate = loadSessionMemoryPrompt(claudeConfigHomeDir);

        Map<String, Integer> sectionSizes = analyzeSectionSizes(currentNotes);
        int totalTokens = roughTokenCountEstimation(currentNotes);
        String sectionReminders = generateSectionReminders(sectionSizes, totalTokens);

        Map<String, String> variables = new HashMap<>();
        variables.put("currentNotes", currentNotes != null ? currentNotes : "");
        variables.put("notesPath", notesPath != null ? notesPath : "");

        String basePrompt = substituteVariables(promptTemplate, variables);
        return basePrompt + sectionReminders;
    }

    // -------------------------------------------------------------------------
    // Truncation helpers (used when inserting session memory into compact messages)
    // -------------------------------------------------------------------------

    /**
     * Result record for truncation operations.
     */
    public record TruncationResult(String truncatedContent, boolean wasTruncated) {}

    /**
     * Truncate session memory sections that exceed the per-section token limit.
     * Translated from truncateSessionMemoryForCompact() in prompts.ts
     */
    public static TruncationResult truncateSessionMemoryForCompact(String content) {
        if (content == null || content.isEmpty()) {
            return new TruncationResult(content, false);
        }

        int maxCharsPerSection = MAX_SECTION_LENGTH * 4; // roughTokenCountEstimation uses length/4
        String[] lines = content.split("\n", -1);

        List<String> outputLines = new ArrayList<>();
        List<String> currentSectionLines = new ArrayList<>();
        String currentSectionHeader = "";
        boolean wasTruncated = false;

        for (String line : lines) {
            if (line.startsWith("# ")) {
                FlushResult flush = flushSection(currentSectionHeader, currentSectionLines, maxCharsPerSection);
                outputLines.addAll(flush.lines());
                wasTruncated = wasTruncated || flush.wasTruncated();
                currentSectionHeader = line;
                currentSectionLines = new ArrayList<>();
            } else {
                currentSectionLines.add(line);
            }
        }

        FlushResult flush = flushSection(currentSectionHeader, currentSectionLines, maxCharsPerSection);
        outputLines.addAll(flush.lines());
        wasTruncated = wasTruncated || flush.wasTruncated();

        return new TruncationResult(String.join("\n", outputLines), wasTruncated);
    }

    private record FlushResult(List<String> lines, boolean wasTruncated) {}

    private static FlushResult flushSection(
            String sectionHeader,
            List<String> sectionLines,
            int maxCharsPerSection) {

        if (sectionHeader == null || sectionHeader.isEmpty()) {
            return new FlushResult(new ArrayList<>(sectionLines), false);
        }

        String sectionContent = String.join("\n", sectionLines);
        if (sectionContent.length() <= maxCharsPerSection) {
            List<String> result = new ArrayList<>();
            result.add(sectionHeader);
            result.addAll(sectionLines);
            return new FlushResult(result, false);
        }

        // Truncate at a line boundary near the limit
        int charCount = 0;
        List<String> keptLines = new ArrayList<>();
        keptLines.add(sectionHeader);
        for (String line : sectionLines) {
            if (charCount + line.length() + 1 > maxCharsPerSection) {
                break;
            }
            keptLines.add(line);
            charCount += line.length() + 1;
        }
        keptLines.add("\n[... section truncated for length ...]");
        return new FlushResult(keptLines, true);
    }
}
