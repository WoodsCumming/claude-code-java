package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Billing service.
 * Translated from src/utils/billing.ts
 *
 * Manages billing access checks for console and Claude.ai subscribers.
 */
@Slf4j
@Service
public class BillingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BillingService.class);


    private static final Set<String> CONSOLE_BILLING_ORG_ROLES = Set.of("admin", "billing");
    private static final Set<String> CONSOLE_BILLING_WS_ROLES = Set.of("workspace_admin", "workspace_billing");
    private static final Set<String> CLAUDE_AI_BILLING_ORG_ROLES = Set.of("admin", "billing", "owner", "primary_owner");
    private static final List<String> CONSUMER_SUBSCRIPTION_TYPES = List.of("max", "pro");

    private final AuthService authService;
    private final GlobalConfigService globalConfigService;

    /** Mock override for /mock-limits testing. null = not overridden. */
    private Boolean mockBillingAccessOverride = null;

    @Autowired
    public BillingService(AuthService authService, GlobalConfigService globalConfigService) {
        this.authService = authService;
        this.globalConfigService = globalConfigService;
    }

    /**
     * Check if the user has console (API key) billing access.
     * Translated from hasConsoleBillingAccess() in billing.ts
     *
     * Returns false when:
     * - Cost warnings are disabled via DISABLE_COST_WARNINGS env var
     * - User is a Claude.ai subscriber (not API key)
     * - User has no authentication token or API key
     * - User lacks admin or billing role at org/workspace level
     */
    public boolean hasConsoleBillingAccess() {
        // Cost reporting disabled via env var
        if (EnvUtils.isEnvTruthy(System.getenv("DISABLE_COST_WARNINGS"))) {
            return false;
        }

        // OAuth subscribers don't show cost (may be wrong if they also have an API key,
        // but a warning is already shown on launch in that case)
        if (authService.isClaudeAISubscriber()) {
            return false;
        }

        // Require at least one form of authentication
        boolean hasToken = authService.hasAuthToken();
        boolean hasApiKey = authService.getApiKey() != null;
        if (!hasToken && !hasApiKey) {
            return false;
        }

        // Check org/workspace roles
        String orgRole = globalConfigService.getOauthAccountOrganizationRole();
        String workspaceRole = globalConfigService.getOauthAccountWorkspaceRole();

        // Hide cost for grandfathered users who have not re-authed since roles were added
        if (orgRole == null || workspaceRole == null) {
            return false;
        }

        return CONSOLE_BILLING_ORG_ROLES.contains(orgRole)
                || CONSOLE_BILLING_WS_ROLES.contains(workspaceRole);
    }

    /**
     * Set a mock billing access override (for /mock-limits testing).
     * Translated from setMockBillingAccessOverride() in billing.ts
     */
    public void setMockBillingAccessOverride(Boolean value) {
        this.mockBillingAccessOverride = value;
    }

    /**
     * Check if the user has Claude.ai billing access.
     * Translated from hasClaudeAiBillingAccess() in billing.ts
     *
     * Returns true for:
     * - Consumer plans (Max/Pro) — individual users always have billing access
     * - Team/Enterprise users with admin or billing org role
     */
    public boolean hasClaudeAiBillingAccess() {
        // Mock override for /mock-limits testing
        if (mockBillingAccessOverride != null) {
            return mockBillingAccessOverride;
        }

        if (!authService.isClaudeAISubscriber()) {
            return false;
        }

        String subscriptionType = authService.getSubscriptionType();

        // Consumer plans — individual users always have billing access
        if (subscriptionType != null && CONSUMER_SUBSCRIPTION_TYPES.contains(subscriptionType)) {
            return true;
        }

        // Team/Enterprise — check for admin or billing org role
        String orgRole = globalConfigService.getOauthAccountOrganizationRole();
        return orgRole != null && CLAUDE_AI_BILLING_ORG_ROLES.contains(orgRole);
    }
}
