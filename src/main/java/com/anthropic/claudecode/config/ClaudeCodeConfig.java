package com.anthropic.claudecode.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.anthropic.claudecode.service.BridgeTrustedDeviceService;
import com.anthropic.claudecode.service.ClaudeApiService;
import com.anthropic.claudecode.service.GenerateAgentService;
import com.anthropic.claudecode.service.SessionIngressService;
import com.anthropic.claudecode.util.SessionIngressAuth;
import okhttp3.OkHttpClient;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Claude Code configuration.
 * Translated from src/utils/config.ts GlobalConfig and ProjectConfig types.
 */
@Configuration
@ConfigurationProperties(prefix = "claude")
public class ClaudeCodeConfig {

    /** Anthropic API key */
    private String apiKey;

    /** Application version */
    private String version = "2.1.88";

    /** API timeout in milliseconds */
    private long apiTimeoutMs = 600_000;

    /** Base URL for the Anthropic API */
    private String baseUrl = "https://api.anthropic.com";

    /** Whether verbose logging is enabled */
    private boolean verbose = false;

    /** Whether to use AWS Bedrock */
    private boolean useBedrock = false;

    /** Whether to use Google Vertex AI */
    private boolean useVertex = false;

    /** Whether to use Azure Foundry */
    private boolean useFoundry = false;

    /** Default model to use */
    private String model = "claude-opus-4-6";

    /** Small/fast model for quick operations */
    private String smallFastModel = "claude-haiku-4-5-20251001";

    /** Permission mode */
    private String permissionMode = "default";

    /** Maximum budget in USD */
    private Double maxBudgetUsd;

    /** Custom system prompt */
    private String customSystemPrompt;

    /** Additional system prompt */
    private String appendSystemPrompt;

    /** Whether auto-compact is enabled */
    private boolean autoCompactEnabled = true;

    /** Environment variables to pass to tools */
    private Map<String, String> env;

    /** Whether this is a non-interactive session */
    private boolean nonInteractiveSession = false;

    /** Working directory */
    private String workingDirectory;

    /** Whether to show turn duration */
    private boolean showTurnDuration = false;

    /** Install method (e.g. "local", "global", "native") */
    private String installMethod;

    /** Subscription type (e.g. "free", "pro", "max", "enterprise") */
    private String subscriptionType;

    // Explicit getters since Lombok @Data may not work in all Java 22 contexts
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }
    public long getApiTimeoutMs() { return apiTimeoutMs; }
    public void setApiTimeoutMs(long v) { this.apiTimeoutMs = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public boolean isVerbose() { return verbose; }
    public void setVerbose(boolean v) { this.verbose = v; }
    public boolean isUseBedrock() { return useBedrock; }
    public void setUseBedrock(boolean v) { this.useBedrock = v; }
    public boolean isUseVertex() { return useVertex; }
    public void setUseVertex(boolean v) { this.useVertex = v; }
    public boolean isUseFoundry() { return useFoundry; }
    public void setUseFoundry(boolean v) { this.useFoundry = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public String getSmallFastModel() { return smallFastModel; }
    public void setSmallFastModel(String v) { this.smallFastModel = v; }
    public String getPermissionMode() { return permissionMode; }
    public void setPermissionMode(String v) { this.permissionMode = v; }
    public Double getMaxBudgetUsd() { return maxBudgetUsd; }
    public void setMaxBudgetUsd(Double v) { this.maxBudgetUsd = v; }
    public String getCustomSystemPrompt() { return customSystemPrompt; }
    public void setCustomSystemPrompt(String v) { this.customSystemPrompt = v; }
    public String getAppendSystemPrompt() { return appendSystemPrompt; }
    public void setAppendSystemPrompt(String v) { this.appendSystemPrompt = v; }
    public boolean isAutoCompactEnabled() { return autoCompactEnabled; }
    public void setAutoCompactEnabled(boolean v) { this.autoCompactEnabled = v; }
    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> v) { this.env = v; }
    public boolean isNonInteractiveSession() { return nonInteractiveSession; }
    public void setNonInteractiveSession(boolean v) { this.nonInteractiveSession = v; }
    public String getWorkingDirectory() { return workingDirectory; }
    public void setWorkingDirectory(String v) { this.workingDirectory = v; }
    public boolean isShowTurnDuration() { return showTurnDuration; }
    public void setShowTurnDuration(boolean v) { this.showTurnDuration = v; }
    public String getInstallMethod() { return installMethod; }
    public void setInstallMethod(String v) { this.installMethod = v; }
    public String getSubscriptionType() { return subscriptionType; }
    public void setSubscriptionType(String v) { this.subscriptionType = v; }

    @Bean
    public SessionIngressService.SessionIngressAuthProvider sessionIngressAuthProvider() {
        return SessionIngressAuth::getSessionIngressAuthToken;
    }

    @Bean
    public ScheduledExecutorService scheduledExecutorService() {
        return Executors.newScheduledThreadPool(4);
    }

    @Bean
    public GenerateAgentService.ClaudeApiClient generateAgentClaudeApiClient(ClaudeApiService claudeApiService) {
        return (userMessage, systemPrompt, model) ->
            claudeApiService.queryHaiku(userMessage, systemPrompt);
    }

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }
}
