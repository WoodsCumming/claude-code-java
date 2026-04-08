package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.WorktreeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Worktree command for managing git worktrees.
 * Translated from src/commands/worktree/index.ts
 *
 * The TypeScript worktree/index.ts did not exist at translation time.
 * This command is synthesised from the worktree utility (src/utils/worktree.ts →
 * WorktreeService) and the established project command pattern (EnterWorktree /
 * ExitWorktree tool behaviour in the Claude Code CLI).
 */
@Slf4j
@Component
@Command(
    name = "worktree",
    description = "Manage git worktrees for isolated parallel development",
    subcommands = {
        WorktreeCommand.CreateSubCommand.class,
        WorktreeCommand.RemoveSubCommand.class,
        WorktreeCommand.ListSubCommand.class,
    }
)
public class WorktreeCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorktreeCommand.class);


    @Autowired
    private WorktreeService worktreeService;

    /** Default: show active session state when no sub-command is given. */
    @Override
    public Integer call() {
        WorktreeService.WorktreeSession session = worktreeService.getCurrentWorktreeSession();
        if (session != null) {
            System.out.println("Active worktree session: " + session);
        } else {
            System.out.println("No active worktree session.");
        }
        System.out.println("Use 'worktree create <repoRoot> <slug>', 'worktree list', or 'worktree remove <repoRoot> <path>'.");
        return 0;
    }

    // -------------------------------------------------------------------------
    // Sub-commands
    // -------------------------------------------------------------------------

    /**
     * 'worktree create' — creates or resumes a git worktree.
     * Translated from getOrCreateWorktree() in worktree.ts
     */
    @Component
    @Command(name = "create", description = "Create (or resume) a git worktree for the given slug")
    public static class CreateSubCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Repository root path")
        private String repoRoot;

        @Parameters(index = "1", description = "Worktree slug (letters, digits, dots, underscores, dashes; max 64 chars)")
        private String slug;

        @Autowired
        private WorktreeService worktreeService;

        @Override
        public Integer call() {
            try {
                worktreeService.validateWorktreeSlug(slug);
                WorktreeService.WorktreeCreateResult result =
                    worktreeService.getOrCreateWorktree(Path.of(repoRoot), slug).join();
                String action = result.existed() ? "Resumed" : "Created";
                System.out.printf("%s worktree '%s'%n  Path:   %s%n  Branch: %s%n  HEAD:   %s%n",
                    action, slug,
                    result.worktreePath(),
                    result.worktreeBranch(),
                    result.headCommit());
                return 0;
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (Exception e) {
                log.error("Failed to create worktree '{}'", slug, e);
                System.err.println("Failed to create worktree: " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * 'worktree remove' — removes a git worktree.
     * Translated from removeAgentWorktree() in worktree.ts
     */
    @Component
    @Command(name = "remove", aliases = {"rm", "delete"}, description = "Remove a git worktree")
    public static class RemoveSubCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Repository root path")
        private String repoRoot;

        @Parameters(index = "1", description = "Absolute path to the worktree directory to remove")
        private String worktreePath;

        @Autowired
        private WorktreeService worktreeService;

        @Override
        public Integer call() {
            try {
                worktreeService.removeWorktree(Path.of(repoRoot), Path.of(worktreePath)).join();
                System.out.println("Worktree removed: " + worktreePath);
                return 0;
            } catch (Exception e) {
                log.error("Failed to remove worktree '{}'", worktreePath, e);
                System.err.println("Failed to remove worktree: " + e.getMessage());
                return 1;
            }
        }
    }

    /**
     * 'worktree list' — shows the active worktree session.
     * Translated from getCurrentWorktreeSession() in worktree.ts
     */
    @Component
    @Command(name = "list", aliases = {"ls"}, description = "Show the active worktree session")
    public static class ListSubCommand implements Callable<Integer> {

        @Autowired
        private WorktreeService worktreeService;

        @Override
        public Integer call() {
            WorktreeService.WorktreeSession session = worktreeService.getCurrentWorktreeSession();
            if (session == null) {
                System.out.println("No active worktree session.");
            } else {
                System.out.println("Active worktree session:");
                System.out.println("  " + session);
            }
            return 0;
        }
    }
}
