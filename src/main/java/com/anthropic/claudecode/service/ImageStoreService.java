package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Image store service for persisting pasted images to disk.
 * Translated from src/utils/imageStore.ts
 *
 * Stores images from pastedContents to the session image-cache directory.
 * Uses an in-memory LRU cache (max 200 entries) to track stored paths.
 */
@Slf4j
@Service
public class ImageStoreService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImageStoreService.class);


    private static final String IMAGE_STORE_DIR = "image-cache";
    private static final int MAX_STORED_IMAGE_PATHS = 200;

    /**
     * Pasted content entry.
     * Mirrors the PastedContent type from config.ts.
     */
    public static class PastedContent {
        public final String type;   // "image" | "text"
        public final int id;
        public final String content;
        public final String mediaType;

        public PastedContent(String type, int id, String content, String mediaType) {
            this.type = type;
            this.id = id;
            this.content = content;
            this.mediaType = mediaType;
        }
    }

    // In-memory LRU cache of stored image paths (id → absolute path)
    // ConcurrentLinkedHashMap gives insertion-order iteration for eviction.
    private final Map<Integer, String> storedImagePaths =
            Collections.synchronizedMap(new LinkedHashMap<>(MAX_STORED_IMAGE_PATHS, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                    return size() > MAX_STORED_IMAGE_PATHS;
                }
            });

    private final BootstrapStateService bootstrapStateService;

    @Autowired
    public ImageStoreService(BootstrapStateService bootstrapStateService) {
        this.bootstrapStateService = bootstrapStateService;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Cache the image path immediately (fast, no file I/O).
     * Translated from cacheImagePath() in imageStore.ts
     *
     * @return the path that will be used, or null if content is not an image
     */
    public String cacheImagePath(PastedContent content) {
        if (!"image".equals(content.type)) {
            return null;
        }
        String imagePath = getImagePath(content.id, content.mediaType != null ? content.mediaType : "image/png");
        storedImagePaths.put(content.id, imagePath);
        return imagePath;
    }

    /**
     * Store an image from pastedContents to disk.
     * Translated from storeImage() in imageStore.ts
     *
     * @return CompletableFuture resolving to the stored path, or null on failure/non-image
     */
    public CompletableFuture<String> storeImage(PastedContent content) {
        if (!"image".equals(content.type)) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureImageStoreDir();
                String imagePath = getImagePath(content.id, content.mediaType != null ? content.mediaType : "image/png");

                // Write base64-decoded image data with restricted permissions (0600)
                byte[] imageBytes = Base64.getDecoder().decode(content.content);
                Path path = Paths.get(imagePath);
                Files.write(path, imageBytes,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

                // Set file permissions to owner-only (mirrors open(..., 0o600) in TS)
                try {
                    path.toFile().setReadable(true, true);
                    path.toFile().setWritable(true, true);
                    path.toFile().setExecutable(false, false);
                } catch (SecurityException ignored) {
                    // Best-effort permission set
                }

                storedImagePaths.put(content.id, imagePath);
                log.debug("Stored image {} to {}", content.id, imagePath);
                return imagePath;
            } catch (IOException e) {
                log.debug("Failed to store image: {}", e.getMessage());
                return null;
            }
        });
    }

    /**
     * Store all images from a pastedContents map to disk.
     * Translated from storeImages() in imageStore.ts
     *
     * @return CompletableFuture resolving to a map of id → stored path
     */
    public CompletableFuture<Map<Integer, String>> storeImages(Map<Integer, PastedContent> pastedContents) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        Map<Integer, String> pathMap = Collections.synchronizedMap(new LinkedHashMap<>());

        for (Map.Entry<Integer, PastedContent> entry : pastedContents.entrySet()) {
            if ("image".equals(entry.getValue().type)) {
                int id = entry.getKey();
                CompletableFuture<Void> f = storeImage(entry.getValue()).thenAccept(path -> {
                    if (path != null) {
                        pathMap.put(id, path);
                    }
                });
                futures.add(f);
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(ignored -> pathMap);
    }

    /**
     * Get the file path for a stored image by ID.
     * Translated from getStoredImagePath() in imageStore.ts
     *
     * @return the path, or null if not in cache
     */
    public String getStoredImagePath(int imageId) {
        return storedImagePaths.get(imageId);
    }

    /**
     * Clear the in-memory cache of stored image paths.
     * Translated from clearStoredImagePaths() in imageStore.ts
     */
    public void clearStoredImagePaths() {
        storedImagePaths.clear();
    }

    /**
     * Clean up old image cache directories from previous sessions.
     * Translated from cleanupOldImageCaches() in imageStore.ts
     */
    public CompletableFuture<Void> cleanupOldImageCaches() {
        return CompletableFuture.runAsync(() -> {
            String baseDir = getClaudeConfigHomeDir() + File.separator + IMAGE_STORE_DIR;
            String currentSessionId = bootstrapStateService.getSessionId();

            File baseDirFile = new File(baseDir);
            if (!baseDirFile.exists()) return;

            File[] sessionDirs;
            try {
                sessionDirs = baseDirFile.listFiles(File::isDirectory);
            } catch (SecurityException e) {
                return;
            }
            if (sessionDirs == null) return;

            for (File sessionDir : sessionDirs) {
                if (sessionDir.getName().equals(currentSessionId)) {
                    continue; // Skip current session
                }
                try {
                    deleteDirectoryRecursively(sessionDir.toPath());
                    log.debug("Cleaned up old image cache: {}", sessionDir);
                } catch (IOException e) {
                    // Ignore errors for individual directories
                }
            }

            // Remove base dir if now empty
            try {
                File[] remaining = baseDirFile.listFiles();
                if (remaining != null && remaining.length == 0) {
                    baseDirFile.delete();
                }
            } catch (SecurityException ignored) {
                // Ignore
            }
        });
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Get the image store directory for the current session.
     * Translated from getImageStoreDir() in imageStore.ts
     */
    private String getImageStoreDir() {
        return getClaudeConfigHomeDir()
                + File.separator + IMAGE_STORE_DIR
                + File.separator + bootstrapStateService.getSessionId();
    }

    /**
     * Ensure the image store directory exists.
     * Translated from ensureImageStoreDir() in imageStore.ts
     */
    private void ensureImageStoreDir() throws IOException {
        Files.createDirectories(Paths.get(getImageStoreDir()));
    }

    /**
     * Get the file path for an image by ID and media type.
     * Translated from getImagePath() in imageStore.ts
     */
    private String getImagePath(int imageId, String mediaType) {
        String extension = extensionFromMediaType(mediaType);
        return getImageStoreDir() + File.separator + imageId + "." + extension;
    }

    /**
     * Derive a file extension from a MIME media type.
     * e.g. "image/jpeg" → "jpeg", "image/png" → "png"
     */
    private static String extensionFromMediaType(String mediaType) {
        if (mediaType == null) return "png";
        String[] parts = mediaType.split("/");
        if (parts.length >= 2 && !parts[1].isBlank()) {
            return parts[1];
        }
        return "png";
    }

    private static String getClaudeConfigHomeDir() {
        String dir = System.getenv("CLAUDE_CONFIG_HOME");
        if (dir != null) return dir;
        return System.getProperty("user.home") + File.separator + ".claude";
    }

    private static void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                            // Best-effort deletion
                        }
                    });
        }
    }
}
