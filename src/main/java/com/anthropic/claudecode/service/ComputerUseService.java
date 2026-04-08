package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import lombok.Data;

/**
 * Computer use service for managing computer use (desktop automation) features.
 * Translated from src/utils/computerUse/gates.ts
 *
 * Controls access to computer use features based on subscription type and feature flags.
 */
@Slf4j
@Service
public class ComputerUseService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ComputerUseService.class);


    private final AuthService authService;

    @Autowired
    public ComputerUseService(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Check if computer use is enabled.
     * Translated from isComputerUseEnabled() in gates.ts
     */
    public boolean isComputerUseEnabled() {
        // Check explicit env override
        if (EnvUtils.isEnvTruthy("CLAUDE_CODE_COMPUTER_USE")) {
            return true;
        }
        if (EnvUtils.isEnvDefinedFalsy("CLAUDE_CODE_COMPUTER_USE")) {
            return false;
        }

        // Ant users always have access for dogfooding
        if ("ant".equals(System.getenv("USER_TYPE"))) {
            return hasRequiredSubscription();
        }

        // Max/Pro only for external rollout
        return hasRequiredSubscription();
    }

    /**
     * Check if the user has the required subscription for computer use.
     * Translated from hasRequiredSubscription() in gates.ts
     */
    private boolean hasRequiredSubscription() {
        if ("ant".equals(System.getenv("USER_TYPE"))) {
            return true;
        }
        String subscriptionType = authService.getSubscriptionType();
        return "max".equals(subscriptionType) || "pro".equals(subscriptionType);
    }

    /**
     * Get the computer use configuration.
     */
    public ComputerUseConfig getConfig() {
        return new ComputerUseConfig(
            isComputerUseEnabled(),
            false,  // pixelValidation
            true,   // clipboardPasteMultiline
            true,   // mouseAnimation
            true,   // hideBeforeAction
            true,   // autoTargetDisplay
            true,   // clipboardGuard
            "pixels" // coordinateMode
        );
    }

    public static class ComputerUseConfig {
        private boolean enabled;
        private boolean pixelValidation;
        private boolean clipboardPasteMultiline;
        private boolean mouseAnimation;
        private boolean hideBeforeAction;
        private boolean autoTargetDisplay;
        private boolean clipboardGuard;
        private String coordinateMode;

        public ComputerUseConfig() {}
        public ComputerUseConfig(boolean enabled, boolean pixelValidation, boolean clipboardPasteMultiline,
                                  boolean mouseAnimation, boolean hideBeforeAction, boolean autoTargetDisplay,
                                  boolean clipboardGuard, String coordinateMode) {
            this.enabled = enabled; this.pixelValidation = pixelValidation;
            this.clipboardPasteMultiline = clipboardPasteMultiline; this.mouseAnimation = mouseAnimation;
            this.hideBeforeAction = hideBeforeAction; this.autoTargetDisplay = autoTargetDisplay;
            this.clipboardGuard = clipboardGuard; this.coordinateMode = coordinateMode;
        }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { enabled = v; }
        public boolean isPixelValidation() { return pixelValidation; }
        public void setPixelValidation(boolean v) { pixelValidation = v; }
        public boolean isClipboardPasteMultiline() { return clipboardPasteMultiline; }
        public void setClipboardPasteMultiline(boolean v) { clipboardPasteMultiline = v; }
        public boolean isMouseAnimation() { return mouseAnimation; }
        public void setMouseAnimation(boolean v) { mouseAnimation = v; }
        public boolean isHideBeforeAction() { return hideBeforeAction; }
        public void setHideBeforeAction(boolean v) { hideBeforeAction = v; }
        public boolean isAutoTargetDisplay() { return autoTargetDisplay; }
        public void setAutoTargetDisplay(boolean v) { autoTargetDisplay = v; }
        public boolean isClipboardGuard() { return clipboardGuard; }
        public void setClipboardGuard(boolean v) { clipboardGuard = v; }
        public String getCoordinateMode() { return coordinateMode; }
        public void setCoordinateMode(String v) { coordinateMode = v; }
    }
}
