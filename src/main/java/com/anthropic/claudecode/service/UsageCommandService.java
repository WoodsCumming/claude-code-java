package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Command registration descriptor for the /usage command.
 * Translated from src/commands/usage/index.ts
 *
 * The /usage command shows plan usage limits and is only available on
 * the {@code claude-ai} platform.  The interactive UI is handled by the
 * React/JSX component loaded lazily in the TypeScript source; in Java
 * this service exposes the command metadata and delegates data retrieval
 * to {@link UsageService}.
 */
@Slf4j
@Service
public class UsageCommandService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UsageCommandService.class);


    // -------------------------------------------------------------------------
    // Command metadata  (mirrors the Command object exported from index.ts)
    // -------------------------------------------------------------------------

    public static final String COMMAND_NAME = "usage";
    public static final String COMMAND_TYPE = "local-jsx";
    public static final String COMMAND_DESCRIPTION = "Show plan usage limits";

    /** Platforms on which this command is available. */
    public static final String[] AVAILABILITY = {"claude-ai"};

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final UsageService usageService;

    public UsageCommandService(UsageService usageService) {
        this.usageService = usageService;
    }

    // -------------------------------------------------------------------------
    // Command descriptor
    // -------------------------------------------------------------------------

    /**
     * Returns the command descriptor used by the command registry.
     * Mirrors the anonymous {@code Command} object exported from {@code index.ts}.
     */
    public CommandDescriptor getDescriptor() {
        return new CommandDescriptor(
                COMMAND_NAME,
                COMMAND_TYPE,
                COMMAND_DESCRIPTION,
                AVAILABILITY
        );
    }

    // -------------------------------------------------------------------------
    // Data access
    // -------------------------------------------------------------------------

    /**
     * Fetch utilization data for display by the UI layer.
     * Delegates to {@link UsageService#fetchUtilization()}.
     */
    public java.util.concurrent.CompletableFuture<UsageService.Utilization> fetchUtilization() {
        return usageService.fetchUtilization();
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
            String[] availability
    ) {}
}
