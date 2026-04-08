package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * MCP Cross-App Access (XAA) service.
 * Translated from src/services/mcp/xaa.ts
 *
 * Obtains MCP access tokens without a browser consent screen by chaining
 * RFC 8693 Token Exchange and RFC 7523 JWT Bearer Grant.
 */
@Slf4j
@Service
public class McpXaaService {



    private static final String TOKEN_EXCHANGE_GRANT = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    private static final String ID_JAG_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:id-jag";
    private static final String ID_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:id_token";
    private static final long XAA_REQUEST_TIMEOUT_MS = 30_000;

    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public McpXaaService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Exchange an ID token for an MCP access token.
     * Translated from obtainXaaToken() in xaa.ts
     */
    public CompletableFuture<Optional<String>> obtainXaaToken(
            String idToken,
            String serverUrl) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Step 1: Discover authorization server metadata
                String asMetadataUrl = serverUrl + "/.well-known/oauth-authorization-server";
                Map<String, Object> asMetadata = fetchJson(asMetadataUrl);
                if (asMetadata == null) return Optional.empty();

                String tokenEndpoint = (String) asMetadata.get("token_endpoint");
                if (tokenEndpoint == null) return Optional.empty();

                // Step 2: Token exchange (RFC 8693): id_token → ID-JAG
                String idJag = tokenExchange(tokenEndpoint, idToken);
                if (idJag == null) return Optional.empty();

                // Step 3: JWT Bearer Grant (RFC 7523): ID-JAG → access_token
                String accessToken = jwtBearerGrant(tokenEndpoint, idJag);
                return Optional.ofNullable(accessToken);

            } catch (Exception e) {
                log.debug("[XAA] Failed to obtain token: {}", e.getMessage());
                return Optional.empty();
            }
        });
    }

    private String tokenExchange(String tokenEndpoint, String idToken) {
        String body = "grant_type=" + urlEncode(TOKEN_EXCHANGE_GRANT)
            + "&subject_token=" + urlEncode(idToken)
            + "&subject_token_type=" + urlEncode(ID_TOKEN_TYPE)
            + "&requested_token_type=" + urlEncode(ID_JAG_TOKEN_TYPE);

        try {
            Request request = new Request.Builder()
                .url(tokenEndpoint)
                .post(RequestBody.create(body, FORM))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                Map<String, Object> result = objectMapper.readValue(response.body().string(), Map.class);
                return (String) result.get("access_token");
            }
        } catch (Exception e) {
            log.debug("[XAA] Token exchange failed: {}", e.getMessage());
            return null;
        }
    }

    private String jwtBearerGrant(String tokenEndpoint, String assertion) {
        String body = "grant_type=" + urlEncode(JWT_BEARER_GRANT)
            + "&assertion=" + urlEncode(assertion);

        try {
            Request request = new Request.Builder()
                .url(tokenEndpoint)
                .post(RequestBody.create(body, FORM))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                Map<String, Object> result = objectMapper.readValue(response.body().string(), Map.class);
                return (String) result.get("access_token");
            }
        } catch (Exception e) {
            log.debug("[XAA] JWT bearer grant failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> fetchJson(String url) {
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                return objectMapper.readValue(response.body().string(), Map.class);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}
