package com.anthropic.claudecode.command;

import com.anthropic.claudecode.config.ClaudeCodeConfig;
import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.QueryEngine;
import com.anthropic.claudecode.tool.*;
import lombok.extern.slf4j.Slf4j;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/**
 * REPL (Read-Eval-Print Loop) launcher.
 * Translated from src/replLauncher.ts
 *
 * Manages the interactive terminal session.
 */
@Slf4j
@Component
public class ReplLauncher {



    private static final String PROMPT = "> ";
    private static final String SYSTEM_PROMPT_TEMPLATE = """
        You are Claude Code, Anthropic's official CLI for Claude.
        You are an interactive agent that helps users with software engineering tasks.
        """;

    private final QueryEngine queryEngine;
    private final ClaudeCodeConfig config;
    private final List<Tool<?, ?>> tools;

    @Autowired
    public ReplLauncher(
            QueryEngine queryEngine,
            ClaudeCodeConfig config,
            List<Tool<?, ?>> tools) {
        this.queryEngine = queryEngine;
        this.config = config;
        this.tools = tools;
    }

    /**
     * Launch the interactive REPL.
     * Translated from launchRepl() in replLauncher.ts
     */
    public int launch(String initialPrompt, boolean continueSession, String resumeSessionId) {
        try {
            Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .build();

            LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .parser(new DefaultParser())
                .build();

            List<Message> messages = new ArrayList<>();
            ToolUseContext context = createContext();

            // Print welcome message
            printWelcome(terminal);

            // Handle initial prompt
            if (initialPrompt != null && !initialPrompt.isBlank()) {
                processUserInput(initialPrompt, messages, context, terminal);
            }

            // Main REPL loop
            while (true) {
                String line;
                try {
                    line = reader.readLine(PROMPT);
                } catch (UserInterruptException e) {
                    terminal.writer().println("\n(Use 'exit' or Ctrl+D to quit)");
                    continue;
                } catch (EndOfFileException e) {
                    break; // Ctrl+D
                }

                if (line == null) break;
                line = line.trim();

                if (line.isEmpty()) continue;

                // Handle slash commands
                if (line.startsWith("/")) {
                    if (!handleSlashCommand(line, messages, context, terminal)) {
                        break; // /exit
                    }
                    continue;
                }

                // Process user message
                processUserInput(line, messages, context, terminal);
            }

            terminal.writer().println("\nGoodbye!");
            terminal.flush();
            terminal.close();
            return 0;

        } catch (Exception e) {
            log.error("REPL failed: {}", e.getMessage(), e);
            return 1;
        }
    }

    /**
     * Run a single query in non-interactive mode.
     */
    public void runSingleQuery(String prompt, String outputFormat) throws Exception {
        List<Message> messages = new ArrayList<>();
        ToolUseContext context = createContext();
        context.getOptions().setNonInteractiveSession(true);

        // Add user message
        Message.UserMessage userMsg = Message.UserMessage.builder()
            .type("user")
            .uuid(UUID.randomUUID().toString())
            .content(List.of(new ContentBlock.TextBlock(prompt)))
            .build();
        messages.add(userMsg);

        // Run query
        boolean isJson = "json".equals(outputFormat) || "stream-json".equals(outputFormat);
        boolean isStreaming = "stream-json".equals(outputFormat);

        log.info("Starting query for prompt: {}", prompt);
        List<Message> result = queryEngine.query(
            messages,
            SYSTEM_PROMPT_TEMPLATE,
            context,
            event -> {
                log.debug("QueryEvent: {}", event.getClass().getSimpleName());
                if (!isJson && event instanceof QueryEngine.TextDelta delta) {
                    System.out.print(delta.text());
                    System.out.flush();
                }
            }
        ).get(600, TimeUnit.SECONDS);
        log.info("Query completed, result messages: {}", result.size());

        if (!isJson) {
            System.out.println(); // Final newline
        } else {
            // Output JSON
            for (Message msg : result) {
                if (msg instanceof Message.AssistantMessage assistantMsg) {
                    System.out.println("{\"type\":\"result\",\"content\":" +
                        extractText(assistantMsg) + "}");
                }
            }
        }
    }

    private void processUserInput(
            String input,
            List<Message> messages,
            ToolUseContext context,
            Terminal terminal) {

        // Add user message
        Message.UserMessage userMsg = Message.UserMessage.builder()
            .type("user")
            .uuid(UUID.randomUUID().toString())
            .content(List.of(new ContentBlock.TextBlock(input)))
            .build();
        messages.add(userMsg);

        try {
            // Run query with streaming output
            List<Message> result = queryEngine.query(
                messages,
                SYSTEM_PROMPT_TEMPLATE,
                context,
                event -> {
                    if (event instanceof QueryEngine.TextDelta delta) {
                        terminal.writer().print(delta.text());
                        terminal.flush();
                    } else if (event instanceof QueryEngine.ToolUseStartEvent toolStart) {
                        terminal.writer().println("\n[" + toolStart.toolName() + "]");
                        terminal.flush();
                    }
                }
            ).get(600, TimeUnit.SECONDS);

            terminal.writer().println(); // Final newline after streaming
            terminal.flush();

            // Update messages with result
            messages.clear();
            messages.addAll(result);

        } catch (Exception e) {
            terminal.writer().println("\nError: " + e.getMessage());
            terminal.flush();
            log.error("Query failed", e);
        }
    }

    private boolean handleSlashCommand(
            String command,
            List<Message> messages,
            ToolUseContext context,
            Terminal terminal) {

        return switch (command.toLowerCase()) {
            case "/exit", "/quit", "/q" -> {
                yield false;
            }
            case "/clear" -> {
                messages.clear();
                terminal.writer().println("Conversation cleared.");
                yield true;
            }
            case "/help" -> {
                printHelp(terminal);
                yield true;
            }
            case "/verbose" -> {
                config.setVerbose(!config.isVerbose());
                terminal.writer().println("Verbose: " + config.isVerbose());
                yield true;
            }
            default -> {
                terminal.writer().println("Unknown command: " + command);
                terminal.writer().println("Type /help for available commands.");
                yield true;
            }
        };
    }

    private void printWelcome(Terminal terminal) {
        terminal.writer().println("Claude Code v" + config.getVersion());
        terminal.writer().println("Type /help for commands, /exit to quit.");
        terminal.writer().println();
        terminal.flush();
    }

    private void printHelp(Terminal terminal) {
        terminal.writer().println("Available commands:");
        terminal.writer().println("  /clear   - Clear conversation history");
        terminal.writer().println("  /verbose - Toggle verbose mode");
        terminal.writer().println("  /help    - Show this help");
        terminal.writer().println("  /exit    - Exit Claude Code");
        terminal.writer().println();
        terminal.flush();
    }

    private ToolUseContext createContext() {
        ToolUseContext.Options options = ToolUseContext.Options.builder()
            .mainLoopModel(config.getModel())
            .verbose(config.isVerbose())
            .tools(tools)
            .isNonInteractiveSession(config.isNonInteractiveSession())
            .commands(List.of())
            .mcpClients(List.of())
            .mcpResources(Map.of())
            .agentDefinitions(new ToolUseContext.AgentDefinitionsResult(List.of(), null))
            .build();

        return ToolUseContext.builder()
            .options(options)
            .messages(new ArrayList<>())
            .readFileState(new HashMap<>())
            .inProgressToolUseIds(new HashSet<>())
            .build();
    }

    private String extractText(Message.AssistantMessage msg) {
        if (msg.getContent() == null) return "\"\"";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ContentBlock.TextBlock text) {
                sb.append(text.getText());
            }
        }
        return "\"" + sb.toString().replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
