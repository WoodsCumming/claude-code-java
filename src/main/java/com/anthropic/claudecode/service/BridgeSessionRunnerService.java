package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.BridgeTypes.SessionActivity;
import com.anthropic.claudecode.model.BridgeTypes.SessionActivityType;
import com.anthropic.claudecode.model.BridgeTypes.SessionDoneStatus;
import com.anthropic.claudecode.model.BridgeTypes.SessionHandle;
import com.anthropic.claudecode.model.BridgeTypes.SessionSpawnOpts;
import com.anthropic.claudecode.model.BridgeTypes.SessionSpawner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Bridge session runner service.
 * Translated from src/bridge/sessionRunner.ts
 *
 * Spawns child CLI processes per bridge session, wires up NDJSON parsing for
 * activity/permission tracking, and returns a SessionHandle.
 */
@Slf4j
@Service
public class BridgeSessionRunnerService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BridgeSessionRunnerService.class);


    private static final int MAX_ACTIVITIES = 10;
    private static final int MAX_STDERR_LINES = 10;

    private final ObjectMapper objectMapper;

    /** Map from tool name to human-readable verb for the status display. */
    private static final Map<String, String> TOOL_VERBS = new HashMap<>();
    static {
        TOOL_VERBS.put("Read",               "Reading");
        TOOL_VERBS.put("Write",              "Writing");
        TOOL_VERBS.put("Edit",               "Editing");
        TOOL_VERBS.put("MultiEdit",          "Editing");
        TOOL_VERBS.put("Bash",               "Running");
        TOOL_VERBS.put("Glob",               "Searching");
        TOOL_VERBS.put("Grep",               "Searching");
        TOOL_VERBS.put("WebFetch",           "Fetching");
        TOOL_VERBS.put("WebSearch",          "Searching");
        TOOL_VERBS.put("Task",               "Running task");
        TOOL_VERBS.put("FileReadTool",       "Reading");
        TOOL_VERBS.put("FileWriteTool",      "Writing");
        TOOL_VERBS.put("FileEditTool",       "Editing");
        TOOL_VERBS.put("GlobTool",           "Searching");
        TOOL_VERBS.put("GrepTool",           "Searching");
        TOOL_VERBS.put("BashTool",           "Running");
        TOOL_VERBS.put("NotebookEditTool",   "Editing notebook");
        TOOL_VERBS.put("LSP",                "LSP");
    }

    @Autowired
    public BridgeSessionRunnerService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ─── Permission request type ──────────────────────────────────────────────

    /**
     * A control_request emitted by the child CLI for per-invocation tool permission.
     * Translated from PermissionRequest in sessionRunner.ts
     */
    public record PermissionRequest(
        String type,
        String requestId,
        PermissionRequestPayload request
    ) {
        public record PermissionRequestPayload(
            String subtype,
            String toolName,
            Map<String, Object> input,
            String toolUseId
        ) {}
    }

    // ─── Spawner deps ─────────────────────────────────────────────────────────

    /**
     * Dependencies for creating a SessionSpawner.
     * Translated from SessionSpawnerDeps in sessionRunner.ts
     */
    public interface SessionSpawnerDeps {
        String getExecPath();
        List<String> getScriptArgs();
        Map<String, String> getEnv();
        boolean isVerbose();
        boolean isSandbox();
        default String getDebugFile() { return null; }
        default String getPermissionMode() { return null; }
        void onDebug(String msg);
        default void onActivity(String sessionId, SessionActivity activity) {}
        default void onPermissionRequest(String sessionId, PermissionRequest request, String accessToken) {}
    }

    // ─── Utility helpers ──────────────────────────────────────────────────────

    /**
     * Sanitize a session ID for use in file names.
     * Translated from safeFilenameId() in sessionRunner.ts
     */
    public static String safeFilenameId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * Generate a human-readable tool summary for the activity display.
     * Translated from toolSummary() in sessionRunner.ts
     */
    public static String toolSummary(String name, Map<String, Object> input) {
        String verb = TOOL_VERBS.getOrDefault(name, name);
        String target = null;
        if (input.get("file_path") instanceof String s) target = s;
        else if (input.get("filePath") instanceof String s) target = s;
        else if (input.get("pattern") instanceof String s) target = s;
        else if (input.get("command") instanceof String s) target = s.length() > 60 ? s.substring(0, 60) : s;
        else if (input.get("url") instanceof String s) target = s;
        else if (input.get("query") instanceof String s) target = s;
        return (target != null && !target.isEmpty()) ? verb + " " + target : verb;
    }

    // ─── Activity extraction ──────────────────────────────────────────────────

    /**
     * Parse a single NDJSON line and extract SessionActivity entries.
     * Translated from extractActivities() in sessionRunner.ts
     */
    @SuppressWarnings("unchecked")
    public List<SessionActivity> extractActivities(String line, String sessionId, Consumer<String> onDebug) {
        List<SessionActivity> activities = new ArrayList<>();
        Map<String, Object> msg;
        try {
            msg = objectMapper.readValue(line, Map.class);
        } catch (Exception e) {
            return activities;
        }
        if (msg == null) return activities;

        String type = (String) msg.get("type");
        long now = System.currentTimeMillis();

        switch (type != null ? type : "") {
            case "assistant" -> {
                Map<String, Object> message = (Map<String, Object>) msg.get("message");
                if (message == null) break;
                Object contentObj = message.get("content");
                if (!(contentObj instanceof List<?> content)) break;

                for (Object blockObj : content) {
                    if (!(blockObj instanceof Map<?, ?> rawBlock)) continue;
                    Map<String, Object> block = (Map<String, Object>) rawBlock;
                    String blockType = (String) block.get("type");

                    if ("tool_use".equals(blockType)) {
                        String toolName = (String) block.getOrDefault("name", "Tool");
                        Map<String, Object> toolInput = block.get("input") instanceof Map<?, ?>
                                ? (Map<String, Object>) block.get("input") : Map.of();
                        String summary = toolSummary(toolName, toolInput);
                        activities.add(SessionActivity.builder()
                                .type(SessionActivityType.TOOL_START)
                                .summary(summary)
                                .timestamp(now)
                                .build());
                        onDebug.accept("[bridge:activity] sessionId=" + sessionId +
                                " tool_use name=" + toolName);
                    } else if ("text".equals(blockType)) {
                        String text = (String) block.getOrDefault("text", "");
                        if (!text.isEmpty()) {
                            activities.add(SessionActivity.builder()
                                    .type(SessionActivityType.TEXT)
                                    .summary(text.length() > 80 ? text.substring(0, 80) : text)
                                    .timestamp(now)
                                    .build());
                            onDebug.accept("[bridge:activity] sessionId=" + sessionId +
                                    " text \"" + (text.length() > 100 ? text.substring(0, 100) : text) + "\"");
                        }
                    }
                }
            }
            case "result" -> {
                String subtype = (String) msg.get("subtype");
                if ("success".equals(subtype)) {
                    activities.add(SessionActivity.builder()
                            .type(SessionActivityType.RESULT)
                            .summary("Session completed")
                            .timestamp(now)
                            .build());
                    onDebug.accept("[bridge:activity] sessionId=" + sessionId + " result subtype=success");
                } else if (subtype != null) {
                    List<String> errors = msg.get("errors") instanceof List<?>
                            ? (List<String>) msg.get("errors") : null;
                    String errorSummary = (errors != null && !errors.isEmpty())
                            ? errors.get(0) : "Error: " + subtype;
                    activities.add(SessionActivity.builder()
                            .type(SessionActivityType.ERROR)
                            .summary(errorSummary)
                            .timestamp(now)
                            .build());
                    onDebug.accept("[bridge:activity] sessionId=" + sessionId +
                            " result subtype=" + subtype + " error=\"" + errorSummary + "\"");
                }
            }
            default -> { /* ignore other message types */ }
        }
        return activities;
    }

    // ─── User message text extraction ─────────────────────────────────────────

    /**
     * Extract plain text from a replayed SDKUserMessage NDJSON line.
     * Returns the trimmed text if this is a real human-authored message, else null.
     * Translated from extractUserMessageText() in sessionRunner.ts
     */
    @SuppressWarnings("unchecked")
    public String extractUserMessageText(Map<String, Object> msg) {
        if (msg.get("parent_tool_use_id") != null) return null;
        if (Boolean.TRUE.equals(msg.get("isSynthetic"))) return null;
        if (Boolean.TRUE.equals(msg.get("isReplay"))) return null;

        Map<String, Object> message = (Map<String, Object>) msg.get("message");
        Object content = message != null ? message.get("content") : null;
        String text = null;
        if (content instanceof String s) {
            text = s;
        } else if (content instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> rawBlock) {
                    Map<String, Object> block = (Map<String, Object>) rawBlock;
                    if ("text".equals(block.get("type")) && block.get("text") instanceof String s) {
                        text = s;
                        break;
                    }
                }
            }
        }
        if (text != null) text = text.trim();
        return (text != null && !text.isEmpty()) ? text : null;
    }

    // ─── SessionSpawner factory ───────────────────────────────────────────────

    /**
     * Build a SessionSpawner that launches a child claude CLI process per session.
     * Translated from createSessionSpawner() in sessionRunner.ts
     */
    public SessionSpawner createSessionSpawner(SessionSpawnerDeps deps) {
        return (opts, dir) -> spawnSession(deps, opts, dir);
    }

    @SuppressWarnings("unchecked")
    private SessionHandle spawnSession(SessionSpawnerDeps deps, SessionSpawnOpts opts, String dir) {
        String safeId = safeFilenameId(opts.getSessionId());

        // Resolve debug file path
        String debugFile = null;
        if (deps.getDebugFile() != null) {
            String df = deps.getDebugFile();
            int ext = df.lastIndexOf('.');
            debugFile = (ext > 0)
                    ? df.substring(0, ext) + "-" + safeId + df.substring(ext)
                    : df + "-" + safeId;
        } else if (deps.isVerbose() || "ant".equals(System.getenv("USER_TYPE"))) {
            debugFile = System.getProperty("java.io.tmpdir") + "/claude/bridge-session-" + safeId + ".log";
        }

        // Transcript file
        BufferedWriter[] transcriptWriter = {null};
        if (deps.getDebugFile() != null) {
            Path transcriptPath = Paths.get(deps.getDebugFile()).getParent()
                    .resolve("bridge-transcript-" + safeId + ".jsonl");
            try {
                Files.createDirectories(transcriptPath.getParent());
                transcriptWriter[0] = new BufferedWriter(new FileWriter(transcriptPath.toFile(), true));
                deps.onDebug("[bridge:session] Transcript log: " + transcriptPath);
            } catch (IOException e) {
                deps.onDebug("[bridge:session] Could not open transcript: " + e.getMessage());
            }
        }

        // Build child process args
        List<String> args = new ArrayList<>(deps.getScriptArgs());
        args.add("--print");
        args.add("--sdk-url"); args.add(opts.getSdkUrl());
        args.add("--session-id"); args.add(opts.getSessionId());
        args.add("--input-format"); args.add("stream-json");
        args.add("--output-format"); args.add("stream-json");
        args.add("--replay-user-messages");
        if (deps.isVerbose()) args.add("--verbose");
        if (debugFile != null) { args.add("--debug-file"); args.add(debugFile); }
        if (deps.getPermissionMode() != null) {
            args.add("--permission-mode"); args.add(deps.getPermissionMode());
        }

        // Build environment
        Map<String, String> env = new HashMap<>(deps.getEnv());
        env.remove("CLAUDE_CODE_OAUTH_TOKEN");
        env.put("CLAUDE_CODE_ENVIRONMENT_KIND", "bridge");
        if (deps.isSandbox()) env.put("CLAUDE_CODE_FORCE_SANDBOX", "1");
        env.put("CLAUDE_CODE_SESSION_ACCESS_TOKEN", opts.getAccessToken());
        env.put("CLAUDE_CODE_POST_FOR_SESSION_INGRESS_V2", "1");
        if (opts.isUseCcrV2()) {
            env.put("CLAUDE_CODE_USE_CCR_V2", "1");
            if (opts.getWorkerEpoch() != null) {
                env.put("CLAUDE_CODE_WORKER_EPOCH", String.valueOf(opts.getWorkerEpoch()));
            }
        }

        deps.onDebug("[bridge:session] Spawning sessionId=" + opts.getSessionId() +
                " sdkUrl=" + opts.getSdkUrl() +
                " accessToken=" + (opts.getAccessToken() != null ? "present" : "MISSING"));
        deps.onDebug("[bridge:session] Child args: " + String.join(" ", args));
        if (debugFile != null) deps.onDebug("[bridge:session] Debug log: " + debugFile);

        // Spawn
        List<String> command = new ArrayList<>();
        command.add(deps.getExecPath());
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(dir));
        pb.environment().clear();
        pb.environment().putAll(env);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to spawn session: " + e.getMessage(), e);
        }

        deps.onDebug("[bridge:session] sessionId=" + opts.getSessionId() + " pid=" + process.pid());

        List<SessionActivity> activities = new ArrayList<>();
        SessionActivity[] currentActivityHolder = {null};
        List<String> lastStderr = new ArrayList<>();
        boolean[] sigkillSent = {false};
        boolean[] firstUserMessageSeen = {false};
        String[] accessTokenHolder = {opts.getAccessToken()};

        // Stderr reader
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (deps.isVerbose()) System.err.println(line);
                    synchronized (lastStderr) {
                        if (lastStderr.size() >= MAX_STDERR_LINES) lastStderr.remove(0);
                        lastStderr.add(line);
                    }
                }
            } catch (IOException ignored) {}
        }, "bridge-stderr-" + safeId);
        stderrThread.setDaemon(true);
        stderrThread.start();

        // Stdout NDJSON reader
        CompletableFuture<SessionDoneStatus> done = new CompletableFuture<>();

        Thread stdoutThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String finalLine = line;

                    // Write to transcript
                    if (transcriptWriter[0] != null) {
                        try {
                            transcriptWriter[0].write(finalLine);
                            transcriptWriter[0].newLine();
                            transcriptWriter[0].flush();
                        } catch (IOException e) {
                            deps.onDebug("[bridge:session] Transcript write error: " + e.getMessage());
                            transcriptWriter[0] = null;
                        }
                    }

                    deps.onDebug("[bridge:ws] sessionId=" + opts.getSessionId() +
                            " <<< " + debugTruncate(finalLine));
                    if (deps.isVerbose()) System.err.println(finalLine);

                    // Extract activities
                    List<SessionActivity> extracted = extractActivities(finalLine, opts.getSessionId(), deps::onDebug);
                    for (SessionActivity activity : extracted) {
                        synchronized (activities) {
                            if (activities.size() >= MAX_ACTIVITIES) activities.remove(0);
                            activities.add(activity);
                        }
                        currentActivityHolder[0] = activity;
                        deps.onActivity(opts.getSessionId(), activity);
                    }

                    // Detect control_request and replayed user messages
                    try {
                        Map<String, Object> parsed = objectMapper.readValue(finalLine, Map.class);
                        if (parsed != null) {
                            String msgType = (String) parsed.get("type");
                            if ("control_request".equals(msgType)) {
                                Map<String, Object> req = (Map<String, Object>) parsed.get("request");
                                if (req != null && "can_use_tool".equals(req.get("subtype"))) {
                                    String requestId = (String) parsed.get("request_id");
                                    String toolName = (String) req.get("tool_name");
                                    Map<String, Object> input = req.get("input") instanceof Map<?, ?>
                                            ? (Map<String, Object>) req.get("input") : Map.of();
                                    String toolUseId = (String) req.get("tool_use_id");
                                    PermissionRequest permReq = new PermissionRequest(
                                            "control_request", requestId,
                                            new PermissionRequest.PermissionRequestPayload(
                                                    "can_use_tool", toolName, input, toolUseId));
                                    deps.onPermissionRequest(opts.getSessionId(), permReq, accessTokenHolder[0]);
                                }
                            } else if ("user".equals(msgType) && !firstUserMessageSeen[0] &&
                                    opts.getOnFirstUserMessage() != null) {
                                String text = extractUserMessageText(parsed);
                                if (text != null) {
                                    firstUserMessageSeen[0] = true;
                                    opts.getOnFirstUserMessage().accept(text);
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (IOException ignored) {}

            // Process exited — determine status
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = -1;
            }

            // Close transcript
            if (transcriptWriter[0] != null) {
                try { transcriptWriter[0].close(); } catch (IOException ignored) {}
            }

            SessionDoneStatus status;
            // On UNIX we can't reliably distinguish SIGTERM from exit here, so check
            // for non-zero code as 'failed', 0 as 'completed'. Interruption via kill()
            // will result in a non-zero code which maps to 'interrupted' via the kill flag.
            if (code == 0) {
                deps.onDebug("[bridge:session] sessionId=" + opts.getSessionId() +
                        " completed exit_code=0 pid=" + process.pid());
                status = SessionDoneStatus.COMPLETED;
            } else {
                deps.onDebug("[bridge:session] sessionId=" + opts.getSessionId() +
                        " failed exit_code=" + code + " pid=" + process.pid());
                status = SessionDoneStatus.FAILED;
            }
            done.complete(status);
        }, "bridge-stdout-" + safeId);
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        // Build and return handle
        return new SessionHandle() {
            @Override public String getSessionId() { return opts.getSessionId(); }
            @Override public CompletableFuture<SessionDoneStatus> getDone() { return done; }

            @Override
            public void kill() {
                if (process.isAlive()) {
                    deps.onDebug("[bridge:session] Sending SIGTERM to sessionId=" +
                            opts.getSessionId() + " pid=" + process.pid());
                    process.destroy(); // SIGTERM on Unix
                }
            }

            @Override
            public void forceKill() {
                if (!sigkillSent[0] && process.isAlive()) {
                    sigkillSent[0] = true;
                    deps.onDebug("[bridge:session] Sending SIGKILL to sessionId=" +
                            opts.getSessionId() + " pid=" + process.pid());
                    process.destroyForcibly(); // SIGKILL
                }
            }

            @Override
            public List<SessionActivity> getActivities() {
                synchronized (activities) { return new ArrayList<>(activities); }
            }

            @Override
            public SessionActivity getCurrentActivity() { return currentActivityHolder[0]; }

            @Override
            public String getAccessToken() { return accessTokenHolder[0]; }

            @Override
            public List<String> getLastStderr() {
                synchronized (lastStderr) { return new ArrayList<>(lastStderr); }
            }

            @Override
            public void writeStdin(String data) {
                OutputStream stdin = process.getOutputStream();
                if (stdin != null) {
                    deps.onDebug("[bridge:ws] sessionId=" + opts.getSessionId() +
                            " >>> " + debugTruncate(data));
                    try {
                        stdin.write(data.getBytes());
                        stdin.flush();
                    } catch (IOException e) {
                        deps.onDebug("[bridge:session] stdin write error: " + e.getMessage());
                    }
                }
            }

            @Override
            public void updateAccessToken(String token) {
                accessTokenHolder[0] = token;
                // Send token refresh via stdin as update_environment_variables message
                try {
                    Map<String, Object> msg = Map.of(
                            "type", "update_environment_variables",
                            "variables", Map.of("CLAUDE_CODE_SESSION_ACCESS_TOKEN", token));
                    writeStdin(objectMapper.writeValueAsString(msg) + "\n");
                    deps.onDebug("[bridge:session] Sent token refresh via stdin for sessionId=" +
                            opts.getSessionId());
                } catch (Exception e) {
                    deps.onDebug("[bridge:session] Failed to send token refresh: " + e.getMessage());
                }
            }
        };
    }

    /** Truncate long strings for debug output (matches debugTruncate in TS). */
    private static String debugTruncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
