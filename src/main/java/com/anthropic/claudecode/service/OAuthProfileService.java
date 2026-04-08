package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.OAuthConfig;
import com.anthropic.claudecode.model.OAuthTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * OAuth profile service.
 * Translated from src/services/oauth/getOauthProfile.ts
 *
 * Fetches user profile information from the OAuth API.
 */
@Slf4j
@Service
public class OAuthProfileService {

    private final ObjectMapper objectMapper;

    @Autowired
    public OAuthProfileService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Get OAuth profile from API key.
     * Translated from getOauthProfileFromApiKey() in getOauthProfile.ts
     */
    public CompletableFuture<Optional<OAuthTypes.OAuthProfileResponse>> getOauthProfileFromApiKey(
            String apiKey,
            String accountUuid) {

        if (apiKey == null || accountUuid == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String endpoint = OAuthConfig.current().getBaseApiUrl() + "/api/claude_cli_profile";

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "?account_uuid=" + URLEncoder.encode(accountUuid, "UTF-8")))
                    .header("x-api-key", apiKey)
                    .header("anthropic-beta", OAuthConfig.OAUTH_BETA_HEADER)
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    OAuthTypes.OAuthProfileResponse profile = objectMapper.readValue(
                        response.body(), OAuthTypes.OAuthProfileResponse.class);
                    return Optional.of(profile);
                }

                return Optional.empty();

            } catch (Exception e) {
                log.debug("Could not get OAuth profile from API key: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }

    /**
     * Get OAuth profile from OAuth token.
     * Translated from getOauthProfileFromOauthToken() in getOauthProfile.ts
     */
    public CompletableFuture<Optional<OAuthTypes.OAuthProfileResponse>> getOauthProfileFromOauthToken(
            String accessToken) {

        if (accessToken == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                String endpoint = OAuthConfig.current().getBaseApiUrl() + "/api/oauth/profile";

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    OAuthTypes.OAuthProfileResponse profile = objectMapper.readValue(
                        response.body(), OAuthTypes.OAuthProfileResponse.class);
                    return Optional.of(profile);
                }

                return Optional.empty();

            } catch (Exception e) {
                log.debug("Could not get OAuth profile from token: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }
}
