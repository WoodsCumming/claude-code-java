package com.anthropic.claudecode.model;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;

/**
 * Permission modes for tool execution.
 * Translated from src/types/permissions.ts
 */
public enum PermissionMode {
    /** Default mode - asks for permission on sensitive operations */
    DEFAULT("default"),

    /** Accept edits mode - auto-accepts file edits */
    ACCEPT_EDITS("acceptEdits"),

    /** Bypass permissions mode - skips all permission checks */
    BYPASS_PERMISSIONS("bypassPermissions"),

    /** Don't ask mode - auto-accepts all operations */
    DONT_ASK("dontAsk"),

    /** Plan mode - only plans, doesn't execute */
    PLAN("plan"),

    /** Auto mode - uses classifier to auto-approve/deny (internal) */
    AUTO("auto"),

    /** Bubble mode - bubbles permission up to parent (internal) */
    BUBBLE("bubble");

    private final String value;

    PermissionMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    /** External modes that can be set by users */
    public static final List<PermissionMode> EXTERNAL_MODES = Arrays.asList(
        DEFAULT, ACCEPT_EDITS, BYPASS_PERMISSIONS, DONT_ASK, PLAN
    );

    public static PermissionMode fromValue(String value) {
        for (PermissionMode mode : values()) {
            if (mode.value.equals(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown permission mode: " + value);
    }

    public boolean isExternal() {
        return EXTERNAL_MODES.contains(this);
    }
}
