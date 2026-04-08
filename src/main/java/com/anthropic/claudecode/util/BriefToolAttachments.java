package com.anthropic.claudecode.util;

import com.anthropic.claudecode.service.BriefToolUploadService;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Shared attachment validation and resolution for SendUserMessage and SendUserFile.
 * Translated from src/tools/BriefTool/attachments.ts
 */
@Slf4j
public class BriefToolAttachments {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BriefToolAttachments.class);


    // Matches common image extensions (png, jpg, jpeg, gif, webp, bmp, svg, etc.)
    private static final Pattern IMAGE_EXTENSION_REGEX =
            Pattern.compile("(?i)\\.(png|jpe?g|gif|webp|bmp|svg|ico|tiff?)$");

    /**
     * Result of validating attachment paths.
     */
    public sealed interface ValidationResult permits ValidationResult.Valid, ValidationResult.Invalid {
        record Valid() implements ValidationResult {}
        record Invalid(String message, int errorCode) implements ValidationResult {}
    }

    /**
     * A resolved attachment with path, size, image flag, and optional upload UUID.
     */
    @Builder
    public record ResolvedAttachment(
            String path,
            long size,
            boolean isImage,
            String fileUuid  // nullable; set after successful upload
    ) {}

    /**
     * Context for upload operations.
     */
    public record UploadContext(boolean replBridgeEnabled) {}

    /**
     * Validate that all provided paths refer to regular, accessible files.
     * Translated from validateAttachmentPaths() in attachments.ts
     */
    public static ValidationResult validateAttachmentPaths(List<String> rawPaths) {
        String cwd = System.getProperty("user.dir");
        for (String rawPath : rawPaths) {
            Path fullPath = expandPath(rawPath);
            if (!Files.exists(fullPath)) {
                return new ValidationResult.Invalid(
                        "Attachment \"" + rawPath + "\" does not exist. Current working directory: " + cwd + ".",
                        1
                );
            }
            if (!Files.isRegularFile(fullPath)) {
                return new ValidationResult.Invalid(
                        "Attachment \"" + rawPath + "\" is not a regular file.",
                        1
                );
            }
            if (!Files.isReadable(fullPath)) {
                return new ValidationResult.Invalid(
                        "Attachment \"" + rawPath + "\" is not accessible (permission denied).",
                        1
                );
            }
        }
        return new ValidationResult.Valid();
    }

    /**
     * Stat each path and optionally upload to get file_uuid values.
     * Translated from resolveAttachments() in attachments.ts
     *
     * @param rawPaths    list of raw (possibly ~-prefixed) paths
     * @param uploadCtx   upload context carrying replBridgeEnabled flag
     * @param uploadService service to perform the actual upload (may be null for non-bridge mode)
     */
    public static CompletableFuture<List<ResolvedAttachment>> resolveAttachments(
            List<String> rawPaths,
            UploadContext uploadCtx,
            BriefToolUploadService uploadService
    ) {
        return CompletableFuture.supplyAsync(() -> {
            List<ResolvedAttachment> stated = new ArrayList<>();
            for (String rawPath : rawPaths) {
                Path fullPath = expandPath(rawPath);
                try {
                    long size = Files.size(fullPath);
                    boolean isImage = IMAGE_EXTENSION_REGEX.matcher(fullPath.toString()).find();
                    stated.add(ResolvedAttachment.builder()
                            .path(fullPath.toString())
                            .size(size)
                            .isImage(isImage)
                            .build());
                } catch (IOException e) {
                    throw new RuntimeException("Failed to stat attachment: " + rawPath, e);
                }
            }
            return stated;
        }).thenCompose(stated -> {
            // Only upload when upload service is provided (bridge mode) and bridge is enabled
            if (uploadService == null) {
                return CompletableFuture.completedFuture(stated);
            }
            boolean shouldUpload = uploadCtx.replBridgeEnabled()
                    || "true".equalsIgnoreCase(System.getenv("CLAUDE_CODE_BRIEF_UPLOAD"));
            if (!shouldUpload) {
                return CompletableFuture.completedFuture(stated);
            }

            // Upload all attachments in parallel
            List<CompletableFuture<String>> uuidFutures = stated.stream()
                    .map(a -> uploadService.uploadBriefAttachment(
                            a.path(), a.size(),
                            new BriefToolUploadService.BriefUploadContext(shouldUpload)))
                    .toList();

            return CompletableFuture.allOf(uuidFutures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        List<ResolvedAttachment> result = new ArrayList<>();
                        for (int i = 0; i < stated.size(); i++) {
                            ResolvedAttachment a = stated.get(i);
                            String uuid = uuidFutures.get(i).join();
                            if (uuid != null) {
                                result.add(ResolvedAttachment.builder()
                                        .path(a.path())
                                        .size(a.size())
                                        .isImage(a.isImage())
                                        .fileUuid(uuid)
                                        .build());
                            } else {
                                result.add(a);
                            }
                        }
                        return result;
                    });
        });
    }

    /**
     * Expand a path, resolving ~ to the user home directory.
     */
    private static Path expandPath(String rawPath) {
        if (rawPath.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), rawPath.substring(2));
        }
        return Path.of(rawPath);
    }

    private BriefToolAttachments() {}
}
