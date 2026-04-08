package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.ContentBlock;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Shared infrastructure for classifier-based permission systems.
 * Translated from src/utils/permissions/classifierShared.ts
 */
@Slf4j
@Service
public class ClassifierSharedService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ClassifierSharedService.class);


    public enum ClassifierBehavior {
        ALLOW("allow"),
        ASK("ask"),
        DENY("deny");

        private final String value;
        ClassifierBehavior(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ClassifierResult {
        private ClassifierBehavior behavior;
        private String matchedRule;
        private String reason;

        public ClassifierBehavior getBehavior() { return behavior; }
        public void setBehavior(ClassifierBehavior v) { behavior = v; }
        public String getMatchedRule() { return matchedRule; }
        public void setMatchedRule(String v) { matchedRule = v; }
        public String getReason() { return reason; }
        public void setReason(String v) { reason = v; }
    }

    /**
     * Extract a tool use block from content by tool name.
     * Translated from extractToolUseBlock() in classifierShared.ts
     */
    public Optional<ContentBlock.ToolUseBlock> extractToolUseBlock(
            List<ContentBlock> content,
            String toolName) {

        if (content == null) return Optional.empty();
        return content.stream()
            .filter(b -> b instanceof ContentBlock.ToolUseBlock)
            .map(b -> (ContentBlock.ToolUseBlock) b)
            .filter(b -> toolName.equals(b.getName()))
            .findFirst();
    }
}
