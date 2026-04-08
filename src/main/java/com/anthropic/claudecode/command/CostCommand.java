package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.CostTrackerService;
import com.anthropic.claudecode.service.AuthService;
import com.anthropic.claudecode.service.ClaudeAiLimitsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Cost command for showing session cost information.
 * Translated from src/commands/cost/cost.ts
 */
@Slf4j
@Component
@Command(
    name = "cost",
    description = "Show the total cost of this session"
)
public class CostCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CostCommand.class);


    private final CostTrackerService costTrackerService;
    private final AuthService authService;
    private final ClaudeAiLimitsService claudeAiLimitsService;

    @Autowired
    public CostCommand(CostTrackerService costTrackerService,
                       AuthService authService,
                       ClaudeAiLimitsService claudeAiLimitsService) {
        this.costTrackerService = costTrackerService;
        this.authService = authService;
        this.claudeAiLimitsService = claudeAiLimitsService;
    }

    @Override
    public Integer call() {
        if (authService.isClaudeAiSubscriber()) {
            String value;
            if (claudeAiLimitsService.isUsingOverage()) {
                value = "You are currently using your overages to power your Claude Code usage. " +
                    "We will automatically switch you back to your subscription rate limits when they reset";
            } else {
                value = "You are currently using your subscription to power your Claude Code usage";
            }

            String userType = System.getenv("USER_TYPE");
            if ("ant".equals(userType)) {
                value += "\n\n[ANT-ONLY] Showing cost anyway:\n " + costTrackerService.formatTotalCost();
            }
            System.out.println(value);
        } else {
            System.out.println(costTrackerService.formatTotalCost());
        }
        return 0;
    }
}
