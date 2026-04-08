package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.KeybindingDefaults;
import com.anthropic.claudecode.model.KeybindingSchema.KeybindingBlock;
import com.anthropic.claudecode.util.EnvUtils;
import com.anthropic.claudecode.util.KeybindingParser;
import com.anthropic.claudecode.util.KeybindingParser.ParsedBinding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * User keybinding configuration loader with hot-reload support.
 *
 * Loads keybindings from ~/.claude/keybindings.json and watches
 * for changes to reload them automatically.
 *
 * NOTE: User keybinding customization is gated on the
 * tengu_keybinding_customization_release feature flag.
 *
 * Translated from src/keybindings/loadUserBindings.ts
 */
@Slf4j
@Service
public class KeybindingsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KeybindingsService.class);


    private static final String KEYBINDINGS_FILENAME = "keybindings.json";

    /**
     * Time in milliseconds to wait for file writes to stabilize.
     * Translated from FILE_STABILITY_THRESHOLD_MS in loadUserBindings.ts
     */
    private static final long FILE_STABILITY_THRESHOLD_MS = 500;

    /**
     * Result of loading keybindings, including any validation warnings.
     * Translated from KeybindingsLoadResult in loadUserBindings.ts
     */
    @Data
    public static class KeybindingsLoadResult {
        private final List<ParsedBinding> bindings;
        private final List<KeybindingWarning> warnings;

        public KeybindingsLoadResult(List<ParsedBinding> bindings, List<KeybindingWarning> warnings) {
            this.bindings = bindings; this.warnings = warnings;
        }
        public List<ParsedBinding> getBindings() { return bindings; }
        public List<KeybindingWarning> getWarnings() { return warnings; }
    }

    /**
     * A validation warning from parsing/validating keybindings.json.
     * Translated from KeybindingWarning in validate.ts
     */
    @Data
    public static class KeybindingWarning {
        private final String type;       // e.g., "parse_error", "duplicate_key", "invalid_action"
        private final String severity;   // "error" | "warning"
        private final String message;
        private final String suggestion; // may be null

        public KeybindingWarning(String type, String severity, String message) {
            this(type, severity, message, null);
        }

        public KeybindingWarning(String type, String severity, String message, String suggestion) {
            this.type = type;
            this.severity = severity;
            this.message = message;
            this.suggestion = suggestion;
        }

        public String getType() { return type; }
        public String getSeverity() { return severity; }
        public String getMessage() { return message; }
        public String getSuggestion() { return suggestion; }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicReference<List<ParsedBinding>> cachedBindings = new AtomicReference<>(null);
    private final AtomicReference<List<KeybindingWarning>> cachedWarnings = new AtomicReference<>(List.of());
    private final List<Consumer<KeybindingsLoadResult>> changeListeners = new CopyOnWriteArrayList<>();

    private WatchService watchService;
    private Thread watchThread;

    /**
     * Tracks the date (YYYY-MM-DD) when we last logged a custom keybindings load event.
     * Used to ensure we fire the event at most once per day.
     * Translated from lastCustomBindingsLogDate in loadUserBindings.ts
     */
    private volatile String lastCustomBindingsLogDate = null;

    // GrowthBook service for feature flag checks (optional dependency)
    private final GrowthBookService growthBookService;

    public KeybindingsService(GrowthBookService growthBookService) {
        this.growthBookService = growthBookService;
    }

    /**
     * Check if keybinding customization is enabled.
     * Returns true if the tengu_keybinding_customization_release GrowthBook gate is enabled.
     * Translated from isKeybindingCustomizationEnabled() in loadUserBindings.ts
     */
    public boolean isKeybindingCustomizationEnabled() {
        if (growthBookService != null) {
            return growthBookService.getFeatureValue("tengu_keybinding_customization_release", false);
        }
        return false;
    }

    /**
     * Get the path to the user keybindings file.
     * Translated from getKeybindingsPath() in loadUserBindings.ts
     */
    public String getKeybindingsPath() {
        return EnvUtils.getClaudeConfigHomeDir() + File.separator + KEYBINDINGS_FILENAME;
    }

    /**
     * Parse default bindings.
     * Translated from getDefaultParsedBindings() in loadUserBindings.ts
     */
    private List<ParsedBinding> getDefaultParsedBindings() {
        return KeybindingParser.parseBindings(KeybindingDefaults.DEFAULT_BINDINGS);
    }

    /**
     * Load and parse keybindings from user config file (async).
     * Returns merged default + user bindings along with validation warnings.
     *
     * For users without the feature flag, always returns default bindings only.
     * Translated from loadKeybindings() in loadUserBindings.ts
     */
    public CompletableFuture<KeybindingsLoadResult> loadKeybindings() {
        return CompletableFuture.supplyAsync(() -> {
            List<ParsedBinding> defaultBindings = getDefaultParsedBindings();

            // Skip user config loading when customization is disabled
            if (!isKeybindingCustomizationEnabled()) {
                return new KeybindingsLoadResult(defaultBindings, List.of());
            }

            String userPath = getKeybindingsPath();

            try {
                String content = Files.readString(Path.of(userPath));
                return parseAndMergeUserBindings(content, userPath, defaultBindings);
            } catch (NoSuchFileException e) {
                // File doesn't exist — use defaults (user can run /keybindings to create)
                return new KeybindingsLoadResult(defaultBindings, List.of());
            } catch (Exception e) {
                log.debug("[keybindings] Error loading {}: {}", userPath, e.getMessage());
                return new KeybindingsLoadResult(defaultBindings, List.of(
                        new KeybindingWarning("parse_error", "error",
                                "Failed to parse keybindings.json: " + e.getMessage())
                ));
            }
        });
    }

    /**
     * Load keybindings synchronously. Uses cached value if available.
     * Translated from loadKeybindingsSync() in loadUserBindings.ts
     */
    public List<ParsedBinding> loadKeybindingsSync() {
        List<ParsedBinding> cached = cachedBindings.get();
        if (cached != null) return cached;
        return loadKeybindingsSyncWithWarnings().getBindings();
    }

    /**
     * Load keybindings synchronously with validation warnings. Uses cached values if available.
     * Translated from loadKeybindingsSyncWithWarnings() in loadUserBindings.ts
     */
    public KeybindingsLoadResult loadKeybindingsSyncWithWarnings() {
        List<ParsedBinding> cached = cachedBindings.get();
        if (cached != null) {
            return new KeybindingsLoadResult(cached, cachedWarnings.get());
        }

        List<ParsedBinding> defaultBindings = getDefaultParsedBindings();

        // Skip user config loading when customization is disabled
        if (!isKeybindingCustomizationEnabled()) {
            cachedBindings.set(defaultBindings);
            cachedWarnings.set(List.of());
            return new KeybindingsLoadResult(defaultBindings, List.of());
        }

        String userPath = getKeybindingsPath();

        try {
            String content = Files.readString(Path.of(userPath));
            KeybindingsLoadResult result = parseAndMergeUserBindings(content, userPath, defaultBindings);
            cachedBindings.set(result.getBindings());
            cachedWarnings.set(result.getWarnings());
            return result;
        } catch (Exception e) {
            // File doesn't exist or error — use defaults
            cachedBindings.set(defaultBindings);
            cachedWarnings.set(List.of());
            return new KeybindingsLoadResult(defaultBindings, List.of());
        }
    }

    /**
     * Parse the JSON content of keybindings.json, validate it, and merge with defaults.
     */
    private KeybindingsLoadResult parseAndMergeUserBindings(
            String content, String userPath, List<ParsedBinding> defaultBindings) {

        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(content);
        } catch (Exception e) {
            log.debug("[keybindings] Invalid JSON in {}: {}", userPath, e.getMessage());
            return new KeybindingsLoadResult(defaultBindings, List.of(
                    new KeybindingWarning("parse_error", "error",
                            "Failed to parse keybindings.json: " + e.getMessage())
            ));
        }

        // Extract bindings array from object wrapper format: { "bindings": [...] }
        if (!parsed.isObject() || !parsed.has("bindings")) {
            String errorMsg = "keybindings.json must have a \"bindings\" array";
            String suggestion = "Use format: { \"bindings\": [ ... ] }";
            log.debug("[keybindings] Invalid keybindings.json: {}", errorMsg);
            return new KeybindingsLoadResult(defaultBindings, List.of(
                    new KeybindingWarning("parse_error", "error", errorMsg, suggestion)
            ));
        }

        JsonNode bindingsNode = parsed.get("bindings");
        if (!bindingsNode.isArray()) {
            String errorMsg = "\"bindings\" must be an array";
            String suggestion = "Set \"bindings\" to an array of keybinding blocks";
            log.debug("[keybindings] Invalid keybindings.json: {}", errorMsg);
            return new KeybindingsLoadResult(defaultBindings, List.of(
                    new KeybindingWarning("parse_error", "error", errorMsg, suggestion)
            ));
        }

        // Parse and validate the user blocks
        List<KeybindingBlock> userBlocks = new ArrayList<>();
        List<KeybindingWarning> warnings = new ArrayList<>();

        for (JsonNode blockNode : bindingsNode) {
            if (!blockNode.isObject() || !blockNode.has("context") || !blockNode.has("bindings")) {
                warnings.add(new KeybindingWarning("parse_error", "error",
                        "keybindings.json contains invalid block structure",
                        "Each block must have \"context\" (string) and \"bindings\" (object)"));
                continue;
            }

            String context = blockNode.get("context").asText();
            JsonNode bindingsMap = blockNode.get("bindings");

            if (!bindingsMap.isObject()) {
                warnings.add(new KeybindingWarning("parse_error", "error",
                        "\"bindings\" in block \"" + context + "\" must be an object"));
                continue;
            }

            Map<String, String> bindings = new LinkedHashMap<>();
            bindingsMap.fields().forEachRemaining(entry -> {
                JsonNode val = entry.getValue();
                // null value = unbind a default shortcut
                if (!val.isNull()) {
                    bindings.put(entry.getKey(), val.asText());
                }
            });

            userBlocks.add(new KeybindingBlock(context, bindings));
        }

        List<ParsedBinding> userParsed = KeybindingParser.parseBindings(userBlocks);
        log.debug("[keybindings] Loaded {} user bindings from {}", userParsed.size(), userPath);

        // User bindings come after defaults, so they override
        List<ParsedBinding> mergedBindings = new ArrayList<>(defaultBindings);
        mergedBindings.addAll(userParsed);

        logCustomBindingsLoadedOncePerDay(userParsed.size());

        if (!warnings.isEmpty()) {
            log.debug("[keybindings] Found {} validation issue(s)", warnings.size());
        }

        return new KeybindingsLoadResult(Collections.unmodifiableList(mergedBindings), warnings);
    }

    /**
     * Log a telemetry event when custom keybindings are loaded, at most once per day.
     * Translated from logCustomBindingsLoadedOncePerDay() in loadUserBindings.ts
     */
    private void logCustomBindingsLoadedOncePerDay(int userBindingCount) {
        String today = java.time.LocalDate.now().toString(); // YYYY-MM-DD
        if (today.equals(lastCustomBindingsLogDate)) return;
        lastCustomBindingsLogDate = today;
        log.debug("[keybindings] Custom keybindings loaded: {} user bindings", userBindingCount);
        // TODO: forward to analytics service: tengu_custom_keybindings_loaded
    }

    /**
     * Initialize file watching for keybindings.json. Call this once when the app starts.
     * For users without the feature flag, this is a no-op.
     * Translated from initializeKeybindingWatcher() in loadUserBindings.ts
     */
    @PostConstruct
    public void initializeKeybindingWatcher() {
        if (initialized.get() || disposed.get()) return;

        if (!isKeybindingCustomizationEnabled()) {
            log.debug("[keybindings] Skipping file watcher - user customization disabled");
            return;
        }

        String userPath = getKeybindingsPath();
        Path watchDir = Path.of(userPath).getParent();

        if (watchDir == null || !Files.isDirectory(watchDir)) {
            log.debug("[keybindings] Not watching: {} does not exist or is not a directory", watchDir);
            return;
        }

        initialized.set(true);
        log.debug("[keybindings] Watching for changes to {}", userPath);

        try {
            watchService = FileSystems.getDefault().newWatchService();
            watchDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

            watchThread = new Thread(() -> runWatchLoop(userPath), "keybindings-watcher");
            watchThread.setDaemon(true);
            watchThread.start();
        } catch (Exception e) {
            log.debug("[keybindings] Could not start file watcher: {}", e.getMessage());
            initialized.set(false);
        }
    }

    private void runWatchLoop(String userPath) {
        String filename = Path.of(userPath).getFileName().toString();
        while (!disposed.get()) {
            try {
                WatchKey key = watchService.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path changed = pathEvent.context();

                    if (!filename.equals(changed.toString())) continue;

                    if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        handleDelete(userPath);
                    } else {
                        // Wait for write to stabilize
                        try { Thread.sleep(FILE_STABILITY_THRESHOLD_MS); } catch (InterruptedException ie) { break; }
                        handleChange(userPath);
                    }
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (Exception e) {
                log.debug("[keybindings] Watch loop error: {}", e.getMessage());
            }
        }
    }

    /**
     * Handle a file change event.
     * Translated from handleChange() in loadUserBindings.ts
     */
    private void handleChange(String path) {
        log.debug("[keybindings] Detected change to {}", path);
        try {
            loadKeybindings().thenAccept(result -> {
                cachedBindings.set(result.getBindings());
                cachedWarnings.set(result.getWarnings());
                notifyListeners(result);
            });
        } catch (Exception e) {
            log.debug("[keybindings] Error reloading: {}", e.getMessage());
        }
    }

    /**
     * Handle a file deletion event — reset to defaults.
     * Translated from handleDelete() in loadUserBindings.ts
     */
    private void handleDelete(String path) {
        log.debug("[keybindings] Detected deletion of {}", path);
        List<ParsedBinding> defaultBindings = getDefaultParsedBindings();
        cachedBindings.set(defaultBindings);
        cachedWarnings.set(List.of());
        notifyListeners(new KeybindingsLoadResult(defaultBindings, List.of()));
    }

    private void notifyListeners(KeybindingsLoadResult result) {
        for (Consumer<KeybindingsLoadResult> listener : changeListeners) {
            try {
                listener.accept(result);
            } catch (Exception e) {
                log.debug("[keybindings] Listener error: {}", e.getMessage());
            }
        }
    }

    /**
     * Subscribe to keybinding changes.
     * The listener receives the new parsed bindings when the file changes.
     * Translated from subscribeToKeybindingChanges in loadUserBindings.ts
     *
     * @return a Runnable that unsubscribes when called
     */
    public Runnable subscribeToKeybindingChanges(Consumer<KeybindingsLoadResult> listener) {
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    /**
     * Get the cached keybinding warnings.
     * Returns empty list if no warnings or bindings haven't been loaded yet.
     * Translated from getCachedKeybindingWarnings() in loadUserBindings.ts
     */
    public List<KeybindingWarning> getCachedKeybindingWarnings() {
        return cachedWarnings.get();
    }

    /**
     * Clean up the file watcher.
     * Translated from disposeKeybindingWatcher() in loadUserBindings.ts
     */
    @PreDestroy
    public void disposeKeybindingWatcher() {
        disposed.set(true);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.debug("[keybindings] Error closing watch service: {}", e.getMessage());
            }
            watchService = null;
        }
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        changeListeners.clear();
    }

    /**
     * Ensure the keybindings file exists (creates it from template if not present).
     * Returns true if the file already existed, false if it was created.
     */
    public boolean ensureKeybindingsFile() {
        String path = getKeybindingsPath();
        java.io.File file = new java.io.File(path);
        if (file.exists()) return true;
        try {
            file.getParentFile().mkdirs();
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), generateKeybindingsTemplate(),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (java.io.IOException ignored) {}
        return false;
    }

    /**
     * Open the keybindings file in the system editor.
     * Returns null on success, or an error message string on failure.
     */
    public String openInEditor(String filePath) {
        try {
            String editor = System.getenv("EDITOR");
            if (editor == null || editor.isBlank()) editor = "vi";
            new ProcessBuilder(editor, filePath).inheritIO().start().waitFor();
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /**
     * Generate a default keybindings template JSON string for the user to customise.
     */
    public String generateKeybindingsTemplate() {
        return "{\n  \"$schema\": \"https://claude.ai/keybindings.schema.json\",\n  \"bindings\": []\n}\n";
    }

    /**
     * Reset internal state for testing.
     * Translated from resetKeybindingLoaderForTesting() in loadUserBindings.ts
     */
    public void resetForTesting() {
        disposeKeybindingWatcher();
        initialized.set(false);
        disposed.set(false);
        cachedBindings.set(null);
        cachedWarnings.set(List.of());
        lastCustomBindingsLogDate = null;
    }
}
