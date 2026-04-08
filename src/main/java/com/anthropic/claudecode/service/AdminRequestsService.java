package com.anthropic.claudecode.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Admin requests service.
 * Translated from src/services/api/adminRequests.ts
 *
 * Manages admin requests for limit increases and seat upgrades.
 * For Team/Enterprise users without billing/admin permissions, this creates
 * requests that their admin can act on.
 */
@Slf4j
@Service
public class AdminRequestsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminRequestsService.class);


    // -------------------------------------------------------------------------
    // Enums — mirrors TypeScript union string literals
    // -------------------------------------------------------------------------

    /** Translated from AdminRequestType union type in adminRequests.ts */
    public enum AdminRequestType {
        @JsonProperty("limit_increase") LIMIT_INCREASE,
        @JsonProperty("seat_upgrade")   SEAT_UPGRADE
    }

    /** Translated from AdminRequestStatus union type in adminRequests.ts */
    public enum AdminRequestStatus {
        @JsonProperty("pending")   PENDING,
        @JsonProperty("approved")  APPROVED,
        @JsonProperty("dismissed") DISMISSED
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdminRequestSeatUpgradeDetails {
        private String message;
        @JsonProperty("current_seat_tier")
        private String currentSeatTier;

        public String getMessage() { return message; }
        public void setMessage(String v) { message = v; }
        public String getCurrentSeatTier() { return currentSeatTier; }
        public void setCurrentSeatTier(String v) { currentSeatTier = v; }
    }

    /**
     * Unified AdminRequest response type.
     * Mirrors the AdminRequest discriminated union in adminRequests.ts.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdminRequest {
        private String uuid;
        private AdminRequestStatus status;
        @JsonProperty("request_type")
        private AdminRequestType requestType;
        private Object details;   // null for limit_increase; AdminRequestSeatUpgradeDetails for seat_upgrade
        @JsonProperty("requester_uuid")
        private String requesterUuid;
        @JsonProperty("created_at")
        private String createdAt;

        public String getUuid() { return uuid; }
        public void setUuid(String v) { uuid = v; }
        public AdminRequestStatus getStatus() { return status; }
        public void setStatus(AdminRequestStatus v) { status = v; }
        public AdminRequestType getRequestType() { return requestType; }
        public void setRequestType(AdminRequestType v) { requestType = v; }
        public Object getDetails() { return details; }
        public void setDetails(Object v) { details = v; }
        public String getRequesterUuid() { return requesterUuid; }
        public void setRequesterUuid(String v) { requesterUuid = v; }
        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String v) { createdAt = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdminRequestEligibilityResponse {
        @JsonProperty("request_type")
        private AdminRequestType requestType;
        @JsonProperty("is_allowed")
        private boolean isAllowed;

        public boolean isIsAllowed() { return isAllowed; }
        public boolean isAllowed() { return isAllowed; }
        public void setIsAllowed(boolean v) { isAllowed = v; }
    }

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final OAuthService oauthService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AdminRequestsService(OAuthService oauthService, ObjectMapper objectMapper) {
        this.oauthService = oauthService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Create an admin request (limit increase or seat upgrade).
     * If a pending request of the same type already exists for this user,
     * returns the existing request instead of creating a new one.
     * Translated from createAdminRequest() in adminRequests.ts
     */
    public CompletableFuture<AdminRequest> createAdminRequest(
            AdminRequestType requestType,
            Object details) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                PreparedRequest prep = prepareApiRequest();

                Map<String, Object> body = new LinkedHashMap<>();
                body.put("request_type", requestType.name().toLowerCase());
                body.put("details", details);

                String bodyJson = objectMapper.writeValueAsString(body);
                String url = getBaseApiUrl()
                        + "/api/oauth/organizations/" + prep.orgUUID + "/admin_requests";

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + prep.accessToken)
                        .header("anthropic-beta", "oauth-2023-11-01")
                        .header("x-organization-uuid", prep.orgUUID)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());

                return objectMapper.readValue(response.body(), AdminRequest.class);

            } catch (Exception e) {
                log.error("createAdminRequest failed: {}", e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Get pending admin requests of a specific type for the current user.
     * Returns null when the request fails.
     * Translated from getMyAdminRequests() in adminRequests.ts
     */
    public CompletableFuture<List<AdminRequest>> getMyAdminRequests(
            AdminRequestType requestType,
            List<AdminRequestStatus> statuses) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                PreparedRequest prep = prepareApiRequest();

                StringBuilder urlBuilder = new StringBuilder(
                        getBaseApiUrl()
                        + "/api/oauth/organizations/" + prep.orgUUID
                        + "/admin_requests/me?request_type=" + requestType.name().toLowerCase());
                for (AdminRequestStatus status : statuses) {
                    urlBuilder.append("&statuses=").append(status.name().toLowerCase());
                }

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(urlBuilder.toString()))
                        .header("Authorization", "Bearer " + prep.accessToken)
                        .header("anthropic-beta", "oauth-2023-11-01")
                        .header("x-organization-uuid", prep.orgUUID)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return objectMapper.readValue(response.body(),
                            objectMapper.getTypeFactory()
                                    .constructCollectionType(ArrayList.class, AdminRequest.class));
                }
                return null;

            } catch (Exception e) {
                log.error("getMyAdminRequests failed: {}", e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * Check if a specific admin request type is allowed for this org.
     * Translated from checkAdminRequestEligibility() in adminRequests.ts
     */
    public CompletableFuture<AdminRequestEligibilityResponse> checkAdminRequestEligibility(
            AdminRequestType requestType) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                PreparedRequest prep = prepareApiRequest();

                String url = getBaseApiUrl()
                        + "/api/oauth/organizations/" + prep.orgUUID
                        + "/admin_requests/eligibility?request_type="
                        + requestType.name().toLowerCase();

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + prep.accessToken)
                        .header("anthropic-beta", "oauth-2023-11-01")
                        .header("x-organization-uuid", prep.orgUUID)
                        .GET()
                        .timeout(Duration.ofSeconds(10))
                        .build();

                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    return objectMapper.readValue(response.body(),
                            AdminRequestEligibilityResponse.class);
                }
                return null;

            } catch (Exception e) {
                log.error("checkAdminRequestEligibility failed: {}", e.getMessage(), e);
                return null;
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private record PreparedRequest(String accessToken, String orgUUID) {}

    private PreparedRequest prepareApiRequest() {
        OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
        if (tokens == null || tokens.getAccessToken() == null) {
            throw new IllegalStateException("No OAuth tokens available");
        }
        String orgUUID = oauthService.getOrganizationUUID().join();
        if (orgUUID == null) {
            throw new IllegalStateException("No organization UUID available");
        }
        return new PreparedRequest(tokens.getAccessToken(), orgUUID);
    }

    private String getBaseApiUrl() {
        String url = System.getenv("ANTHROPIC_BASE_API_URL");
        return url != null ? url : "https://api.anthropic.com";
    }

    // Convenience overloads accepting String requestType
    public CompletableFuture<AdminRequestEligibilityResponse> checkAdminRequestEligibility(String requestType) {
        try {
            return checkAdminRequestEligibility(AdminRequestType.valueOf(
                requestType.toUpperCase().replace("-", "_")));
        } catch (Exception e) {
            return checkAdminRequestEligibility(AdminRequestType.LIMIT_INCREASE);
        }
    }

    public CompletableFuture<List<AdminRequest>> getMyAdminRequests(String requestType, List<String> statuses) {
        AdminRequestType type;
        try {
            type = AdminRequestType.valueOf(requestType.toUpperCase().replace("-", "_"));
        } catch (Exception e) {
            type = AdminRequestType.LIMIT_INCREASE;
        }
        List<AdminRequestStatus> statusList = new java.util.ArrayList<>();
        if (statuses != null) {
            for (String s : statuses) {
                try {
                    statusList.add(AdminRequestStatus.valueOf(s.toUpperCase()));
                } catch (Exception e) {
                    // skip unknown
                }
            }
        }
        return getMyAdminRequests(type, statusList);
    }

    public CompletableFuture<AdminRequest> createAdminRequest(CreateAdminRequestPayload payload) {
        if (payload == null) return CompletableFuture.completedFuture(null);
        return createAdminRequest(payload.requestType(), payload.details());
    }

    /** Payload for createAdminRequest. */
    public record CreateAdminRequestPayload(AdminRequestType requestType, Object details) {}
}
