package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.EnvUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Paste store service for persisting large pasted content to disk.
 * Translated from src/utils/pasteStore.ts
 *
 * Uses content-addressable storage: the hash of the content is the filename,
 * so the same content is never written twice. Old files are pruned by a
 * time-based cleanup.
 */
@Slf4j
@Service
public class PasteStoreService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PasteStoreService.class);


    private static final String PASTE_STORE_DIR = "paste-cache";

    // =========================================================================
    // Directory helpers
    // =========================================================================

    /**
     * Get the paste store directory (persistent across sessions).
     * Translated from getPasteStoreDir() in pasteStore.ts
     */
    public String getPasteStoreDir() {
        return EnvUtils.getClaudeConfigHomeDir() + "/" + PASTE_STORE_DIR;
    }

    private String getPastePath(String hash) {
        return getPasteStoreDir() + "/" + hash + ".txt";
    }

    // =========================================================================
    // Hashing
    // =========================================================================

    /**
     * Generate a 16-hex-char SHA-256 hash of the content.
     * Exported so callers can obtain the hash synchronously before async storage.
     * Translated from hashPastedText() in pasteStore.ts
     */
    public String hashPastedText(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().substring(0, 16);
        } catch (Exception e) {
            // Fallback — should never happen with SHA-256
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }

    // =========================================================================
    // Store / retrieve
    // =========================================================================

    /**
     * Store pasted text content to disk.
     * The hash should be pre-computed with hashPastedText() so the caller can
     * use it immediately without waiting for the async disk write.
     * Translated from storePastedText() in pasteStore.ts
     */
    public CompletableFuture<Void> storePastedText(String hash, String content) {
        return CompletableFuture.runAsync(() -> {
            try {
                String dir = getPasteStoreDir();
                Files.createDirectories(Paths.get(dir));

                Path pastePath = Paths.get(getPastePath(hash));
                // Content-addressable: same hash = same content, overwriting is safe
                Files.writeString(pastePath, content,
                        java.nio.charset.StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);

                // Restrict permissions to owner read/write (mode 0600)
                try {
                    pastePath.toFile().setReadable(false, false);
                    pastePath.toFile().setReadable(true, true);
                    pastePath.toFile().setWritable(false, false);
                    pastePath.toFile().setWritable(true, true);
                } catch (Exception ignored) {}

                log.debug("Stored paste {} to {}", hash, pastePath);
            } catch (IOException e) {
                log.debug("Failed to store paste: {}", e.getMessage());
            }
        });
    }

    /**
     * Retrieve pasted text content by its hash.
     * Returns empty if not found or on error.
     * Translated from retrievePastedText() in pasteStore.ts
     */
    public CompletableFuture<Optional<String>> retrievePastedText(String hash) {
        return CompletableFuture.supplyAsync(() -> {
            Path pastePath = Paths.get(getPastePath(hash));
            if (!Files.exists(pastePath)) return Optional.empty();
            try {
                return Optional.of(Files.readString(pastePath, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.debug("Failed to retrieve paste {}: {}", hash, e.getMessage());
                return Optional.empty();
            }
        });
    }

    /**
     * Synchronous retrieve (convenience method).
     */
    public Optional<String> retrievePastedTextSync(String hash) {
        Path pastePath = Paths.get(getPastePath(hash));
        if (!Files.exists(pastePath)) return Optional.empty();
        try {
            return Optional.of(Files.readString(pastePath, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Failed to retrieve paste {}: {}", hash, e.getMessage());
            return Optional.empty();
        }
    }

    // =========================================================================
    // Cleanup
    // =========================================================================

    /**
     * Clean up old paste files that are no longer referenced.
     * Removes files whose last-modified time is before {@code cutoffDate}.
     * Translated from cleanupOldPastes() in pasteStore.ts
     */
    public CompletableFuture<Void> cleanupOldPastes(Date cutoffDate) {
        return CompletableFuture.runAsync(() -> {
            Path pasteDir = Paths.get(getPasteStoreDir());
            if (!Files.exists(pasteDir)) return;

            long cutoffTime = cutoffDate.getTime();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(pasteDir, "*.txt")) {
                for (Path filePath : stream) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(
                                filePath, BasicFileAttributes.class);
                        Instant modified = attrs.lastModifiedTime().toInstant();
                        if (modified.toEpochMilli() < cutoffTime) {
                            Files.deleteIfExists(filePath);
                            log.debug("Cleaned up old paste: {}", filePath);
                        }
                    } catch (IOException e) {
                        // Ignore errors for individual files — same behaviour as TS version
                        log.debug("Could not check/delete paste file {}: {}", filePath, e.getMessage());
                    }
                }
            } catch (IOException e) {
                // Directory can't be read — nothing to clean up
                log.debug("Cannot read paste store directory: {}", e.getMessage());
            }
        });
    }

    /**
     * Convenience overload: remove pastes older than {@code maxAgeMs} milliseconds.
     */
    public CompletableFuture<Void> cleanupOldPastes(long maxAgeMs) {
        return cleanupOldPastes(new Date(System.currentTimeMillis() - maxAgeMs));
    }
}
