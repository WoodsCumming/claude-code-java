package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.DxtUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Official marketplace GCS service.
 * Translated from src/utils/plugins/officialMarketplaceGcs.ts
 *
 * Fetches the official marketplace from a GCS mirror instead of git-cloning GitHub.
 */
@Slf4j
@Service
public class OfficialMarketplaceGcsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OfficialMarketplaceGcsService.class);


    private static final String GCS_BASE_URL = "https://storage.googleapis.com/claude-code-marketplace";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public OfficialMarketplaceGcsService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Fetch the latest marketplace from GCS.
     * Translated from fetchMarketplaceFromGcs() in officialMarketplaceGcs.ts
     */
    public CompletableFuture<GcsFetchResult> fetchMarketplaceFromGcs(String targetDir) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Get the latest SHA pointer
                String latestUrl = GCS_BASE_URL + "/latest";
                Request request = new Request.Builder().url(latestUrl).get().build();

                String latestSha;
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        return new GcsFetchResult(false, null, "Failed to fetch latest pointer");
                    }
                    latestSha = response.body().string().trim();
                }

                // Check if we already have this version
                String sentinelPath = targetDir + "/.gcs-sha";
                if (new File(sentinelPath).exists()) {
                    String currentSha = Files.readString(Paths.get(sentinelPath)).trim();
                    if (latestSha.equals(currentSha)) {
                        return new GcsFetchResult(true, latestSha, null);
                    }
                }

                // Download the marketplace zip
                String zipUrl = GCS_BASE_URL + "/" + latestSha + "/marketplace.zip";
                Request zipRequest = new Request.Builder().url(zipUrl).get().build();

                String tempZipPath = System.getProperty("java.io.tmpdir") + "/claude-marketplace.zip";
                try (Response zipResponse = httpClient.newCall(zipRequest).execute()) {
                    if (!zipResponse.isSuccessful() || zipResponse.body() == null) {
                        return new GcsFetchResult(false, null, "Failed to download marketplace zip");
                    }
                    Files.write(Paths.get(tempZipPath), zipResponse.body().bytes());
                }

                // Extract the zip
                Path targetPath = Paths.get(targetDir);
                Files.createDirectories(targetPath);
                DxtUtils.extractDxt(Paths.get(tempZipPath), targetPath);
                new File(tempZipPath).delete();

                // Write sentinel
                Files.writeString(Paths.get(sentinelPath), latestSha);

                return new GcsFetchResult(true, latestSha, null);

            } catch (Exception e) {
                log.debug("Could not fetch marketplace from GCS: {}", e.getMessage());
                return new GcsFetchResult(false, null, e.getMessage());
            }
        });
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class GcsFetchResult {
        private boolean success;
        private String sha;
        private String error;

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean v) { success = v; }
        public String getSha() { return sha; }
        public void setSha(String v) { sha = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
    }
}
