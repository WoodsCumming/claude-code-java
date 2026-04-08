package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.PlanModeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

/**
 * Plan command for enabling plan mode or viewing/editing the current session plan.
 *
 * Translated from src/commands/plan/index.ts and src/commands/plan/plan.tsx
 *
 * TypeScript original behaviour:
 *   - If NOT already in plan mode:
 *       - Enables plan mode (sets toolPermissionContext.mode = 'plan')
 *       - If a description argument was provided (not "open"), triggers a query with
 *         shouldQuery:true so Claude creates a plan for the given description
 *       - Otherwise just acknowledges "Enabled plan mode"
 *   - If ALREADY in plan mode:
 *       - No plan content yet  → "Already in plan mode. No plan written yet."
 *       - "/plan open"         → open the plan file in $EDITOR / $VISUAL
 *       - Otherwise            → display the current plan content + file path
 *                                and hint how to open it in the editor
 *
 * Java translation:
 *   - No arg or "open": enable plan mode (or open plan file if already in plan mode)
 *   - <description>   : enable plan mode and record the description
 */
@Slf4j
@Component
@Command(
    name = "plan",
    description = "Enable plan mode or view the current session plan",
    mixinStandardHelpOptions = true
)
public class PlanCommand implements Callable<Integer> {



    /**
     * Argument hint: [open|&lt;description&gt;]
     *   open        → enable plan mode, or if already enabled open the plan file in editor
     *   description → enable plan mode with a given task description
     */
    @Parameters(index = "0", description = "open|<description>", arity = "0..1")
    private String arg;

    private final PlanModeService planModeService;

    @Autowired
    public PlanCommand(PlanModeService planModeService) {
        this.planModeService = planModeService;
    }

    @Override
    public Integer call() {
        boolean inPlanMode = planModeService.isInPlanMode();

        if (!inPlanMode) {
            // Enable plan mode
            planModeService.enablePlanMode();

            if (arg != null && !arg.isBlank() && !"open".equals(arg.trim())) {
                // Description provided → Claude should create a plan for this task
                planModeService.setPlanDescription(arg.trim());
                System.out.println("Enabled plan mode");
                // In the full implementation a query would be triggered here so Claude
                // can draft the plan immediately (shouldQuery: true in TypeScript).
            } else {
                System.out.println("Enabled plan mode");
            }
            return 0;
        }

        // Already in plan mode
        String planContent = planModeService.getCurrentPlan();
        String planPath = planModeService.getPlanFilePath();

        if (planContent == null || planContent.isBlank()) {
            System.out.println("Already in plan mode. No plan written yet.");
            return 0;
        }

        // "/plan open" → launch the editor
        if (arg != null && "open".equals(arg.trim())) {
            return openPlanInEditor(planPath);
        }

        // Show the current plan
        System.out.println("Current Plan");
        System.out.println(planPath);
        System.out.println();
        System.out.println(planContent);
        String editorName = planModeService.getConfiguredEditorName();
        if (editorName != null && !editorName.isBlank()) {
            System.out.printf("%n\"/plan open\" to edit this plan in %s%n", editorName);
        }
        return 0;
    }

    private int openPlanInEditor(String planPath) {
        String editor = System.getenv("VISUAL");
        if (editor == null || editor.isBlank()) {
            editor = System.getenv("EDITOR");
        }
        if (editor == null || editor.isBlank()) {
            editor = "vi";
        }
        try {
            Process process = new ProcessBuilder(editor, planPath)
                .inheritIO()
                .start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("Opened plan in editor: " + planPath);
            } else {
                System.out.println("Failed to open plan in editor (exit code " + exitCode + ")");
            }
            return exitCode;
        } catch (Exception e) {
            log.error("Failed to open plan file in editor", e);
            System.out.println("Failed to open plan in editor: " + e.getMessage());
            return 1;
        }
    }
}
