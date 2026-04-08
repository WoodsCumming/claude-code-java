package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.net.URI;

/**
 * Browser service.
 * Opens URLs in the system default browser.
 * Translated from src/utils/open.ts
 */
@Slf4j
@Service
public class BrowserService {

    /**
     * Attempts to open the given URL in the default browser.
     *
     * @param url the URL to open
     * @return true if the browser was opened successfully, false otherwise
     */
    public boolean openBrowser(String url) {
        // Try Java Desktop API first
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return true;
            }
        } catch (Exception e) {
            log.debug("Desktop.browse() failed: {}", e.getMessage());
        }

        // OS-specific fallbacks
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) {
                new ProcessBuilder("open", url).start();
                return true;
            } else if (os.contains("linux")) {
                new ProcessBuilder("xdg-open", url).start();
                return true;
            } else if (os.contains("win")) {
                new ProcessBuilder("cmd", "/c", "start", url).start();
                return true;
            }
        } catch (Exception e) {
            log.debug("OS-specific browser open failed: {}", e.getMessage());
        }

        return false;
    }
}
