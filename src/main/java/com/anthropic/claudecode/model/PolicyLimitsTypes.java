package com.anthropic.claudecode.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Policy limits types.
 * Translated from src/services/policyLimits/types.ts
 *
 * Types for the policy limits API response.
 */
public class PolicyLimitsTypes {

    /**
     * Schema for the policy limits API response.
     * Only blocked policies are included. If a policy key is absent, it's allowed.
     * Translated from PolicyLimitsResponse in types.ts
     */
    public static class PolicyLimitsResponse {
        private Map<String, PolicyRestriction> restrictions;

        public PolicyLimitsResponse() {}
        public PolicyLimitsResponse(Map<String, PolicyRestriction> restrictions) { this.restrictions = restrictions; }
        public Map<String, PolicyRestriction> getRestrictions() { return restrictions; }
        public void setRestrictions(Map<String, PolicyRestriction> v) { restrictions = v; }
    }

    /**
     * A single policy restriction entry.
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PolicyRestriction {
        private boolean allowed;

        public boolean isAllowed() { return allowed; }
        public void setAllowed(boolean v) { allowed = v; }
    }

    /**
     * Result of fetching policy limits.
     * Translated from PolicyLimitsFetchResult in types.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PolicyLimitsFetchResult {
        private boolean success;
        /**
         * null means 304 Not Modified (cache is still valid).
         */
        private Map<String, PolicyRestriction> restrictions;
        private String etag;
        private String error;
        /**
         * If true, don't retry on failure (e.g., auth errors).
         */
        private boolean skipRetry;

        public static PolicyLimitsFetchResult success(Map<String, PolicyRestriction> restrictions, String etag) {
            PolicyLimitsFetchResult r = new PolicyLimitsFetchResult();
            r.success = true;
            r.restrictions = restrictions;
            r.etag = etag;
            return r;
        }

        public static PolicyLimitsFetchResult notModified(String etag) {
            PolicyLimitsFetchResult r = new PolicyLimitsFetchResult();
            r.success = true;
            r.restrictions = null; // null signals 304 Not Modified
            r.etag = etag;
            return r;
        }

        public static PolicyLimitsFetchResult failure(String error, boolean skipRetry) {
            PolicyLimitsFetchResult r = new PolicyLimitsFetchResult();
            r.success = false;
            r.error = error;
            r.skipRetry = skipRetry;
            return r;
        }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean v) { success = v; }
        public String getEtag() { return etag; }
        public void setEtag(String v) { etag = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
        public boolean isSkipRetry() { return skipRetry; }
        public void setSkipRetry(boolean v) { skipRetry = v; }
    
        public Map<String, PolicyRestriction> getRestrictions() { return restrictions; }
    

    }
}
