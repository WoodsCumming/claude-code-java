package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.*;

/**
 * Plugin zip cache adapters service.
 * Translated from src/utils/plugins/zipCacheAdapters.ts
 *
 * I/O helpers for the plugin zip cache.
 */
@Slf4j
@Service
public class PluginZipCacheAdaptersService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PluginZipCacheAdaptersService.class);


    private final ObjectMapper objectMapper;
    private final PluginZipCacheService zipCacheService;

    @Autowired
    public PluginZipCacheAdaptersService(ObjectMapper objectMapper,
                                          PluginZipCacheService zipCacheService) {
        this.objectMapper = objectMapper;
        this.zipCacheService = zipCacheService;
    }

    /**
     * Create a ZIP archive of a plugin directory.
     * Translated from createPluginZip() in zipCacheAdapters.ts
     */
    public CompletableFuture<Optional<String>> createPluginZip(
            String pluginDir,
            String pluginId) {

        return CompletableFuture.supplyAsync(() -> {
            if (!zipCacheService.isZipCacheEnabled()) return Optional.empty();

            String cacheDir = zipCacheService.getZipCacheDir();
            String zipPath = cacheDir + "/plugins/" + pluginId + ".zip";

            try {
                new File(zipPath).getParentFile().mkdirs();

                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipPath))) {
                    Path pluginPath = Paths.get(pluginDir);
                    Files.walk(pluginPath)
                        .filter(Files::isRegularFile)
                        .forEach(file -> {
                            try {
                                String entryName = pluginPath.relativize(file).toString();
                                zos.putNextEntry(new ZipEntry(entryName));
                                Files.copy(file, zos);
                                zos.closeEntry();
                            } catch (Exception e) {
                                log.debug("Could not add file to zip: {}", e.getMessage());
                            }
                        });
                }

                return Optional.of(zipPath);
            } catch (Exception e) {
                log.debug("Could not create plugin zip: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }

    /**
     * Read the known marketplaces from zip cache.
     */
    public Optional<Map<String, Object>> readZipCacheMarketplaces() {
        if (!zipCacheService.isZipCacheEnabled()) return Optional.empty();

        String path = zipCacheService.getZipCacheDir() + "/known_marketplaces.json";
        try {
            Map<String, Object> data = objectMapper.readValue(new File(path), Map.class);
            return Optional.of(data);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
