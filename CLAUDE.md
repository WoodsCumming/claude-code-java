# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About This Project

This is a Java port of the Claude Code CLI (TypeScript source at `~/code/mt/claude-code-source-code`). It translates the original TypeScript/React/Ink codebase into a Spring Boot application using PicoCLI for CLI parsing and JLine for REPL interaction.

## Build & Run Commands

**Requires Java 21+ to run** (system default may be Java 17; use Java 22 explicitly):

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-22.jdk/Contents/Home

# Compile
mvn clean compile

# Package
mvn package -DskipTests

# Run via Maven (dev)
mvn spring-boot:run -Dspring-boot.run.arguments="--help"
mvn spring-boot:run -Dspring-boot.run.arguments="-p 'your prompt here'"

# Run the JAR directly (preferred)
java -jar target/claude-code-2.1.88.jar --help
java -jar target/claude-code-2.1.88.jar -p "hello"
java -jar target/claude-code-2.1.88.jar   # interactive REPL
```

Authentication uses `ANTHROPIC_API_KEY` env var or the OAuth token from `AuthService` (reads from keychain/storage via `SecureStorageService`).

## Architecture Overview

### Request Flow

```
CLI args
  → ClaudeCodeApplication (CommandLineRunner wraps PicoCLI)
  → MainCommand.call()
      ├── print/non-interactive: ReplLauncher.runSingleQuery()
      └── interactive REPL:      ReplLauncher.launch()
  → QueryEngine.query()
  → QueryEngine.queryLoop()          ← main agentic loop
      ├── MicroCompactService        ← compress messages if needed
      ├── AutoCompactService         ← reactive compaction on token limit
      ├── ClaudeApiService.streamMessages()  ← Anthropic API call
      ├── [if tool_use blocks] ToolExecutionService.runTools()
      │     └── Tool<Input,Output>.call()    ← individual tool
      └── StopHookService            ← post-turn hooks
```

### Key Classes

| Class | Role |
|---|---|
| `QueryEngine` | Core agentic loop. Manages `mutableMessages` history, calls API, dispatches tools, handles token budgets and compaction. |
| `ClaudeApiService` | Wraps `AnthropicClient`; selects API provider (direct/Bedrock/Vertex/Foundry); converts messages to API format. |
| `AnthropicClient` | Low-level OkHttp client. Auth via `getApiKey()` (env `ANTHROPIC_API_KEY`) or `getOauthToken()` (delegates to `AuthService`). |
| `ReplLauncher` | Interactive REPL (JLine) and single-query runner. Emits `TextDelta` events from `AssistantMessageEvent`. |
| `ToolExecutionService` | Looks up tool by name from the injected `List<Tool>`, checks permissions, calls `tool.call()`. |
| `StreamingToolExecutor` | Parallel tool execution during streaming. Implements `QueryEngine.StreamingToolExecutor` interface. |
| `AuthService` | OAuth token management; `getClaudeAiOAuthAccessToken()` used by `AnthropicClient`. |

### Tool Framework

All tools implement `Tool<Input, Output>` and are `@Component`-annotated. They are collected into a `List<Tool<?, ?>>` bean in `ToolConfig` and injected wherever needed.

- `getInputSchema()` — JSON Schema for the tool's input
- `call(input, context)` — execute the tool, returns `CompletableFuture<Output>`
- `checkPermissions(input, context)` — return `PermissionResult` before execution
- `isConcurrencySafe()` — whether tool can run in parallel with others

`ToolSearchTool` uses `@Lazy` injection of the tool list to break the circular dependency `AgentTool → ToolSearchTool → List<Tool> → AgentTool`.

### Spring Configuration

- **`ClaudeCodeConfig`** (`@Configuration @ConfigurationProperties(prefix="claude")`) — application properties + registers `RestTemplate`, `OkHttpClient`, `HttpClient`, `ScheduledExecutorService`, `SecureStorageService` stub, and `BridgeTrustedDeviceService.GrowthBookService` impl beans.
- **`ToolConfig`** — registers all 40+ tool beans and the `StreamingToolExecutorFactory` bean.
- **`application.yml`** — `spring.main.allow-circular-references=true` is set; default model is `claude-opus-4-6`.

### Important Patterns

**`QueryEngine.query()` vs `submitMessage()`**: `query()` is a simplified wrapper used by `ReplLauncher`; it sets `mutableMessages` from the passed list before calling `runTurn()`. `submitMessage()` is the full API used for multi-turn sessions.

**`QueryEvent` stream**: `queryLoop` emits `TextDelta` events when an `AssistantMessageEvent` arrives (text blocks extracted inline). `ReplLauncher` listens for `TextDelta` to print output.

**Null safety in `Map.of()`**: Java's `Map.of()` rejects null values. Use `HashMap` when values may be null (e.g. `StopHookService.buildHookInput`).

**`@Builder.Default`**: Fields with initializing expressions inside `@Builder` classes must be annotated `@Builder.Default`, otherwise Lombok ignores the initializer.

**Duplicate `log` fields**: Classes annotated `@Slf4j` must not also declare `private static final Logger log = ...` manually — Lombok generates it.

## Translating from TypeScript Source

When adding or fixing a feature, the TypeScript source is at `~/code/mt/claude-code-source-code/src/`. Key mappings:

| TS pattern | Java equivalent |
|---|---|
| `async function*` generator | `CompletableFuture` + `Consumer<QueryEvent>` callbacks |
| `yield` stream event | `onEvent.accept(QueryEvent.xxx(...))` |
| Module-level state | Spring `@Service` singleton fields |
| `zod` schema | `Map<String, Object>` JSON Schema in `getInputSchema()` |
| `AbortController` | `volatile boolean aborted` field on `QueryEngine` |
