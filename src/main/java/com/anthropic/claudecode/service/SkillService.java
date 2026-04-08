package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import lombok.Data;

/**
 * Skill management and usage tracking service.
 * Translated from:
 *   - src/skills/loadSkillsDir.ts (skill loading)
 *   - src/utils/suggestions/skillUsageTracking.ts (usage scoring)
 *
 * Manages user-defined skills (slash commands) loaded from the skills directory.
 * Tracks usage frequency and recency to power suggestion ranking.
 */
@Slf4j
@Service
public class SkillService {



    private static final String SKILLS_DIR = "skills";

    /**
     * Debounce interval for persisting skill usage.
     * The ranking algorithm uses a 7-day half-life, so sub-minute granularity
     * is irrelevant — bail out before file I/O within this window.
     * Translated from SKILL_USAGE_DEBOUNCE_MS in skillUsageTracking.ts
     */
    private static final long SKILL_USAGE_DEBOUNCE_MS = 60_000L;

    // =========================================================================
    // Skill data model
    // =========================================================================

    public static class Skill {
        private String name;
        private String description;
        private String prompt;
        private String filePath;

        public Skill() {}
        public Skill(String name, String description, String prompt, String filePath) {
            this.name = name; this.description = description; this.prompt = prompt; this.filePath = filePath;
        }
        public String getName() { return name; }
        public void setName(String v) { name = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String v) { prompt = v; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String v) { filePath = v; }
    }

    /**
     * Persisted usage record for a single skill.
     * Translated from the skillUsage record shape in skillUsageTracking.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class SkillUsageRecord {
        private int usageCount;
        private long lastUsedAt;

        public int getUsageCount() { return usageCount; }
        public void setUsageCount(int v) { usageCount = v; }
        public long getLastUsedAt() { return lastUsedAt; }
        public void setLastUsedAt(long v) { lastUsedAt = v; }
    
    }

    // =========================================================================
    // State
    // =========================================================================

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    /**
     * Process-lifetime debounce cache.
     * Mirrors lastWriteBySkill in skillUsageTracking.ts —
     * avoids lock + read + parse on debounced calls.
     */
    private final Map<String, Long> lastWriteBySkill = new HashMap<>();

    /**
     * In-memory skill usage store (backed by global config in the TS codebase).
     * Keyed by skill name.
     */
    private final Map<String, SkillUsageRecord> skillUsage = new HashMap<>();

    // =========================================================================
    // Skill loading (loadSkillsDir.ts)
    // =========================================================================

    /**
     * Get the skills directory path.
     */
    public String getSkillsPath() {
        return EnvUtils.getClaudeConfigHomeDir() + "/" + SKILLS_DIR;
    }

    /**
     * Load skills from the skills directory.
     * Translated from loadSkillsDir() in loadSkillsDir.ts
     */
    public Map<String, Skill> loadSkills() {
        skills.clear();
        String skillsPath = getSkillsPath();
        File dir = new File(skillsPath);

        if (!dir.exists()) {
            log.debug("Skills directory does not exist: {}", skillsPath);
            return skills;
        }

        File[] files = dir.listFiles((d, name) ->
            name.endsWith(".md") || name.endsWith(".txt"));

        if (files == null) return skills;

        for (File file : files) {
            try {
                String content = Files.readString(file.toPath());
                String name = file.getName().replaceAll("\\.(md|txt)$", "");

                // Extract description from first line (if starts with #)
                String[] lines = content.split("\n", 2);
                String description = lines.length > 0 && lines[0].startsWith("#")
                    ? lines[0].substring(1).trim()
                    : name;

                Skill skill = new Skill(name, description, content, file.getAbsolutePath());
                skills.put(name, skill);
                log.debug("Loaded skill: {}", name);

            } catch (Exception e) {
                log.warn("Could not load skill {}: {}", file.getName(), e.getMessage());
            }
        }

        return skills;
    }

    /**
     * Get a skill by name.
     */
    public Optional<Skill> getSkill(String name) {
        if (skills.isEmpty()) loadSkills();
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * List all available skills.
     */
    public List<Skill> listSkills() {
        if (skills.isEmpty()) loadSkills();
        return new ArrayList<>(skills.values());
    }

    /**
     * Check if a skill exists.
     */
    public boolean hasSkill(String name) {
        return getSkill(name).isPresent();
    }

    // =========================================================================
    // Skill usage tracking (skillUsageTracking.ts)
    // =========================================================================

    /**
     * Records a skill usage for ranking purposes.
     * Updates both usage count and last used timestamp.
     *
     * Translated from recordSkillUsage() in skillUsageTracking.ts
     *
     * The ranking algorithm uses a 7-day half-life, so sub-minute granularity
     * is irrelevant. Returns early before file I/O if the debounce window
     * has not elapsed.
     */
    public synchronized void recordSkillUsage(String skillName) {
        long now = System.currentTimeMillis();
        Long lastWrite = lastWriteBySkill.get(skillName);
        if (lastWrite != null && (now - lastWrite) < SKILL_USAGE_DEBOUNCE_MS) {
            return;
        }
        lastWriteBySkill.put(skillName, now);

        SkillUsageRecord existing = skillUsage.get(skillName);
        skillUsage.put(skillName, new SkillUsageRecord(
                (existing != null ? existing.getUsageCount() : 0) + 1,
                now
        ));
    }

    /**
     * Calculates a usage score for a skill based on frequency and recency.
     * Higher scores indicate more frequently and recently used skills.
     *
     * The score uses exponential decay with a half-life of 7 days,
     * meaning usage from 7 days ago is worth half as much as usage today.
     *
     * Minimum recency factor of 0.1 to avoid completely dropping old but
     * heavily used skills.
     *
     * Translated from getSkillUsageScore() in skillUsageTracking.ts
     */
    public double getSkillUsageScore(String skillName) {
        SkillUsageRecord usage = skillUsage.get(skillName);
        if (usage == null) return 0.0;

        // Recency decay: halve score every 7 days
        double daysSinceUse = (System.currentTimeMillis() - usage.getLastUsedAt())
                / (1000.0 * 60 * 60 * 24);
        double recencyFactor = Math.pow(0.5, daysSinceUse / 7.0);

        return usage.getUsageCount() * Math.max(recencyFactor, 0.1);
    }

    /**
     * Get usage record for a skill (for persistence / display).
     */
    public Optional<SkillUsageRecord> getUsageRecord(String skillName) {
        return Optional.ofNullable(skillUsage.get(skillName));
    }

    /**
     * Load usage records from an external source (e.g., global config on startup).
     * Replaces the current in-memory state.
     */
    public synchronized void loadUsageRecords(Map<String, SkillUsageRecord> records) {
        skillUsage.clear();
        if (records != null) {
            skillUsage.putAll(records);
        }
    }

    /**
     * Return a snapshot of all usage records for persistence.
     */
    public Map<String, SkillUsageRecord> getAllUsageRecords() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(skillUsage));
    }
}
