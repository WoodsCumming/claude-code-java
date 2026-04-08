package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.FeedbackService;
import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Feedback command for submitting feedback about Claude Code.
 * Translated from src/commands/feedback/index.ts
 */
@Slf4j
@Component
@Command(
    name = "feedback",
    aliases = {"bug"},
    description = "Submit feedback about Claude Code"
)
public class FeedbackCommand implements Callable<Integer> {



    @Parameters(index = "0", description = "Feedback report", arity = "0..1")
    private String report;

    private final FeedbackService feedbackService;

    @Autowired
    public FeedbackCommand(FeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @Override
    public Integer call() {
        // Check if feedback is disabled
        if (EnvUtils.isEnvTruthy("CLAUDE_CODE_USE_BEDROCK")
                || EnvUtils.isEnvTruthy("CLAUDE_CODE_USE_VERTEX")
                || EnvUtils.isEnvTruthy("CLAUDE_CODE_USE_FOUNDRY")
                || EnvUtils.isEnvTruthy("DISABLE_FEEDBACK_COMMAND")
                || EnvUtils.isEnvTruthy("DISABLE_BUG_COMMAND")) {
            System.out.println("Feedback command is not available in this environment.");
            return 1;
        }

        if (report == null || report.isBlank()) {
            System.out.println("Please provide feedback. Usage: /feedback <your feedback>");
            System.out.println("Or visit: https://github.com/anthropics/claude-code/issues");
            return 0;
        }

        try {
            feedbackService.submitFeedback(report);
            System.out.println("Thank you for your feedback!");
            return 0;
        } catch (Exception e) {
            log.error("Failed to submit feedback", e);
            System.out.println("Failed to submit feedback. Please visit: https://github.com/anthropics/claude-code/issues");
            return 1;
        }
    }
}
