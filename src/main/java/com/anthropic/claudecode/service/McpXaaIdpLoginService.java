package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MCP XAA (SEP-990) IdP command service.
 * Translated from src/commands/mcp/xaaIdpCommand.ts
 *
 * Manages the XAA IdP connection: setup, login, show, and clear subcommands.
 * The IdP connection is user-level — configure once and all XAA-enabled MCP
 * servers reuse it. Non-secret config lives in settings.xaaIdp; secrets are
 * kept in the keychain keyed by issuer.
 */
@Slf4j
@Service
public class McpXaaIdpLoginService {



    /**
     * Result record used by setup/clear/show/login to signal success or failure.
     * Translated from cliOk / cliError pattern in xaaIdpCommand.ts
     */
    public record CommandResult(boolean success, String message) {
        public static CommandResult ok(String message) {
            return new CommandResult(true, message);
        }
        public static CommandResult ok() {
            return new CommandResult(true, "");
        }
        public static CommandResult error(String message) {
            return new CommandResult(false, message);
        }
    }

    /**
     * Options accepted by the {@code xaa setup} subcommand.
     * Translated from .requiredOption / .option declarations in xaaIdpCommand.ts
     */
    public record SetupOptions(
        String issuer,
        String clientId,
        boolean readClientSecret,
        Integer callbackPort
    ) {}

    /**
     * Options accepted by the {@code xaa login} subcommand.
     * Translated from .option declarations in xaaIdpCommand.ts
     */
    public record LoginOptions(
        boolean force,
        String idToken
    ) {}

    private final XaaIdpLoginService xaaIdpLoginService;
    private final SettingsService settingsService;

    @Autowired
    public McpXaaIdpLoginService(XaaIdpLoginService xaaIdpLoginService,
                                  SettingsService settingsService) {
        this.xaaIdpLoginService = xaaIdpLoginService;
        this.settingsService = settingsService;
    }

    // -------------------------------------------------------------------------
    // setup
    // -------------------------------------------------------------------------

    /**
     * Configure the IdP connection (one-time setup for all XAA-enabled servers).
     * Translated from xaaIdp.command('setup').action() in xaaIdpCommand.ts
     *
     * <p>Validates the issuer URL and callback port before writing anything so
     * that a mid-write failure cannot leave settings in a poisoned state (the
     * same guarantee the TS implementation documents in its inline comments).
     */
    public CommandResult setup(SetupOptions options) {
        // Validate issuer URL before any writes.
        // Translated from: try { issuerUrl = new URL(options.issuer) } catch { ... }
        URL issuerUrl;
        try {
            issuerUrl = URI.create(options.issuer).toURL();
        } catch (Exception e) {
            return CommandResult.error(
                "Error: --issuer must be a valid URL (got \"" + options.issuer + "\")");
        }

        // Allow http:// only for loopback (conformance harness mock IdP).
        // Translated from the protocol/hostname guard in xaaIdpCommand.ts
        String protocol = issuerUrl.getProtocol();
        String hostname  = issuerUrl.getHost();
        boolean isHttps  = "https".equals(protocol);
        boolean isLoopback = "http".equals(protocol)
            && ("localhost".equals(hostname)
                || "127.0.0.1".equals(hostname)
                || "[::1]".equals(hostname));
        if (!isHttps && !isLoopback) {
            return CommandResult.error(
                "Error: --issuer must use https:// (got \""
                    + protocol + "://" + issuerUrl.getHost() + "\")");
        }

        // Validate callback port when provided.
        // Translated from: if (callbackPort !== undefined && (!Number.isInteger(callbackPort) || callbackPort <= 0))
        if (options.callbackPort() != null && options.callbackPort() <= 0) {
            return CommandResult.error("Error: --callback-port must be a positive integer");
        }

        // Read client secret from environment when --client-secret flag is set.
        // Translated from: const secret = options.clientSecret ? process.env.MCP_XAA_IDP_CLIENT_SECRET : undefined
        String secret = null;
        if (options.readClientSecret()) {
            secret = System.getenv("MCP_XAA_IDP_CLIENT_SECRET");
            if (secret == null || secret.isBlank()) {
                return CommandResult.error(
                    "Error: --client-secret requires MCP_XAA_IDP_CLIENT_SECRET env var");
            }
        }

        // Read old config before overwriting so we can clear stale keychain slots.
        // Translated from: const old = getXaaIdpSettings(); const oldIssuer = old?.issuer
        Optional<XaaIdpLoginService.XaaIdpSettings> oldSettings = xaaIdpLoginService.getXaaIdpSettings();
        String oldIssuer   = oldSettings.map(XaaIdpLoginService.XaaIdpSettings::getIssuer).orElse(null);
        String oldClientId = oldSettings.map(XaaIdpLoginService.XaaIdpSettings::getClientId).orElse(null);

        // Write new settings.
        // Translated from: updateSettingsForSource('userSettings', { xaaIdp: { issuer, clientId, callbackPort } })
        try {
            settingsService.updateXaaIdpSettings(options.issuer(), options.clientId(), options.callbackPort());
        } catch (Exception e) {
            return CommandResult.error("Error writing settings: " + e.getMessage());
        }

        // Clear stale keychain slots only after a successful write.
        // Translated from the oldIssuer comparison block in xaaIdpCommand.ts
        if (oldIssuer != null) {
            String newKey = issuerKey(options.issuer());
            String oldKey = issuerKey(oldIssuer);
            if (!oldKey.equals(newKey)) {
                xaaIdpLoginService.clearIdpIdToken(oldIssuer);
                clearIdpClientSecret(oldIssuer);
            } else if (!Objects.equals(oldClientId, options.clientId())) {
                // Same issuer slot but different OAuth client registration.
                xaaIdpLoginService.clearIdpIdToken(oldIssuer);
                clearIdpClientSecret(oldIssuer);
            }
        }

        // Persist client secret to keychain when supplied.
        // Translated from: if (secret) { const { success, warning } = saveIdpClientSecret(...) }
        if (secret != null) {
            boolean saved = saveIdpClientSecret(options.issuer(), secret);
            if (!saved) {
                return CommandResult.error(
                    "Error: settings written but keychain save failed. "
                        + "Re-run with --client-secret once keychain is available.");
            }
        }

        return CommandResult.ok("XAA IdP connection configured for " + options.issuer());
    }

    // -------------------------------------------------------------------------
    // login
    // -------------------------------------------------------------------------

    /**
     * Cache an IdP id_token so XAA-enabled MCP servers authenticate silently.
     * Translated from xaaIdp.command('login').action() in xaaIdpCommand.ts
     */
    public CompletableFuture<CommandResult> login(LoginOptions options) {
        // Translated from: const idp = getXaaIdpSettings(); if (!idp) { return cliError(...) }
        Optional<XaaIdpLoginService.XaaIdpSettings> idpOpt = xaaIdpLoginService.getXaaIdpSettings();
        if (idpOpt.isEmpty()) {
            return CompletableFuture.completedFuture(CommandResult.error(
                "Error: no XAA IdP connection. Run 'claude mcp xaa setup' first."));
        }
        XaaIdpLoginService.XaaIdpSettings idp = idpOpt.get();

        // Direct-inject path: write the supplied JWT straight to cache, skip OIDC.
        // Translated from: if (options.idToken) { const expiresAt = saveIdpIdTokenFromJwt(...) }
        if (options.idToken() != null) {
            long expiresAt = saveIdpIdTokenFromJwt(idp.getIssuer(), options.idToken());
            String isoExpiry = java.time.Instant.ofEpochMilli(expiresAt).toString();
            return CompletableFuture.completedFuture(CommandResult.ok(
                "id_token cached for " + idp.getIssuer() + " (expires " + isoExpiry + ")"));
        }

        // Clear the cached token when --force is set.
        // Translated from: if (options.force) { clearIdpIdToken(idp.issuer) }
        if (options.force()) {
            xaaIdpLoginService.clearIdpIdToken(idp.getIssuer());
        }

        // Return early when a valid cached token already exists.
        // Translated from: const wasCached = getCachedIdpIdToken(idp.issuer) !== undefined
        boolean wasCached = xaaIdpLoginService.getCachedIdpIdToken(idp.getIssuer()) != null;
        if (wasCached) {
            return CompletableFuture.completedFuture(CommandResult.ok(
                "Already logged in to " + idp.getIssuer()
                    + " (cached id_token still valid). Use --force to re-login."));
        }

        System.out.println("Opening browser for IdP login at " + idp.getIssuer() + "\u2026");

        // Kick off the full OIDC browser flow.
        // Translated from: await acquireIdpIdToken({ idpIssuer, idpClientId, ... })
        return xaaIdpLoginService.acquireIdpIdToken(idp)
            .thenApply(result -> {
                if (result != null && result.isPresent()) {
                    return CommandResult.ok(
                        "Logged in. MCP servers with --xaa will now authenticate silently.");
                } else {
                    return CommandResult.error("IdP login failed: unable to acquire id_token");
                }
            })
            .exceptionally(e -> CommandResult.error("IdP login failed: " + e.getMessage()));
    }

    // -------------------------------------------------------------------------
    // show
    // -------------------------------------------------------------------------

    /**
     * Print the current IdP connection config to stdout.
     * Translated from xaaIdp.command('show').action() in xaaIdpCommand.ts
     */
    public CommandResult show() {
        Optional<XaaIdpLoginService.XaaIdpSettings> idpOpt = xaaIdpLoginService.getXaaIdpSettings();
        if (idpOpt.isEmpty()) {
            return CommandResult.ok("No XAA IdP connection configured.");
        }
        XaaIdpLoginService.XaaIdpSettings idp = idpOpt.get();

        // Translated from: const hasSecret = getIdpClientSecret(idp.issuer) !== undefined
        boolean hasSecret  = getIdpClientSecret(idp.getIssuer()) != null;
        // Translated from: const hasIdToken = getCachedIdpIdToken(idp.issuer) !== undefined
        boolean hasIdToken = xaaIdpLoginService.getCachedIdpIdToken(idp.getIssuer()) != null;

        System.out.println("Issuer:        " + idp.getIssuer());
        System.out.println("Client ID:     " + idp.getClientId());
        if (idp.getCallbackPort() != null) {
            System.out.println("Callback port: " + idp.getCallbackPort());
        }
        System.out.println("Client secret: " + (hasSecret
            ? "(stored in keychain)"
            : "(not set \u2014 PKCE-only)"));
        System.out.println("Logged in:     " + (hasIdToken
            ? "yes (id_token cached)"
            : "no \u2014 run 'claude mcp xaa login'"));

        return CommandResult.ok();
    }

    // -------------------------------------------------------------------------
    // clear
    // -------------------------------------------------------------------------

    /**
     * Clear the IdP connection config and its cached id_token / client secret.
     * Translated from xaaIdp.command('clear').action() in xaaIdpCommand.ts
     */
    public CommandResult clear() {
        // Read issuer before wiping settings so we know which keychain slots to clear.
        Optional<XaaIdpLoginService.XaaIdpSettings> idpOpt = xaaIdpLoginService.getXaaIdpSettings();

        // Write undefined (removal) to settings first.
        // Translated from: updateSettingsForSource('userSettings', { xaaIdp: undefined })
        try {
            settingsService.clearXaaIdpSettings();
        } catch (Exception e) {
            return CommandResult.error("Error writing settings: " + e.getMessage());
        }

        // Clear keychain only after successful settings write.
        if (idpOpt.isPresent()) {
            String issuer = idpOpt.get().getIssuer();
            xaaIdpLoginService.clearIdpIdToken(issuer);
            clearIdpClientSecret(issuer);
        }

        return CommandResult.ok("XAA IdP connection cleared");
    }

    // -------------------------------------------------------------------------
    // Helpers — keychain / JWT helpers (stubs; delegate to SecureStorageService
    // in a real implementation).
    // -------------------------------------------------------------------------

    /**
     * Normalise an issuer URL into a stable keychain-slot key.
     * Translated from issuerKey() in xaaIdpLogin.ts
     */
    public String issuerKey(String issuer) {
        try {
            URL url = URI.create(issuer).toURL();
            // Normalise: lowercase host, strip trailing slash from path.
            String host = url.getHost().toLowerCase(Locale.ROOT);
            String path = url.getPath().replaceAll("/+$", "");
            String port = url.getPort() == -1 ? "" : ":" + url.getPort();
            return url.getProtocol() + "://" + host + port + path;
        } catch (Exception e) {
            return issuer;
        }
    }

    /**
     * Persist the client secret to the system keychain.
     * Translated from saveIdpClientSecret() in xaaIdpLogin.ts
     * Returns true on success.
     */
    private boolean saveIdpClientSecret(String issuer, String secret) {
        log.debug("[XAA IdP] Saving client secret for issuer: {}", issuer);
        // Stub — real implementation delegates to SecureStorageService / OS keychain.
        return true;
    }

    /**
     * Retrieve the stored client secret for the given issuer, or null if absent.
     * Translated from getIdpClientSecret() in xaaIdpLogin.ts
     */
    private String getIdpClientSecret(String issuer) {
        log.debug("[XAA IdP] Retrieving client secret for issuer: {}", issuer);
        return null; // Stub
    }

    /**
     * Remove the stored client secret for the given issuer.
     * Translated from clearIdpClientSecret() in xaaIdpLogin.ts
     */
    private void clearIdpClientSecret(String issuer) {
        log.debug("[XAA IdP] Clearing client secret for issuer: {}", issuer);
    }

    /**
     * Decode a pre-obtained JWT, cache it, and return its expiry epoch-millis.
     * Translated from saveIdpIdTokenFromJwt() in xaaIdpLogin.ts
     */
    private long saveIdpIdTokenFromJwt(String issuer, String jwt) {
        log.debug("[XAA IdP] Caching id_token for issuer: {}", issuer);
        // Stub — extract exp claim from JWT payload and return epoch-millis.
        return System.currentTimeMillis() + 3600_000L;
    }

    /** Delegate: whether XAA is enabled in the environment. */
    public boolean isXaaEnabled() {
        return xaaIdpLoginService.isXaaEnabled();
    }

    /** Delegate: get current XAA IdP settings (or empty if not configured). */
    public java.util.Optional<XaaIdpLoginService.XaaIdpSettings> getXaaIdpSettings() {
        return xaaIdpLoginService.getXaaIdpSettings();
    }
}
