package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Project onboarding state service.
 * Translated from src/projectOnboardingState.ts
 *
 * Tracks onboarding steps for new projects and controls when to display
 * the onboarding UI.
 */
@Slf4j
@Service
public class ProjectOnboardingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ProjectOnboardingService.class);


    private final ProjectConfigService projectConfigService;

    // Memoized shouldShow result (translated from memoize() in projectOnboardingState.ts)
    private final AtomicBoolean shouldShowCached = new AtomicBoolean(false);
    private volatile boolean shouldShowComputed = false;

    @Autowired
    public ProjectOnboardingService(ProjectConfigService projectConfigService) {
        this.projectConfigService = projectConfigService;
    }

    // =========================================================================
    // Steps
    // =========================================================================

    /**
     * Get the onboarding steps for the current working directory.
     * Translated from getSteps() in projectOnboardingState.ts
     *
     * Steps:
     *   1. workspace — prompt to create/clone a repo (enabled only when dir is empty)
     *   2. claudemd  — prompt to run /init (enabled only when dir is non-empty)
     */
    public List<Step> getSteps() {
        String cwd = System.getProperty("user.dir");
        boolean hasClaudeMd = new File(cwd, "CLAUDE.md").exists();
        boolean isWorkspaceDirEmpty = isDirEmpty(cwd);

        return List.of(
            new Step(
                "workspace",
                "Ask Claude to create a new app or clone a repository",
                /* isComplete= */ false,
                /* isCompletable= */ true,
                /* isEnabled= */ isWorkspaceDirEmpty
            ),
            new Step(
                "claudemd",
                "Run /init to create a CLAUDE.md file with instructions for Claude",
                /* isComplete= */ hasClaudeMd,
                /* isCompletable= */ true,
                /* isEnabled= */ !isWorkspaceDirEmpty
            )
        );
    }

    // =========================================================================
    // Completion checks
    // =========================================================================

    /**
     * Whether all completable, enabled onboarding steps are complete.
     * Translated from isProjectOnboardingComplete() in projectOnboardingState.ts
     */
    public boolean isProjectOnboardingComplete() {
        return getSteps().stream()
            .filter(s -> s.isCompletable() && s.isEnabled())
            .allMatch(Step::isComplete);
    }

    // In-memory tracking since ProjectConfig doesn't yet have these fields persisted
    private volatile boolean hasCompletedOnboarding = false;
    private volatile int onboardingSeenCount = 0;

    /**
     * Mark onboarding as complete in project config if all steps are done.
     * Short-circuits on cached state — called on every prompt submit.
     * Translated from maybeMarkProjectOnboardingComplete() in projectOnboardingState.ts
     */
    public void maybeMarkProjectOnboardingComplete() {
        if (hasCompletedOnboarding) return;
        if (isProjectOnboardingComplete()) {
            hasCompletedOnboarding = true;
            // Persist to project config when the field becomes available
            projectConfigService.updateProjectConfig(current -> current);
        }
    }

    /**
     * Whether the onboarding UI should be shown on first render.
     * Memoized — only computed once per process.
     * Translated from shouldShowProjectOnboarding() memoized in projectOnboardingState.ts
     */
    public boolean shouldShowProjectOnboarding() {
        if (shouldShowComputed) return shouldShowCached.get();
        shouldShowComputed = true;

        if (hasCompletedOnboarding
                || onboardingSeenCount >= 4
                || "1".equals(System.getenv("IS_DEMO"))) {
            shouldShowCached.set(false);
            return false;
        }

        boolean result = !isProjectOnboardingComplete();
        shouldShowCached.set(result);
        return result;
    }

    /**
     * Increment the count of times the onboarding screen has been shown.
     * Translated from incrementProjectOnboardingSeenCount() in projectOnboardingState.ts
     */
    public void incrementProjectOnboardingSeenCount() {
        onboardingSeenCount++;
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private boolean isDirEmpty(String path) {
        File dir = new File(path);
        if (!dir.isDirectory()) return false;
        String[] files = dir.list();
        return files == null || files.length == 0;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * Onboarding step descriptor.
     * Translated from Step type in projectOnboardingState.ts
     */
    public static class Step {
        private String key;
        private String text;
        private boolean complete;
        private boolean completable;
        private boolean enabled;

        public Step() {}
        public Step(String key, String text, boolean complete, boolean completable, boolean enabled) {
            this.key = key; this.text = text; this.complete = complete;
            this.completable = completable; this.enabled = enabled;
        }
        public String getKey() { return key; }
        public String getText() { return text; }
        public boolean isComplete() { return complete; }
        public boolean isCompletable() { return completable; }
        public boolean isEnabled() { return enabled; }
        public void setKey(String v) { key = v; }
        public void setText(String v) { text = v; }
        public void setComplete(boolean v) { complete = v; }
        public void setCompletable(boolean v) { completable = v; }
        public void setEnabled(boolean v) { enabled = v; }
    }
}
