package com.anthropic.claudecode.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * DXT (Desktop Extension Tool) utilities.
 * Translated from src/utils/dxt/helpers.ts and src/utils/dxt/zip.ts
 *
 * DXT files are zip archives containing MCP server plugins.
 */
@Slf4j
public class DxtUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DxtUtils.class);


    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse and validate a DXT manifest from JSON.
     * Translated from validateManifest() in helpers.ts
     */
    public static Map<String, Object> validateManifest(String manifestJson) throws Exception {
        Map<String, Object> manifest = objectMapper.readValue(manifestJson, Map.class);

        // Basic validation
        if (!manifest.containsKey("name")) {
            throw new IllegalArgumentException("Invalid manifest: missing 'name' field");
        }
        if (!manifest.containsKey("version")) {
            throw new IllegalArgumentException("Invalid manifest: missing 'version' field");
        }

        return manifest;
    }

    /**
     * Extract a DXT file (zip archive) to a directory.
     * Translated from extractDxt() in zip.ts
     */
    public static void extractDxt(Path dxtPath, Path targetDir) throws Exception {
        Files.createDirectories(targetDir);

        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(dxtPath.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();

                // Security: prevent path traversal
                if (!entryPath.startsWith(targetDir)) {
                    throw new SecurityException("Zip path traversal detected: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * Read the manifest from a DXT file.
     */
    public static Map<String, Object> readManifestFromDxt(Path dxtPath) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(dxtPath.toFile()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("manifest.json".equals(entry.getName())) {
                    byte[] bytes = zis.readAllBytes();
                    String json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    return validateManifest(json);
                }
                zis.closeEntry();
            }
        }
        throw new IllegalArgumentException("No manifest.json found in DXT file");
    }

    private DxtUtils() {}
}
