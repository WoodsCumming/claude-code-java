package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PermissionMode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Permission mode transition service.
 * Translated from src/utils/permissions/getNextPermissionMode.ts
 *
 * Handles cycling through permission modes.
 */
@Slf4j
@Service
public class PermissionModeTransitionService {



    /**
     * Get the next permission mode in the cycle.
     * Translated from getNextPermissionMode() in getNextPermissionMode.ts
     */
    public PermissionMode getNextPermissionMode(PermissionMode current, boolean autoModeAvailable) {
        if (current == null) return PermissionMode.DEFAULT;

        return switch (current) {
            case DEFAULT -> PermissionMode.ACCEPT_EDITS;
            case ACCEPT_EDITS -> autoModeAvailable ? PermissionMode.AUTO : PermissionMode.DEFAULT;
            case AUTO -> PermissionMode.DEFAULT;
            case PLAN -> PermissionMode.DEFAULT;
            default -> PermissionMode.DEFAULT;
        };
    }

    /**
     * Get the previous permission mode.
     */
    public PermissionMode getPreviousPermissionMode(PermissionMode current, boolean autoModeAvailable) {
        if (current == null) return PermissionMode.DEFAULT;

        return switch (current) {
            case DEFAULT -> autoModeAvailable ? PermissionMode.AUTO : PermissionMode.ACCEPT_EDITS;
            case ACCEPT_EDITS -> PermissionMode.DEFAULT;
            case AUTO -> PermissionMode.ACCEPT_EDITS;
            default -> PermissionMode.DEFAULT;
        };
    }
}
