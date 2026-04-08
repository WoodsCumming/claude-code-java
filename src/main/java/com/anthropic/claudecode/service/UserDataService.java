package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * User data service for analytics and user context.
 * Translated from src/utils/user.ts
 *
 * Provides core user data used as the base for all analytics providers,
 * including GrowthBook. Caches email asynchronously at startup so
 * getCoreUserData() remains synchronous.
 */
@Slf4j
@Service
public class UserDataService {



    // =========================================================================
    // Email cache — mirrors the module-level variables in user.ts
    // =========================================================================

    /**
     * null  = not fetched yet
     * ""    = fetch attempted but no email found
     * other = fetched email
     */
    private final AtomicReference<String> cachedEmail = new AtomicReference<>(null);
    private volatile CompletableFuture<String> emailFetchPromise = null;

    // =========================================================================
    // Dependencies
    // =========================================================================

    private final GlobalConfigService globalConfigService;
    private final AuthService authService;
    private final AppState appState;

    @Value("${app.version:unknown}")
    private String appVersion;

    @Autowired
    public UserDataService(GlobalConfigService globalConfigService,
                            AuthService authService,
                            AppState appState) {
        this.globalConfigService = globalConfigService;
        this.authService = authService;
        this.appState = appState;
    }

    // =========================================================================
    // Initialisation
    // =========================================================================

    /**
     * Initialize user data asynchronously. Should be called early in startup.
     * Pre-fetches the email so getCoreUserData() can remain synchronous.
     * Translated from initUser() in user.ts
     */
    public synchronized CompletableFuture<Void> initUser() {
        if (cachedEmail.get() == null && emailFetchPromise == null) {
            emailFetchPromise = getEmailAsync().thenApply(email -> {
                cachedEmail.set(email != null ? email : "");
                emailFetchPromise = null;
                // Invalidate the memoised getCoreUserData so next call picks up email.
                invalidateCoreUserDataCache();
                return email;
            });
        }
        return emailFetchPromise != null ? emailFetchPromise.thenAccept(e -> {}) : CompletableFuture.completedFuture(null);
    }

    /**
     * Reset all user data caches. Call on auth changes (login/logout/account switch)
     * so the next getCoreUserData() call picks up fresh credentials and email.
     * Translated from resetUserCache() in user.ts
     */
    public synchronized void resetUserCache() {
        cachedEmail.set(null);
        emailFetchPromise = null;
        invalidateCoreUserDataCache();
    }

    // =========================================================================
    // Core user data
    // =========================================================================

    /** Memoisation cache for getCoreUserData — invalidated by resetUserCache(). */
    private volatile CoreUserData cachedCoreUserData = null;

    private void invalidateCoreUserDataCache() {
        cachedCoreUserData = null;
    }

    /**
     * Get core user data.
     * This is the base representation that gets transformed for different analytics providers.
     * Memoised — invalidated when resetUserCache() or initUser() completes.
     *
     * Translated from getCoreUserData() in user.ts
     */
    public synchronized CoreUserData getCoreUserData() {
        if (cachedCoreUserData != null) return cachedCoreUserData;

        String deviceId = globalConfigService.getOrCreateUserID();

        // OAuth account data — only when actively using OAuth authentication.
        String organizationUuid = null;
        String accountUuid = null;
        var oauthAccount = globalConfigService.getGlobalConfig().getOauthAccount();
        if (oauthAccount != null) {
            organizationUuid = oauthAccount.getOrganizationUuid();
            accountUuid = oauthAccount.getAccountUuid();
        }

        CoreUserData data = new CoreUserData();
        data.setDeviceId(deviceId);
        data.setSessionId(appState.getSessionId());
        data.setEmail(getEmail());
        data.setAppVersion(appVersion);
        data.setPlatform(System.getProperty("os.name", "unknown").toLowerCase());
        data.setOrganizationUuid(organizationUuid);
        data.setAccountUuid(accountUuid);
        data.setUserType(System.getenv("USER_TYPE"));

        // GitHub Actions metadata when running in CI.
        String ghActions = System.getenv("GITHUB_ACTIONS");
        if ("true".equalsIgnoreCase(ghActions)) {
            GitHubActionsMetadata gha = new GitHubActionsMetadata();
            gha.setActor(System.getenv("GITHUB_ACTOR"));
            gha.setActorId(System.getenv("GITHUB_ACTOR_ID"));
            gha.setRepository(System.getenv("GITHUB_REPOSITORY"));
            gha.setRepositoryId(System.getenv("GITHUB_REPOSITORY_ID"));
            gha.setRepositoryOwner(System.getenv("GITHUB_REPOSITORY_OWNER"));
            gha.setRepositoryOwnerId(System.getenv("GITHUB_REPOSITORY_OWNER_ID"));
            data.setGithubActionsMetadata(gha);
        }

        cachedCoreUserData = data;
        return data;
    }

    /**
     * Get user data for GrowthBook (same as core data, with analytics metadata included).
     * Translated from getUserForGrowthBook() in user.ts
     */
    public CoreUserData getUserForGrowthBook() {
        return getCoreUserData();
    }

    // =========================================================================
    // Git email helpers
    // =========================================================================

    /**
     * Get the user's git email from {@code git config user.email}.
     * Translated from getGitEmail() / getEmailAsync() in user.ts
     */
    public CompletableFuture<String> getGitEmail() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "config", "--get", "user.email");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes()).trim();
                int exit = p.waitFor();
                return (exit == 0 && !output.isEmpty()) ? output : null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private String getEmail() {
        // Return cached email if available (from async initialization).
        String cached = cachedEmail.get();
        if (cached != null) return cached.isEmpty() ? null : cached;

        // OAuth email — only when actively using OAuth authentication.
        var oauthAccount = globalConfigService.getGlobalConfig().getOauthAccount();
        if (oauthAccount != null && oauthAccount.getEmailAddress() != null) {
            return oauthAccount.getEmailAddress();
        }

        // Ant-only fallbacks below (no blocking I/O).
        if (!"ant".equals(System.getenv("USER_TYPE"))) return null;

        String cooCreator = System.getenv("COO_CREATOR");
        if (cooCreator != null && !cooCreator.isEmpty()) {
            return cooCreator + "@anthropic.com";
        }

        // initUser() was not called — return null instead of blocking.
        return null;
    }

    private CompletableFuture<String> getEmailAsync() {
        // OAuth email first.
        var oauthAccount = globalConfigService.getGlobalConfig().getOauthAccount();
        if (oauthAccount != null && oauthAccount.getEmailAddress() != null) {
            return CompletableFuture.completedFuture(oauthAccount.getEmailAddress());
        }

        if (!"ant".equals(System.getenv("USER_TYPE"))) {
            return CompletableFuture.completedFuture(null);
        }

        String cooCreator = System.getenv("COO_CREATOR");
        if (cooCreator != null && !cooCreator.isEmpty()) {
            return CompletableFuture.completedFuture(cooCreator + "@anthropic.com");
        }

        return getGitEmail();
    }

    // =========================================================================
    // Inner record/data types
    // =========================================================================

    /**
     * Core user data used as base for all analytics providers.
     * Also the format used by GrowthBook.
     * Translated from CoreUserData in user.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CoreUserData {
        private String deviceId;
        private String sessionId;
        private String email;
        private String appVersion;
        private String platform;
        private String organizationUuid;
        private String accountUuid;
        private String userType;
        private String subscriptionType;
        private String rateLimitTier;
        private Long firstTokenTime;
        private GitHubActionsMetadata githubActionsMetadata;

        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String v) { deviceId = v; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String v) { sessionId = v; }
        public String getEmail() { return email; }
        public void setEmail(String v) { email = v; }
        public String getAppVersion() { return appVersion; }
        public void setAppVersion(String v) { appVersion = v; }
        public String getPlatform() { return platform; }
        public void setPlatform(String v) { platform = v; }
        public String getOrganizationUuid() { return organizationUuid; }
        public void setOrganizationUuid(String v) { organizationUuid = v; }
        public String getAccountUuid() { return accountUuid; }
        public void setAccountUuid(String v) { accountUuid = v; }
        public String getUserType() { return userType; }
        public void setUserType(String v) { userType = v; }
        public String getSubscriptionType() { return subscriptionType; }
        public void setSubscriptionType(String v) { subscriptionType = v; }
        public String getRateLimitTier() { return rateLimitTier; }
        public void setRateLimitTier(String v) { rateLimitTier = v; }
        public Long getFirstTokenTime() { return firstTokenTime; }
        public void setFirstTokenTime(Long v) { firstTokenTime = v; }
        public GitHubActionsMetadata getGithubActionsMetadata() { return githubActionsMetadata; }
        public void setGithubActionsMetadata(GitHubActionsMetadata v) { githubActionsMetadata = v; }
    

    }

    /**
     * GitHub Actions metadata when running in CI.
     * Translated from GitHubActionsMetadata in user.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GitHubActionsMetadata {
        private String actor;
        private String actorId;
        private String repository;
        private String repositoryId;
        private String repositoryOwner;
        private String repositoryOwnerId;

        public String getActor() { return actor; }
        public void setActor(String v) { actor = v; }
        public String getActorId() { return actorId; }
        public void setActorId(String v) { actorId = v; }
        public String getRepository() { return repository; }
        public void setRepository(String v) { repository = v; }
        public String getRepositoryId() { return repositoryId; }
        public void setRepositoryId(String v) { repositoryId = v; }
        public String getRepositoryOwner() { return repositoryOwner; }
        public void setRepositoryOwner(String v) { repositoryOwner = v; }
        public String getRepositoryOwnerId() { return repositoryOwnerId; }
        public void setRepositoryOwnerId(String v) { repositoryOwnerId = v; }
    

    }
}
