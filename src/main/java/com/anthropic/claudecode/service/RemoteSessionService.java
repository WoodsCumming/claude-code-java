package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Remote session management service for the /session command.
 * Translated from src/commands/session/index.ts and AppState remoteSessionUrl.
 *
 * Provides access to the current remote session URL and generates QR codes
 * for scanning on mobile devices.
 */
@Slf4j
@Service
public class RemoteSessionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RemoteSessionService.class);


    private final AppState appState;

    @Autowired
    public RemoteSessionService(AppState appState) {
        this.appState = appState;
    }

    /**
     * Check whether the current session is a remote session.
     * Translated from getIsRemoteMode() in AppState.
     *
     * @return true if a remote session URL is active
     */
    public boolean isRemoteMode() {
        String url = getRemoteSessionUrl();
        return url != null && !url.isBlank();
    }

    /**
     * Get the current remote session URL.
     * Translated from AppState.remoteSessionUrl.
     *
     * @return the URL, or null if not in remote mode
     */
    public String getRemoteSessionUrl() {
        try {
            return appState.getRemoteSessionUrl();
        } catch (Exception e) {
            log.debug("[RemoteSessionService] Could not get session URL: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Generate a text-art QR code for the given URL.
     * Translated from the QR code rendering in session.tsx.
     *
     * Returns null if QR code generation fails; the caller should fall back
     * to displaying the plain URL.
     *
     * @param url the URL to encode
     * @return a multi-line string containing the QR code, or null on failure
     */
    public String generateQrCode(String url) {
        // Stub: a full implementation would use a QR library such as qrcodegen.
        // Return null to let the caller display the plain URL.
        return null;
    }
}
