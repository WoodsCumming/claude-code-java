package com.anthropic.claudecode.service;

import com.anthropic.claudecode.service.GlobalConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.util.concurrent.CompletableFuture;

/**
 * First token date service.
 * Translated from src/services/api/firstTokenDate.ts
 *
 * Fetches and stores when the user first used Claude Code.
 */
@Slf4j
@Service
public class FirstTokenDateService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FirstTokenDateService.class);


    private final OAuthService oauthService;
    private final GlobalConfigService globalConfigService;

    @Autowired
    public FirstTokenDateService(OAuthService oauthService, GlobalConfigService globalConfigService) {
        this.oauthService = oauthService;
        this.globalConfigService = globalConfigService;
    }

    /**
     * Fetch and store the first token date.
     * Translated from fetchAndStoreClaudeCodeFirstTokenDate() in firstTokenDate.ts
     */
    public CompletableFuture<Void> fetchAndStoreFirstTokenDate() {
        return CompletableFuture.runAsync(() -> {
            try {
                OAuthService.OAuthTokens tokens = oauthService.getCurrentTokens();
                if (tokens == null) return;

                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/api/organization/claude_code_first_token_date"))
                    .header("Authorization", "Bearer " + tokens.getAccessToken())
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();

                HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    log.debug("First token date fetched successfully");
                }

            } catch (Exception e) {
                log.debug("Could not fetch first token date: {}", e.getMessage());
            }
        });
    }
}
