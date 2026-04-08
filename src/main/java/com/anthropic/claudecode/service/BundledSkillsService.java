package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.Command;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiFunction;

/**
 * Bundled skills service.
 * Translated from src/skills/bundledSkills.ts
 *
 * Manages built-in skills that ship with the CLI binary.
 * Bundled skills are registered programmatically at startup.
 */
@Slf4j
@Service
public class BundledSkillsService {



    /** In-process registry — analogous to the module-level `bundledSkills` array in TS. */
    private final List<Command> bundledSkills = new CopyOnWriteArrayList<>();

    // =========================================================================
    // BundledSkillDefinition
    // Translated from BundledSkillDefinition type in bundledSkills.ts
    // =========================================================================

    @Data
    @lombok.Builder
    
    public static class BundledSkillDefinition {
        private String name;
        private String description;
        private List<String> aliases;
        private String whenToUse;
        private String argumentHint;
        private List<String> allowedTools;
        private String model;
        private Boolean disableModelInvocation;
        private Boolean userInvocable;
        /** "inline" | "fork" */
        private String context;
        private String agent;
        /**
         * Additional reference files to extract to disk on first invocation.
         * Keys are relative paths, values are content strings.
         * Translated from files?: Record<string, string> in BundledSkillDefinition.
         */
        private Map<String, String> files;
        /**
         * Prompt provider — receives (args, toolUseContext) and returns prompt content.
         * Translated from getPromptForCommand in BundledSkillDefinition.
         */
        private transient BiFunction<String, Object, CompletableFuture<List<Object>>> getPromptForCommand;
        private transient java.util.function.BooleanSupplier isEnabled;
        private transient Object hooks; // HooksSettings
    
        public List<String> getAliases() { return aliases; }
    
        public String getDescription() { return description; }
    
        public Map<String, String> getFiles() { return files; }
    
        public BiFunction<String, Object, CompletableFuture<List<Object>>> getGetPromptForCommand() { return getPromptForCommand; }
    
        public String getName() { return name; }
    
        public Boolean getUserInvocable() { return userInvocable; }
    
        public String getAgent() { return agent; }
    
        public List<String> getAllowedTools() { return allowedTools; }
    
        public String getArgumentHint() { return argumentHint; }
    
        public String getContext() { return context; }
    
        public Boolean getDisableModelInvocation() { return disableModelInvocation; }
    
        public String getModel() { return model; }
    
        public String getWhenToUse() { return whenToUse; }
    }

    // =========================================================================
    // registerBundledSkill
    // Translated from registerBundledSkill() in bundledSkills.ts
    // =========================================================================

    /**
     * Register a bundled skill that will be available to the model.
     * Translated from registerBundledSkill() in bundledSkills.ts
     *
     * If the definition includes reference files, they are extracted lazily
     * (once per process) on the first invocation, and a base-directory prefix
     * is prepended to the returned prompt blocks.
     */
    public void registerBundledSkill(BundledSkillDefinition definition) {
        Map<String, String> files = definition.getFiles();
        String skillRoot = null;
        BiFunction<String, Object, CompletableFuture<List<Object>>> promptFn =
                definition.getGetPromptForCommand();

        if (files != null && !files.isEmpty()) {
            skillRoot = getBundledSkillExtractDir(definition.getName());
            // Closure-local memoization: extract once per process.
            final String finalSkillRoot = skillRoot;
            final CompletableFuture<String>[] extractionHolder = new CompletableFuture[1];
            final BiFunction<String, Object, CompletableFuture<List<Object>>> inner = promptFn;

            promptFn = (args, ctx) -> {
                if (extractionHolder[0] == null) {
                    extractionHolder[0] = extractBundledSkillFiles(definition.getName(), files);
                }
                return extractionHolder[0].thenCompose(extractedDir ->
                    inner.apply(args, ctx).thenApply(blocks -> {
                        if (extractedDir == null) return blocks;
                        return prependBaseDir(blocks, extractedDir);
                    })
                );
            };
        }

        boolean userInvocable = definition.getUserInvocable() != null
                ? definition.getUserInvocable() : true;

        Command command = Command.builder()
                .name(definition.getName())
                .description(definition.getDescription())
                .type(Command.CommandType.PROMPT)
                .source("bundled")
                .loadedFrom("bundled")
                .aliases(definition.getAliases())
                .allowedTools(definition.getAllowedTools() != null
                        ? definition.getAllowedTools() : Collections.emptyList())
                .argumentHint(definition.getArgumentHint())
                .whenToUse(definition.getWhenToUse())
                .model(definition.getModel())
                .disableModelInvocation(definition.getDisableModelInvocation() != null
                        ? definition.getDisableModelInvocation() : false)
                .userInvocable(userInvocable)
                .skillRoot(skillRoot)
                .context(definition.getContext())
                .agent(definition.getAgent())
                .build();

        bundledSkills.add(command);
        log.debug("Registered bundled skill: {}", definition.getName());
    }

    // =========================================================================
    // getBundledSkills
    // Translated from getBundledSkills() in bundledSkills.ts
    // =========================================================================

    /**
     * Get all registered bundled skills.
     * Returns a copy to prevent external mutation.
     * Translated from getBundledSkills() in bundledSkills.ts
     */
    public List<Command> getBundledSkills() {
        return Collections.unmodifiableList(new ArrayList<>(bundledSkills));
    }

    // =========================================================================
    // clearBundledSkills
    // Translated from clearBundledSkills() in bundledSkills.ts
    // =========================================================================

    /**
     * Clear bundled skills registry (for testing).
     * Translated from clearBundledSkills() in bundledSkills.ts
     */
    public void clearBundledSkills() {
        bundledSkills.clear();
    }

    // =========================================================================
    // getBundledSkillExtractDir
    // Translated from getBundledSkillExtractDir() in bundledSkills.ts
    // =========================================================================

    /**
     * Deterministic extraction directory for a bundled skill's reference files.
     * Translated from getBundledSkillExtractDir() in bundledSkills.ts
     */
    public String getBundledSkillExtractDir(String skillName) {
        return getBundledSkillsRoot() + File.separator + skillName;
    }

    /**
     * Root directory for bundled skill extraction (analogous to getBundledSkillsRoot() in TS).
     * Uses a per-process nonce to resist pre-created symlinks/directories.
     */
    private static final String BUNDLED_SKILLS_ROOT_NONCE =
            Long.toHexString(ProcessHandle.current().pid())
                    + "_" + Long.toHexString(System.nanoTime());

    public String getBundledSkillsRoot() {
        String tmp = System.getProperty("java.io.tmpdir");
        return tmp + File.separator + ".claude-bundled-skills-" + BUNDLED_SKILLS_ROOT_NONCE;
    }

    // =========================================================================
    // extractBundledSkillFiles (private)
    // Translated from extractBundledSkillFiles() in bundledSkills.ts
    // =========================================================================

    /**
     * Extract a bundled skill's reference files to disk so the model can
     * Read/Grep them on demand. Called lazily on first skill invocation.
     *
     * Returns the directory written to, or null if the write failed
     * (skill continues to work without the base-directory prefix).
     */
    private CompletableFuture<String> extractBundledSkillFiles(
            String skillName, Map<String, String> files) {
        return CompletableFuture.supplyAsync(() -> {
            String dir = getBundledSkillExtractDir(skillName);
            try {
                writeSkillFiles(dir, files);
                return dir;
            } catch (Exception e) {
                log.debug("Failed to extract bundled skill '{}' to {}: {}",
                        skillName, dir, e.getMessage());
                return null;
            }
        });
    }

    // =========================================================================
    // writeSkillFiles (private)
    // Translated from writeSkillFiles() in bundledSkills.ts
    // =========================================================================

    private void writeSkillFiles(String baseDir, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String relPath = entry.getKey();
            String content = entry.getValue();

            Path target = resolveSkillFilePath(baseDir, relPath);
            Files.createDirectories(target.getParent(),
                    java.nio.file.attribute.PosixFilePermissions
                            .asFileAttribute(Set.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE,
                                    PosixFilePermission.OWNER_EXECUTE)));
            // Write with exclusive create (O_CREAT | O_EXCL) — analogous to SAFE_WRITE_FLAGS in TS
            try {
                Files.writeString(target, content,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                // Restrict to owner-only (0o600)
                try {
                    Files.setPosixFilePermissions(target, Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE));
                } catch (UnsupportedOperationException ignored) {
                    // Non-POSIX filesystem (Windows) — skip permission setting
                }
            } catch (FileAlreadyExistsException ignored) {
                // Idempotent: file already extracted, no need to overwrite
            }
        }
    }

    // =========================================================================
    // resolveSkillFilePath (private)
    // Translated from resolveSkillFilePath() in bundledSkills.ts
    // =========================================================================

    /** Normalize and validate a skill-relative path; throws on traversal. */
    private static Path resolveSkillFilePath(String baseDir, String relPath) throws IOException {
        Path base = Path.of(baseDir).normalize();
        Path resolved = base.resolve(relPath).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("bundled skill file path escapes skill dir: " + relPath);
        }
        return resolved;
    }

    // =========================================================================
    // prependBaseDir (private)
    // Translated from prependBaseDir() in bundledSkills.ts
    // =========================================================================

    /**
     * Prepend a "Base directory for this skill: <dir>\n\n" prefix to the first
     * text block in the prompt blocks list.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> prependBaseDir(List<Object> blocks, String baseDir) {
        String prefix = "Base directory for this skill: " + baseDir + "\n\n";
        List<Object> result = new ArrayList<>();
        if (!blocks.isEmpty() && blocks.get(0) instanceof Map<?, ?> first
                && "text".equals(first.get("type"))) {
            Map<String, Object> updated = new LinkedHashMap<>((Map<String, Object>) first);
            updated.put("text", prefix + updated.get("text"));
            result.add(updated);
            result.addAll(blocks.subList(1, blocks.size()));
        } else {
            Map<String, Object> prefixBlock = new LinkedHashMap<>();
            prefixBlock.put("type", "text");
            prefixBlock.put("text", prefix);
            result.add(prefixBlock);
            result.addAll(blocks);
        }
        return result;
    }

    // =========================================================================
    // Convenience accessor (unchanged from previous version)
    // =========================================================================

    /** Get available user-invocable skills as simple name/description pairs. */
    public List<SkillInfo> getAvailableSkills() {
        List<SkillInfo> skills = new ArrayList<>();
        for (Command skill : bundledSkills) {
            if (Boolean.TRUE.equals(skill.getUserInvocable())) {
                skills.add(new SkillInfo(
                        skill.getName(),
                        skill.getDescription() != null ? skill.getDescription() : ""));
            }
        }
        return skills;
    }

    @Data
    public static class SkillInfo {
        private String name;
        private String description;
        private String triggerPattern;
        private String usageHint;

        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getTriggerPattern() { return triggerPattern; }
        public void setTriggerPattern(String v) { triggerPattern = v; }
        public String getUsageHint() { return usageHint; }
        public void setUsageHint(String v) { usageHint = v; }

        public SkillInfo() {}
        public SkillInfo(String name, String description) {
            this.name = name;
            this.description = description;
        }
    }
}
