package com.anthropic.claudecode.model;

import lombok.Builder;
import lombok.Value;

/**
 * Color theme configuration.
 * Translated from src/utils/theme.ts
 */
@Value
@Builder
public class Theme {

    // Main colors
    String claude;
    String permission;
    String planMode;
    String text;
    String inverseText;
    String inactive;
    String subtle;
    String suggestion;
    String background;

    // Semantic colors
    String success;
    String error;
    String warning;

    // Diff colors
    String diffAdded;
    String diffRemoved;

    // Default dark theme
    public static Theme darkTheme() {
        return Theme.builder()
            .claude("#D97706") // amber
            .permission("#8B5CF6") // violet
            .planMode("#3B82F6") // blue
            .text("#F9FAFB")
            .inverseText("#111827")
            .inactive("#6B7280")
            .subtle("#9CA3AF")
            .suggestion("#60A5FA")
            .background("#111827")
            .success("#10B981")
            .error("#EF4444")
            .warning("#F59E0B")
            .diffAdded("#065F46")
            .diffRemoved("#7F1D1D")
            .build();
    }

    // Default light theme
    public static Theme lightTheme() {
        return Theme.builder()
            .claude("#B45309") // amber dark
            .permission("#7C3AED") // violet dark
            .planMode("#2563EB") // blue dark
            .text("#111827")
            .inverseText("#F9FAFB")
            .inactive("#9CA3AF")
            .subtle("#6B7280")
            .suggestion("#2563EB")
            .background("#FFFFFF")
            .success("#059669")
            .error("#DC2626")
            .warning("#D97706")
            .diffAdded("#DCFCE7")
            .diffRemoved("#FEE2E2")
            .build();
    }
}
