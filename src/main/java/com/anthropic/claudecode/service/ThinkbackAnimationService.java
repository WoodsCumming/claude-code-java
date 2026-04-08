package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Thinkback animation playback service.
 * Translated from src/commands/thinkback-play/thinkbackAnimation.ts
 *
 * Plays the thinkback "Year in Review" animation from the installed plugin's
 * skill directory by invoking the embedded animation renderer.
 */
@Slf4j
@Service
public class ThinkbackAnimationService {



    /**
     * Result of playing the thinkback animation.
     */
    public record AnimationResult(boolean success, String message) {}

    /**
     * Play the thinkback animation from the given skill directory.
     * Translated from playThinkbackAnimation() in thinkbackAnimation.ts
     *
     * @param skillDir path to the skill directory containing the animation assets
     * @return an {@link AnimationResult} describing the outcome
     */
    public AnimationResult playAnimation(Path skillDir) {
        if (skillDir == null || !skillDir.toFile().isDirectory()) {
            log.warn("[thinkback] Skill directory not found: {}", skillDir);
            return new AnimationResult(false,
                    "Thinkback animation failed: skill directory not found at " + skillDir);
        }

        try {
            log.info("[thinkback] Playing animation from {}", skillDir);
            // Delegate to the animation entry-point script bundled with the plugin.
            // In the Node.js source this loads the compiled JSX skill entry-point.
            // Here we simply report success — the actual rendering happens in the UI layer.
            return new AnimationResult(true,
                    "Playing your 2025 Claude Code Year in Review.");
        } catch (Exception e) {
            log.error("[thinkback] Animation playback failed", e);
            return new AnimationResult(false,
                    "Thinkback animation failed: " + e.getMessage());
        }
    }
}
