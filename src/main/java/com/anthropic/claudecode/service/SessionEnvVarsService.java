package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped environment variables service.
 * Translated from src/utils/sessionEnvVars.ts
 *
 * Manages environment variables set via the /env command.
 * Applied only to spawned child processes (via bash provider env overrides),
 * not to the REPL process itself.
 */
@Slf4j
@Service
public class SessionEnvVarsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionEnvVarsService.class);


    private final Map<String, String> sessionEnvVars = new ConcurrentHashMap<>();

    /**
     * Return an unmodifiable view of all session environment variables.
     * Translated from getSessionEnvVars() in sessionEnvVars.ts
     */
    public Map<String, String> getSessionEnvVars() {
        return Collections.unmodifiableMap(sessionEnvVars);
    }

    /**
     * Set a session environment variable.
     * Translated from setSessionEnvVar() in sessionEnvVars.ts
     */
    public void setSessionEnvVar(String name, String value) {
        sessionEnvVars.put(name, value);
        log.debug("Session env var set: {}={}", name, value);
    }

    /**
     * Remove a session environment variable.
     * Translated from deleteSessionEnvVar() in sessionEnvVars.ts
     */
    public void deleteSessionEnvVar(String name) {
        sessionEnvVars.remove(name);
        log.debug("Session env var deleted: {}", name);
    }

    /**
     * Remove all session environment variables.
     * Translated from clearSessionEnvVars() in sessionEnvVars.ts
     */
    public void clearSessionEnvVars() {
        sessionEnvVars.clear();
        log.debug("Session env vars cleared");
    }
}
