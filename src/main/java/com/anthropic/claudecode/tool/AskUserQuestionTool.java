package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import lombok.Data;

/**
 * Tool for asking the user questions with multiple choice options.
 * Translated from src/tools/AskUserQuestionTool/AskUserQuestionTool.tsx
 *
 * Presents questions to the user with structured options and collects their answers.
 */
@Slf4j
@Component
public class AskUserQuestionTool extends AbstractTool<AskUserQuestionTool.Input, AskUserQuestionTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AskUserQuestionTool.class);


    public static final String TOOL_NAME = "AskUserQuestion";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "questions", Map.of(
                    "type", "array",
                    "description", "Questions to ask the user (1-4 questions)",
                    "items", Map.of(
                        "type", "object",
                        "properties", Map.of(
                            "question", Map.of("type", "string"),
                            "header", Map.of("type", "string"),
                            "options", Map.of(
                                "type", "array",
                                "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                        "label", Map.of("type", "string"),
                                        "description", Map.of("type", "string")
                                    )
                                )
                            ),
                            "multiSelect", Map.of("type", "boolean")
                        )
                    )
                )
            ),
            "required", List.of("questions")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        // In non-interactive mode, return empty answers
        if (context.getOptions() != null && context.getOptions().isNonInteractiveSession()) {
            return futureResult(Output.builder()
                .answers(Map.of())
                .build());
        }

        // In interactive mode, would show UI and wait for user input
        // For now, return empty (actual implementation would block on user input)
        return futureResult(Output.builder()
            .answers(Map.of())
            .build());
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        return CompletableFuture.completedFuture("Asking user question(s)");
    }

    public boolean requiresUserInteraction() { return true; }

    @Override
    public boolean isReadOnly(Input input) { return true; }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        return Map.of(
            "type", "tool_result",
            "tool_use_id", toolUseId,
            "content", content.getAnswers() != null ? content.getAnswers().toString() : "{}"
        );
    }

    // =========================================================================
    // Input/Output types
    // =========================================================================

    @Data
    @lombok.Builder
    
    public static class Input {
        private List<Question> questions;
        private Map<String, Object> annotations;
        private Map<String, String> answers;
    }

    @Data
    @lombok.Builder
    
    public static class Output {
        private Map<String, String> answers;
    
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private Map<String, String> answers;
            public OutputBuilder answers(Map<String, String> v) { this.answers = v; return this; }
            public Output build() {
                Output o = new Output();
                o.answers = answers;
                return o;
            }
        }
    
        public Map<String, String> getAnswers() { return answers; }
    

        public Output() {}
        public Output(Map<String, String> answers) {
            this.answers = answers;
        }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Question {
        private String question;
        private String header;
        private List<QuestionOption> options;
        private boolean multiSelect;

        public String getQuestion() { return question; }
        public void setQuestion(String v) { question = v; }
        public String getHeader() { return header; }
        public void setHeader(String v) { header = v; }
        public List<QuestionOption> getOptions() { return options; }
        public void setOptions(List<QuestionOption> v) { options = v; }
        public boolean isMultiSelect() { return multiSelect; }
        public void setMultiSelect(boolean v) { multiSelect = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class QuestionOption {
        private String label;
        private String description;
        private String preview;

        public String getLabel() { return label; }
        public void setLabel(String v) { label = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { description = v; }
        public String getPreview() { return preview; }
        public void setPreview(String v) { preview = v; }
    }
}
