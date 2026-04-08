package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Bundled skills initialization service.
 * Translated from src/skills/bundled/index.ts
 *
 * Initializes all bundled skills that ship with the CLI.
 */
@Slf4j
@Service
public class BundledSkillsInitService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BundledSkillsInitService.class);


    private final BundledSkillsService bundledSkillsService;

    @Autowired
    public BundledSkillsInitService(BundledSkillsService bundledSkillsService) {
        this.bundledSkillsService = bundledSkillsService;
    }

    /**
     * Initialize all bundled skills.
     * Translated from initBundledSkills() in index.ts
     */
    public void initBundledSkills() {
        registerUpdateConfigSkill();
        registerKeybindingsSkill();
        registerVerifySkill();
        registerDebugSkill();
        registerLoremIpsumSkill();
        registerSkillifySkill();
        registerRememberSkill();
        registerSimplifySkill();
        registerBatchSkill();
        registerStuckSkill();

        log.debug("Bundled skills initialized");
    }

    private void registerUpdateConfigSkill() {
        bundledSkillsService.registerBundledSkill(
            BundledSkillsService.BundledSkillDefinition.builder()
                .name("update-config")
                .description("Configure the Claude Code harness via settings.json")
                .userInvocable(false)
                .build()
        );
    }

    private void registerKeybindingsSkill() {
        bundledSkillsService.registerBundledSkill(
            BundledSkillsService.BundledSkillDefinition.builder()
                .name("keybindings-help")
                .description("Customize keyboard shortcuts and keybindings")
                .userInvocable(false)
                .build()
        );
    }

    private void registerVerifySkill() {
        bundledSkillsService.registerBundledSkill(
            BundledSkillsService.BundledSkillDefinition.builder()
                .name("verify")
                .description("Verify code changes")
                .userInvocable(false)
                .build()
        );
    }

    private void registerDebugSkill() {
        bundledSkillsService.registerBundledSkill(
            BundledSkillsService.BundledSkillDefinition.builder()
                .name("debug")
                .description("Debug issues")
                .userInvocable(false)
                .build()
        );
    }

    private void registerLoremIpsumSkill() {
        bundledSkillsService.registerBundledSkill(
            BundledSkillsService.BundledSkillDefinition.builder()
                .name("lorem-ipsum")
                .description("Generate lorem ipsum placeholder text")
                .userInvocable(true)
                .build()
        );
    }

    private void registerSkillifySkill() {
        bundledSkillsService.registerBundledSkill(
            BundledSkillsService.BundledSkillDefinition.builder()
                .name("skillify")
                .description("Convert a prompt into a reusable skill")
                .userInvocable(false)
                .build()
        );
    }

    private void registerRememberSkill() {
        bundledSkillsService.registerBundledSkill(
            BundledSkillsService.BundledSkillDefinition.builder()
                .name("remember")
                .description("Save information to memory")
                .userInvocable(false)
                .build()
        );
    }

    private void registerSimplifySkill() {
        bundledSkillsService.registerBundledSkill(
            BundledSkillsService.BundledSkillDefinition.builder()
                .name("simplify")
                .description("Review and simplify code")
                .userInvocable(false)
                .build()
        );
    }

    private void registerBatchSkill() {
        bundledSkillsService.registerBundledSkill(
            BundledSkillsService.BundledSkillDefinition.builder()
                .name("batch")
                .description("Run multiple commands in batch")
                .userInvocable(false)
                .build()
        );
    }

    private void registerStuckSkill() {
        bundledSkillsService.registerBundledSkill(
            BundledSkillsService.BundledSkillDefinition.builder()
                .name("stuck")
                .description("Get help when stuck")
                .userInvocable(false)
                .build()
        );
    }
}
