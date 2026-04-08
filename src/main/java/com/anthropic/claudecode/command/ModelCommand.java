package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.ModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Model command for viewing or setting the AI model used by Claude Code.
 *
 * Translated from src/commands/model/index.ts and src/commands/model/model.tsx
 *
 * TypeScript original behaviour:
 *   - No args / info args ("?", "--info"): show current model + effort level
 *   - With model name: validates the model (allowlist check, 1M-context access check,
 *     alias resolution), then sets it; also adjusts fast-mode if the new model
 *     does not support it
 *   - "default" as model name: resets to the default model
 *   - The command is "immediate" when shouldInferenceConfigCommandBeImmediate() is true
 *     (i.e. the inference config flag is enabled)
 *
 * Java translation maps:
 *   - No args           → show current model (+ session override if present)
 *   - "default"         → reset to default model
 *   - Known alias/name  → validate & set via ModelService
 *   - --list            → list all available models
 */
@Slf4j
@Component
@Command(
    name = "model",
    description = "Set the AI model for Claude Code",
    mixinStandardHelpOptions = true
)
public class ModelCommand implements Callable<Integer> {



    /** Model name to set, or "default" to reset, or omit to show current. */
    @Parameters(index = "0", description = "Model name to use (use 'default' to reset)", arity = "0..1")
    private String modelName;

    /** List all available models with availability markers. */
    @Option(names = {"--list", "-l"}, description = "List all available models")
    private boolean listModels;

    private final ModelService modelService;

    @Autowired
    public ModelCommand(ModelService modelService) {
        this.modelService = modelService;
    }

    @Override
    public Integer call() {
        // --list: enumerate available models
        if (listModels) {
            return listAvailableModels();
        }

        // No argument: show current model info
        if (modelName == null || modelName.isBlank()) {
            return showCurrentModel();
        }

        // "default": reset to the default model
        if ("default".equalsIgnoreCase(modelName.trim())) {
            return resetToDefault();
        }

        // Named model: validate and set
        return setModel(modelName.trim());
    }

    private int showCurrentModel() {
        String current = modelService.getMainLoopModel();
        String sessionOverride = modelService.getSessionModel();
        String effortLevel = modelService.getEffortLevel();

        String displayModel = renderModelLabel(current);
        String effortInfo = (effortLevel != null && !effortLevel.isBlank())
            ? " (effort: " + effortLevel + ")"
            : "";

        if (sessionOverride != null) {
            System.out.printf(
                "Current model: %s (session override from plan mode)%nBase model: %s%s%n",
                renderModelLabel(sessionOverride), displayModel, effortInfo
            );
        } else {
            System.out.printf("Current model: %s%s%n", displayModel, effortInfo);
        }
        return 0;
    }

    private int listAvailableModels() {
        String current = modelService.getMainLoopModel();
        List<String> available = modelService.getAvailableModels();

        System.out.println("Available models:");
        for (String model : available) {
            String marker = model.equals(current) ? " (current)" : "";
            System.out.println("  " + renderModelLabel(model) + marker);
        }
        System.out.println();
        System.out.println("Use /model <name> to switch, or /model default to reset.");
        return 0;
    }

    private int resetToDefault() {
        try {
            modelService.setMainLoopModel(null);
            String defaultName = renderModelLabel(modelService.getMainLoopModel());
            System.out.printf("Reset model to default: %s%n", defaultName);
            return 0;
        } catch (Exception e) {
            log.error("Failed to reset model to default", e);
            System.out.println("Failed to reset model: " + e.getMessage());
            return 1;
        }
    }

    private int setModel(String name) {
        // Organisation allowlist check
        if (!modelService.isModelAllowed(name)) {
            System.out.printf(
                "Model '%s' is not available. Your organisation restricts model selection.%n", name
            );
            return 1;
        }

        // 1M-context access checks (Opus / Sonnet)
        if (modelService.isOpus1mUnavailable(name)) {
            System.out.println(
                "Opus 4.6 with 1M context is not available for your account. " +
                "Learn more: https://code.claude.com/docs/en/model-config#extended-context-with-1m"
            );
            return 1;
        }
        if (modelService.isSonnet1mUnavailable(name)) {
            System.out.println(
                "Sonnet 4.6 with 1M context is not available for your account. " +
                "Learn more: https://code.claude.com/docs/en/model-config#extended-context-with-1m"
            );
            return 1;
        }

        // Known alias: skip API validation
        if (modelService.isKnownAlias(name)) {
            return applyModel(name);
        }

        // Unknown name: validate via API
        try {
            ModelService.ValidationResult result = modelService.validateModel(name);
            if (!result.isValid()) {
                String msg = result.getError() != null ? result.getError()
                    : "Model '" + name + "' not found";
                System.out.println(msg);
                return 1;
            }
            return applyModel(name);
        } catch (Exception e) {
            log.error("Failed to validate model '{}'", name, e);
            System.out.println("Failed to validate model: " + e.getMessage());
            return 1;
        }
    }

    private int applyModel(String name) {
        try {
            modelService.setMainLoopModel(name);
            boolean fastModeDisabled = false;
            StringBuilder msg = new StringBuilder("Set model to " + renderModelLabel(name));

            if (modelService.isBilledAsExtraUsage(name)) {
                msg.append(" · Billed as extra usage");
            }
            if (fastModeDisabled) {
                msg.append(" · Fast mode OFF");
            }

            System.out.println(msg);
            return 0;
        } catch (Exception e) {
            log.error("Failed to set model to '{}'", name, e);
            System.out.println("Failed to set model: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Renders a model identifier as a human-readable label.
     * A null value (default) is rendered as "&lt;default&gt;".
     */
    private String renderModelLabel(String model) {
        return modelService.renderModelName(model);
    }
}
