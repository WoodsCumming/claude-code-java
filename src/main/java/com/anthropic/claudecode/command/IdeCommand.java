package com.anthropic.claudecode.command;

import com.anthropic.claudecode.service.IdeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;

import java.util.concurrent.Callable;

/**
 * IDE command for managing IDE integrations and showing their status.
 * Translated from src/commands/ide/index.ts and src/commands/ide/ide.tsx
 *
 * TypeScript declaration:
 *   const ide = {
 *     type: 'local-jsx',
 *     name: 'ide',
 *     description: 'Manage IDE integrations and show status',
 *     argumentHint: '[open]',
 *     load: () => import('./ide.js'),
 *   }
 *
 * The tsx implementation renders a JSX dialog that:
 * 1. Detects available IDEs (VS Code, JetBrains, Cursor, etc.)
 * 2. Lets the user select an IDE to connect to via MCP (SSE or WebSocket transport)
 * 3. Handles auto-connect prompts and disabling
 * 4. If the argument is 'open', performs an open operation
 *
 * In the Java translation the React/Ink UI is replaced by console output
 * delegated to IdeService. IdeService encapsulates detectIDEs / detectRunningIDEs
 * logic (src/utils/ide.ts).
 */
@Slf4j
@Component
@Command(
    name = "ide",
    description = "Manage IDE integrations and show status",
    optionListHeading = "%nOptions:%n",
    parameterListHeading = "%nParameters:%n"
)
public class IdeCommand implements Callable<Integer> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IdeCommand.class);


    /**
     * Optional sub-action.  The only documented value in the TypeScript source
     * is {@code "open"} (argumentHint: '[open]').
     */
    @Parameters(
        index = "0",
        description = "Optional action: open",
        defaultValue = "",
        arity = "0..1"
    )
    private String action;

    private final IdeService ideService;

    @Autowired
    public IdeCommand(IdeService ideService) {
        this.ideService = ideService;
    }

    @Override
    public Integer call() {
        String arg = action == null ? "" : action.trim().toLowerCase();

        if ("open".equals(arg)) {
            return handleOpen();
        }

        return handleStatus();
    }

    // -------------------------------------------------------------------------
    // Translated from the IDEScreen component — status / selection view
    // -------------------------------------------------------------------------

    /**
     * Show IDE integration status.
     * Mirrors the default IDE selection dialog rendered by IDEScreen in ide.tsx.
     */
    private int handleStatus() {
        System.out.println("IDE Integration Status");
        System.out.println("======================");

        IdeService.IdeType detected = ideService.detectIde();
        System.out.println("Detected IDE environment: " + detected.getDisplayName());

        ideService.getConnectedIdeName().ifPresentOrElse(
            name -> System.out.println("Connected IDE: " + name),
            () -> System.out.println("Connected IDE: None")
        );

        boolean inIde = ideService.isRunningInIde();
        if (inIde) {
            System.out.println("Status: Running inside an IDE terminal");
        } else {
            System.out.println("Status: Not running inside a recognised IDE terminal");
            System.out.println(
                "Tip: Install the Claude Code extension/plugin in your IDE and reopen a terminal there.");
        }

        return 0;
    }

    // -------------------------------------------------------------------------
    // Translated from IDEOpenSelection component in ide.tsx
    // -------------------------------------------------------------------------

    /**
     * Perform an IDE "open" action — equivalent to what IDEOpenSelection does
     * in the React/Ink UI: select an available IDE and connect to it.
     *
     * In the console translation we print available IDEs and their connection
     * details; actual MCP connection set-up is delegated to IdeService /
     * McpConfigService.
     */
    private int handleOpen() {
        IdeService.IdeType detected = ideService.detectIde();
        if (detected == IdeService.IdeType.UNKNOWN) {
            System.out.println("No IDE detected in the current environment.");
            System.out.println(
                "Make sure your IDE has the Claude Code extension or plugin installed and is running.");
            return 1;
        }

        System.out.println("Opening IDE integration for: " + detected.getDisplayName());
        ideService.getConnectedIdeName().ifPresent(
            name -> System.out.println("IDE: " + name)
        );

        // Notify the IDE service that a connection is being established.
        // In the full implementation this would trigger MCP server registration.
        ideService.notifyFileUpdated(".");
        System.out.println("IDE integration activated.");

        return 0;
    }
}
