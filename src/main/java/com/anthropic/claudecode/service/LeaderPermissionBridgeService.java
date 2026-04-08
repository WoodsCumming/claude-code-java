package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PermissionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Leader permission bridge service.
 * Translated from src/utils/swarm/leaderPermissionBridge.ts
 *
 * Allows in-process teammates to use the leader's permission dialog.
 */
@Slf4j
@Service
public class LeaderPermissionBridgeService {



    private volatile Consumer<Map<String, Object>> registeredSetter;
    private volatile Consumer<Map<String, Object>> registeredPermissionContextSetter;

    /**
     * Register the leader's tool use confirm queue setter.
     * Translated from registerLeaderToolUseConfirmQueue() in leaderPermissionBridge.ts
     */
    public void registerLeaderToolUseConfirmQueue(Consumer<Map<String, Object>> setter) {
        this.registeredSetter = setter;
    }

    /**
     * Register the leader's permission context setter.
     * Translated from registerLeaderPermissionContextSetter() in leaderPermissionBridge.ts
     */
    public void registerLeaderPermissionContextSetter(Consumer<Map<String, Object>> setter) {
        this.registeredPermissionContextSetter = setter;
    }

    /**
     * Unregister the leader's setters.
     * Translated from unregisterLeaderSetters() in leaderPermissionBridge.ts
     */
    public void unregisterLeaderSetters() {
        this.registeredSetter = null;
        this.registeredPermissionContextSetter = null;
    }

    /**
     * Get the registered setter if available.
     */
    public Optional<Consumer<Map<String, Object>>> getRegisteredSetter() {
        return Optional.ofNullable(registeredSetter);
    }

    /**
     * Check if the bridge is registered.
     */
    public boolean isRegistered() {
        return registeredSetter != null;
    }
}
