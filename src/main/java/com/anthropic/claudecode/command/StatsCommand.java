package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.CostTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import java.util.concurrent.Callable;

/**
 * Stats command for showing usage statistics.
 * Translated from src/commands/stats/
 */
@Slf4j
@Component
@Command(
    name = "stats",
    description = "Show your Claude Code usage statistics and activity"
)
public class StatsCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StatsCommand.class);


    private final CostTracker costTracker;

    @Autowired
    public StatsCommand(CostTracker costTracker) {
        this.costTracker = costTracker;
    }

    @Override
    public Integer call() {
        CostTracker.SessionStats stats = costTracker.getStats();
        System.out.println("Claude Code Usage Statistics");
        System.out.println("============================");
        System.out.println(stats.formatSummary());
        return 0;
    }
}
