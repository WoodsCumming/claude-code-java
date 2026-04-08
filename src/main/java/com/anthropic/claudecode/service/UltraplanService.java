package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Ultraplan CCR session service.
 * Translated from src/utils/ultraplan/ccrSession.ts
 *
 * Polls a remote CCR session for an approved ExitPlanMode tool_result,
 * then extracts the plan text. Uses pollRemoteSessionEvents (shared with
 * RemoteAgentTask) for pagination and typed SDK messages.
 */
@Slf4j
@Service
public class UltraplanService {



    /** Polling interval between remote session event fetches. */
    private static final long POLL_INTERVAL_MS = 3000;

    /**
     * Max consecutive failures before aborting — guards against endless 5xx
     * chains over a 30-minute poll window (~600 calls).
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    /**
     * Sentinel included by the browser PlanModal when the user clicks
     * "teleport back to terminal". Plan text follows on the next line.
     * Translated from ULTRAPLAN_TELEPORT_SENTINEL in ccrSession.ts
     */
    public static final String ULTRAPLAN_TELEPORT_SENTINEL = "__ULTRAPLAN_TELEPORT_LOCAL__";

    private final TeleportService teleportService;

    @Autowired
    public UltraplanService(TeleportService teleportService) {
        this.teleportService = teleportService;
    }

    // =========================================================================
    // Public types — mirrors TypeScript types in ccrSession.ts
    // =========================================================================

    /**
     * Reason for a poll failure.
     * Translated from PollFailReason in ccrSession.ts
     */
    public enum PollFailReason {
        terminated,
        timeout_pending,
        timeout_no_plan,
        extract_marker_missing,
        network_or_unknown,
        stopped
    }

    /**
     * Exception thrown when the poll loop cannot produce a plan.
     * Translated from UltraplanPollError in ccrSession.ts
     */
    public static class UltraplanPollError extends RuntimeException {
        private final PollFailReason reason;
        private final int rejectCount;

        public UltraplanPollError(String message, PollFailReason reason, int rejectCount) {
            super(message);
            this.reason = reason;
            this.rejectCount = rejectCount;
        }

        public UltraplanPollError(String message, PollFailReason reason, int rejectCount, Throwable cause) {
            super(message, cause);
            this.reason = reason;
            this.rejectCount = rejectCount;
        }

        public PollFailReason getReason() { return reason; }
        public int getRejectCount() { return rejectCount; }
    }

    /**
     * Verdict from one ExitPlanModeScanner ingest cycle.
     * Translated from ScanResult in ccrSession.ts
     */
    public sealed interface ScanResult
        permits ScanResult.Approved, ScanResult.Teleport, ScanResult.Rejected,
                ScanResult.Pending, ScanResult.Terminated, ScanResult.Unchanged {

        record Approved(String plan) implements ScanResult {}
        record Teleport(String plan) implements ScanResult {}
        record Rejected(String id) implements ScanResult {}
        record Pending() implements ScanResult {}
        record Terminated(String subtype) implements ScanResult {}
        record Unchanged() implements ScanResult {}
    }

    /**
     * UI phase derived from the event stream.
     * Translated from UltraplanPhase in ccrSession.ts
     */
    public enum UltraplanPhase { running, needs_input, plan_ready }

    /**
     * Final result of a successful poll.
     * Translated from PollResult in ccrSession.ts
     */
    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class PollResult {
        private String plan;
        private int rejectCount;
        /** "local" = user clicked teleport; "remote" = user approved in-CCR execution. */
        private String executionTarget;

        public String getPlan() { return plan; }
        public void setPlan(String v) { plan = v; }
        public void setRejectCount(int v) { rejectCount = v; }
        public String getExecutionTarget() { return executionTarget; }
        public void setExecutionTarget(String v) { executionTarget = v; }
    }

    // =========================================================================
    // ExitPlanModeScanner — stateful classifier for the CCR event stream
    // =========================================================================

    /**
     * Pure stateful classifier for the CCR event stream.
     * Translated from ExitPlanModeScanner in ccrSession.ts
     *
     * Ingests SDKMessage batches and returns the current ExitPlanMode verdict.
     * No I/O — feed it synthetic or recorded events for unit tests.
     *
     * Precedence: approved > terminated > rejected > pending > unchanged
     */
    public static class ExitPlanModeScanner {

        private static final String EXIT_PLAN_MODE_V2_TOOL_NAME = "exit_plan_mode";

        private final List<String> exitPlanCalls = new ArrayList<>();
        private final Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        private final Set<String> rejectedIds = new LinkedHashSet<>();
        private Map<String, Object> terminated = null;
        private boolean rescanAfterRejection = false;
        boolean everSeenPending = false;


        /**
         * Returns the number of rejected plan IDs seen so far.
         */
        public int getRejectCount() {
            return rejectedIds.size();
        }

        /**
         * True when an ExitPlanMode tool_use exists with no tool_result yet.
         */
        public boolean hasPendingPlan() {
            for (int i = exitPlanCalls.size() - 1; i >= 0; i--) {
                String id = exitPlanCalls.get(i);
                if (!rejectedIds.contains(id)) {
                    return !results.containsKey(id);
                }
            }
            return false;
        }

        /**
         * Ingest a batch of SDK messages and return the current verdict.
         * Translated from ingest() in ExitPlanModeScanner in ccrSession.ts
         *
         * @param newEvents list of SDK message maps (type:"assistant"|"user"|"result")
         */
        @SuppressWarnings("unchecked")
        public ScanResult ingest(List<Map<String, Object>> newEvents) {
            for (Map<String, Object> m : newEvents) {
                String type = (String) m.get("type");
                if ("assistant".equals(type)) {
                    Map<String, Object> message = (Map<String, Object>) m.get("message");
                    if (message == null) continue;
                    List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
                    if (content == null) continue;
                    for (Map<String, Object> block : content) {
                        if (!"tool_use".equals(block.get("type"))) continue;
                        if (EXIT_PLAN_MODE_V2_TOOL_NAME.equals(block.get("name"))) {
                            String id = (String) block.get("id");
                            if (id != null) exitPlanCalls.add(id);
                        }
                    }
                } else if ("user".equals(type)) {
                    Map<String, Object> message = (Map<String, Object>) m.get("message");
                    if (message == null) continue;
                    Object raw = message.get("content");
                    if (!(raw instanceof List)) continue;
                    List<Map<String, Object>> content = (List<Map<String, Object>>) raw;
                    for (Map<String, Object> block : content) {
                        if ("tool_result".equals(block.get("type"))) {
                            String toolUseId = (String) block.get("tool_use_id");
                            if (toolUseId != null) results.put(toolUseId, block);
                        }
                    }
                } else if ("result".equals(type)) {
                    String subtype = (String) m.get("subtype");
                    if (!"success".equals(subtype)) {
                        terminated = m;
                    }
                }
            }

            boolean shouldScan = !newEvents.isEmpty() || rescanAfterRejection;
            rescanAfterRejection = false;

            ScanResult found = null;
            if (shouldScan) {
                for (int i = exitPlanCalls.size() - 1; i >= 0; i--) {
                    String id = exitPlanCalls.get(i);
                    if (rejectedIds.contains(id)) continue;
                    Map<String, Object> tr = results.get(id);
                    if (tr == null) {
                        found = new ScanResult.Pending();
                    } else if (Boolean.TRUE.equals(tr.get("is_error"))) {
                        String teleportPlan = extractTeleportPlan(tr.get("content"));
                        found = teleportPlan != null
                            ? new ScanResult.Teleport(teleportPlan)
                            : new ScanResult.Rejected(id);
                    } else {
                        String plan;
                        try {
                            plan = extractApprovedPlan(tr.get("content"));
                        } catch (Exception e) {
                            throw new RuntimeException(e.getMessage(), e);
                        }
                        found = new ScanResult.Approved(plan);
                    }
                    break;
                }
                if (found instanceof ScanResult.Approved || found instanceof ScanResult.Teleport) {
                    return found;
                }
            }

            // Bookkeeping before terminated check
            if (found instanceof ScanResult.Rejected rej) {
                rejectedIds.add(rej.id());
                rescanAfterRejection = true;
            }
            if (terminated != null) {
                return new ScanResult.Terminated((String) terminated.get("subtype"));
            }
            if (found instanceof ScanResult.Rejected) return found;
            if (found instanceof ScanResult.Pending) {
                everSeenPending = true;
                return found;
            }
            return new ScanResult.Unchanged();
        }
    }

    // =========================================================================
    // Main polling loop
    // =========================================================================

    /**
     * Poll the remote CCR session until the user approves or rejects the plan.
     * Translated from pollForApprovedExitPlanMode() in ccrSession.ts
     *
     * @param sessionId       Remote session ID to poll
     * @param timeoutMs       Maximum total wait time in milliseconds
     * @param onPhaseChange   Optional callback when the UltraplanPhase changes
     * @param shouldStop      Optional supplier; returns true to abort early
     * @return CompletableFuture resolving to the approved PollResult
     */
    public CompletableFuture<PollResult> pollForApprovedExitPlanMode(
            String sessionId,
            long timeoutMs,
            Consumer<UltraplanPhase> onPhaseChange,
            BooleanSupplier shouldStop) {

        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.currentTimeMillis() + timeoutMs;
            ExitPlanModeScanner scanner = new ExitPlanModeScanner();
            String[] cursor = {null};
            int[] failures = {0};
            UltraplanPhase[] lastPhase = {UltraplanPhase.running};

            while (System.currentTimeMillis() < deadline) {
                if (shouldStop != null && shouldStop.getAsBoolean()) {
                    throw new UltraplanPollError("poll stopped by caller", PollFailReason.stopped, scanner.getRejectCount());
                }

                List<Map<String, Object>> newEvents;
                String sessionStatus;
                try {
                    // Poll remote session events
                    Map<String, Object> pollResult = pollRemoteSessionEvents(sessionId, cursor[0]);
                    newEvents = (List<Map<String, Object>>) pollResult.get("newEvents");
                    cursor[0] = (String) pollResult.get("lastEventId");
                    sessionStatus = (String) pollResult.get("sessionStatus");
                    failures[0] = 0;
                } catch (Exception e) {
                    boolean transient_ = TeleportService.isTransientNetworkError(e);
                    if (!transient_ || ++failures[0] >= MAX_CONSECUTIVE_FAILURES) {
                        throw new UltraplanPollError(
                            e.getMessage() != null ? e.getMessage() : e.toString(),
                            PollFailReason.network_or_unknown,
                            scanner.getRejectCount(),
                            e);
                    }
                    sleep(POLL_INTERVAL_MS);
                    continue;
                }

                ScanResult result;
                try {
                    result = scanner.ingest(newEvents);
                } catch (Exception e) {
                    throw new UltraplanPollError(
                        e.getMessage() != null ? e.getMessage() : e.toString(),
                        PollFailReason.extract_marker_missing,
                        scanner.getRejectCount());
                }

                if (result instanceof ScanResult.Approved approved) {
                    return new PollResult(approved.plan(), scanner.getRejectCount(), "remote");
                }
                if (result instanceof ScanResult.Teleport teleport) {
                    return new PollResult(teleport.plan(), scanner.getRejectCount(), "local");
                }
                if (result instanceof ScanResult.Terminated term) {
                    throw new UltraplanPollError(
                        "remote session ended (" + term.subtype() + ") before plan approval",
                        PollFailReason.terminated,
                        scanner.getRejectCount());
                }

                // Determine current phase
                boolean quietIdle =
                    ("idle".equals(sessionStatus) || "requires_action".equals(sessionStatus))
                    && newEvents.isEmpty();
                UltraplanPhase phase = scanner.hasPendingPlan()
                    ? UltraplanPhase.plan_ready
                    : quietIdle ? UltraplanPhase.needs_input : UltraplanPhase.running;

                if (phase != lastPhase[0]) {
                    log.debug("[ultraplan] phase {} → {}", lastPhase[0], phase);
                    lastPhase[0] = phase;
                    if (onPhaseChange != null) onPhaseChange.accept(phase);
                }

                sleep(POLL_INTERVAL_MS);
            }

            throw new UltraplanPollError(
                scanner.everSeenPending
                    ? "no approval after " + (timeoutMs / 1000) + "s"
                    : "ExitPlanMode never reached after " + (timeoutMs / 1000)
                      + "s (the remote container failed to start, or session ID mismatch?)",
                scanner.everSeenPending ? PollFailReason.timeout_pending : PollFailReason.timeout_no_plan,
                scanner.getRejectCount());
        });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Fetch new events from the remote session (stub — delegates to TeleportService).
     * Returns a map with keys: newEvents, lastEventId, sessionStatus.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pollRemoteSessionEvents(String sessionId, String cursor) {
        // In the full implementation this would call the dedicated pollRemoteSessionEvents
        // endpoint. Stubbed here to match the structure expected by the caller.
        try {
            List<Map<String, Object>> events = teleportService
                .pollRemoteSessionEvents(sessionId, cursor).join();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("newEvents", events);
            result.put("lastEventId", cursor);
            result.put("sessionStatus", "running");
            return result;
        } catch (Exception e) {
            throw new RuntimeException("pollRemoteSessionEvents failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extract the plan text after the ULTRAPLAN_TELEPORT_SENTINEL marker.
     * Returns null when the sentinel is absent.
     * Translated from extractTeleportPlan() in ccrSession.ts
     */
    private static String extractTeleportPlan(Object content) {
        String text = contentToText(content);
        String marker = ULTRAPLAN_TELEPORT_SENTINEL + "\n";
        int idx = text.indexOf(marker);
        if (idx == -1) return null;
        String plan = text.substring(idx + marker.length());
        // trimEnd equivalent
        int end = plan.length();
        while (end > 0 && Character.isWhitespace(plan.charAt(end - 1))) end--;
        return plan.substring(0, end);
    }

    /**
     * Extract the plan text after the "## Approved Plan:" marker.
     * Translated from extractApprovedPlan() in ccrSession.ts
     */
    private static String extractApprovedPlan(Object content) {
        String text = contentToText(content);
        List<String> markers = List.of(
            "## Approved Plan (edited by user):\n",
            "## Approved Plan:\n"
        );
        for (String marker : markers) {
            int idx = text.indexOf(marker);
            if (idx != -1) {
                String plan = text.substring(idx + marker.length());
                int end = plan.length();
                while (end > 0 && Character.isWhitespace(plan.charAt(end - 1))) end--;
                return plan.substring(0, end);
            }
        }
        throw new RuntimeException(
            "ExitPlanMode approved but tool_result has no \"## Approved Plan:\" marker — "
            + "remote may have hit the empty-plan or isAgent branch. Content preview: "
            + text.substring(0, Math.min(200, text.length())));
    }

    /**
     * Normalise tool_result content (string or [{type:"text",text:...}]) to a plain string.
     * Translated from contentToText() in ccrSession.ts
     */
    @SuppressWarnings("unchecked")
    private static String contentToText(Object content) {
        if (content instanceof String s) return s;
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Object text = map.get("text");
                    if (text instanceof String t) sb.append(t);
                }
            }
            return sb.toString();
        }
        return "";
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
