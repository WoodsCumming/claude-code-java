package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/**
 * Settings source types.
 * Translated from src/utils/settings/constants.ts
 */
public enum SettingSource {
    USER_SETTINGS("userSettings"),
    PROJECT_SETTINGS("projectSettings"),
    LOCAL_SETTINGS("localSettings"),
    FLAG_SETTINGS("flagSettings"),
    POLICY_SETTINGS("policySettings");

    private final String value;

    SettingSource(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static final List<SettingSource> ALL_SOURCES = List.of(
        USER_SETTINGS, PROJECT_SETTINGS, LOCAL_SETTINGS, FLAG_SETTINGS, POLICY_SETTINGS
    );

    /**
     * Get the display name for a setting source.
     * Translated from getSettingSourceName() in constants.ts
     */
    public String getDisplayName() {
        return switch (this) {
            case USER_SETTINGS -> "user";
            case PROJECT_SETTINGS -> "project";
            case LOCAL_SETTINGS -> "project, gitignored";
            case FLAG_SETTINGS -> "cli flag";
            case POLICY_SETTINGS -> "managed";
        };
    }

    /**
     * Get the short display name.
     * Translated from getSourceDisplayName() in constants.ts
     */
    public String getShortDisplayName() {
        return switch (this) {
            case USER_SETTINGS -> "User";
            case PROJECT_SETTINGS -> "Project";
            case LOCAL_SETTINGS -> "Local";
            case FLAG_SETTINGS -> "Flag";
            case POLICY_SETTINGS -> "Managed";
        };
    }

    public static SettingSource fromValue(String value) {
        for (SettingSource source : values()) {
            if (source.value.equals(value)) return source;
        }
        throw new IllegalArgumentException("Unknown setting source: " + value);
    }
}
