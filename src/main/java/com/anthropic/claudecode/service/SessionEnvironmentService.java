package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import com.anthropic.claudecode.util.PlatformUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Session environment script management service.
 * Translated from src/utils/sessionEnvironment.ts
 *
 * Manages hook-generated shell environment scripts for the session directory.
 * Scripts are sourced before each shell command to propagate venv/conda
 * activations and other environment changes across shell invocations.
 */
@Slf4j
@Service
public class SessionEnvironmentService {



    /**
     * Hook event types that may produce environment files.
     * Translated from the hookEvent parameter of getHookEnvFilePath() in sessionEnvironment.ts
     */
    public enum HookEvent {
        SETUP("setup"),
        SESSION_START("sessionstart"),
        CWD_CHANGED("cwdchanged"),
        FILE_CHANGED("filechanged");

        private final String prefix;

        HookEvent(String prefix) {
            this.prefix = prefix;
        }

        public String getPrefix() {
            return prefix;
        }
    }

    private static final Pattern HOOK_ENV_REGEX =
            Pattern.compile("^(setup|sessionstart|cwdchanged|filechanged)-hook-(\\d+)\\.sh$");

    private static final Map<String, Integer> HOOK_ENV_PRIORITY = Map.of(
            "setup", 0,
            "sessionstart", 1,
            "cwdchanged", 2,
            "filechanged", 3
    );

    // Cache states mirror the TypeScript:
    //   null field absent  → not yet loaded (need to check disk)
    //   empty Optional     → checked disk, no files exist
    //   non-empty Optional → loaded and cached
    private volatile String cachedScript = null;  // null = unloaded
    private volatile boolean cacheLoaded = false;

    private final SessionService sessionService;

    @Autowired
    public SessionEnvironmentService(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Return the session-scoped environment directory, creating it if needed.
     * Translated from getSessionEnvDirPath() in sessionEnvironment.ts
     */
    public String getSessionEnvDirPath() throws IOException {
        String dir = EnvUtils.getClaudeConfigHomeDir()
                + "/session-env/"
                + sessionService.getCurrentSessionId();
        Files.createDirectories(Paths.get(dir));
        return dir;
    }

    /**
     * Return the path for a hook's environment file.
     * Translated from getHookEnvFilePath() in sessionEnvironment.ts
     */
    public String getHookEnvFilePath(HookEvent hookEvent, int hookIndex) throws IOException {
        return getSessionEnvDirPath() + "/" + hookEvent.getPrefix() + "-hook-" + hookIndex + ".sh";
    }

    /**
     * Clear cwd-scoped hook env files (filechanged-hook-* and cwdchanged-hook-*).
     * Translated from clearCwdEnvFiles() in sessionEnvironment.ts
     */
    public void clearCwdEnvFiles() {
        try {
            String dir = getSessionEnvDirPath();
            File[] files = new File(dir).listFiles();
            if (files == null) return;
            for (File f : files) {
                String name = f.getName();
                Matcher m = HOOK_ENV_REGEX.matcher(name);
                if (!m.matches()) continue;
                String type = m.group(1);
                if ("filechanged".equals(type) || "cwdchanged".equals(type)) {
                    Files.writeString(f.toPath(), "");
                }
            }
        } catch (Exception e) {
            // ENOENT is fine; log anything else
            if (!isEnoent(e)) {
                log.debug("Failed to clear cwd env files: {}", e.getMessage());
            }
        }
    }

    /**
     * Invalidate the in-memory session environment cache.
     * Translated from invalidateSessionEnvCache() in sessionEnvironment.ts
     */
    public synchronized void invalidateSessionEnvCache() {
        log.debug("Invalidating session environment cache");
        cachedScript = null;
        cacheLoaded = false;
    }

    /**
     * Build (or return cached) the composite shell environment script for the
     * current session. Returns null when no scripts were found.
     * Translated from getSessionEnvironmentScript() in sessionEnvironment.ts
     */
    public synchronized String getSessionEnvironmentScript() {
        if ("windows".equals(PlatformUtils.getPlatform())) {
            log.debug("Session environment not yet supported on Windows");
            return null;
        }

        if (cacheLoaded) {
            return cachedScript;
        }

        List<String> scripts = new ArrayList<>();

        // Load CLAUDE_ENV_FILE from parent process (e.g. HFI trajectory runner)
        String envFile = System.getenv("CLAUDE_ENV_FILE");
        if (envFile != null && !envFile.isEmpty()) {
            try {
                String envScript = Files.readString(Paths.get(envFile)).strip();
                if (!envScript.isEmpty()) {
                    scripts.add(envScript);
                    log.debug("Session environment loaded from CLAUDE_ENV_FILE: {} ({} chars)",
                            envFile, envScript.length());
                }
            } catch (Exception e) {
                if (!isEnoent(e)) {
                    log.debug("Failed to read CLAUDE_ENV_FILE: {}", e.getMessage());
                }
            }
        }

        // Load hook environment files from session directory
        try {
            String sessionEnvDir = getSessionEnvDirPath();
            File dir = new File(sessionEnvDir);
            File[] rawFiles = dir.listFiles();
            if (rawFiles != null) {
                List<String> hookFiles = new ArrayList<>();
                for (File f : rawFiles) {
                    if (HOOK_ENV_REGEX.matcher(f.getName()).matches()) {
                        hookFiles.add(f.getName());
                    }
                }
                hookFiles.sort(this::sortHookEnvFiles);

                for (String fileName : hookFiles) {
                    Path filePath = Paths.get(sessionEnvDir, fileName);
                    try {
                        String content = Files.readString(filePath).strip();
                        if (!content.isEmpty()) {
                            scripts.add(content);
                        }
                    } catch (Exception e) {
                        if (!isEnoent(e)) {
                            log.debug("Failed to read hook file {}: {}", filePath, e.getMessage());
                        }
                    }
                }

                if (!hookFiles.isEmpty()) {
                    log.debug("Session environment loaded from {} hook file(s)", hookFiles.size());
                }
            }
        } catch (Exception e) {
            if (!isEnoent(e)) {
                log.debug("Failed to load session environment from hooks: {}", e.getMessage());
            }
        }

        if (scripts.isEmpty()) {
            log.debug("No session environment scripts found");
            cachedScript = null;
        } else {
            cachedScript = String.join("\n", scripts);
            log.debug("Session environment script ready ({} chars total)", cachedScript.length());
        }

        cacheLoaded = true;
        return cachedScript;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Sort hook env files: first by hook type priority, then by index.
     * Translated from sortHookEnvFiles() in sessionEnvironment.ts
     */
    private int sortHookEnvFiles(String a, String b) {
        Matcher aMatch = HOOK_ENV_REGEX.matcher(a);
        Matcher bMatch = HOOK_ENV_REGEX.matcher(b);
        String aType = aMatch.matches() ? aMatch.group(1) : "";
        String bType = bMatch.matches() ? bMatch.group(1) : "";
        // reset so we can access groups again
        aMatch = HOOK_ENV_REGEX.matcher(a);
        bMatch = HOOK_ENV_REGEX.matcher(b);
        aMatch.matches();
        bMatch.matches();

        if (!aType.equals(bType)) {
            int ap = HOOK_ENV_PRIORITY.getOrDefault(aType, 99);
            int bp = HOOK_ENV_PRIORITY.getOrDefault(bType, 99);
            return Integer.compare(ap, bp);
        }
        int aIndex = 0, bIndex = 0;
        try {
            aIndex = aMatch.group(2) != null ? Integer.parseInt(aMatch.group(2)) : 0;
        } catch (Exception ignored) {}
        try {
            bIndex = bMatch.group(2) != null ? Integer.parseInt(bMatch.group(2)) : 0;
        } catch (Exception ignored) {}
        return Integer.compare(aIndex, bIndex);
    }

    private static boolean isEnoent(Exception e) {
        return e instanceof NoSuchFileException
                || (e.getMessage() != null && e.getMessage().contains("ENOENT"));
    }
}
