package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.util.ModelUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import lombok.Data;

/**
 * Permission explainer service.
 * Translated from src/utils/permissions/permissionExplainer.ts
 *
 * Generates AI-powered explanations for permission decisions.
 */
@Slf4j
@Service
public class PermissionExplainerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PermissionExplainerService.class);


    public enum RiskLevel {
        LOW(1), MEDIUM(2), HIGH(3);

        private final int numeric;
        RiskLevel(int numeric) { this.numeric = numeric; }
        public int getNumeric() { return numeric; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PermissionExplanation {
        private RiskLevel riskLevel;
        private String explanation;
        private String reasoning;
        private String risk;

        public RiskLevel getRiskLevel() { return riskLevel; }
        public void setRiskLevel(RiskLevel v) { riskLevel = v; }
        public String getExplanation() { return explanation; }
        public void setExplanation(String v) { explanation = v; }
        public String getReasoning() { return reasoning; }
        public void setReasoning(String v) { reasoning = v; }
        public String getRisk() { return risk; }
        public void setRisk(String v) { risk = v; }
    
    }

    private final AnthropicClient anthropicClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public PermissionExplainerService(AnthropicClient anthropicClient, ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Generate a permission explanation.
     * Translated from generatePermissionExplanation() in permissionExplainer.ts
     */
    public CompletableFuture<Optional<PermissionExplanation>> generatePermissionExplanation(
            String toolName,
            Object toolInput,
            String toolDescription) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                String prompt = buildExplanationPrompt(toolName, toolInput, toolDescription);

                List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", prompt)
                );

                AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
                    .model(ModelUtils.getSmallFastModel())
                    .maxTokens(512)
                    .messages(messages)
                    .build();

                AnthropicClient.MessageResponse response = anthropicClient
                    .createMessage(request)
                    .get();

                String text = extractText(response);
                return parseExplanation(text);

            } catch (Exception e) {
                log.debug("Permission explanation failed: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }

    private String buildExplanationPrompt(String toolName, Object toolInput, String toolDescription) {
        return String.format(
            "Analyze this tool call for security risk:\n\nTool: %s\nInput: %s\nDescription: %s\n\n" +
            "Respond with JSON: {\"riskLevel\": \"LOW|MEDIUM|HIGH\", \"explanation\": \"...\", " +
            "\"reasoning\": \"...\", \"risk\": \"...\"}",
            toolName,
            toolInput != null ? toolInput.toString() : "{}",
            toolDescription != null ? toolDescription : ""
        );
    }

    private Optional<PermissionExplanation> parseExplanation(String text) {
        if (text == null || text.isEmpty()) return Optional.empty();

        try {
            // Extract JSON from response
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end < 0) return Optional.empty();

            String json = text.substring(start, end + 1);
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

            String riskLevelStr = (String) parsed.get("riskLevel");
            RiskLevel riskLevel = riskLevelStr != null
                ? RiskLevel.valueOf(riskLevelStr.toUpperCase())
                : RiskLevel.MEDIUM;

            return Optional.of(new PermissionExplanation(
                riskLevel,
                (String) parsed.getOrDefault("explanation", ""),
                (String) parsed.getOrDefault("reasoning", ""),
                (String) parsed.getOrDefault("risk", "")
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String extractText(AnthropicClient.MessageResponse response) {
        if (response.getContent() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : response.getContent()) {
            if ("text".equals(block.get("type"))) {
                sb.append(block.get("text"));
            }
        }
        return sb.toString();
    }
}
