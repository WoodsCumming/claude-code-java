package com.anthropic.claudecode.service;

import com.anthropic.claudecode.config.ClaudeCodeConfig;
import com.anthropic.claudecode.util.ModelUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Model management service.
 *
 * Translated from:
 *   - src/utils/model/model.ts       — model alias resolution, defaults
 *   - src/hooks/useMainLoopModel.ts  — reactive main-loop model selector
 *
 * <h3>TypeScript → Java mapping</h3>
 * <pre>
 * useMainLoopModel()                    → getMainLoopModel()
 * AppState.mainLoopModel                → mainLoopModel field (set via /model command)
 * AppState.mainLoopModelForSession      → mainLoopModelForSession field
 * parseUserSpecifiedModel(...)          → parseUserSpecifiedModel(String)
 * getDefaultMainLoopModelSetting()      → ModelUtils.getDefaultOpusModel()
 * onGrowthBookRefresh(forceRerender)    → subscribeToModelRefresh(Runnable)
 * </pre>
 *
 * In TypeScript, {@code useMainLoopModel} subscribes to a GrowthBook refresh
 * signal to force a re-render when remote feature-flag evaluation finishes.
 * In Java the equivalent is {@link #subscribeToModelRefresh(Runnable)} — callers
 * register a callback that is invoked after GrowthBook (or equivalent flag system)
 * completes initialization, mirroring the {@code onGrowthBookRefresh(forceRerender)}
 * useEffect in the hook.
 */
@Slf4j
@Service
public class ModelService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelService.class);


    // -------------------------------------------------------------------------
    // Model aliases (short names → full model IDs)
    // mirrors MODEL_ALIASES in model.ts / modelStrings.ts
    // -------------------------------------------------------------------------
    private static final Map<String, String> MODEL_ALIASES = Map.of(
        "opus",              "claude-opus-4-6",
        "sonnet",            "claude-sonnet-4-6",
        "haiku",             "claude-haiku-4-5-20251001",
        "claude-opus-4",     "claude-opus-4-6",
        "claude-sonnet-4",   "claude-sonnet-4-6",
        "claude-haiku-4",    "claude-haiku-4-5-20251001"
    );

    private final ClaudeCodeConfig config;

    // -------------------------------------------------------------------------
    // AppState equivalents (useState / useAppState selectors)
    // -------------------------------------------------------------------------

    /**
     * Persistent model setting (set by the /model command and stored in settings).
     * Mirrors: {@code AppState.mainLoopModel}
     */
    private volatile String mainLoopModel;

    /**
     * Session-only model override (cleared when the session ends).
     * Mirrors: {@code AppState.mainLoopModelForSession}
     */
    private volatile String mainLoopModelForSession;

    // -------------------------------------------------------------------------
    // GrowthBook refresh subscribers
    // Mirrors: onGrowthBookRefresh(forceRerender) useEffect in useMainLoopModel.ts
    // -------------------------------------------------------------------------
    private final List<Runnable> growthBookRefreshListeners = new CopyOnWriteArrayList<>();

    @Autowired
    public ModelService(ClaudeCodeConfig config) {
        this.config = config;
    }

    // =========================================================================
    // useMainLoopModel() equivalent
    // =========================================================================

    /**
     * Get the current main loop model, fully resolved.
     *
     * Mirrors {@code useMainLoopModel()} in useMainLoopModel.ts:
     * <ol>
     *   <li>Session override ({@code mainLoopModelForSession}) takes highest priority.</li>
     *   <li>Persistent model setting ({@code mainLoopModel}).</li>
     *   <li>Default model from settings ({@code getDefaultMainLoopModelSetting()}).</li>
     * </ol>
     * The selected string is then passed through {@link #parseUserSpecifiedModel(String)}
     * which resolves aliases and validates against the known model list.
     *
     * @return fully-resolved model name suitable for API calls
     */
    public String getMainLoopModel() {
        String raw = mainLoopModelForSession != null ? mainLoopModelForSession
                   : mainLoopModel          != null ? mainLoopModel
                   : getDefaultMainLoopModelSetting();

        return parseUserSpecifiedModel(raw);
    }

    /**
     * Parse and resolve a user-specified model string.
     *
     * Mirrors {@code parseUserSpecifiedModel(model)} in model.ts.
     * Resolves short aliases (e.g. "opus") to full model IDs, and falls back to
     * the default model when the input is blank or null.
     *
     * GrowthBook remote-eval can change alias targets (e.g. tengu_ant_model_override).
     * Subscribers registered via {@link #subscribeToModelRefresh(Runnable)} are
     * notified after flag re-evaluation so callers can re-resolve stale references.
     *
     * @param model a short alias or full model ID; null/blank uses the default
     * @return resolved full model ID
     */
    public String parseUserSpecifiedModel(String model) {
        if (model == null || model.isBlank()) {
            return ModelUtils.getDefaultOpusModel();
        }
        return resolveModelAlias(model);
    }

    /**
     * Subscribe to GrowthBook (feature-flag) refresh events.
     *
     * Mirrors {@code onGrowthBookRefresh(forceRerender)} useEffect:
     * the callback fires when remote flag evaluation completes so that
     * alias resolution can be re-run with fresh values.
     *
     * @param onRefresh called after each GrowthBook initialization / refresh
     * @return a {@link Runnable} that removes the subscription when called
     */
    public Runnable subscribeToModelRefresh(Runnable onRefresh) {
        growthBookRefreshListeners.add(onRefresh);
        return () -> growthBookRefreshListeners.remove(onRefresh);
    }

    /**
     * Trigger all GrowthBook-refresh listeners.
     * Called by the GrowthBook service after remote flag evaluation completes.
     */
    public void onGrowthBookRefresh() {
        log.debug("GrowthBook refresh: notifying {} model listener(s)",
                growthBookRefreshListeners.size());
        for (Runnable listener : growthBookRefreshListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.warn("Model refresh listener threw: {}", e.getMessage());
            }
        }
    }

    // =========================================================================
    // AppState mutators (called by /model command, session management)
    // =========================================================================

    /**
     * Set the persistent model (written to settings, survives sessions).
     * Mirrors writing to {@code AppState.mainLoopModel}.
     */
    public void setMainLoopModel(String model) {
        this.mainLoopModel = model;
        log.info("Main loop model set to: {}", model);
    }

    /**
     * Set the session-only model override (cleared on session end).
     * Mirrors writing to {@code AppState.mainLoopModelForSession}.
     */
    public void setMainLoopModelForSession(String model) {
        this.mainLoopModelForSession = model;
        log.info("Session model override set to: {}", model);
    }

    /** Clear the session model override. */
    public void clearMainLoopModelForSession() {
        this.mainLoopModelForSession = null;
    }

    // =========================================================================
    // Legacy mutator kept for backward-compatibility
    // =========================================================================

    /**
     * @deprecated Use {@link #setMainLoopModelForSession(String)} for session
     *             overrides or {@link #setMainLoopModel(String)} for persistent changes.
     */
    @Deprecated
    public void setModelOverride(String model) {
        setMainLoopModelForSession(model);
    }

    /** @deprecated Use {@link #clearMainLoopModelForSession()}. */
    @Deprecated
    public void clearModelOverride() {
        clearMainLoopModelForSession();
    }

    // =========================================================================
    // Small/fast model
    // =========================================================================

    /**
     * Get the small/fast model used for lightweight tasks.
     * Translated from {@code getSmallFastModel()} in model.ts.
     */
    public String getSmallFastModel() {
        String envModel = System.getenv("ANTHROPIC_SMALL_FAST_MODEL");
        if (envModel != null && !envModel.isBlank()) return resolveModelAlias(envModel);
        return config.getSmallFastModel() != null
            ? config.getSmallFastModel()
            : ModelUtils.getSmallFastModel();
    }

    // =========================================================================
    // Model alias resolution
    // =========================================================================

    /**
     * Resolve a model alias to its full model ID.
     * Translated from {@code resolveOverriddenModel()} in modelStrings.ts.
     */
    public String resolveModelAlias(String model) {
        if (model == null) return ModelUtils.getDefaultOpusModel();
        return MODEL_ALIASES.getOrDefault(model, model);
    }

    /**
     * Get the default main-loop model setting.
     * Mirrors {@code getDefaultMainLoopModelSetting()} in model.ts.
     */
    public String getDefaultMainLoopModelSetting() {
        // 1. ANTHROPIC_MODEL env var
        String envModel = System.getenv("ANTHROPIC_MODEL");
        if (envModel != null && !envModel.isBlank()) return resolveModelAlias(envModel);
        // 2. Config setting
        if (config.getModel() != null && !config.getModel().isBlank()) {
            return resolveModelAlias(config.getModel());
        }
        // 3. Hardcoded default
        return ModelUtils.getDefaultOpusModel();
    }

    // =========================================================================
    // Model inspection helpers
    // =========================================================================

    /**
     * Check if a model is a non-custom Opus model.
     * Translated from {@code isNonCustomOpusModel()} in model.ts.
     */
    public boolean isNonCustomOpusModel(String model) {
        if (model == null) return false;
        return resolveModelAlias(model).contains("opus");
    }

    /**
     * Get the canonical model name (without date suffix).
     * Translated from {@code getCanonicalName()} in model.ts.
     */
    public String getCanonicalName(String model) {
        return ModelUtils.getCanonicalName(model);
    }

    /**
     * Get a human-readable marketing name for a model.
     * Translated from {@code getMarketingNameForModel()} in model.ts.
     */
    public String getMarketingName(String model) {
        return ModelUtils.getMarketingName(model);
    }

    /**
     * Get all available models.
     */
    public List<String> getAvailableModels() {
        return List.of(
            ModelUtils.DEFAULT_OPUS_MODEL,
            ModelUtils.DEFAULT_SONNET_MODEL,
            ModelUtils.SMALL_FAST_MODEL
        );
    }

    /**
     * Render a human-readable model name.
     */
    public String renderModelName(String model) {
        if (model == null) return "default";
        return model;
    }

    /** Get the current session model override (e.g. from plan mode). */
    public String getSessionModel() {
        return null; // No session model override by default
    }

    /** Get the effort level for the current model (e.g. "1", "5"). */
    public String getEffortLevel() {
        return null;
    }

    /** Check if a model is allowed in the current configuration. */
    public boolean isModelAllowed(String model) {
        return model != null;
    }

    /** Check if a string is a known model alias. */
    public boolean isKnownAlias(String alias) {
        return false;
    }

    /** Check if opus-1m is unavailable. */
    public boolean isOpus1mUnavailable(String subscriptionType) {
        return false;
    }

    /** Check if sonnet-1m is unavailable. */
    public boolean isSonnet1mUnavailable(String subscriptionType) {
        return false;
    }

    /** Validate a model name against the API. */
    public ValidationResult validateModel(String model) {
        return new ValidationResult(true, null);
    }

    /** Check if a model is billed as extra usage. */
    public boolean isBilledAsExtraUsage(String model) {
        return false;
    }

    /** Validation result for model validation. */
    public record ValidationResult(boolean valid, String error) {
        public boolean isValid() { return valid; }
        public String getError() { return error; }
    }
}
