package com.anthropic.claudecode.util;

/**
 * Voice mode enablement utilities.
 * Translated from src/voice/voiceModeEnabled.ts
 *
 * <p>Three distinct checks are provided, mirroring the TypeScript source:
 * <ol>
 *   <li>{@link #isVoiceGrowthBookEnabled()} — kill-switch check via GrowthBook flag.</li>
 *   <li>{@link #hasVoiceAuth()} — checks that an Anthropic OAuth token exists.</li>
 *   <li>{@link #isVoiceModeEnabled()} — full runtime check combining both.</li>
 * </ol>
 */
public class VoiceModeUtils {

    /**
     * Kill-switch check for voice mode.
     *
     * Returns {@code true} unless the {@code tengu_amber_quartz_disabled}
     * GrowthBook flag is turned on (emergency off). Default {@code false}
     * means a missing/stale cache reads as "not killed", so fresh installs
     * get voice working immediately without waiting for GrowthBook init.
     *
     * In the Java layer the GrowthBook value is represented by the
     * {@code CLAUDE_VOICE_GROWTHBOOK_DISABLED} environment variable
     * (set to {@code "true"} to simulate the kill-switch being flipped).
     *
     * Translated from isVoiceGrowthBookEnabled() in voiceModeEnabled.ts
     */
    public static boolean isVoiceGrowthBookEnabled() {
        // Positive pattern: return true unless the kill-switch is explicitly set.
        String killSwitch = System.getenv("CLAUDE_VOICE_GROWTHBOOK_DISABLED");
        if (killSwitch != null && (killSwitch.equalsIgnoreCase("true") || killSwitch.equals("1"))) {
            return false;
        }
        return true;
    }

    /**
     * Auth-only check for voice mode.
     *
     * Returns {@code true} when Anthropic OAuth is the active auth provider
     * AND an access token is present. Voice mode requires the
     * {@code voice_stream} endpoint on claude.ai, which is unavailable with
     * API keys, Bedrock, Vertex, or Foundry.
     *
     * In the Java layer:
     * <ul>
     *   <li>Anthropic OAuth is detected by the absence of an
     *       {@code ANTHROPIC_API_KEY} env var (same heuristic as the TS
     *       {@code isAnthropicAuthEnabled()} helper).</li>
     *   <li>Token presence is checked via {@code CLAUDE_AI_OAUTH_ACCESS_TOKEN}.</li>
     * </ul>
     *
     * Translated from hasVoiceAuth() in voiceModeEnabled.ts
     */
    public static boolean hasVoiceAuth() {
        // isAnthropicAuthEnabled equivalent: no API key means OAuth provider
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            return false;
        }
        // Check that an OAuth access token actually exists
        String accessToken = System.getenv("CLAUDE_AI_OAUTH_ACCESS_TOKEN");
        return accessToken != null && !accessToken.isBlank();
    }

    /**
     * Full runtime check: auth + GrowthBook kill-switch.
     *
     * Use for command-time paths where a fresh credential read is acceptable.
     *
     * Translated from isVoiceModeEnabled() in voiceModeEnabled.ts
     */
    public static boolean isVoiceModeEnabled() {
        return hasVoiceAuth() && isVoiceGrowthBookEnabled();
    }

    private VoiceModeUtils() {}
}
