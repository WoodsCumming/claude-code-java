package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Command registration descriptor for the /theme command.
 * Translated from src/commands/theme/index.ts
 *
 * In TypeScript this file is a thin registry entry that lazily loads the
 * React/JSX component responsible for the interactive theme picker.  In the
 * Java service layer the interactive UI is handled by the frontend; this
 * service exposes the command metadata and any server-side logic needed.
 */
@Slf4j
@Service
public class ThemeCommandService {



    // -------------------------------------------------------------------------
    // Command metadata  (mirrors the Command object in TypeScript)
    // -------------------------------------------------------------------------

    public static final String COMMAND_NAME = "theme";
    public static final String COMMAND_TYPE = "local-jsx";
    public static final String COMMAND_DESCRIPTION = "Change the theme";
    public static final boolean IS_HIDDEN = false;

    // -------------------------------------------------------------------------
    // Theme definitions
    // -------------------------------------------------------------------------

    /**
     * Available colour themes.
     * Extend this enum as new themes are added upstream.
     */
    public enum Theme {
        DARK("dark"),
        LIGHT("light"),
        SYSTEM("system");

        private final String value;

        Theme(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Theme fromValue(String value) {
            for (Theme t : values()) {
                if (t.value.equalsIgnoreCase(value)) return t;
            }
            throw new IllegalArgumentException("Unknown theme: " + value);
        }
    }

    // -------------------------------------------------------------------------
    // Command descriptor
    // -------------------------------------------------------------------------

    /**
     * Returns the command descriptor used by the command registry.
     * Mirrors the {@code Command} object exported from {@code index.ts}.
     */
    public CommandDescriptor getDescriptor() {
        return new CommandDescriptor(
                COMMAND_NAME,
                COMMAND_TYPE,
                COMMAND_DESCRIPTION,
                IS_HIDDEN,
                null   // availability — not restricted
        );
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    /**
     * Immutable descriptor for a registered command.
     * Mirrors the {@code Command} type from {@code commands.ts}.
     */
    public record CommandDescriptor(
            String name,
            String type,
            String description,
            boolean isHidden,
            String[] availability
    ) {}
}
