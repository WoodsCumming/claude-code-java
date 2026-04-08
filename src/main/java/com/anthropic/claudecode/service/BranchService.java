package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conversation branching service.
 * Translated from src/commands/branch/branch.ts
 *
 * Creates a fork of the current conversation session by copying the transcript
 * file and rewriting session identifiers, then resumes into the new fork.
 */
@Slf4j
@Service
public class BranchService {



    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final SessionStorageService sessionStorageService;
    private final AnalyticsService analyticsService;
    private final ObjectMapper objectMapper;

    @Autowired
    public BranchService(SessionStorageService sessionStorageService,
                          AnalyticsService analyticsService,
                          ObjectMapper objectMapper) {
        this.sessionStorageService = sessionStorageService;
        this.analyticsService = analyticsService;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Entry-point for the /branch slash command.
     * Mirrors call() in branch.ts.
     *
     * @param args        Optional custom title for the branched conversation.
     * @param originalCwd Working directory at session start.
     * @param sessionId   Current session identifier.
     * @return A {@link BranchResult} describing success or failure.
     */
    public CompletableFuture<BranchResult> branch(String args,
                                                   String originalCwd,
                                                   String sessionId) {
        String customTitle = (args != null && !args.isBlank()) ? args.trim() : null;

        return CompletableFuture.supplyAsync(() -> {
            try {
                ForkData fork = createFork(customTitle, originalCwd, sessionId);

                // Determine display title (first user prompt or custom label)
                String firstPrompt = deriveFirstPrompt(fork.serializedMessages());
                String baseName = (customTitle != null) ? customTitle : firstPrompt;
                String effectiveTitle = getUniqueForkName(baseName);

                sessionStorageService.saveCustomTitle(fork.sessionId(), effectiveTitle, fork.forkPath());

                analyticsService.logEvent("tengu_conversation_forked", Map.of(
                    "message_count", fork.serializedMessages().size(),
                    "has_custom_title", customTitle != null
                ));

                String titleInfo = (customTitle != null) ? " \"" + customTitle + "\"" : "";
                String resumeHint = "\nTo resume the original: claude -r " + sessionId;
                String successMessage = "Branched conversation" + titleInfo +
                    ". You are now in the branch." + resumeHint;

                return BranchResult.success(fork.sessionId(), effectiveTitle, successMessage);

            } catch (Exception e) {
                String msg = (e instanceof RuntimeException re && re.getMessage() != null)
                    ? e.getMessage() : "Unknown error occurred";
                log.error("Failed to branch conversation: {}", msg, e);
                return BranchResult.failure("Failed to branch conversation: " + msg);
            }
        });
    }

    // ---------------------------------------------------------------------------
    // Title derivation
    // ---------------------------------------------------------------------------

    /**
     * Derives a single-line title from the first user message.
     * Mirrors deriveFirstPrompt() in branch.ts.
     */
    /** Convenience method to create a branch synchronously with a name. */
    public void createBranch(String branchName) {
        branch(branchName, null, null).join();
    }

    public String deriveFirstPrompt(List<SerializedMessage> messages) {
        if (messages == null || messages.isEmpty()) return "Branched conversation";

        SerializedMessage firstUser = messages.stream()
            .filter(m -> "user".equals(m.type()))
            .findFirst()
            .orElse(null);

        if (firstUser == null || firstUser.content() == null) return "Branched conversation";

        String raw = firstUser.content();
        String collapsed = raw.replaceAll("\\s+", " ").trim();
        if (collapsed.length() > 100) collapsed = collapsed.substring(0, 100);
        return collapsed.isEmpty() ? "Branched conversation" : collapsed;
    }

    // ---------------------------------------------------------------------------
    // Fork creation
    // ---------------------------------------------------------------------------

    /**
     * Creates the fork session file by reading the current transcript and
     * rewriting session IDs.  Mirrors createFork() in branch.ts.
     */
    private ForkData createFork(String customTitle, String originalCwd, String originalSessionId)
            throws IOException {

        String forkSessionId = UUID.randomUUID().toString();

        Path projectDir = sessionStorageService.getProjectDirPath(originalCwd);
        Files.createDirectories(projectDir);

        Path currentTranscript = sessionStorageService.getTranscriptPath(originalSessionId);
        if (!Files.exists(currentTranscript)) {
            throw new RuntimeException("No conversation to branch");
        }

        byte[] content = Files.readAllBytes(currentTranscript);
        if (content.length == 0) {
            throw new RuntimeException("No conversation to branch");
        }

        // Parse JSONL transcript
        List<Map<String, Object>> entries = parseJsonl(content);

        // Filter to main conversation messages (exclude sidechains)
        List<Map<String, Object>> mainEntries = entries.stream()
            .filter(e -> isTranscriptMessage(e) && !Boolean.TRUE.equals(e.get("isSidechain")))
            .toList();

        if (mainEntries.isEmpty()) {
            throw new RuntimeException("No messages to branch");
        }

        // Collect content-replacement records for the original session
        List<Map<String, Object>> replacementRecords = entries.stream()
            .filter(e -> "content-replacement".equals(e.get("type"))
                && originalSessionId.equals(e.get("sessionId")))
            .flatMap(e -> {
                Object reps = e.get("replacements");
                if (reps instanceof List<?> list) {
                    return list.stream().map(r -> (Map<String, Object>) r);
                }
                return java.util.stream.Stream.empty();
            })
            .toList();

        // Build forked JSONL lines
        List<String> lines = new ArrayList<>();
        List<SerializedMessage> serializedMessages = new ArrayList<>();
        String parentUuid = null;

        for (Map<String, Object> entry : mainEntries) {
            Map<String, Object> forked = new LinkedHashMap<>(entry);
            forked.put("sessionId", forkSessionId);
            forked.put("parentUuid", parentUuid);
            forked.put("isSidechain", false);
            forked.put("forkedFrom", Map.of(
                "sessionId", originalSessionId,
                "messageUuid", entry.get("uuid")
            ));

            SerializedMessage serialized = new SerializedMessage(
                (String) entry.get("type"),
                forkSessionId,
                (String) entry.get("uuid"),
                extractContentAsString(entry)
            );
            serializedMessages.add(serialized);

            lines.add(objectMapper.writeValueAsString(forked));

            if (!"progress".equals(entry.get("type"))) {
                parentUuid = (String) entry.get("uuid");
            }
        }

        // Append content-replacement entry for the fork
        if (!replacementRecords.isEmpty()) {
            Map<String, Object> forkReplacement = Map.of(
                "type", "content-replacement",
                "sessionId", forkSessionId,
                "replacements", replacementRecords
            );
            lines.add(objectMapper.writeValueAsString(forkReplacement));
        }

        // Write fork file
        Path forkPath = sessionStorageService.getTranscriptPathForSession(forkSessionId);
        String fileContent = String.join("\n", lines) + "\n";

        Set<PosixFilePermission> perms = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE
        );
        Files.writeString(forkPath, fileContent);
        try {
            Files.setPosixFilePermissions(forkPath, perms);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (e.g. Windows) — skip permission setting
        }

        return new ForkData(forkSessionId, customTitle, forkPath.toString(), serializedMessages);
    }

    // ---------------------------------------------------------------------------
    // Unique name resolution
    // ---------------------------------------------------------------------------

    /**
     * Generates a unique fork name, appending " (Branch)" and incrementing the
     * numeric suffix on collision.  Mirrors getUniqueForkName() in branch.ts.
     */
    private String getUniqueForkName(String baseName) {
        String candidate = baseName + " (Branch)";

        List<SessionStorageService.SessionInfo> exactMatches =
            sessionStorageService.searchSessionsByCustomTitle(candidate, true);

        if (exactMatches.isEmpty()) {
            return candidate;
        }

        // Name collision — find all forks with this base and pick the next number
        List<SessionStorageService.SessionInfo> existingForks =
            sessionStorageService.searchSessionsByCustomTitle(baseName + " (Branch", false);

        Set<Integer> usedNumbers = new HashSet<>();
        usedNumbers.add(1); // "(Branch)" without number counts as 1

        Pattern pattern = Pattern.compile(
            "^" + Pattern.quote(baseName) + " \\(Branch(?: (\\d+))?\\)$"
        );

        for (SessionStorageService.SessionInfo session : existingForks) {
            if (session.customTitle() == null) continue;
            Matcher matcher = pattern.matcher(session.customTitle());
            if (matcher.matches()) {
                String numStr = matcher.group(1);
                usedNumbers.add(numStr != null ? Integer.parseInt(numStr) : 1);
            }
        }

        int next = 2;
        while (usedNumbers.contains(next)) {
            next++;
        }
        return baseName + " (Branch " + next + ")";
    }

    // ---------------------------------------------------------------------------
    // JSONL helpers
    // ---------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonl(byte[] content) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        String text = new String(content);
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(objectMapper.readValue(trimmed, Map.class));
            }
        }
        return result;
    }

    private boolean isTranscriptMessage(Map<String, Object> entry) {
        String type = (String) entry.get("type");
        return "user".equals(type) || "assistant".equals(type) || "progress".equals(type);
    }

    private String extractContentAsString(Map<String, Object> entry) {
        Object content = entry.get("content");
        if (content == null) return null;
        if (content instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            return content.toString();
        }
    }

    // ---------------------------------------------------------------------------
    // Inner types
    // ---------------------------------------------------------------------------

    /**
     * Immutable record representing a serialized conversation message.
     */
    public record SerializedMessage(
        String type,
        String sessionId,
        String uuid,
        String content
    ) {}

    /**
     * Internal data holder returned by {@link #createFork}.
     */
    private record ForkData(
        String sessionId,
        String title,
        String forkPath,
        List<SerializedMessage> serializedMessages
    ) {}

    /**
     * Sealed result type for the branch operation.
     * Mirrors the success/failure paths in call() in branch.ts.
     */
    public sealed interface BranchResult permits BranchResult.Success, BranchResult.Failure {

        static BranchResult success(String sessionId, String title, String message) {
            return new Success(sessionId, title, message);
        }

        static BranchResult failure(String message) {
            return new Failure(message);
        }

        record Success(String sessionId, String title, String message) implements BranchResult {}

        record Failure(String message) implements BranchResult {}
    }
}
