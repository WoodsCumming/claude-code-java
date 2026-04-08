package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

/**
 * OAuth-related types.
 * Translated from src/services/oauth/types.ts
 */
public class OAuthTypes {

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OAuthTokens {
        private String accessToken;
        private String refreshToken;
        private long expiresAt;
        private String scope;
        private String tokenType;

        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String v) { accessToken = v; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String v) { refreshToken = v; }
        public long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(long v) { expiresAt = v; }
        public String getScope() { return scope; }
        public void setScope(String v) { scope = v; }
        public String getTokenType() { return tokenType; }
        public void setTokenType(String v) { tokenType = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class OAuthTokenExchangeResponse {
        private String accessToken;
        private String refreshToken;
        private int expiresIn;
        private String scope;
        private String tokenType;

        public int getExpiresIn() { return expiresIn; }
        public void setExpiresIn(int v) { expiresIn = v; }
    }

    public static class OAuthProfileResponse {
        private String accountUuid;
        private String emailAddress;
        private String organizationUuid;
        private String organizationName;
        private String organizationRole;
        private String workspaceRole;
        private String displayName;
        private Boolean hasExtraUsageEnabled;
        private String billingType;
        private String subscriptionType;
        private String rateLimitTier;

        public OAuthProfileResponse() {}
        public String getAccountUuid() { return accountUuid; }
        public void setAccountUuid(String v) { accountUuid = v; }
        public String getEmailAddress() { return emailAddress; }
        public void setEmailAddress(String v) { emailAddress = v; }
        public String getOrganizationUuid() { return organizationUuid; }
        public void setOrganizationUuid(String v) { organizationUuid = v; }
        public String getOrganizationName() { return organizationName; }
        public void setOrganizationName(String v) { organizationName = v; }
        public String getOrganizationRole() { return organizationRole; }
        public void setOrganizationRole(String v) { organizationRole = v; }
        public String getWorkspaceRole() { return workspaceRole; }
        public void setWorkspaceRole(String v) { workspaceRole = v; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { displayName = v; }
        public Boolean getHasExtraUsageEnabled() { return hasExtraUsageEnabled; }
        public void setHasExtraUsageEnabled(Boolean v) { hasExtraUsageEnabled = v; }
        public String getBillingType() { return billingType; }
        public void setBillingType(String v) { billingType = v; }
        public String getSubscriptionType() { return subscriptionType; }
        public void setSubscriptionType(String v) { subscriptionType = v; }
        public String getRateLimitTier() { return rateLimitTier; }
        public void setRateLimitTier(String v) { rateLimitTier = v; }
    }

    public enum SubscriptionType {
        FREE("free"),
        PRO("pro"),
        MAX("max"),
        TEAM("team"),
        ENTERPRISE("enterprise"),
        API_KEY("api_key");

        private final String value;
        SubscriptionType(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    public enum RateLimitTier {
        DEFAULT("default"),
        DEFAULT_CLAUDE_MAX_20X("default_claude_max_20x"),
        PREMIUM("premium");

        private final String value;
        RateLimitTier(String value) { this.value = value; }
    }

    public enum BillingType {
        CLAUDE_AI("claude_ai"),
        CONSOLE("console");

        private final String value;
        BillingType(String value) { this.value = value; }
    }
}
