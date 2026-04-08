package com.anthropic.claudecode.service;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock rate limits service for internal testing.
 * Translated from src/services/mockRateLimits.ts
 *
 * ANT-ONLY: For internal testing/demo purposes only.
 * Allows testing various rate limit scenarios without hitting actual limits.
 */
@Slf4j
@Service
public class MockRateLimitsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MockRateLimitsService.class);


    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------

    /** Mirrors MockScenario union type in mockRateLimits.ts */
    public enum MockScenario {
        NORMAL("normal"),
        SESSION_LIMIT_REACHED("session-limit-reached"),
        APPROACHING_WEEKLY_LIMIT("approaching-weekly-limit"),
        WEEKLY_LIMIT_REACHED("weekly-limit-reached"),
        OVERAGE_ACTIVE("overage-active"),
        OVERAGE_WARNING("overage-warning"),
        OVERAGE_EXHAUSTED("overage-exhausted"),
        OUT_OF_CREDITS("out-of-credits"),
        ORG_ZERO_CREDIT_LIMIT("org-zero-credit-limit"),
        ORG_SPEND_CAP_HIT("org-spend-cap-hit"),
        MEMBER_ZERO_CREDIT_LIMIT("member-zero-credit-limit"),
        SEAT_TIER_ZERO_CREDIT_LIMIT("seat-tier-zero-credit-limit"),
        OPUS_LIMIT("opus-limit"),
        OPUS_WARNING("opus-warning"),
        SONNET_LIMIT("sonnet-limit"),
        SONNET_WARNING("sonnet-warning"),
        FAST_MODE_LIMIT("fast-mode-limit"),
        FAST_MODE_SHORT_LIMIT("fast-mode-short-limit"),
        EXTRA_USAGE_REQUIRED("extra-usage-required"),
        CLEAR("clear");

        @JsonValue
        private final String value;
        MockScenario(String v) { this.value = v; }
        public String getValue() { return value; }
    }

    private record ExceededLimit(String type, long resetsAt) {}

    // -------------------------------------------------------------------------
    // State — mirrors module-level vars in mockRateLimits.ts
    // -------------------------------------------------------------------------

    private final Map<String, String> mockHeaders = new ConcurrentHashMap<>();
    private volatile boolean mockEnabled = false;
    private volatile String mockHeaderless429Message = null;
    private volatile String mockSubscriptionType = null;
    private volatile Long mockFastModeRateLimitDurationMs = null;
    private volatile Long mockFastModeRateLimitExpiresAt = null;
    private final List<ExceededLimit> exceededLimits = new ArrayList<>();

    private static final String DEFAULT_MOCK_SUBSCRIPTION = "max";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Whether mock rate limits are currently active. */
    public boolean shouldProcessMockLimits() {
        if (!"ant".equals(System.getenv("USER_TYPE"))) return false;
        return mockEnabled || System.getenv("CLAUDE_MOCK_HEADERLESS_429") != null;
    }

    /**
     * Set a single mock header by logical key.
     * Translated from setMockHeader() in mockRateLimits.ts
     */
    public void setMockHeader(String key, String value) {
        if (!"ant".equals(System.getenv("USER_TYPE"))) return;
        mockEnabled = true;

        String fullKey = "retry-after".equals(key)
                ? "retry-after"
                : "anthropic-ratelimit-unified-" + key;

        if (value == null || "clear".equals(value)) {
            mockHeaders.remove(fullKey);
            if ("claim".equals(key)) exceededLimits.clear();
            if ("status".equals(key) || "overage-status".equals(key)) updateRetryAfter();
            if (mockHeaders.isEmpty()) mockEnabled = false;
            return;
        }

        // Reset times: interpret number as hours from now
        if ("reset".equals(key) || "overage-reset".equals(key)) {
            try {
                double hours = Double.parseDouble(value);
                value = String.valueOf((long)(System.currentTimeMillis() / 1000 + hours * 3600));
            } catch (NumberFormatException ignored) {}
        }

        // Claim key: add to exceededLimits
        if ("claim".equals(key)) {
            List<String> validClaims = List.of("five_hour","seven_day","seven_day_opus","seven_day_sonnet");
            if (validClaims.contains(value)) {
                long resetsAt;
                if ("five_hour".equals(value)) {
                    resetsAt = System.currentTimeMillis() / 1000 + 5 * 3600;
                } else if (value.startsWith("seven_day")) {
                    resetsAt = System.currentTimeMillis() / 1000 + 7 * 24 * 3600;
                } else {
                    resetsAt = System.currentTimeMillis() / 1000 + 3600;
                }
                String finalValue = value;
                exceededLimits.removeIf(l -> l.type().equals(finalValue));
                exceededLimits.add(new ExceededLimit(value, resetsAt));
                updateRepresentativeClaim();
                return;
            }
        }

        mockHeaders.put(fullKey, value);
        if ("status".equals(key) || "overage-status".equals(key)) updateRetryAfter();
    }

    /**
     * Set a mock scenario preset.
     * Translated from setMockRateLimitScenario() in mockRateLimits.ts
     */
    public void setMockRateLimitScenario(MockScenario scenario) {
        if (!"ant".equals(System.getenv("USER_TYPE"))) return;
        if (scenario == MockScenario.CLEAR) {
            clearMockHeaders();
            return;
        }
        mockEnabled = true;
        long fiveHoursFromNow = System.currentTimeMillis() / 1000 + 5 * 3600;
        long sevenDaysFromNow = System.currentTimeMillis() / 1000 + 7 * 24 * 3600;
        mockHeaders.clear();
        mockHeaderless429Message = null;

        boolean preserveExceededLimits = scenario == MockScenario.OVERAGE_ACTIVE
                || scenario == MockScenario.OVERAGE_WARNING
                || scenario == MockScenario.OVERAGE_EXHAUSTED;
        if (!preserveExceededLimits) exceededLimits.clear();

        switch (scenario) {
            case NORMAL -> {
                mockHeaders.put("anthropic-ratelimit-unified-status", "allowed");
                mockHeaders.put("anthropic-ratelimit-unified-reset", String.valueOf(fiveHoursFromNow));
            }
            case SESSION_LIMIT_REACHED -> {
                exceededLimits.add(new ExceededLimit("five_hour", fiveHoursFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
            }
            case APPROACHING_WEEKLY_LIMIT -> {
                mockHeaders.put("anthropic-ratelimit-unified-status", "allowed_warning");
                mockHeaders.put("anthropic-ratelimit-unified-reset", String.valueOf(sevenDaysFromNow));
                mockHeaders.put("anthropic-ratelimit-unified-representative-claim", "seven_day");
            }
            case WEEKLY_LIMIT_REACHED -> {
                exceededLimits.add(new ExceededLimit("seven_day", sevenDaysFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
            }
            case OVERAGE_ACTIVE -> {
                if (exceededLimits.isEmpty())
                    exceededLimits.add(new ExceededLimit("five_hour", fiveHoursFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-status", "allowed");
                mockHeaders.put("anthropic-ratelimit-unified-overage-reset",
                        String.valueOf(endOfNextMonthEpoch()));
            }
            case OVERAGE_WARNING -> {
                if (exceededLimits.isEmpty())
                    exceededLimits.add(new ExceededLimit("five_hour", fiveHoursFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-status", "allowed_warning");
                mockHeaders.put("anthropic-ratelimit-unified-overage-reset",
                        String.valueOf(endOfNextMonthEpoch()));
            }
            case OVERAGE_EXHAUSTED -> {
                if (exceededLimits.isEmpty())
                    exceededLimits.add(new ExceededLimit("five_hour", fiveHoursFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-reset",
                        String.valueOf(endOfNextMonthEpoch()));
            }
            case OUT_OF_CREDITS -> {
                if (exceededLimits.isEmpty())
                    exceededLimits.add(new ExceededLimit("five_hour", fiveHoursFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-disabled-reason", "out_of_credits");
                mockHeaders.put("anthropic-ratelimit-unified-overage-reset",
                        String.valueOf(endOfNextMonthEpoch()));
            }
            case ORG_ZERO_CREDIT_LIMIT -> {
                if (exceededLimits.isEmpty())
                    exceededLimits.add(new ExceededLimit("five_hour", fiveHoursFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-disabled-reason",
                        "org_service_zero_credit_limit");
                mockHeaders.put("anthropic-ratelimit-unified-overage-reset",
                        String.valueOf(endOfNextMonthEpoch()));
            }
            case ORG_SPEND_CAP_HIT -> {
                if (exceededLimits.isEmpty())
                    exceededLimits.add(new ExceededLimit("five_hour", fiveHoursFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-disabled-reason",
                        "org_level_disabled_until");
                mockHeaders.put("anthropic-ratelimit-unified-overage-reset",
                        String.valueOf(endOfNextMonthEpoch()));
            }
            case MEMBER_ZERO_CREDIT_LIMIT -> {
                if (exceededLimits.isEmpty())
                    exceededLimits.add(new ExceededLimit("five_hour", fiveHoursFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-disabled-reason",
                        "member_zero_credit_limit");
                mockHeaders.put("anthropic-ratelimit-unified-overage-reset",
                        String.valueOf(endOfNextMonthEpoch()));
            }
            case SEAT_TIER_ZERO_CREDIT_LIMIT -> {
                if (exceededLimits.isEmpty())
                    exceededLimits.add(new ExceededLimit("five_hour", fiveHoursFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-status", "rejected");
                mockHeaders.put("anthropic-ratelimit-unified-overage-disabled-reason",
                        "seat_tier_zero_credit_limit");
                mockHeaders.put("anthropic-ratelimit-unified-overage-reset",
                        String.valueOf(endOfNextMonthEpoch()));
            }
            case OPUS_LIMIT -> {
                exceededLimits.add(new ExceededLimit("seven_day_opus", sevenDaysFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
            }
            case OPUS_WARNING -> {
                mockHeaders.put("anthropic-ratelimit-unified-status", "allowed_warning");
                mockHeaders.put("anthropic-ratelimit-unified-reset", String.valueOf(sevenDaysFromNow));
                mockHeaders.put("anthropic-ratelimit-unified-representative-claim", "seven_day_opus");
            }
            case SONNET_LIMIT -> {
                exceededLimits.add(new ExceededLimit("seven_day_sonnet", sevenDaysFromNow));
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
            }
            case SONNET_WARNING -> {
                mockHeaders.put("anthropic-ratelimit-unified-status", "allowed_warning");
                mockHeaders.put("anthropic-ratelimit-unified-reset", String.valueOf(sevenDaysFromNow));
                mockHeaders.put("anthropic-ratelimit-unified-representative-claim", "seven_day_sonnet");
            }
            case FAST_MODE_LIMIT -> {
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
                mockFastModeRateLimitDurationMs = 10L * 60 * 1000; // 10 min
            }
            case FAST_MODE_SHORT_LIMIT -> {
                updateRepresentativeClaim();
                mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
                mockFastModeRateLimitDurationMs = 10L * 1000; // 10 sec
            }
            case EXTRA_USAGE_REQUIRED ->
                mockHeaderless429Message = "Extra usage is required for long context requests.";
        }
    }

    /**
     * Set mock early warning utilization headers.
     * Translated from setMockEarlyWarning() in mockRateLimits.ts
     */
    public void setMockEarlyWarning(String claimAbbrev, double utilization, Long hoursFromNow) {
        if (!"ant".equals(System.getenv("USER_TYPE"))) return;
        mockEnabled = true;
        clearMockEarlyWarning();

        long defaultHours = "5h".equals(claimAbbrev) ? 4L : 5L * 24;
        long hours = hoursFromNow != null ? hoursFromNow : defaultHours;
        long resetsAt = System.currentTimeMillis() / 1000 + hours * 3600;

        mockHeaders.put("anthropic-ratelimit-unified-" + claimAbbrev + "-utilization",
                String.valueOf(utilization));
        mockHeaders.put("anthropic-ratelimit-unified-" + claimAbbrev + "-reset",
                String.valueOf(resetsAt));
        mockHeaders.put("anthropic-ratelimit-unified-" + claimAbbrev + "-surpassed-threshold",
                String.valueOf(utilization));

        mockHeaders.putIfAbsent("anthropic-ratelimit-unified-status", "allowed");
    }

    /** Clear early warning headers. Translated from clearMockEarlyWarning() */
    public void clearMockEarlyWarning() {
        mockHeaders.remove("anthropic-ratelimit-unified-5h-utilization");
        mockHeaders.remove("anthropic-ratelimit-unified-5h-reset");
        mockHeaders.remove("anthropic-ratelimit-unified-5h-surpassed-threshold");
        mockHeaders.remove("anthropic-ratelimit-unified-7d-utilization");
        mockHeaders.remove("anthropic-ratelimit-unified-7d-reset");
        mockHeaders.remove("anthropic-ratelimit-unified-7d-surpassed-threshold");
    }

    /**
     * Add an exceeded limit with custom reset time.
     * Translated from addExceededLimit() in mockRateLimits.ts
     */
    public void addExceededLimit(String type, double hoursFromNow) {
        if (!"ant".equals(System.getenv("USER_TYPE"))) return;
        mockEnabled = true;
        long resetsAt = (long)(System.currentTimeMillis() / 1000 + hoursFromNow * 3600);
        exceededLimits.removeIf(l -> l.type().equals(type));
        exceededLimits.add(new ExceededLimit(type, resetsAt));
        if (!exceededLimits.isEmpty()) {
            mockHeaders.put("anthropic-ratelimit-unified-status", "rejected");
        }
        updateRepresentativeClaim();
    }

    /**
     * Get mock headers for injection into real responses.
     * Translated from getMockHeaders() in mockRateLimits.ts
     */
    public Map<String, String> getMockHeaders() {
        if (!mockEnabled || !"ant".equals(System.getenv("USER_TYPE"))
                || mockHeaders.isEmpty()) {
            return null;
        }
        return Collections.unmodifiableMap(mockHeaders);
    }

    /**
     * Apply mock headers on top of real response headers.
     * Translated from applyMockHeaders() in mockRateLimits.ts
     */
    public Map<String, String> applyMockHeaders(Map<String, String> headers) {
        Map<String, String> mock = getMockHeaders();
        if (mock == null) return headers;
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.putAll(mock);
        return merged;
    }

    /**
     * Process rate limit headers: apply mocks if active.
     * Translated from processRateLimitHeaders() in mockRateLimits.ts
     */
    public Map<String, String> processRateLimitHeaders(Map<String, String> headers) {
        Map<String, String> mock = getMockHeaders();
        if (mock == null) return headers;
        return applyMockHeaders(headers);
    }

    /**
     * Get the headerless 429 message if set.
     * Translated from getMockHeaderless429Message() in mockRateLimits.ts
     */
    public String getMockHeaderless429Message() {
        if (!"ant".equals(System.getenv("USER_TYPE"))) return null;
        String envMsg = System.getenv("CLAUDE_MOCK_HEADERLESS_429");
        if (envMsg != null) return envMsg;
        if (!mockEnabled) return null;
        return mockHeaderless429Message;
    }

    /** Clear all mock state. Translated from clearMockHeaders() */
    public void clearMockHeaders() {
        mockHeaders.clear();
        exceededLimits.clear();
        mockSubscriptionType = null;
        mockFastModeRateLimitDurationMs = null;
        mockFastModeRateLimitExpiresAt = null;
        mockHeaderless429Message = null;
        mockEnabled = false;
    }

    /** Set mock subscription type. */
    public void setMockSubscriptionType(String subscriptionType) {
        if (!"ant".equals(System.getenv("USER_TYPE"))) return;
        mockEnabled = true;
        mockSubscriptionType = subscriptionType;
    }

    /** Get effective mock subscription type. */
    public String getMockSubscriptionType() {
        if (!mockEnabled || !"ant".equals(System.getenv("USER_TYPE"))) return null;
        return mockSubscriptionType != null ? mockSubscriptionType : DEFAULT_MOCK_SUBSCRIPTION;
    }

    public boolean shouldUseMockSubscription() {
        return mockEnabled && mockSubscriptionType != null
                && "ant".equals(System.getenv("USER_TYPE"));
    }

    public boolean isMockFastModeRateLimitScenario() {
        return mockFastModeRateLimitDurationMs != null;
    }

    /**
     * Check and return fast-mode mock headers if active.
     * Translated from checkMockFastModeRateLimit() in mockRateLimits.ts
     */
    public Map<String, String> checkMockFastModeRateLimit(boolean isFastModeActive) {
        if (mockFastModeRateLimitDurationMs == null) return null;
        if (!isFastModeActive) return null;

        if (mockFastModeRateLimitExpiresAt != null
                && System.currentTimeMillis() >= mockFastModeRateLimitExpiresAt) {
            clearMockHeaders();
            return null;
        }

        if (mockFastModeRateLimitExpiresAt == null) {
            mockFastModeRateLimitExpiresAt =
                    System.currentTimeMillis() + mockFastModeRateLimitDurationMs;
        }

        long remainingMs = mockFastModeRateLimitExpiresAt - System.currentTimeMillis();
        Map<String, String> result = new LinkedHashMap<>(mockHeaders);
        result.put("retry-after", String.valueOf(Math.max(1, (long) Math.ceil(remainingMs / 1000.0))));
        return result;
    }

    /**
     * Get a human-readable description of the mock status.
     * Translated from getMockStatus() in mockRateLimits.ts
     */
    public String getMockStatus() {
        if (!mockEnabled && (mockHeaders.isEmpty() && mockSubscriptionType == null)) {
            return "No mock headers active (using real limits)";
        }
        StringBuilder sb = new StringBuilder("Active mock headers:\n");
        String effectiveSub = mockSubscriptionType != null
                ? mockSubscriptionType : DEFAULT_MOCK_SUBSCRIPTION;
        if (mockSubscriptionType != null) {
            sb.append("  Subscription Type: ").append(mockSubscriptionType).append(" (explicitly set)\n");
        } else {
            sb.append("  Subscription Type: ").append(effectiveSub).append(" (default)\n");
        }
        for (Map.Entry<String, String> e : mockHeaders.entrySet()) {
            String fmtKey = e.getKey()
                    .replace("anthropic-ratelimit-unified-", "")
                    .replace("-", " ");
            if (e.getKey().contains("reset")) {
                long ts = Long.parseLong(e.getValue());
                sb.append("  ").append(fmtKey).append(": ")
                  .append(e.getValue()).append(" (")
                  .append(new java.util.Date(ts * 1000)).append(")\n");
            } else {
                sb.append("  ").append(fmtKey).append(": ").append(e.getValue()).append("\n");
            }
        }
        if (!exceededLimits.isEmpty()) {
            sb.append("\nExceeded limits (contributing to representative claim):\n");
            for (ExceededLimit l : exceededLimits) {
                sb.append("  ").append(l.type()).append(": resets at ")
                  .append(new java.util.Date(l.resetsAt() * 1000)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * Get scenario description. Translated from getScenarioDescription() in mockRateLimits.ts
     */
    public String getScenarioDescription(MockScenario scenario) {
        return switch (scenario) {
            case NORMAL -> "Normal usage, no limits";
            case SESSION_LIMIT_REACHED -> "Session rate limit exceeded";
            case APPROACHING_WEEKLY_LIMIT -> "Approaching weekly aggregate limit";
            case WEEKLY_LIMIT_REACHED -> "Weekly aggregate limit exceeded";
            case OVERAGE_ACTIVE -> "Using extra usage (overage active)";
            case OVERAGE_WARNING -> "Approaching extra usage limit";
            case OVERAGE_EXHAUSTED -> "Both subscription and extra usage limits exhausted";
            case OUT_OF_CREDITS -> "Out of extra usage credits (wallet empty)";
            case ORG_ZERO_CREDIT_LIMIT -> "Org spend cap is zero (no extra usage budget)";
            case ORG_SPEND_CAP_HIT -> "Org spend cap hit for the month";
            case MEMBER_ZERO_CREDIT_LIMIT -> "Member limit is zero (admin can allocate more)";
            case SEAT_TIER_ZERO_CREDIT_LIMIT -> "Seat tier limit is zero (admin can allocate more)";
            case OPUS_LIMIT -> "Opus limit reached";
            case OPUS_WARNING -> "Approaching Opus limit";
            case SONNET_LIMIT -> "Sonnet limit reached";
            case SONNET_WARNING -> "Approaching Sonnet limit";
            case FAST_MODE_LIMIT -> "Fast mode rate limit";
            case FAST_MODE_SHORT_LIMIT -> "Fast mode rate limit (short)";
            case EXTRA_USAGE_REQUIRED -> "Headerless 429: Extra usage required for 1M context";
            case CLEAR -> "Clear mock headers (use real limits)";
        };
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void updateRetryAfter() {
        String status = mockHeaders.get("anthropic-ratelimit-unified-status");
        String overageStatus = mockHeaders.get("anthropic-ratelimit-unified-overage-status");
        String reset = mockHeaders.get("anthropic-ratelimit-unified-reset");

        if ("rejected".equals(status)
                && (overageStatus == null || "rejected".equals(overageStatus))
                && reset != null) {
            long resetTs = Long.parseLong(reset);
            long secondsUntil = Math.max(0, resetTs - System.currentTimeMillis() / 1000);
            mockHeaders.put("retry-after", String.valueOf(secondsUntil));
        } else {
            mockHeaders.remove("retry-after");
        }
    }

    private void updateRepresentativeClaim() {
        if (exceededLimits.isEmpty()) {
            mockHeaders.remove("anthropic-ratelimit-unified-representative-claim");
            mockHeaders.remove("anthropic-ratelimit-unified-reset");
            mockHeaders.remove("retry-after");
            return;
        }
        ExceededLimit furthest = exceededLimits.stream()
                .max(Comparator.comparingLong(ExceededLimit::resetsAt))
                .orElseThrow();
        mockHeaders.put("anthropic-ratelimit-unified-representative-claim", furthest.type());
        mockHeaders.put("anthropic-ratelimit-unified-reset", String.valueOf(furthest.resetsAt()));

        if ("rejected".equals(mockHeaders.get("anthropic-ratelimit-unified-status"))) {
            String overageStatus = mockHeaders.get("anthropic-ratelimit-unified-overage-status");
            if (overageStatus == null || "rejected".equals(overageStatus)) {
                long secondsUntil = Math.max(0,
                        furthest.resetsAt() - System.currentTimeMillis() / 1000);
                mockHeaders.put("retry-after", String.valueOf(secondsUntil));
            } else {
                mockHeaders.remove("retry-after");
            }
        } else {
            mockHeaders.remove("retry-after");
        }
    }

    private long endOfNextMonthEpoch() {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MONTH, 1);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis() / 1000;
    }
}
