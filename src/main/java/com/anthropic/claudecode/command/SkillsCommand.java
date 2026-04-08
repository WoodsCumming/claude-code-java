package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.BundledSkillsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Skills command — list available skills that can be invoked by the slash-command system.
 *
 * Translated from src/commands/skills/index.ts and src/commands/skills/skills.tsx
 *
 * TypeScript original behaviour:
 *   - Opens a SkillsMenu component (local-jsx) showing all bundled skills
 *   - Each skill entry shows: name, trigger pattern, and description
 *   - The menu is sourced from context.options.commands (the live command registry)
 *
 * Java translation:
 *   - Queries BundledSkillsService for the full list of available skills
 *   - Default: prints a formatted table of skill name + description
 *   - --verbose / -v: additionally prints trigger patterns and usage hints
 */
@Slf4j
@Component
@Command(
    name = "skills",
    description = "List available skills"
)
public class SkillsCommand implements Callable<Integer> {



    /** Print trigger patterns and extended descriptions alongside each skill. */
    @Option(names = {"--verbose", "-v"}, description = "Show detailed information for each skill")
    private boolean verbose;

    private final BundledSkillsService bundledSkillsService;

    @Autowired
    public SkillsCommand(BundledSkillsService bundledSkillsService) {
        this.bundledSkillsService = bundledSkillsService;
    }

    @Override
    public Integer call() {
        List<BundledSkillsService.SkillInfo> skills = bundledSkillsService.getAvailableSkills();

        if (skills.isEmpty()) {
            System.out.println("No skills available.");
            return 0;
        }

        System.out.println("Available skills:");
        System.out.println();

        for (BundledSkillsService.SkillInfo skill : skills) {
            if (verbose) {
                System.out.printf("  %-24s%s%n", skill.getName(), skill.getDescription());
                if (skill.getTriggerPattern() != null && !skill.getTriggerPattern().isBlank()) {
                    System.out.printf("    %-22s%s%n", "Trigger:", skill.getTriggerPattern());
                }
                if (skill.getUsageHint() != null && !skill.getUsageHint().isBlank()) {
                    System.out.printf("    %-22s%s%n", "Usage:", skill.getUsageHint());
                }
                System.out.println();
            } else {
                System.out.printf("  %-24s%s%n", skill.getName(), skill.getDescription());
            }
        }

        if (!verbose) {
            System.out.println();
            System.out.println("Use /skills --verbose for detailed trigger and usage information.");
        }

        return 0;
    }
}
