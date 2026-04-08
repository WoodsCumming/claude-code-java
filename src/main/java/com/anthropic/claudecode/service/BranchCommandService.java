package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Branch command service — models the 'branch' slash-command metadata and
 * dispatches to the branch implementation.
 * Translated from src/commands/branch/index.ts
 *
 * <p>The TypeScript source registers a lazy-loaded local-jsx command with the
 * name "branch". When the {@code FORK_SUBAGENT} feature flag is disabled it
 * also exposes a "fork" alias. The command creates a branch of the current
 * conversation at the current point.
 */
@Slf4j
@Service
public class BranchCommandService {



    /** Command name. Translated from: name: 'branch' in index.ts */
    public static final String NAME = "branch";

    /** Command description. Translated from: description in index.ts */
    public static final String DESCRIPTION =
        "Create a branch of the current conversation at this point";

    /** Argument hint. Translated from: argumentHint: '[name]' in index.ts */
    public static final String ARGUMENT_HINT = "[name]";

    /** Command type. Translated from: type: 'local-jsx' in index.ts */
    public static final String TYPE = "local-jsx";

    private final BranchService branchService;
    private final ConfigService configService;

    @Autowired
    public BranchCommandService(BranchService branchService,
                                 ConfigService configService) {
        this.branchService = branchService;
        this.configService = configService;
    }

    /**
     * Compute the effective aliases for the branch command.
     * Translated from: aliases: feature('FORK_SUBAGENT') ? [] : ['fork'] in index.ts
     *
     * <p>When the FORK_SUBAGENT feature is disabled the command is also
     * reachable as /fork (only when /fork doesn't exist as its own command).
     *
     * @return list of alias strings (may be empty)
     */
    public List<String> getAliases() {
        boolean forkSubagentEnabled = isForkSubagentEnabled();
        if (forkSubagentEnabled) {
            return List.of();
        }
        return List.of("fork");
    }

    /**
     * Execute the branch command, optionally naming the new branch.
     * Translated from the lazy load of branch.js in index.ts
     *
     * @param branchName optional name for the new conversation branch
     */
    public void branch(String branchName) {
        log.info("Creating conversation branch: {}", branchName != null ? branchName : "(unnamed)");
        branchService.createBranch(branchName);
    }

    /**
     * Check whether the FORK_SUBAGENT feature flag is enabled.
     * Translated from: feature('FORK_SUBAGENT') in index.ts (bun:bundle feature flag)
     */
    private boolean isForkSubagentEnabled() {
        String val = System.getenv("FORK_SUBAGENT");
        if (val == null || val.isBlank()) return false;
        return val.equalsIgnoreCase("true") || val.equals("1") || val.equalsIgnoreCase("yes");
    }
}
