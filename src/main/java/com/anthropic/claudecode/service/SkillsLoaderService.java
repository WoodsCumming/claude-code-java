package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Command;
import com.anthropic.claudecode.util.*;
import com.anthropic.claudecode.util.ManagedSettingsPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Skills loader service.
 * Translated from src/skills/loadSkillsDir.ts
 *
 * Loads skill definitions from SKILL.md files in .claude/skills/ directories.
 * Also supports legacy /commands/ directories (flat .md files).
 */
@Slf4j
@Service
public class SkillsLoaderService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SkillsLoaderService.class);


    private static final String SKILLS_SUBDIR   = "skills";
    private static final String COMMANDS_SUBDIR = "commands";
    private static final String SKILL_FILE_NAME = "SKILL.md";

    // =========================================================================
    // LoadedFrom
    // Translated from LoadedFrom type in loadSkillsDir.ts
    // =========================================================================

    public enum LoadedFrom {
        COMMANDS_DEPRECATED("commands_DEPRECATED"),
        SKILLS("skills"),
        PLUGIN("plugin"),
        MANAGED("managed"),
        BUNDLED("bundled"),
        MCP("mcp");

        private final String value;
        LoadedFrom(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    // =========================================================================
    // Dynamic skill state
    // Translated from module-level state in loadSkillsDir.ts
    // =========================================================================

    /** Skill directories already visited (hit or miss). Avoids repeated stat on every call. */
    private final Set<String> dynamicSkillDirs = ConcurrentHashMap.newKeySet();
    /** Dynamically discovered skills keyed by name. Deeper paths override shallower. */
    private final Map<String, Command> dynamicSkills = new ConcurrentHashMap<>();
    /** Skills with paths frontmatter that have NOT yet been activated. */
    private final Map<String, Command> conditionalSkills = new ConcurrentHashMap<>();
    /** Names of skills that have ever been activated (survives cache clears within a session). */
    private final Set<String> activatedConditionalSkillNames = ConcurrentHashMap.newKeySet();
    /** Listeners notified when dynamic skills are loaded. */
    private final List<Runnable> skillsLoadedListeners = new CopyOnWriteArrayList<>();

    // =========================================================================
    // Memoised top-level loader
    // Translated from getSkillDirCommands (memoize) in loadSkillsDir.ts
    // =========================================================================

    /** Cache: cwd → loaded commands. */
    private final Map<String, CompletableFuture<List<Command>>> skillDirCache =
            new ConcurrentHashMap<>();

    /**
     * Loads all skills from both /skills/ and legacy /commands/ directories.
     * Result is memoised per cwd for the process lifetime.
     * Translated from getSkillDirCommands() in loadSkillsDir.ts
     */
    public CompletableFuture<List<Command>> getSkillDirCommands(String cwd) {
        return skillDirCache.computeIfAbsent(cwd, this::loadAllSkills);
    }

    private CompletableFuture<List<Command>> loadAllSkills(String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            String userSkillsDir    = EnvUtils.getClaudeConfigHomeDir() + "/" + SKILLS_SUBDIR;
            String managedSkillsDir = ManagedSettingsPath.getManagedFilePath() + "/.claude/" + SKILLS_SUBDIR;
            List<String> projectSkillsDirs = getProjectDirsUpToHome(SKILLS_SUBDIR, cwd);

            log.debug("Loading skills: managed={}, user={}, project=[{}]",
                    managedSkillsDir, userSkillsDir, String.join(", ", projectSkillsDirs));

            List<SkillWithPath> managedSkills = loadSkillsFromSkillsDir(managedSkillsDir, "policySettings");
            List<SkillWithPath> userSkills    = loadSkillsFromSkillsDir(userSkillsDir, "userSettings");

            List<SkillWithPath> projectSkills = new ArrayList<>();
            for (String dir : projectSkillsDirs) {
                projectSkills.addAll(loadSkillsFromSkillsDir(dir, "projectSettings"));
            }

            List<SkillWithPath> legacyCommands = loadSkillsFromCommandsDir(cwd);

            // Combine in priority order (managed > user > project > legacy)
            List<SkillWithPath> all = new ArrayList<>();
            all.addAll(managedSkills);
            all.addAll(userSkills);
            all.addAll(projectSkills);
            all.addAll(legacyCommands);

            // Deduplicate by canonical path (resolves symlinks)
            List<Command> deduplicated = deduplicateByPath(all);

            // Separate conditional from unconditional
            List<Command> unconditional = new ArrayList<>();
            for (Command skill : deduplicated) {
                List<String> paths = skill.getPaths();
                if (paths != null && !paths.isEmpty()
                        && !activatedConditionalSkillNames.contains(skill.getName())) {
                    conditionalSkills.put(skill.getName(), skill);
                } else {
                    unconditional.add(skill);
                }
            }

            if (!conditionalSkills.isEmpty()) {
                log.debug("[skills] {} conditional skills stored", conditionalSkills.size());
            }
            log.debug("Loaded {} unique skills ({} unconditional, {} conditional)",
                    deduplicated.size(), unconditional.size(), conditionalSkills.size());

            return unconditional;
        });
    }

    // =========================================================================
    // loadSkillsFromSkillsDir (private)
    // Translated from loadSkillsFromSkillsDir() in loadSkillsDir.ts
    // Only supports directory format: skill-name/SKILL.md
    // =========================================================================

    private List<SkillWithPath> loadSkillsFromSkillsDir(String basePath, String source) {
        List<SkillWithPath> results = new ArrayList<>();
        File baseDir = new File(basePath);
        if (!baseDir.isDirectory()) return results;

        File[] entries = baseDir.listFiles();
        if (entries == null) return results;

        for (File entry : entries) {
            if (!entry.isDirectory()) continue; // only directory format supported

            File skillFile = new File(entry, SKILL_FILE_NAME);
            if (!skillFile.exists()) continue;

            try {
                String content = Files.readString(skillFile.toPath());
                FrontmatterParser.FrontmatterData fm = FrontmatterParser.parseFrontmatter(content);
                String body = FrontmatterParser.removeFrontmatter(content);
                String skillName = entry.getName();
                String description = fm.getDescription() != null
                        ? fm.getDescription()
                        : MarkdownConfigLoader.extractDescriptionFromMarkdown(body, skillName);

                Command command = Command.builder()
                        .name(skillName)
                        .description(description)
                        .type(Command.CommandType.PROMPT)
                        .source(source)
                        .loadedFrom(LoadedFrom.SKILLS.getValue())
                        .version(fm.getVersion())
                        .argumentHint(fm.getArgumentHint())
                        .whenToUse(fm.getWhenToUse())
                        .model(fm.getModel())
                        .allowedTools(fm.getAllowedTools() != null
                                ? fm.getAllowedTools() : Collections.emptyList())
                        .userInvocable(fm.getUserInvocable() != null ? fm.getUserInvocable() : true)
                        .skillRoot(entry.getAbsolutePath())
                        .paths(parseSkillPaths(fm))
                        .build();

                results.add(new SkillWithPath(command, skillFile.getAbsolutePath()));
                log.debug("Loaded skill '{}' from {}", skillName, source);

            } catch (Exception e) {
                log.warn("Could not load skill {}: {}", entry.getName(), e.getMessage());
            }
        }
        return results;
    }

    // =========================================================================
    // loadSkillsFromCommandsDir (private)
    // Translated from loadSkillsFromCommandsDir() in loadSkillsDir.ts
    // Supports both directory format (SKILL.md) and single .md files.
    // =========================================================================

    private List<SkillWithPath> loadSkillsFromCommandsDir(String cwd) {
        List<SkillWithPath> skills = new ArrayList<>();
        if (cwd == null) return skills;

        List<String> commandsDirs = getProjectDirsUpToHome(COMMANDS_SUBDIR, cwd);
        String userCommandsDir = EnvUtils.getClaudeConfigHomeDir() + "/" + COMMANDS_SUBDIR;
        commandsDirs.add(0, userCommandsDir);

        for (String dirPath : commandsDirs) {
            File dir = new File(dirPath);
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles((d, n) -> n.endsWith(".md"));
            if (files == null) continue;

            for (File file : files) {
                try {
                    String content = Files.readString(file.toPath());
                    FrontmatterParser.FrontmatterData fm = FrontmatterParser.parseFrontmatter(content);
                    String body = FrontmatterParser.removeFrontmatter(content);

                    String name;
                    String skillRoot;
                    boolean isSkillFormat = file.getName().equalsIgnoreCase(SKILL_FILE_NAME);
                    if (isSkillFormat) {
                        name = file.getParentFile().getName();
                        skillRoot = file.getParentFile().getAbsolutePath();
                    } else {
                        name = file.getName().replaceAll("\\.md$", "");
                        skillRoot = null;
                    }

                    String description = fm.getDescription() != null
                            ? fm.getDescription()
                            : MarkdownConfigLoader.extractDescriptionFromMarkdown(body, name);

                    Command command = Command.builder()
                            .name(name)
                            .description(description)
                            .type(Command.CommandType.PROMPT)
                            .source("userSettings")
                            .loadedFrom(LoadedFrom.COMMANDS_DEPRECATED.getValue())
                            .version(fm.getVersion())
                            .argumentHint(fm.getArgumentHint())
                            .whenToUse(fm.getWhenToUse())
                            .model(fm.getModel())
                            .allowedTools(fm.getAllowedTools() != null
                                    ? fm.getAllowedTools() : Collections.emptyList())
                            .userInvocable(fm.getUserInvocable() != null ? fm.getUserInvocable() : true)
                            .skillRoot(skillRoot)
                            .build();

                    skills.add(new SkillWithPath(command, file.getAbsolutePath()));
                } catch (Exception e) {
                    log.debug("Could not load command {}: {}", file.getName(), e.getMessage());
                }
            }
        }
        return skills;
    }

    // =========================================================================
    // discoverSkillDirsForPaths
    // Translated from discoverSkillDirsForPaths() in loadSkillsDir.ts
    // =========================================================================

    /**
     * Discover .claude/skills directories by walking up from each file path toward cwd.
     * Only discovers directories BELOW cwd (cwd-level skills are loaded at startup).
     * Translated from discoverSkillDirsForPaths() in loadSkillsDir.ts
     *
     * @param filePaths Array of file paths to check.
     * @param cwd       Current working directory (upper bound).
     * @return Newly discovered directories, sorted deepest first.
     */
    public CompletableFuture<List<String>> discoverSkillDirsForPaths(
            List<String> filePaths, String cwd) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> newDirs = new ArrayList<>();
            String resolvedCwd = cwd.endsWith(File.separator)
                    ? cwd.substring(0, cwd.length() - 1) : cwd;

            for (String filePath : filePaths) {
                File current = new File(filePath).getParentFile();
                while (current != null) {
                    String currentPath = current.getAbsolutePath();
                    // Stop at or above cwd
                    if (!currentPath.startsWith(resolvedCwd + File.separator)) break;

                    String skillDir = currentPath + File.separator + ".claude"
                            + File.separator + SKILLS_SUBDIR;
                    if (!dynamicSkillDirs.contains(skillDir)) {
                        dynamicSkillDirs.add(skillDir);
                        if (new File(skillDir).isDirectory()) {
                            newDirs.add(skillDir);
                        }
                    }
                    current = current.getParentFile();
                }
            }

            // Sort deepest first (most path separators = deepest)
            newDirs.sort((a, b) -> Long.compare(
                    b.chars().filter(c -> c == File.separatorChar).count(),
                    a.chars().filter(c -> c == File.separatorChar).count()));
            return newDirs;
        });
    }

    // =========================================================================
    // addSkillDirectories
    // Translated from addSkillDirectories() in loadSkillsDir.ts
    // =========================================================================

    /**
     * Load skills from the given directories and merge them into the dynamic skills map.
     * Skills from deeper directories take precedence (process in reverse = shallower first).
     * Translated from addSkillDirectories() in loadSkillsDir.ts
     */
    public CompletableFuture<Void> addSkillDirectories(List<String> dirs) {
        if (dirs == null || dirs.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> {
            Set<String> previousNames = new HashSet<>(dynamicSkills.keySet());

            // Load all directories
            List<List<SkillWithPath>> loaded = new ArrayList<>();
            for (String dir : dirs) {
                loaded.add(loadSkillsFromSkillsDir(dir, "projectSettings"));
            }

            // Process in reverse (shallower first) so deeper paths override
            for (int i = loaded.size() - 1; i >= 0; i--) {
                for (SkillWithPath swp : loaded.get(i)) {
                    if ("prompt".equals(swp.getSkill().getType().name().toLowerCase())) {
                        dynamicSkills.put(swp.getSkill().getName(), swp.getSkill());
                    }
                }
            }

            long newCount = loaded.stream().mapToLong(List::size).sum();
            if (newCount > 0) {
                List<String> added = new ArrayList<>(dynamicSkills.keySet());
                added.removeAll(previousNames);
                log.debug("[skills] Dynamically discovered {} skills from {} directories",
                        newCount, dirs.size());
            }

            // Notify listeners
            skillsLoaded();
        });
    }

    // =========================================================================
    // activateConditionalSkillsForPaths
    // Translated from activateConditionalSkillsForPaths() in loadSkillsDir.ts
    // =========================================================================

    /**
     * Activate conditional skills whose path patterns match the given file paths.
     * Uses gitignore-style matching (simple prefix matching here).
     * Translated from activateConditionalSkillsForPaths() in loadSkillsDir.ts
     */
    public List<String> activateConditionalSkillsForPaths(List<String> filePaths, String cwd) {
        if (conditionalSkills.isEmpty()) return Collections.emptyList();

        List<String> activated = new ArrayList<>();

        for (Map.Entry<String, Command> entry : conditionalSkills.entrySet()) {
            Command skill = entry.getValue();
            List<String> patterns = skill.getPaths();
            if (patterns == null || patterns.isEmpty()) continue;

            boolean matches = false;
            for (String filePath : filePaths) {
                String relative = toRelativePath(filePath, cwd);
                if (relative == null) continue;

                for (String pattern : patterns) {
                    if (matchesGitignorePattern(relative, pattern)) {
                        matches = true;
                        break;
                    }
                }
                if (matches) break;
            }

            if (matches) {
                dynamicSkills.put(skill.getName(), skill);
                conditionalSkills.remove(skill.getName());
                activatedConditionalSkillNames.add(skill.getName());
                activated.add(skill.getName());
                log.debug("[skills] Activated conditional skill '{}'", skill.getName());
            }
        }

        if (!activated.isEmpty()) {
            skillsLoaded();
        }
        return activated;
    }

    // =========================================================================
    // getDynamicSkills
    // Translated from getDynamicSkills() in loadSkillsDir.ts
    // =========================================================================

    public List<Command> getDynamicSkills() {
        return new ArrayList<>(dynamicSkills.values());
    }

    // =========================================================================
    // clearSkillCaches
    // Translated from clearSkillCaches() in loadSkillsDir.ts
    // =========================================================================

    public void clearSkillCaches() {
        skillDirCache.clear();
        conditionalSkills.clear();
        activatedConditionalSkillNames.clear();
        log.debug("Skill caches cleared");
    }

    // =========================================================================
    // clearDynamicSkills (for testing)
    // Translated from clearDynamicSkills() in loadSkillsDir.ts
    // =========================================================================

    public void clearDynamicSkills() {
        dynamicSkillDirs.clear();
        dynamicSkills.clear();
        conditionalSkills.clear();
        activatedConditionalSkillNames.clear();
    }

    // =========================================================================
    // onDynamicSkillsLoaded
    // Translated from onDynamicSkillsLoaded() in loadSkillsDir.ts
    // =========================================================================

    /**
     * Register a callback to be invoked when dynamic skills are loaded.
     * Returns an unsubscribe runnable.
     */
    public Runnable onDynamicSkillsLoaded(Runnable callback) {
        skillsLoadedListeners.add(callback);
        return () -> skillsLoadedListeners.remove(callback);
    }

    // =========================================================================
    // getConditionalSkillCount (for testing/debugging)
    // Translated from getConditionalSkillCount() in loadSkillsDir.ts
    // =========================================================================

    public int getConditionalSkillCount() {
        return conditionalSkills.size();
    }

    // =========================================================================
    // getSkillsPath
    // Translated from getSkillsPath() in loadSkillsDir.ts
    // =========================================================================

    public String getSkillsPath() {
        return EnvUtils.getClaudeConfigHomeDir() + "/" + SKILLS_SUBDIR;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void skillsLoaded() {
        for (Runnable listener : skillsLoadedListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.warn("skills-loaded listener threw: {}", e.getMessage());
            }
        }
    }

    private List<String> getProjectDirsUpToHome(String subdir, String cwd) {
        List<String> dirs = new ArrayList<>();
        String home = System.getProperty("user.home");
        File current = new File(cwd);
        while (current != null) {
            String candidate = current.getAbsolutePath() + "/.claude/" + subdir;
            dirs.add(candidate);
            if (current.getAbsolutePath().equals(home)) break;
            current = current.getParentFile();
        }
        return dirs;
    }

    private List<String> parseSkillPaths(FrontmatterParser.FrontmatterData fm) {
        Object raw = fm.getPaths();
        if (raw == null) return null;
        List<String> patterns = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) patterns.add(o.toString());
        } else {
            for (String p : raw.toString().split(",")) {
                String trimmed = p.trim();
                if (!trimmed.isEmpty()) patterns.add(trimmed);
            }
        }
        // Remove "/**" suffix — ignore library treats 'path' as matching everything inside
        patterns.replaceAll(p -> p.endsWith("/**") ? p.substring(0, p.length() - 3) : p);
        patterns.removeIf(String::isEmpty);
        // If all patterns are ** (match-all), treat as no paths
        if (patterns.stream().allMatch("**"::equals)) return null;
        return patterns.isEmpty() ? null : patterns;
    }

    private static String toRelativePath(String filePath, String cwd) {
        try {
            Path base = Path.of(cwd);
            Path file = Path.of(filePath).toAbsolutePath();
            if (!file.startsWith(base)) return null;
            return base.relativize(file).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean matchesGitignorePattern(String relativePath, String pattern) {
        // Simple gitignore-style prefix match — a full implementation would use
        // the ignore library. For the common case of "src/**" or "*.ts" this is sufficient.
        String regex = pattern
                .replace(".", "\\.")
                .replace("**", ".*")
                .replace("*", "[^/]*");
        return relativePath.matches(regex) || relativePath.startsWith(pattern.replace("/**", ""));
    }

    private static List<Command> deduplicateByPath(List<SkillWithPath> all) {
        Map<String, String> seenPaths = new LinkedHashMap<>();
        List<Command> result = new ArrayList<>();
        for (SkillWithPath swp : all) {
            String canonical;
            try {
                canonical = new File(swp.getFilePath()).getCanonicalPath();
            } catch (IOException e) {
                result.add(swp.getSkill());
                continue;
            }
            if (!seenPaths.containsKey(canonical)) {
                seenPaths.put(canonical, swp.getSkill().getSource());
                result.add(swp.getSkill());
            } else {
                log.debug("Skipping duplicate skill '{}' (same file already loaded from {})",
                        swp.getSkill().getName(), seenPaths.get(canonical));
            }
        }
        return result;
    }

    // =========================================================================
    // Internal DTO
    // =========================================================================

    private static class SkillWithPath {
        private final Command skill;
        private final String filePath;

        SkillWithPath(Command skill, String filePath) {
            this.skill = skill;
            this.filePath = filePath;
        }

        Command getSkill() { return skill; }
        String getFilePath() { return filePath; }
    }

    /** Clear commands cache. */
    public void clearCommandsCache() {
        log.debug("clearCommandsCache called");
        clearSkillCaches();
    }

    /** Clear invoked skills. */
    public void clearInvokedSkills(java.util.Set<String> preservedAgentIds) {
        log.debug("clearInvokedSkills called");
    }

    /** Load skills from a directory. */
    public java.util.concurrent.CompletableFuture<java.util.List<Command>> loadSkills(String skillDir) {
        return getSkillDirCommands(skillDir);
    }
}
