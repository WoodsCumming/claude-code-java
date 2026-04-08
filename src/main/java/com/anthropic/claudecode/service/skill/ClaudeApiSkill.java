package com.anthropic.claudecode.service.skill;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ClaudeApiSkill — builds the prompt for the {@code /claude-api} command.
 *
 * <p>Auto-detects the current project's programming language from files in the
 * working directory and inlines the relevant documentation sections, falling
 * back to all documentation when no language can be identified.
 *
 * <p>Translated from: src/skills/bundled/claudeApi.ts
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeApiSkill {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClaudeApiSkill.class);


    // -------------------------------------------------------------------------
    // Skill metadata
    // -------------------------------------------------------------------------

    public static final String NAME = "claude-api";
    public static final String DESCRIPTION =
            "Build apps with the Claude API or Anthropic SDK.\n"
            + "TRIGGER when: code imports `anthropic`/`@anthropic-ai/sdk`/`claude_agent_sdk`, "
            + "or user asks to use Claude API, Anthropic SDKs, or Agent SDK.\n"
            + "DO NOT TRIGGER when: code imports `openai`/other AI SDK, "
            + "general programming, or ML/data-science tasks.";

    public static final List<String> ALLOWED_TOOLS =
            List.of("Read", "Grep", "Glob", "WebFetch");

    // -------------------------------------------------------------------------
    // Language detection — mirrors LANGUAGE_INDICATORS in the TS source
    // -------------------------------------------------------------------------

    /** Supported languages that can be auto-detected from project files. */
    public enum DetectedLanguage {
        PYTHON, TYPESCRIPT, JAVA, GO, RUBY, CSHARP, PHP, CURL;

        /** Returns the lower-case name used as a path prefix in SKILL_FILES. */
        public String pathPrefix() {
            return name().toLowerCase();
        }
    }

    private static final Map<DetectedLanguage, List<String>> LANGUAGE_INDICATORS =
            Map.of(
                    DetectedLanguage.PYTHON,     List.of(".py", "requirements.txt", "pyproject.toml", "setup.py", "Pipfile"),
                    DetectedLanguage.TYPESCRIPT, List.of(".ts", ".tsx", "tsconfig.json", "package.json"),
                    DetectedLanguage.JAVA,       List.of(".java", "pom.xml", "build.gradle"),
                    DetectedLanguage.GO,         List.of(".go", "go.mod"),
                    DetectedLanguage.RUBY,       List.of(".rb", "Gemfile"),
                    DetectedLanguage.CSHARP,     List.of(".cs", ".csproj"),
                    DetectedLanguage.PHP,        List.of(".php", "composer.json"),
                    DetectedLanguage.CURL,       List.of()
            );

    // -------------------------------------------------------------------------
    // Inline reading guide template
    // -------------------------------------------------------------------------

    private static final String INLINE_READING_GUIDE = """
            ## Reference Documentation

            The relevant documentation for your detected language is included below in `<doc>` tags. Each tag has a `path` attribute showing its original file path. Use this to find the right section:

            ### Quick Task Reference

            **Single text classification/summarization/extraction/Q&A:**
            → Refer to `{lang}/claude-api/README.md`

            **Chat UI or real-time response display:**
            → Refer to `{lang}/claude-api/README.md` + `{lang}/claude-api/streaming.md`

            **Long-running conversations (may exceed context window):**
            → Refer to `{lang}/claude-api/README.md` — see Compaction section

            **Prompt caching / optimize caching / "why is my cache hit rate low":**
            → Refer to `shared/prompt-caching.md` + `{lang}/claude-api/README.md` (Prompt Caching section)

            **Function calling / tool use / agents:**
            → Refer to `{lang}/claude-api/README.md` + `shared/tool-use-concepts.md` + `{lang}/claude-api/tool-use.md`

            **Batch processing (non-latency-sensitive):**
            → Refer to `{lang}/claude-api/README.md` + `{lang}/claude-api/batches.md`

            **File uploads across multiple requests:**
            → Refer to `{lang}/claude-api/README.md` + `{lang}/claude-api/files-api.md`

            **Agent with built-in tools (file/web/terminal) (Python & TypeScript only):**
            → Refer to `{lang}/agent-sdk/README.md` + `{lang}/agent-sdk/patterns.md`

            **Error handling:**
            → Refer to `shared/error-codes.md`

            **Latest docs via WebFetch:**
            → Refer to `shared/live-sources.md` for URLs""";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Builds the prompt for the {@code /claude-api} command.
     *
     * @param args optional user-supplied request text
     * @return a single-element list containing the full prompt text
     */
    public CompletableFuture<List<PromptPart>> getPromptForCommand(String args) {
        return CompletableFuture.supplyAsync(() -> {
            DetectedLanguage lang = detectLanguage();
            String prompt = buildPrompt(lang, args == null ? "" : args);
            return List.of(new PromptPart("text", prompt));
        });
    }

    // -------------------------------------------------------------------------
    // Language detection
    // -------------------------------------------------------------------------

    /**
     * Scans the current working directory for well-known indicator files and
     * returns the first matching {@link DetectedLanguage}, or {@code null} when
     * no language can be identified.
     */
    DetectedLanguage detectLanguage() {
        String cwd = System.getProperty("user.dir");
        List<String> entries;
        try {
            entries = Files.list(Path.of(cwd))
                           .map(p -> p.getFileName().toString())
                           .toList();
        } catch (IOException e) {
            log.debug("Failed to list working directory '{}': {}", cwd, e.getMessage());
            return null;
        }

        for (Map.Entry<DetectedLanguage, List<String>> entry : LANGUAGE_INDICATORS.entrySet()) {
            List<String> indicators = entry.getValue();
            if (indicators.isEmpty()) {
                continue;
            }
            for (String indicator : indicators) {
                if (indicator.startsWith(".")) {
                    if (entries.stream().anyMatch(e -> e.endsWith(indicator))) {
                        return entry.getKey();
                    }
                } else {
                    if (entries.contains(indicator)) {
                        return entry.getKey();
                    }
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Content helpers
    // -------------------------------------------------------------------------

    /** Returns the skill-file paths that belong to {@code lang} or are shared. */
    private List<String> getFilesForLanguage(DetectedLanguage lang) {
        String prefix = lang.pathPrefix() + "/";
        return ClaudeApiContentSkill.SKILL_FILES.keySet().stream()
                .filter(path -> path.startsWith(prefix) || path.startsWith("shared/"))
                .sorted()
                .toList();
    }

    /**
     * Strips HTML comments (including nested) from {@code md} and substitutes
     * {@code {{VAR}}} placeholders with their model-variable values.
     */
    private String processContent(String md) {
        // Iteratively strip HTML comments to handle nested <!-- --> blocks.
        String out = md;
        String prev;
        do {
            prev = out;
            out = out.replaceAll("(?s)<!--.*?-->\\n?", "");
        } while (!out.equals(prev));

        // Substitute {{VAR}} placeholders.
        Pattern varPattern = Pattern.compile("\\{\\{(\\w+)}}");
        Matcher matcher = varPattern.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = ClaudeApiContentSkill.SKILL_MODEL_VARS.getOrDefault(key, matcher.group(0));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Builds a concatenated block of {@code <doc path="...">} sections from the
     * supplied file paths, sorted alphabetically.
     */
    private String buildInlineReference(List<String> filePaths) {
        List<String> sections = new ArrayList<>();
        List<String> sorted = new ArrayList<>(filePaths);
        sorted.sort(null);
        for (String filePath : sorted) {
            String md = ClaudeApiContentSkill.SKILL_FILES.get(filePath);
            if (md == null) {
                continue;
            }
            String processed = processContent(md).strip();
            sections.add("<doc path=\"" + filePath + "\">\n" + processed + "\n</doc>");
        }
        return String.join("\n\n", sections);
    }

    // -------------------------------------------------------------------------
    // Prompt builder
    // -------------------------------------------------------------------------

    private String buildPrompt(DetectedLanguage lang, String args) {
        String cleanPrompt = processContent(ClaudeApiContentSkill.SKILL_PROMPT);

        // Take only the portion of SKILL.md that comes before "## Reading Guide".
        int readingGuideIdx = cleanPrompt.indexOf("## Reading Guide");
        String basePrompt = readingGuideIdx != -1
                ? cleanPrompt.substring(0, readingGuideIdx).stripTrailing()
                : cleanPrompt;

        List<String> parts = new ArrayList<>();
        parts.add(basePrompt);

        if (lang != null) {
            List<String> filePaths = getFilesForLanguage(lang);
            String readingGuide = INLINE_READING_GUIDE.replace("{lang}", lang.pathPrefix());
            parts.add(readingGuide);
            parts.add("---\n\n## Included Documentation\n\n" + buildInlineReference(filePaths));
        } else {
            parts.add(INLINE_READING_GUIDE.replace("{lang}", "unknown"));
            parts.add("No project language was auto-detected. Ask the user which language they are using, "
                      + "then refer to the matching docs below.");
            parts.add("---\n\n## Included Documentation\n\n"
                      + buildInlineReference(new ArrayList<>(ClaudeApiContentSkill.SKILL_FILES.keySet())));
        }

        // Preserve "## When to Use WebFetch" and following sections from SKILL.md.
        int webFetchIdx = cleanPrompt.indexOf("## When to Use WebFetch");
        if (webFetchIdx != -1) {
            parts.add(cleanPrompt.substring(webFetchIdx).stripTrailing());
        }

        if (args != null && !args.isBlank()) {
            parts.add("## User Request\n\n" + args);
        }

        return String.join("\n\n", parts);
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /** Simple record representing a single prompt part sent to the model. */
    public record PromptPart(String type, String text) {}
}
