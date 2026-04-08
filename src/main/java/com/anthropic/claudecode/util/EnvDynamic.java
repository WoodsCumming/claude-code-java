package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dynamic environment detection utilities.
 * Translated from src/utils/envDynamic.ts
 */
@Slf4j
public class EnvDynamic {



    private static volatile Boolean isDockerCached;
    private static volatile Boolean isJetBrainsCached;

    /**
     * Check if running inside Docker.
     * Translated from getIsDocker() in envDynamic.ts
     */
    public static CompletableFuture<Boolean> isDocker() {
        if (isDockerCached != null) {
            return CompletableFuture.completedFuture(isDockerCached);
        }

        if (!PlatformUtils.isLinux()) {
            isDockerCached = false;
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            boolean result = new File("/.dockerenv").exists();
            isDockerCached = result;
            return result;
        });
    }

    /**
     * Check if running in a bubblewrap sandbox.
     * Translated from getIsBubblewrapSandbox() in envDynamic.ts
     */
    public static boolean isBubblewrapSandbox() {
        return PlatformUtils.isLinux()
            && EnvUtils.isEnvTruthy(System.getenv("CLAUDE_CODE_BUBBLEWRAP"));
    }

    /**
     * Initialize JetBrains detection.
     * Translated from initJetBrainsDetection() in envDynamic.ts
     */
    public static void initJetBrainsDetection() {
        // Check for JetBrains IDE environment variables
        String[] jetbrainsVars = {
            "IDEA_INITIAL_DIRECTORY",
            "JETBRAINS_IDE",
            "CLION_IDE",
            "WEBIDE_INITIAL_DIRECTORY"
        };

        for (String var : jetbrainsVars) {
            if (System.getenv(var) != null) {
                isJetBrainsCached = true;
                return;
            }
        }
        isJetBrainsCached = false;
    }

    /**
     * Check if running inside JetBrains IDE.
     */
    public static boolean isJetBrainsIde() {
        if (isJetBrainsCached == null) {
            initJetBrainsDetection();
        }
        return Boolean.TRUE.equals(isJetBrainsCached);
    }

    private EnvDynamic() {}
}
