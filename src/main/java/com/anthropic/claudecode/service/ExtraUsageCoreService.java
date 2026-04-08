package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Extra-usage management service.
 * Translated from src/commands/extra-usage/extra-usage-core.ts
 *
 * Handles the /extra-usage command: for team/enterprise users it requests or
 * checks the status of an admin-driven usage-limit increase; for individual
 * users it opens the billing page in a browser.
 */
@Slf4j
@Service
public class ExtraUsageCoreService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExtraUsageCoreService.class);


    // ---------------------------------------------------------------------------
    // URLs
    // ---------------------------------------------------------------------------

    private static final String URL_ADMIN_USAGE  = "https://claude.ai/admin-settings/usage";
    private static final String URL_PERSONAL_USAGE = "https://claude.ai/settings/usage";

    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final AdminRequestsService adminRequestsService;
    private final OverageCreditGrantService overageCreditGrantService;
    private final UsageService usageService;
    private final AuthService authService;
    private final BillingService billingService;
    private final GlobalConfigService globalConfigService;

    @Autowired
    public ExtraUsageCoreService(AdminRequestsService adminRequestsService,
                                   OverageCreditGrantService overageCreditGrantService,
                                   UsageService usageService,
                                   AuthService authService,
                                   BillingService billingService,
                                   GlobalConfigService globalConfigService) {
        this.adminRequestsService = adminRequestsService;
        this.overageCreditGrantService = overageCreditGrantService;
        this.usageService = usageService;
        this.authService = authService;
        this.billingService = billingService;
        this.globalConfigService = globalConfigService;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Runs the core extra-usage logic.
     * Mirrors runExtraUsage() in extra-usage-core.ts.
     *
     * @return A {@link ExtraUsageResult} indicating what happened.
     */
    public CompletableFuture<ExtraUsageResult> runExtraUsage() {
        return CompletableFuture.supplyAsync(() -> {
            // Mark this feature as visited on first run
            // Track first visit (simplified - field not yet in GlobalConfig)
            log.debug("[ExtraUsage] First visit tracking skipped (field not available)");

            // Invalidate the overage-credit-grant cache so that a follow-up read
            // fetches the current granted state from the API.
            overageCreditGrantService.invalidateOverageCreditGrantCache();

            String subscriptionType = authService.getSubscriptionType();
            boolean isTeamOrEnterprise = "team".equals(subscriptionType)
                || "enterprise".equals(subscriptionType);
            boolean hasBillingAccess = billingService.hasClaudeAiBillingAccess();

            if (!hasBillingAccess && isTeamOrEnterprise) {
                return handleTeamEnterprise();
            }

            // Individual / pro users: open the usage page directly.
            String url = isTeamOrEnterprise ? URL_ADMIN_USAGE : URL_PERSONAL_USAGE;
            return openBrowser(url);
        });
    }

    // ---------------------------------------------------------------------------
    // Team / enterprise flow
    // ---------------------------------------------------------------------------

    /**
     * Handles the request flow for team and enterprise users who do not have
     * direct billing access.  Mirrors the non-billingAccess branch in runExtraUsage().
     */
    private ExtraUsageResult handleTeamEnterprise() {
        // 1. Check if overage is already unlimited — nothing to request.
        try {
            UsageService.Utilization utilization = usageService.fetchUtilization().join();
            UsageService.ExtraUsage extraUsage = (utilization != null)
                ? utilization.getExtraUsage() : null;

            if (extraUsage != null && extraUsage.isEnabled() && extraUsage.getMonthlyLimit() == null) {
                return ExtraUsageResult.message(
                    "Your organization already has unlimited extra usage. No request needed."
                );
            }

            // 2. Check admin-request eligibility.
            try {
                AdminRequestsService.AdminRequestEligibilityResponse eligibility =
                    adminRequestsService.checkAdminRequestEligibility("limit_increase").join();
                if (eligibility != null && !eligibility.isAllowed()) {
                    return ExtraUsageResult.message(
                        "Please contact your admin to manage extra usage settings."
                    );
                }
            } catch (Exception e) {
                log.warn("Eligibility check failed, continuing: {}", e.getMessage());
                // Continue — the create endpoint will enforce if necessary.
            }

            // 3. Check for pending or dismissed requests.
            try {
                List<AdminRequestsService.AdminRequest> existing =
                    adminRequestsService.getMyAdminRequests(
                        "limit_increase", List.of("pending", "dismissed")
                    ).join();

                if (existing != null && !existing.isEmpty()) {
                    return ExtraUsageResult.message(
                        "You have already submitted a request for extra usage to your admin."
                    );
                }
            } catch (Exception e) {
                log.warn("Could not fetch existing admin requests: {}", e.getMessage());
                // Fall through to creating a new request.
            }

            // 4. Submit a new admin request.
            try {
                adminRequestsService.createAdminRequest(
                    new AdminRequestsService.CreateAdminRequestPayload(
                        AdminRequestsService.AdminRequestType.LIMIT_INCREASE, null
                    )
                ).join();

                String confirmMsg = (extraUsage != null && extraUsage.isEnabled())
                    ? "Request sent to your admin to increase extra usage."
                    : "Request sent to your admin to enable extra usage.";
                return ExtraUsageResult.message(confirmMsg);

            } catch (Exception e) {
                log.error("Failed to create admin request: {}", e.getMessage(), e);
                // Fall through to generic message.
            }

        } catch (Exception e) {
            log.error("Failed to fetch utilization: {}", e.getMessage(), e);
            // Fall through to generic message.
        }

        return ExtraUsageResult.message(
            "Please contact your admin to manage extra usage settings."
        );
    }

    // ---------------------------------------------------------------------------
    // Browser helper
    // ---------------------------------------------------------------------------

    private ExtraUsageResult openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return ExtraUsageResult.browserOpened(url, true);
            }
            // Fallback: try xdg-open / open on Unix/macOS
            String os = System.getProperty("os.name", "").toLowerCase();
            String[] cmd = os.contains("mac")
                ? new String[]{"open", url}
                : new String[]{"xdg-open", url};
            new ProcessBuilder(cmd).start();
            return ExtraUsageResult.browserOpened(url, true);
        } catch (Exception e) {
            log.error("Failed to open browser: {}", e.getMessage(), e);
            return ExtraUsageResult.message(
                "Failed to open browser. Please visit " + url + " to manage extra usage."
            );
        }
    }

    // ---------------------------------------------------------------------------
    // Result type
    // ---------------------------------------------------------------------------

    /**
     * Sealed result type for the extra-usage command.
     * Mirrors the ExtraUsageResult union in extra-usage-core.ts.
     */
    public sealed interface ExtraUsageResult
            permits ExtraUsageResult.Message, ExtraUsageResult.BrowserOpened {

        static ExtraUsageResult message(String value) {
            return new Message(value);
        }

        static ExtraUsageResult browserOpened(String url, boolean opened) {
            return new BrowserOpened(url, opened);
        }

        /** A plain text message to display to the user. */
        record Message(String value) implements ExtraUsageResult {}

        /** Indicates a browser tab was (attempted to be) opened. */
        record BrowserOpened(String url, boolean opened) implements ExtraUsageResult {}
    }
}
