package com.anthropic.claudecode.util;

import lombok.Builder;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Registry of supported config/settings keys.
 * Translated from src/tools/ConfigTool/supportedSettings.ts
 *
 * <p>Each entry describes the storage location, value type, user-visible description,
 * optional allowed values, and optional read/write hooks.</p>
 */
public class ConfigToolSupportedSettings {

    /** Where the setting is persisted. */
    public enum SettingSource { GLOBAL, SETTINGS }

    /** The primitive type of the value. */
    public enum SettingType { BOOLEAN, STRING }

    /**
     * App-state keys that can be synced immediately for UI effect.
     * Translated from SyncableAppStateKey in supportedSettings.ts
     */
    public enum SyncableAppStateKey {
        VERBOSE, MAIN_LOOP_MODEL, THINKING_ENABLED
    }

    /**
     * Full configuration descriptor for a single setting.
     * Translated from SettingConfig in supportedSettings.ts
     */
    public static class SettingConfig {
        private final SettingSource source;
        private final SettingType type;
        private final String description;
        private final List<String> path;
        private final List<String> options;
        private final Supplier<List<String>> getOptions;
        private final SyncableAppStateKey appStateKey;
        private final Function<Object, CompletableFuture<ValidationResult>> validateOnWrite;
        private final Function<Object, Object> formatOnRead;

        private SettingConfig(Builder b) {
            this.source = b.source; this.type = b.type; this.description = b.description;
            this.path = b.path; this.options = b.options; this.getOptions = b.getOptions;
            this.appStateKey = b.appStateKey; this.validateOnWrite = b.validateOnWrite;
            this.formatOnRead = b.formatOnRead;
        }
        public SettingSource source() { return source; }
        public SettingType type() { return type; }
        public String description() { return description; }
        public List<String> path() { return path; }
        public List<String> options() { return options; }
        public Supplier<List<String>> getOptions() { return getOptions; }
        public SyncableAppStateKey appStateKey() { return appStateKey; }
        public Function<Object, CompletableFuture<ValidationResult>> validateOnWrite() { return validateOnWrite; }
        public Function<Object, Object> formatOnRead() { return formatOnRead; }

        public static Builder builder() { return new Builder(); }
        public static class Builder {
            private SettingSource source;
            private SettingType type;
            private String description;
            private List<String> path;
            private List<String> options;
            private Supplier<List<String>> getOptions;
            private SyncableAppStateKey appStateKey;
            private Function<Object, CompletableFuture<ValidationResult>> validateOnWrite;
            private Function<Object, Object> formatOnRead;
            public Builder source(SettingSource v) { this.source = v; return this; }
            public Builder type(SettingType v) { this.type = v; return this; }
            public Builder description(String v) { this.description = v; return this; }
            public Builder path(List<String> v) { this.path = v; return this; }
            public Builder options(List<String> v) { this.options = v; return this; }
            public Builder getOptions(Supplier<List<String>> v) { this.getOptions = v; return this; }
            public Builder appStateKey(SyncableAppStateKey v) { this.appStateKey = v; return this; }
            public Builder validateOnWrite(Function<Object, CompletableFuture<ValidationResult>> v) { this.validateOnWrite = v; return this; }
            public Builder formatOnRead(Function<Object, Object> v) { this.formatOnRead = v; return this; }
            public SettingConfig build() { return new SettingConfig(this); }
        }
    }

    /** Validation result returned by validateOnWrite callbacks. */
    public record ValidationResult(boolean valid, String error) {}

    // -------------------------------------------------------------------------
    // Registry
    // -------------------------------------------------------------------------

    private static final Map<String, SettingConfig> SUPPORTED_SETTINGS = buildRegistry();

    private static Map<String, SettingConfig> buildRegistry() {
        Map<String, SettingConfig> map = new LinkedHashMap<>();

        map.put("theme", SettingConfig.builder()
                .source(SettingSource.GLOBAL)
                .type(SettingType.STRING)
                .description("Color theme for the UI")
                .options(List.of("dark", "light", "light-daltonism", "dark-daltonism"))
                .build());

        map.put("editorMode", SettingConfig.builder()
                .source(SettingSource.GLOBAL)
                .type(SettingType.STRING)
                .description("Key binding mode")
                .options(List.of("normal", "vim", "emacs"))
                .build());

        map.put("verbose", SettingConfig.builder()
                .source(SettingSource.GLOBAL)
                .type(SettingType.BOOLEAN)
                .description("Show detailed debug output")
                .appStateKey(SyncableAppStateKey.VERBOSE)
                .build());

        map.put("preferredNotifChannel", SettingConfig.builder()
                .source(SettingSource.GLOBAL)
                .type(SettingType.STRING)
                .description("Preferred notification channel")
                .options(List.of("terminal", "iterm2", "desktop"))
                .build());

        map.put("autoCompactEnabled", SettingConfig.builder()
                .source(SettingSource.GLOBAL)
                .type(SettingType.BOOLEAN)
                .description("Auto-compact when context is full")
                .build());

        map.put("autoMemoryEnabled", SettingConfig.builder()
                .source(SettingSource.SETTINGS)
                .type(SettingType.BOOLEAN)
                .description("Enable auto-memory")
                .build());

        map.put("autoDreamEnabled", SettingConfig.builder()
                .source(SettingSource.SETTINGS)
                .type(SettingType.BOOLEAN)
                .description("Enable background memory consolidation")
                .build());

        map.put("fileCheckpointingEnabled", SettingConfig.builder()
                .source(SettingSource.GLOBAL)
                .type(SettingType.BOOLEAN)
                .description("Enable file checkpointing for code rewind")
                .build());

        map.put("showTurnDuration", SettingConfig.builder()
                .source(SettingSource.GLOBAL)
                .type(SettingType.BOOLEAN)
                .description("Show turn duration message after responses (e.g., \"Cooked for 1m 6s\")")
                .build());

        map.put("terminalProgressBarEnabled", SettingConfig.builder()
                .source(SettingSource.GLOBAL)
                .type(SettingType.BOOLEAN)
                .description("Show OSC 9;4 progress indicator in supported terminals")
                .build());

        map.put("todoFeatureEnabled", SettingConfig.builder()
                .source(SettingSource.GLOBAL)
                .type(SettingType.BOOLEAN)
                .description("Enable todo/task tracking")
                .build());

        map.put("model", SettingConfig.builder()
                .source(SettingSource.SETTINGS)
                .type(SettingType.STRING)
                .description("Override the default model")
                .appStateKey(SyncableAppStateKey.MAIN_LOOP_MODEL)
                .getOptions(() -> List.of("sonnet", "opus", "haiku"))
                .validateOnWrite(v -> CompletableFuture.completedFuture(
                        new ValidationResult(true, null)))
                .formatOnRead(v -> v == null ? "default" : v)
                .build());

        map.put("alwaysThinkingEnabled", SettingConfig.builder()
                .source(SettingSource.SETTINGS)
                .type(SettingType.BOOLEAN)
                .description("Enable extended thinking (false to disable)")
                .appStateKey(SyncableAppStateKey.THINKING_ENABLED)
                .build());

        map.put("permissions.defaultMode", SettingConfig.builder()
                .source(SettingSource.SETTINGS)
                .type(SettingType.STRING)
                .description("Default permission mode for tool usage")
                .options(List.of("default", "plan", "acceptEdits", "dontAsk"))
                .build());

        map.put("language", SettingConfig.builder()
                .source(SettingSource.SETTINGS)
                .type(SettingType.STRING)
                .description("Preferred language for Claude responses and voice dictation (e.g., \"japanese\", \"spanish\")")
                .build());

        map.put("teammateMode", SettingConfig.builder()
                .source(SettingSource.GLOBAL)
                .type(SettingType.STRING)
                .description("How to spawn teammates: \"tmux\" for traditional tmux, \"in-process\" for same process, \"auto\" to choose automatically")
                .options(List.of("tmux", "in-process", "auto"))
                .build());

        return Collections.unmodifiableMap(map);
    }

    // -------------------------------------------------------------------------
    // Public API — mirrors the exported functions in supportedSettings.ts
    // -------------------------------------------------------------------------

    /**
     * Returns true if the given key is a known setting.
     * Translated from isSupported() in supportedSettings.ts
     */
    public static boolean isSupported(String key) {
        return SUPPORTED_SETTINGS.containsKey(key);
    }

    /**
     * Returns the SettingConfig for the given key, or empty if unknown.
     * Translated from getConfig() in supportedSettings.ts
     */
    public static Optional<SettingConfig> getConfig(String key) {
        return Optional.ofNullable(SUPPORTED_SETTINGS.get(key));
    }

    /**
     * Returns all known setting keys.
     * Translated from getAllKeys() in supportedSettings.ts
     */
    public static List<String> getAllKeys() {
        return new ArrayList<>(SUPPORTED_SETTINGS.keySet());
    }

    /**
     * Returns the static or dynamic options list for a setting, or empty if none.
     * Translated from getOptionsForSetting() in supportedSettings.ts
     */
    public static Optional<List<String>> getOptionsForSetting(String key) {
        SettingConfig config = SUPPORTED_SETTINGS.get(key);
        if (config == null) return Optional.empty();
        if (config.options() != null && !config.options().isEmpty()) {
            return Optional.of(new ArrayList<>(config.options()));
        }
        if (config.getOptions() != null) {
            return Optional.of(config.getOptions().get());
        }
        return Optional.empty();
    }

    /**
     * Returns the storage path segments for a setting key.
     * Translated from getPath() in supportedSettings.ts
     */
    public static List<String> getPath(String key) {
        SettingConfig config = SUPPORTED_SETTINGS.get(key);
        if (config != null && config.path() != null && !config.path().isEmpty()) {
            return config.path();
        }
        return Arrays.asList(key.split("\\."));
    }

    private ConfigToolSupportedSettings() {}
}
