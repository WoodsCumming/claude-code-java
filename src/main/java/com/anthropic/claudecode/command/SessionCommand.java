package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.RemoteSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

/**
 * Session command — show the remote session URL and QR code.
 *
 * Translated from src/commands/session/index.ts and src/commands/session/session.tsx
 *
 * TypeScript original behaviour:
 *   - name: "session", aliases: ["remote"]
 *   - isEnabled: () =&gt; getIsRemoteMode()  — command is only enabled when running in remote mode
 *   - isHidden:  !getIsRemoteMode()        — hidden from help when not in remote mode
 *   - Displays the remoteSessionUrl from AppState
 *   - Asynchronously renders a UTF-8 QR code for the URL
 *   - Shows "Not in remote mode. Start with `claude --remote`..." if no URL
 *   - ESC dismisses the panel (local-jsx command)
 *
 * Java translation:
 *   - Checks whether remote mode is active via RemoteSessionService.isRemoteMode()
 *   - Prints the remote session URL and a text-art QR code placeholder
 *   - Returns exit code 1 if not in remote mode (mirrors isEnabled check)
 */
@Slf4j
@Component
@Command(
    name = "session",
    aliases = {"remote"},
    description = "Show remote session URL and QR code"
)
public class SessionCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionCommand.class);


    private final RemoteSessionService remoteSessionService;

    @Autowired
    public SessionCommand(RemoteSessionService remoteSessionService) {
        this.remoteSessionService = remoteSessionService;
    }

    @Override
    public Integer call() {
        // Guard: only available in remote mode
        if (!remoteSessionService.isRemoteMode()) {
            System.out.println(
                "Not in remote mode. Start with `claude --remote` to use this command."
            );
            return 1;
        }

        String sessionUrl = remoteSessionService.getRemoteSessionUrl();
        if (sessionUrl == null || sessionUrl.isBlank()) {
            System.out.println(
                "Not in remote mode. Start with `claude --remote` to use this command."
            );
            return 1;
        }

        System.out.println("Remote session");
        System.out.println();

        // Attempt to generate a QR code for the URL
        String qrCode = remoteSessionService.generateQrCode(sessionUrl);
        if (qrCode != null && !qrCode.isBlank()) {
            System.out.println(qrCode);
        } else {
            System.out.println("(QR code generation failed — URL is still available below)");
        }

        System.out.println();
        System.out.print("Open in browser: ");
        System.out.println(sessionUrl);
        return 0;
    }
}
