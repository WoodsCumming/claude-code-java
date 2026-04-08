package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Internal logging service.
 * Translated from src/services/internalLogging.ts
 *
 * Provides internal logging utilities for Anthropic-internal (ant) users only.
 * Logs tool permission context, Kubernetes namespace, and container ID.
 * All methods are no-ops for non-ant users.
 */
@Slf4j
@Service
public class InternalLoggingService {



    private static final String NAMESPACE_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";
    private static final String MOUNTINFO_PATH = "/proc/self/mountinfo";
    private static final Pattern CONTAINER_ID_PATTERN =
        Pattern.compile("(?:/docker/containers/|/sandboxes/)([0-9a-f]{64})");

    private static final String NAMESPACE_NOT_FOUND = "namespace not found";
    private static final String CONTAINER_ID_NOT_FOUND = "container ID not found";
    private static final String CONTAINER_ID_NOT_FOUND_IN_MOUNTINFO = "container ID not found in mountinfo";

    /**
     * Memoized Kubernetes namespace — null means not yet resolved.
     * Translated from the memoize(getKubernetesNamespace) pattern in internalLogging.ts
     */
    private final AtomicReference<String> cachedK8sNamespace = new AtomicReference<>();

    /**
     * Memoized OCI container ID — null means not yet resolved.
     * Translated from the memoize(getContainerId) pattern in internalLogging.ts
     */
    private final AtomicReference<String> cachedContainerId = new AtomicReference<>();

    private final AnalyticsService analyticsService;

    @Autowired
    public InternalLoggingService(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    /**
     * Get the current Kubernetes namespace.
     *
     * Returns null on laptops/local development.
     * Returns "default" for devboxes in the default namespace.
     * Returns NAMESPACE_NOT_FOUND if the namespace file cannot be read.
     * Result is memoized — computed at most once per JVM instance.
     * Translated from getKubernetesNamespace() in internalLogging.ts
     */
    public CompletableFuture<String> getKubernetesNamespace() {
        if (!isAntUser()) {
            return CompletableFuture.completedFuture(null);
        }

        String cached = cachedK8sNamespace.get();
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            String result;
            try {
                result = Files.readString(Paths.get(NAMESPACE_PATH)).trim();
            } catch (Exception e) {
                result = NAMESPACE_NOT_FOUND;
            }
            cachedK8sNamespace.compareAndSet(null, result);
            return cachedK8sNamespace.get();
        });
    }

    /**
     * Get the OCI container ID from within a running container.
     *
     * Reads /proc/self/mountinfo and extracts the 64-char hex container ID.
     * Supports both Docker (/docker/containers/...) and containerd/CRI-O (/sandboxes/...).
     * Returns CONTAINER_ID_NOT_FOUND if not running inside a container.
     * Result is memoized — computed at most once per JVM instance.
     * Translated from getContainerId() in internalLogging.ts
     */
    public CompletableFuture<String> getContainerId() {
        if (!isAntUser()) {
            return CompletableFuture.completedFuture(null);
        }

        String cached = cachedContainerId.get();
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> {
            String result;
            try {
                String mountinfo = Files.readString(Paths.get(MOUNTINFO_PATH)).trim();
                String found = null;
                for (String line : mountinfo.split("\n")) {
                    Matcher m = CONTAINER_ID_PATTERN.matcher(line);
                    if (m.find()) {
                        found = m.group(1);
                        break;
                    }
                }
                result = found != null ? found : CONTAINER_ID_NOT_FOUND_IN_MOUNTINFO;
            } catch (Exception e) {
                result = CONTAINER_ID_NOT_FOUND;
            }
            cachedContainerId.compareAndSet(null, result);
            return cachedContainerId.get();
        });
    }

    /**
     * Logs an event with the current namespace and tool permission context.
     * No-op for non-ant users.
     * Translated from logPermissionContextForAnts() in internalLogging.ts
     *
     * @param toolPermissionContextJson  JSON-serialized ToolPermissionContext, or null
     * @param moment                     "summary" or "initialization"
     */
    public CompletableFuture<Void> logPermissionContextForAnts(
            String toolPermissionContextJson,
            String moment) {

        if (!isAntUser()) {
            return CompletableFuture.completedFuture(null);
        }

        return getKubernetesNamespace()
            .thenCompose(namespace ->
                getContainerId().thenAccept(containerId -> {
                    java.util.Map<String, Object> props = new java.util.HashMap<>();
                    props.put("moment", moment);
                    props.put("namespace", namespace);
                    props.put("toolPermissionContext", toolPermissionContextJson != null
                        ? toolPermissionContextJson : "null");
                    props.put("containerId", containerId);
                    analyticsService.logEvent("tengu_internal_record_permission_context", props);
                })
            );
    }

    /**
     * Returns true if the current user is an Anthropic-internal ant user.
     */
    private boolean isAntUser() {
        return "ant".equals(System.getenv("USER_TYPE"));
    }
}
