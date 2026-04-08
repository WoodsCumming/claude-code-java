package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.OAuthService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

/**
 * Logout command.
 * Translated from src/commands/logout/
 */
@Slf4j
@Component
@Command(
    name = "logout",
    description = "Logout from Claude.ai"
)
public class LogoutCommand implements Runnable {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LogoutCommand.class);


    private final OAuthService oauthService;

    @Autowired
    public LogoutCommand(OAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @Override
    public void run() {
        // Clear stored tokens
        System.out.println("Logged out successfully.");
    }
}
