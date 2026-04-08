package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Bash command classifier service (stub for external builds).
 * Translated from src/utils/permissions/bashClassifier.ts
 *
 * This is a stub - the actual classifier is ant-only.
 */
@Slf4j
@Service
public class BashClassifierService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BashClassifierService.class);


    public static final String PROMPT_PREFIX = "prompt:";

    public enum ClassifierBehavior {
        DENY, ASK, ALLOW
    }

    public static class ClassifierResult {
        private boolean matches;
        private String matchedDescription;
        private String confidence; // "high" | "medium" | "low"
        private String reason;
        public ClassifierResult() {}
        public ClassifierResult(boolean matches, String matchedDescription, String confidence, String reason) {
            this.matches = matches; this.matchedDescription = matchedDescription;
            this.confidence = confidence; this.reason = reason;
        }
        public boolean isMatches() { return matches; }
        public String getMatchedDescription() { return matchedDescription; }
        public String getConfidence() { return confidence; }
        public String getReason() { return reason; }
    }

    /**
     * Check if classifier permissions are enabled.
     * Translated from isClassifierPermissionsEnabled() in bashClassifier.ts
     */
    public boolean isClassifierPermissionsEnabled() {
        return false; // Ant-only feature
    }

    /**
     * Classify a bash command.
     * Translated from classifyBashCommand() in bashClassifier.ts
     */
    public CompletableFuture<ClassifierResult> classifyBashCommand(
            String command,
            String cwd,
            List<String> descriptions,
            ClassifierBehavior behavior) {

        // Stub: always return "not matches"
        return CompletableFuture.completedFuture(new ClassifierResult(
            false,
            null,
            "high",
            "Classifier feature is disabled"
        ));
    }

    /**
     * Extract prompt description from rule content.
     */
    public String extractPromptDescription(String ruleContent) {
        return null;
    }

    /**
     * Create prompt rule content.
     */
    public String createPromptRuleContent(String description) {
        return PROMPT_PREFIX + " " + description.trim();
    }

    /**
     * Get deny descriptions.
     */
    public List<String> getBashPromptDenyDescriptions(Object context) {
        return List.of();
    }

    /**
     * Get ask descriptions.
     */
    public List<String> getBashPromptAskDescriptions(Object context) {
        return List.of();
    }

    /**
     * Get allow descriptions.
     */
    public List<String> getBashPromptAllowDescriptions(Object context) {
        return List.of();
    }
}
