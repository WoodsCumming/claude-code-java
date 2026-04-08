package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Utility for copying ANSI-rendered screenshots to the system clipboard.
 * Supports macOS (osascript), Linux (xclip/xsel), and Windows (PowerShell).
 * Translated from src/utils/screenshotClipboard.ts
 */
@Slf4j
public class ScreenshotClipboardUtils {



    /**
     * Result of a clipboard copy operation.
     */
    public record ClipboardResult(boolean success, String message) {}

    /**
     * Copies an image (rendered from ANSI text) to the system clipboard.
     * Pipeline: ANSI text → PNG bytes → temp file → platform clipboard command.
     *
     * @param pngBytes the PNG image bytes to copy
     * @return CompletableFuture with success/message result
     */
    public static CompletableFuture<ClipboardResult> copyPngBytesToClipboard(byte[] pngBytes) {
        return CompletableFuture.supplyAsync(() -> {
            Path tempDir = null;
            Path pngPath = null;
            try {
                tempDir = Path.of(System.getProperty("java.io.tmpdir"), "claude-code-screenshots");
                Files.createDirectories(tempDir);

                pngPath = tempDir.resolve("screenshot-" + System.currentTimeMillis() + ".png");
                Files.write(pngPath, pngBytes);

                ClipboardResult result = copyPngToClipboard(pngPath);

                try {
                    Files.deleteIfExists(pngPath);
                } catch (IOException e) {
                    // Ignore cleanup errors
                }

                return result;
            } catch (Exception e) {
                log.error("Failed to copy screenshot to clipboard", e);
                return new ClipboardResult(false,
                        "Failed to copy screenshot: " + e.getMessage());
            }
        });
    }

    /**
     * Platform-dispatching helper that invokes the appropriate clipboard command.
     *
     * @param pngPath path to the PNG file to copy
     * @return ClipboardResult
     */
    private static ClipboardResult copyPngToClipboard(Path pngPath) {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("mac") || os.contains("darwin")) {
            return copyPngToClipboardMacOs(pngPath);
        } else if (os.contains("linux")) {
            return copyPngToClipboardLinux(pngPath);
        } else if (os.contains("win")) {
            return copyPngToClipboardWindows(pngPath);
        }

        return new ClipboardResult(false,
                "Screenshot to clipboard is not supported on " + os);
    }

    /**
     * macOS: use osascript to read the PNG file as «class PNGf» into clipboard.
     */
    private static ClipboardResult copyPngToClipboardMacOs(Path pngPath) {
        String escaped = pngPath.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        String script = "set the clipboard to (read (POSIX file \"" + escaped
                + "\") as \u00abclass PNGf\u00bb)";

        try {
            ProcessResult result = runProcess(5, TimeUnit.SECONDS,
                    "osascript", "-e", script);
            if (result.exitCode() == 0) {
                return new ClipboardResult(true, "Screenshot copied to clipboard");
            }
            return new ClipboardResult(false,
                    "Failed to copy to clipboard: " + result.stderr());
        } catch (Exception e) {
            return new ClipboardResult(false,
                    "Failed to copy to clipboard: " + e.getMessage());
        }
    }

    /**
     * Linux: try xclip first, then fall back to xsel.
     */
    private static ClipboardResult copyPngToClipboardLinux(Path pngPath) {
        try {
            ProcessResult xclipResult = runProcess(5, TimeUnit.SECONDS,
                    "xclip", "-selection", "clipboard", "-t", "image/png",
                    "-i", pngPath.toString());
            if (xclipResult.exitCode() == 0) {
                return new ClipboardResult(true, "Screenshot copied to clipboard");
            }
        } catch (Exception ignored) {
            // xclip not available — try xsel
        }

        try {
            ProcessResult xselResult = runProcess(5, TimeUnit.SECONDS,
                    "xsel", "--clipboard", "--input", "--type", "image/png");
            if (xselResult.exitCode() == 0) {
                return new ClipboardResult(true, "Screenshot copied to clipboard");
            }
        } catch (Exception ignored) {
            // xsel not available either
        }

        return new ClipboardResult(false,
                "Failed to copy to clipboard. Please install xclip or xsel: sudo apt install xclip");
    }

    /**
     * Windows: use PowerShell to load the image and put it in the clipboard.
     */
    private static ClipboardResult copyPngToClipboardWindows(Path pngPath) {
        String safePath = pngPath.toString().replace("'", "''");
        String psScript = "Add-Type -AssemblyName System.Windows.Forms; "
                + "[System.Windows.Forms.Clipboard]::SetImage("
                + "[System.Drawing.Image]::FromFile('" + safePath + "'))";

        try {
            ProcessResult result = runProcess(5, TimeUnit.SECONDS,
                    "powershell", "-NoProfile", "-Command", psScript);
            if (result.exitCode() == 0) {
                return new ClipboardResult(true, "Screenshot copied to clipboard");
            }
            return new ClipboardResult(false,
                    "Failed to copy to clipboard: " + result.stderr());
        } catch (Exception e) {
            return new ClipboardResult(false,
                    "Failed to copy to clipboard: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Internal process runner
    // -----------------------------------------------------------------------

    private record ProcessResult(int exitCode, String stdout, String stderr) {}

    private static ProcessResult runProcess(long timeout, TimeUnit unit, String... command)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        boolean finished = process.waitFor(timeout, unit);
        if (!finished) {
            process.destroyForcibly();
            return new ProcessResult(-1, "", "Process timed out");
        }

        String stdout = new String(process.getInputStream().readAllBytes()).trim();
        String stderr = new String(process.getErrorStream().readAllBytes()).trim();
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }
}
