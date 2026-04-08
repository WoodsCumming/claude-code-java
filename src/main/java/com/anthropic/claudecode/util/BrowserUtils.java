package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;
import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Browser and path-opening utilities.
 * Translated from src/utils/browser.ts
 */
@Slf4j
public class BrowserUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BrowserUtils.class);


    // =========================================================================
    // URL validation
    // =========================================================================

    /**
     * Validate that a URL is syntactically correct and uses http/https.
     * Throws {@link IllegalArgumentException} on invalid input.
     * Translated from validateUrl() in browser.ts
     */
    public static URI validateUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid URL format: " + url, e);
        }

        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException(
                    "Invalid URL protocol: must use http:// or https://, got " + scheme);
        }
        return uri;
    }

    // =========================================================================
    // Browser opening
    // =========================================================================

    /**
     * Open a URL in the system's default browser.
     * Uses {@code open} on macOS, {@code explorer} / {@code rundll32} on Windows,
     * {@code xdg-open} on Linux, or {@link Desktop} as a fallback.
     * Translated from openBrowser() in browser.ts
     *
     * @param url URL to open (must be http or https)
     * @return CompletableFuture resolving to true on success, false on failure
     */
    public static CompletableFuture<Boolean> openBrowser(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                URI uri = validateUrl(url);
                String browserEnv = System.getenv("BROWSER");
                String os = System.getProperty("os.name", "").toLowerCase();

                if (os.contains("win")) {
                    if (browserEnv != null) {
                        // Browsers on Windows require a shell; treat as a file:/// without it
                        int code = new ProcessBuilder(browserEnv, "\"" + url + "\"")
                                .start().waitFor();
                        return code == 0;
                    }
                    int code = new ProcessBuilder("rundll32", "url,OpenURL", url)
                            .start().waitFor();
                    return code == 0;
                } else {
                    String command = browserEnv != null ? browserEnv
                            : (os.contains("mac") ? "open" : "xdg-open");
                    int code = new ProcessBuilder(command, url).start().waitFor();
                    return code == 0;
                }
            } catch (IllegalArgumentException e) {
                log.debug("Invalid URL for openBrowser: {}", e.getMessage());
                return false;
            } catch (Exception e) {
                // Fallback via java.awt.Desktop
                try {
                    URI uri = new URI(url);
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(uri);
                        return true;
                    }
                } catch (Exception ignored) {
                    // Swallow — best-effort
                }
                log.debug("Could not open browser: {}", e.getMessage());
                return false;
            }
        });
    }

    // =========================================================================
    // Path opening
    // =========================================================================

    /**
     * Open a file or folder path using the system's default handler.
     * Uses {@code open} on macOS, {@code explorer} on Windows, {@code xdg-open} on Linux.
     * Translated from openPath() in browser.ts
     *
     * @param path file or directory path to open
     * @return CompletableFuture resolving to true on success, false on failure
     */
    public static CompletableFuture<Boolean> openPath(String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                String command;
                if (os.contains("win")) {
                    command = "explorer";
                } else if (os.contains("mac")) {
                    command = "open";
                } else {
                    command = "xdg-open";
                }
                int code = new ProcessBuilder(command, path).start().waitFor();
                return code == 0;
            } catch (Exception e) {
                log.debug("Could not open path: {}", e.getMessage());
                return false;
            }
        });
    }

    private BrowserUtils() {}
}
