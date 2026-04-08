package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import lombok.Data;

/**
 * Auto mode state management and CLI subcommand handlers.
 *
 * Merges two TypeScript sources:
 * <ul>
 *   <li>{@code src/utils/permissions/autoModeState.ts} — runtime auto mode flags</li>
 *   <li>{@code src/cli/handlers/autoMode.ts} — CLI handlers:
 *       {@code autoModeDefaultsHandler}, {@code autoModeConfigHandler},
 *       {@code autoModeCritiqueHandler}</li>
 * </ul>
 *
 * Translated from src/cli/handlers/autoMode.ts and src/utils/permissions/autoModeState.ts
 */
@Slf4j
@Component
public class AutoModeState {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AutoModeState.class);


    // =========================================================================
    // Runtime state flags  (src/utils/permissions/autoModeState.ts)
    // =========================================================================

    private final AtomicBoolean autoModeActive = new AtomicBoolean(false);
    private final AtomicBoolean autoModeFlagCli = new AtomicBoolean(false);
    private final AtomicBoolean autoModeCircuitBroken = new AtomicBoolean(false);

    public void setAutoModeActive(boolean active) {
        autoModeActive.set(active);
    }

    public boolean isAutoModeActive() {
        return autoModeActive.get();
    }

    public void setAutoModeFlagCli(boolean passed) {
        autoModeFlagCli.set(passed);
    }

    public boolean getAutoModeFlagCli() {
        return autoModeFlagCli.get();
    }

    public void setAutoModeCircuitBroken(boolean broken) {
        autoModeCircuitBroken.set(broken);
    }

    public boolean isAutoModeCircuitBroken() {
        return autoModeCircuitBroken.get();
    }

    public void resetForTesting() {
        autoModeActive.set(false);
        autoModeFlagCli.set(false);
        autoModeCircuitBroken.set(false);
    }

    // =========================================================================
    // Domain model  (mirrors AutoModeRules in yoloClassifier.ts)
    // =========================================================================

    /**
     * Classifier rules for auto mode.
     * Mirrors the {@code AutoModeRules} type in yoloClassifier.ts.
     */
    public static class AutoModeRules {
        /** Actions the classifier should auto-approve. */
        private List<String> allow = new ArrayList<>();
        /** Actions the classifier should block (require user confirmation). */
        private List<String> soft_deny = new ArrayList<>();
        /** Context about the user's setup that helps the classifier. */
        private List<String> environment = new ArrayList<>();

        public AutoModeRules() {}
        public AutoModeRules(List<String> allow, List<String> soft_deny, List<String> environment) {
            if (allow != null) this.allow = allow;
            if (soft_deny != null) this.soft_deny = soft_deny;
            if (environment != null) this.environment = environment;
        }
        public List<String> getAllow() { return allow; }
        public void setAllow(List<String> v) { allow = v; }
        public List<String> getSoft_deny() { return soft_deny; }
        public void setSoft_deny(List<String> v) { soft_deny = v; }
        public List<String> getEnvironment() { return environment; }
        public void setEnvironment(List<String> v) { environment = v; }
    }

    // =========================================================================
    // CLI handlers  (src/cli/handlers/autoMode.ts)
    // =========================================================================

    /**
     * Dump the default external auto mode classifier rules to stdout.
     * Translated from {@code autoModeDefaultsHandler()} in autoMode.ts.
     */
    public void autoModeDefaultsHandler() {
        writeRules(getDefaultExternalAutoModeRules());
    }

    /**
     * Dump the effective auto mode config: user settings where provided,
     * external defaults otherwise.  Per-section REPLACE semantics.
     * Translated from {@code autoModeConfigHandler()} in autoMode.ts.
     */
    public void autoModeConfigHandler() {
        AutoModeRules config = getAutoModeConfig();
        AutoModeRules defaults = getDefaultExternalAutoModeRules();
        AutoModeRules effective = new AutoModeRules(
            (config.getAllow() != null && !config.getAllow().isEmpty())
                ? config.getAllow() : defaults.getAllow(),
            (config.getSoft_deny() != null && !config.getSoft_deny().isEmpty())
                ? config.getSoft_deny() : defaults.getSoft_deny(),
            (config.getEnvironment() != null && !config.getEnvironment().isEmpty())
                ? config.getEnvironment() : defaults.getEnvironment()
        );
        writeRules(effective);
    }

    private static final String CRITIQUE_SYSTEM_PROMPT =
        "You are an expert reviewer of auto mode classifier rules for Claude Code.\n\n" +
        "Claude Code has an \"auto mode\" that uses an AI classifier to decide whether " +
        "tool calls should be auto-approved or require user confirmation. Users can " +
        "write custom rules in three categories:\n\n" +
        "- **allow**: Actions the classifier should auto-approve\n" +
        "- **soft_deny**: Actions the classifier should block (require user confirmation)\n" +
        "- **environment**: Context about the user's setup that helps the classifier make decisions\n\n" +
        "Your job is to critique the user's custom rules for clarity, completeness, " +
        "and potential issues. The classifier is an LLM that reads these rules as " +
        "part of its system prompt.\n\n" +
        "For each rule, evaluate:\n" +
        "1. **Clarity**: Is the rule unambiguous? Could the classifier misinterpret it?\n" +
        "2. **Completeness**: Are there gaps or edge cases the rule doesn't cover?\n" +
        "3. **Conflicts**: Do any of the rules conflict with each other?\n" +
        "4. **Actionability**: Is the rule specific enough for the classifier to act on?\n\n" +
        "Be concise and constructive. Only comment on rules that could be improved. " +
        "If all rules look good, say so.";

    /**
     * Critique user-written auto mode rules using a side query to the model.
     * Translated from {@code autoModeCritiqueHandler()} in autoMode.ts.
     *
     * @param model  optional model override (null = use main loop model)
     */
    public CompletableFuture<Void> autoModeCritiqueHandler(String model) {
        return CompletableFuture.runAsync(() -> {
            AutoModeRules config = getAutoModeConfig();
            boolean hasCustomRules =
                (config.getAllow() != null && !config.getAllow().isEmpty()) ||
                (config.getSoft_deny() != null && !config.getSoft_deny().isEmpty()) ||
                (config.getEnvironment() != null && !config.getEnvironment().isEmpty());

            if (!hasCustomRules) {
                System.out.print(
                    "No custom auto mode rules found.\n\n" +
                    "Add rules to your settings file under autoMode.{allow, soft_deny, environment}.\n" +
                    "Run `claude auto-mode defaults` to see the default rules for reference.\n");
                return;
            }

            AutoModeRules defaults = getDefaultExternalAutoModeRules();
            String userRulesSummary =
                formatRulesForCritique("allow", config.getAllow(), defaults.getAllow()) +
                formatRulesForCritique("soft_deny", config.getSoft_deny(), defaults.getSoft_deny()) +
                formatRulesForCritique("environment", config.getEnvironment(), defaults.getEnvironment());

            System.out.println("Analyzing your auto mode rules\u2026\n");

            // Real implementation would call sideQuery(). Placeholder output:
            log.info("Would critique rules with model={} using system prompt:\n{}",
                     model != null ? model : "default",
                     CRITIQUE_SYSTEM_PROMPT);
            System.out.println("(Auto mode critique requires an active Claude session.)");
        });
    }

    /**
     * Format rules for the critique prompt.
     * Translated from {@code formatRulesForCritique()} in autoMode.ts.
     */
    private String formatRulesForCritique(String section,
                                           List<String> userRules,
                                           List<String> defaultRules) {
        if (userRules == null || userRules.isEmpty()) return "";
        String customLines = userRules.stream().map(r -> "- " + r).collect(Collectors.joining("\n"));
        String defaultLines = defaultRules == null ? "" :
            defaultRules.stream().map(r -> "- " + r).collect(Collectors.joining("\n"));
        return "## " + section + " (custom rules replacing defaults)\n" +
               "Custom:\n" + customLines + "\n\n" +
               "Defaults being replaced:\n" + defaultLines + "\n\n";
    }

    // =========================================================================
    // Configuration helpers  (mirrors yoloClassifier.ts / settings.ts)
    // =========================================================================

    /**
     * Get the default external auto mode rules.
     * Mirrors {@code getDefaultExternalAutoModeRules()} in yoloClassifier.ts.
     */
    public AutoModeRules getDefaultExternalAutoModeRules() {
        return new AutoModeRules(
            List.of(
                "Read-only file system operations (reading files, listing directories)",
                "Running tests and builds that don't modify files",
                "Fetching URLs and web resources",
                "Running shell commands that are clearly safe and reversible"
            ),
            List.of(
                "Deleting files or directories",
                "Modifying system configuration",
                "Installing or uninstalling packages globally",
                "Pushing to remote repositories"
            ),
            List.of()
        );
    }

    /**
     * Get the user's auto mode config from settings.
     * Mirrors {@code getAutoModeConfig()} in settings.ts.
     * Returns empty rules if no config is set.
     */
    public AutoModeRules getAutoModeConfig() {
        // In a full implementation this would read from the user's settings file.
        // Return empty rules as a safe default.
        return new AutoModeRules(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void writeRules(AutoModeRules rules) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
            System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rules));
        } catch (Exception e) {
            log.error("Failed to serialize auto mode rules", e);
        }
    }
}
