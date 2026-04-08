package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Plugin telemetry helpers and skill-loaded event logging.
 *
 * Translated from:
 *   - src/utils/telemetry/pluginTelemetry.ts  (plugin field builders, hash, scope)
 *   - src/utils/telemetry/skillLoadedEvent.ts  (logSkillsLoaded event)
 *
 * Implements the twin-column privacy pattern: every user-defined-name field
 * emits both a raw value (routed to PII-tagged _PROTO_* BQ columns) and a
 * redacted twin (real name iff marketplace is on allowlist, else "third-party").
 *
 * plugin_id_hash provides an opaque per-plugin aggregation key with no privacy
 * dependency — sha256(name@marketplace + FIXED_SALT) truncated to 16 chars.
 *
 * Skill-loaded events:
 *   logSkillsLoaded() fires a 'tengu_skill_loaded' analytics event for each
 *   prompt-type skill available at session startup. This enables analytics on
 *   which skills are available across sessions.
 */
@Slf4j
public class PluginTelemetry {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginTelemetry.class);


    private static final String BUILTIN_MARKETPLACE_NAME = "builtin";

    /**
     * Fixed salt for plugin_id_hash.
     * Customers can compute the same hash on their known plugin names to
     * reverse-match their own telemetry.
     */
    private static final String PLUGIN_ID_HASH_SALT = "claude-plugin-telemetry-v1";

    // =========================================================================
    // Types
    // =========================================================================

    /**
     * 4-value scope enum for plugin origin.
     * Translated from TelemetryPluginScope in pluginTelemetry.ts.
     */
    public enum TelemetryPluginScope {
        OFFICIAL("official"),
        ORG("org"),
        USER_LOCAL("user-local"),
        DEFAULT_BUNDLE("default-bundle");

        private final String value;
        TelemetryPluginScope(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /** How a plugin arrived in the session. Translated from EnabledVia in pluginTelemetry.ts. */
    public enum EnabledVia {
        USER_INSTALL("user-install"),
        ORG_POLICY("org-policy"),
        DEFAULT_ENABLE("default-enable"),
        SEED_MOUNT("seed-mount");

        private final String value;
        EnabledVia(String value) { this.value = value; }
    }

    /** How a skill/command invocation was triggered. Translated from InvocationTrigger. */
    public enum InvocationTrigger {
        USER_SLASH("user-slash"),
        CLAUDE_PROACTIVE("claude-proactive"),
        NESTED_SKILL("nested-skill");

        private final String value;
        InvocationTrigger(String value) { this.value = value; }
    }

    /** Where a skill invocation executes. Translated from SkillExecutionContext. */
    public enum SkillExecutionContext {
        FORK("fork"),
        INLINE("inline"),
        REMOTE("remote");

        private final String value;
        SkillExecutionContext(String value) { this.value = value; }
    }

    /** How a plugin install was initiated. Translated from InstallSource. */
    public enum InstallSource {
        CLI_EXPLICIT("cli-explicit"),
        UI_DISCOVER("ui-discover"),
        UI_SUGGESTION("ui-suggestion"),
        DEEP_LINK("deep-link");

        private final String value;
        InstallSource(String value) { this.value = value; }
    }

    /**
     * Bounded-cardinality error category for plugin operation failures.
     * Translated from PluginCommandErrorCategory in pluginTelemetry.ts.
     */
    public enum PluginCommandErrorCategory {
        NETWORK("network"),
        NOT_FOUND("not-found"),
        PERMISSION("permission"),
        VALIDATION("validation"),
        UNKNOWN("unknown");

        private final String value;
        PluginCommandErrorCategory(String value) { this.value = value; }
    }

    /**
     * Common plugin telemetry fields.
     * Translated from the return type of buildPluginTelemetryFields() in pluginTelemetry.ts.
     */
    public record PluginTelemetryFields(
        String pluginIdHash,
        String pluginScope,
        String pluginNameRedacted,
        String marketplaceNameRedacted,
        boolean isOfficialPlugin
    ) {}

    /**
     * A single skill available at session startup.
     * Mirrors the fields read from SkillCommand in skillLoadedEvent.ts.
     */
    public record SkillInfo(
        /** Skill name (PII — goes to _PROTO_skill_name BQ column). */
        String name,
        /** Source of the skill definition file. */
        String source,
        /** Where the skill was loaded from (e.g. "project", "user"). */
        String loadedFrom,
        /** Optional skill kind (e.g. "prompt"). */
        String kind
    ) {}

    // =========================================================================
    // Hash computation
    // =========================================================================

    /**
     * Opaque per-plugin aggregation key (16-char SHA-256 prefix).
     * Translated from hashPluginId() in pluginTelemetry.ts.
     */
    public static String hashPluginId(String name, String marketplace) {
        try {
            String key = marketplace != null
                    ? name + "@" + marketplace.toLowerCase()
                    : name;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(
                    (key + PLUGIN_ID_HASH_SALT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            return "unknown";
        }
    }

    // =========================================================================
    // Scope determination
    // =========================================================================

    /**
     * Determines the telemetry plugin scope based on marketplace and managed names.
     * Translated from getTelemetryPluginScope() in pluginTelemetry.ts.
     */
    public static TelemetryPluginScope getTelemetryPluginScope(
            String name, String marketplace, Set<String> managedNames) {
        if (BUILTIN_MARKETPLACE_NAME.equals(marketplace)) return TelemetryPluginScope.DEFAULT_BUNDLE;
        if (isOfficialMarketplaceName(marketplace))       return TelemetryPluginScope.OFFICIAL;
        if (managedNames != null && managedNames.contains(name)) return TelemetryPluginScope.ORG;
        return TelemetryPluginScope.USER_LOCAL;
    }

    private static boolean isOfficialMarketplaceName(String marketplace) {
        if (marketplace == null) return false;
        return "anthropic".equals(marketplace) || "claude".equals(marketplace);
    }

    // =========================================================================
    // Field builders
    // =========================================================================

    /**
     * Common plugin telemetry fields keyed off name@marketplace.
     * Translated from buildPluginTelemetryFields() in pluginTelemetry.ts.
     */
    public static PluginTelemetryFields buildPluginTelemetryFields(
            String name, String marketplace, Set<String> managedNames) {
        TelemetryPluginScope scope = getTelemetryPluginScope(name, marketplace, managedNames);
        boolean isAnthropicControlled = scope == TelemetryPluginScope.OFFICIAL
                || scope == TelemetryPluginScope.DEFAULT_BUNDLE;
        return new PluginTelemetryFields(
                hashPluginId(name, marketplace),
                scope.getValue(),
                isAnthropicControlled ? name : "third-party",
                (isAnthropicControlled && marketplace != null) ? marketplace : "third-party",
                isAnthropicControlled);
    }

    /** Convenience overload with managedNames=null. */
    public static PluginTelemetryFields buildPluginTelemetryFields(
            String name, String marketplace) {
        return buildPluginTelemetryFields(name, marketplace, null);
    }

    /**
     * Per-invocation field builder (managedNames=null).
     * Translated from buildPluginCommandTelemetryFields() in pluginTelemetry.ts.
     */
    public static PluginTelemetryFields buildPluginCommandTelemetryFields(
            String pluginName, String repository) {
        String marketplace = parseMarketplace(repository);
        return buildPluginTelemetryFields(pluginName, marketplace, null);
    }

    // =========================================================================
    // Skill-loaded event  (skillLoadedEvent.ts)
    // =========================================================================

    /**
     * Log a 'tengu_skill_loaded' event for each skill in the provided list.
     *
     * This enables analytics on which skills are available across sessions.
     * Only skills with type "prompt" generate events.
     *
     * Translated from logSkillsLoaded() in skillLoadedEvent.ts.
     *
     * @param skills              list of available skills (prompt-type only)
     * @param skillBudget         character budget for skill content
     * @param analyticsEventSink  callback that logs an analytics event by name+metadata
     */
    public static void logSkillsLoaded(
            List<SkillInfo> skills,
            int skillBudget,
            AnalyticsEventSink analyticsEventSink) {
        if (skills == null || skills.isEmpty()) return;

        for (SkillInfo skill : skills) {
            Map<String, Object> eventMetadata = new LinkedHashMap<>();
            // _PROTO_skill_name routes to the privileged skill_name BQ column.
            eventMetadata.put("_PROTO_skill_name", skill.name());
            eventMetadata.put("skill_source",      skill.source());
            eventMetadata.put("skill_loaded_from", skill.loadedFrom());
            eventMetadata.put("skill_budget",      skillBudget);
            if (skill.kind() != null) {
                eventMetadata.put("skill_kind", skill.kind());
            }

            try {
                analyticsEventSink.logEvent("tengu_skill_loaded", eventMetadata);
            } catch (Exception e) {
                log.warn("[PluginTelemetry] Failed to log tengu_skill_loaded for {}: {}",
                        skill.name(), e.getMessage());
            }
        }
    }

    /**
     * Async variant of logSkillsLoaded().
     * Translated from the async nature of logSkillsLoaded() in skillLoadedEvent.ts.
     */
    public static CompletableFuture<Void> logSkillsLoadedAsync(
            List<SkillInfo> skills,
            int skillBudget,
            AnalyticsEventSink analyticsEventSink) {
        return CompletableFuture.runAsync(() ->
                logSkillsLoaded(skills, skillBudget, analyticsEventSink));
    }

    /**
     * Functional interface for analytics event emission.
     * Decouples PluginTelemetry from the concrete AnalyticsService bean.
     */
    @FunctionalInterface
    public interface AnalyticsEventSink {
        void logEvent(String eventName, Map<String, Object> metadata);
    }

    // =========================================================================
    // Error classification
    // =========================================================================

    /**
     * Maps a free-form error message to a stable category.
     * Translated from classifyPluginCommandError() in pluginTelemetry.ts.
     */
    public static PluginCommandErrorCategory classifyPluginCommandError(Throwable error) {
        String msg = error != null
                ? (error.getMessage() != null ? error.getMessage() : error.toString())
                : "unknown";
        return classifyPluginCommandError(msg);
    }

    public static PluginCommandErrorCategory classifyPluginCommandError(String msg) {
        if (msg == null) return PluginCommandErrorCategory.UNKNOWN;
        String lower = msg.toLowerCase();
        if (lower.matches(".*enotfound.*|.*econnrefused.*|.*eai_again.*|.*etimedout.*"
                + "|.*econnreset.*|.*network.*|.*could not resolve.*"
                + "|.*connection refused.*|.*timed out.*")) {
            return PluginCommandErrorCategory.NETWORK;
        }
        if (lower.matches(".*\\b404\\b.*|.*not found.*|.*does not exist.*|.*no such plugin.*")) {
            return PluginCommandErrorCategory.NOT_FOUND;
        }
        if (lower.matches(".*\\b40[13]\\b.*|.*eacces.*|.*eperm.*|.*permission denied.*|.*unauthorized.*")) {
            return PluginCommandErrorCategory.PERMISSION;
        }
        if (lower.matches(".*invalid.*|.*malformed.*|.*schema.*|.*validation.*|.*parse error.*")) {
            return PluginCommandErrorCategory.VALIDATION;
        }
        return PluginCommandErrorCategory.UNKNOWN;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String parseMarketplace(String repository) {
        if (repository == null) return null;
        int atIdx = repository.lastIndexOf('@');
        return atIdx >= 0 ? repository.substring(atIdx + 1) : null;
    }

    private PluginTelemetry() {}
}
