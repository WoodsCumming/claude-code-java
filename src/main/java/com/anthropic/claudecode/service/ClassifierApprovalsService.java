package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Data;

/**
 * Classifier approvals tracking service.
 * Translated from src/utils/classifierApprovals.ts
 *
 * Tracks which tool uses were auto-approved by classifiers.
 */
@Slf4j
@Service
public class ClassifierApprovalsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClassifierApprovalsService.class);


    private final Map<String, ClassifierApproval> approvals = new ConcurrentHashMap<>();
    private final Set<String> checking = ConcurrentHashMap.newKeySet();

    public enum ClassifierType {
        BASH("bash"),
        AUTO_MODE("auto-mode");

        private final String value;
        ClassifierType(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    public static class ClassifierApproval {
        private ClassifierType classifier;
        private String matchedRule;
        private String reason;

        public ClassifierApproval() {}
        public ClassifierApproval(ClassifierType classifier, String matchedRule, String reason) {
            this.classifier = classifier; this.matchedRule = matchedRule; this.reason = reason;
        }
        public ClassifierType getClassifier() { return classifier; }
        public void setClassifier(ClassifierType v) { classifier = v; }
        public String getMatchedRule() { return matchedRule; }
        public void setMatchedRule(String v) { matchedRule = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { reason = v; }
    }

    /**
     * Set a bash classifier approval.
     * Translated from setClassifierApproval() in classifierApprovals.ts
     */
    public void setClassifierApproval(String toolUseId, String matchedRule) {
        approvals.put(toolUseId, new ClassifierApproval(ClassifierType.BASH, matchedRule, null));
    }

    /**
     * Get a bash classifier approval.
     * Translated from getClassifierApproval() in classifierApprovals.ts
     */
    public Optional<String> getClassifierApproval(String toolUseId) {
        ClassifierApproval approval = approvals.get(toolUseId);
        if (approval == null || approval.getClassifier() != ClassifierType.BASH) {
            return Optional.empty();
        }
        return Optional.ofNullable(approval.getMatchedRule());
    }

    /**
     * Set an auto-mode (YOLO) classifier approval.
     * Translated from setYoloClassifierApproval() in classifierApprovals.ts
     */
    public void setYoloClassifierApproval(String toolUseId, String reason) {
        approvals.put(toolUseId, new ClassifierApproval(ClassifierType.AUTO_MODE, null, reason));
    }

    /**
     * Get an auto-mode classifier approval.
     * Translated from getYoloClassifierApproval() in classifierApprovals.ts
     */
    public Optional<String> getYoloClassifierApproval(String toolUseId) {
        ClassifierApproval approval = approvals.get(toolUseId);
        if (approval == null || approval.getClassifier() != ClassifierType.AUTO_MODE) {
            return Optional.empty();
        }
        return Optional.ofNullable(approval.getReason());
    }

    /**
     * Mark a tool use as being checked by a classifier.
     */
    public void setClassifierChecking(String toolUseId) {
        checking.add(toolUseId);
    }

    /**
     * Check if a tool use is being checked by a classifier.
     */
    public boolean isClassifierChecking(String toolUseId) {
        return checking.contains(toolUseId);
    }

    /**
     * Clear classifier checking state.
     */
    public void clearClassifierChecking(String toolUseId) {
        checking.remove(toolUseId);
    }

    /**
     * Delete a specific classifier approval by tool use ID.
     * Translated from deleteClassifierApproval() in classifierApprovals.ts
     */
    public void deleteClassifierApproval(String toolUseId) {
        approvals.remove(toolUseId);
    }

    /**
     * Clear all classifier approvals and checking state (for session reset).
     * Translated from clearClassifierApprovals() in classifierApprovals.ts
     */
    public void clearClassifierApprovals() {
        approvals.clear();
        checking.clear();
    }

    /**
     * Clear all approvals (alias for clearClassifierApprovals).
     */
    public void clear() {
        clearClassifierApprovals();
    }
}
