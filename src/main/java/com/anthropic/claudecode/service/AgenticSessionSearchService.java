package com.anthropic.claudecode.service;

import com.anthropic.claudecode.client.AnthropicClient;
import com.anthropic.claudecode.util.ModelUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Agentic session search service.
 * Translated from src/utils/agenticSessionSearch.ts
 *
 * Uses Claude (small fast model) to semantically search through session history.
 * Pre-filters sessions by keyword match, then sends the top candidates to the
 * model for relevance ranking.
 */
@Slf4j
@Service
public class AgenticSessionSearchService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgenticSessionSearchService.class);


    // Limits for transcript extraction — translated from constants in agenticSessionSearch.ts
    private static final int MAX_TRANSCRIPT_CHARS = 2000;
    private static final int MAX_MESSAGES_TO_SCAN = 100;
    private static final int MAX_SESSIONS_TO_SEARCH = 100;

    /**
     * System prompt for the session search model call.
     * Translated from SESSION_SEARCH_SYSTEM_PROMPT in agenticSessionSearch.ts
     */
    private static final String SESSION_SEARCH_SYSTEM_PROMPT = """
        Your goal is to find relevant sessions based on a user's search query.

        You will be given a list of sessions with their metadata and a search query. Identify which sessions are most relevant to the query.

        Each session may include:
        - Title (display name or custom title)
        - Tag (user-assigned category, shown as [tag: name] - users tag sessions with /tag command to categorize them)
        - Branch (git branch name, shown as [branch: name])
        - Summary (AI-generated summary)
        - First message (beginning of the conversation)
        - Transcript (excerpt of conversation content)

        IMPORTANT: Tags are user-assigned labels that indicate the session's topic or category. If the query matches a tag exactly or partially, those sessions should be highly prioritized.

        For each session, consider (in order of priority):
        1. Exact tag matches (highest priority - user explicitly categorized this session)
        2. Partial tag matches or tag-related terms
        3. Title matches (custom titles or first message content)
        4. Branch name matches
        5. Summary and transcript content matches
        6. Semantic similarity and related concepts

        CRITICAL: Be VERY inclusive in your matching. Include sessions that:
        - Contain the query term anywhere in any field
        - Are semantically related to the query (e.g., "testing" matches sessions about "tests", "unit tests", "QA", etc.)
        - Discuss topics that could be related to the query
        - Have transcripts that mention the concept even in passing

        When in doubt, INCLUDE the session. It's better to return too many results than too few.

        Return sessions ordered by relevance (most relevant first). If truly no sessions have ANY connection to the query, return an empty array - but this should be rare.

        Respond with ONLY the JSON object, no markdown formatting:
        {"relevant_indices": [2, 5, 0]}
        """;

    private final AnthropicClient anthropicClient;
    private final SessionStorageService sessionStorageService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgenticSessionSearchService(
            AnthropicClient anthropicClient,
            SessionStorageService sessionStorageService,
            ObjectMapper objectMapper) {
        this.anthropicClient = anthropicClient;
        this.sessionStorageService = sessionStorageService;
        this.objectMapper = objectMapper;
    }

    // ── Main search entry point ───────────────────────────────────────────────

    /**
     * Performs an agentic search using Claude to find relevant sessions.
     * Translated from agenticSessionSearch() in agenticSessionSearch.ts
     *
     * @param query the search query
     * @param logs  all available session log options
     * @return ordered list of matching sessions (most relevant first)
     */
    public CompletableFuture<List<SessionStorageService.SessionMetadata>> agenticSessionSearch(
            String query,
            List<SessionStorageService.SessionMetadata> logs) {

        if (query == null || query.isBlank() || logs == null || logs.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }

        String queryLower = query.toLowerCase();

        // Pre-filter: find sessions that contain the query term in any searchable field
        List<SessionStorageService.SessionMetadata> matchingLogs = logs.stream()
            .filter(log -> logContainsQuery(log, queryLower))
            .collect(Collectors.toList());

        // Take up to MAX_SESSIONS_TO_SEARCH; fill remaining slots with non-matching logs
        List<SessionStorageService.SessionMetadata> logsToSearch;
        if (matchingLogs.size() >= MAX_SESSIONS_TO_SEARCH) {
            logsToSearch = matchingLogs.subList(0, MAX_SESSIONS_TO_SEARCH);
        } else {
            List<SessionStorageService.SessionMetadata> nonMatchingLogs = logs.stream()
                .filter(log -> !logContainsQuery(log, queryLower))
                .collect(Collectors.toList());
            int remainingSlots = MAX_SESSIONS_TO_SEARCH - matchingLogs.size();
            logsToSearch = new ArrayList<>(matchingLogs);
            logsToSearch.addAll(nonMatchingLogs.subList(0, Math.min(remainingSlots, nonMatchingLogs.size())));
        }

        log.debug("Agentic search: {}/{} logs, query=\"{}\", matching: {}",
            logsToSearch.size(), logs.size(), query, matchingLogs.size());

        // Build the session list for the prompt
        String sessionList = buildSessionList(logsToSearch);
        String userMessage = "Sessions:\n" + sessionList + "\n\nSearch query: \"" + query + "\"\n\n"
            + "Find the sessions that are most relevant to this query.";

        log.debug("Agentic search prompt (first 500 chars): {}...",
            userMessage.substring(0, Math.min(500, userMessage.length())));

        String model = ModelUtils.getSmallFastModel();
        log.debug("Agentic search using model: {}", model);

        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", userMessage));

        AnthropicClient.MessageRequest request = AnthropicClient.MessageRequest.builder()
            .model(model)
            .maxTokens(1024)
            .system(List.of(Map.of("type", "text", "text", SESSION_SEARCH_SYSTEM_PROMPT)))
            .messages(messages)
            .build();

        return CompletableFuture.supplyAsync(() -> {
            try {
                AnthropicClient.MessageResponse response = anthropicClient
                    .createMessage(request)
                    .get();

                String textContent = extractTextContent(response);
                if (textContent == null || textContent.isBlank()) {
                    log.debug("No text content in agentic search response");
                    return List.of();
                }

                log.debug("Agentic search response: {}", textContent);

                // Extract JSON from response
                java.util.regex.Matcher jsonMatch = java.util.regex.Pattern
                    .compile("\\{[\\s\\S]*\\}")
                    .matcher(textContent);
                if (!jsonMatch.find()) {
                    log.debug("Could not find JSON in agentic search response");
                    return List.<SessionStorageService.SessionMetadata>of();
                }

                // Parse {"relevant_indices": [2, 5, 0]}
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(jsonMatch.group(), Map.class);
                @SuppressWarnings("unchecked")
                List<Integer> relevantIndices = (List<Integer>) parsed.getOrDefault("relevant_indices", List.of());

                List<SessionStorageService.SessionMetadata> relevantLogs = relevantIndices.stream()
                    .filter(idx -> idx >= 0 && idx < logsToSearch.size())
                    .map(logsToSearch::get)
                    .collect(Collectors.toList());

                log.debug("Agentic search found {} relevant sessions", relevantLogs.size());
                return relevantLogs;

            } catch (Exception e) {
                log.error("Session search failed: {}", e.getMessage());
                log.debug("Agentic search error: {}", e.toString());
                return List.<SessionStorageService.SessionMetadata>of();
            }
        });
    }

    // ── Keyword pre-filter ────────────────────────────────────────────────────

    /**
     * Checks if a log contains the query term in any searchable field.
     * Translated from logContainsQuery() in agenticSessionSearch.ts
     */
    private boolean logContainsQuery(SessionStorageService.SessionMetadata log, String queryLower) {
        // Check name/title
        if (log.getName() != null && log.getName().toLowerCase().contains(queryLower)) return true;

        // Check summary
        if (log.getSummary() != null && log.getSummary().toLowerCase().contains(queryLower)) return true;

        // Check tag
        if (log.getTag() != null && log.getTag().toLowerCase().contains(queryLower)) return true;

        // Check git branch
        if (log.getGitBranch() != null && log.getGitBranch().toLowerCase().contains(queryLower)) return true;

        // Check first prompt
        if (log.getFirstPrompt() != null && log.getFirstPrompt().toLowerCase().contains(queryLower)) return true;

        return false;
    }

    // ── Transcript extraction ─────────────────────────────────────────────────

    /**
     * Extracts a truncated transcript from session messages (first + last N/2 messages).
     * Translated from extractTranscript() in agenticSessionSearch.ts
     */
    private String extractTranscript(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) return "";

        List<Map<String, Object>> messagesToScan;
        if (messages.size() <= MAX_MESSAGES_TO_SCAN) {
            messagesToScan = messages;
        } else {
            messagesToScan = new ArrayList<>(messages.subList(0, MAX_MESSAGES_TO_SCAN / 2));
            messagesToScan.addAll(messages.subList(messages.size() - MAX_MESSAGES_TO_SCAN / 2, messages.size()));
        }

        String text = messagesToScan.stream()
            .map(this::extractMessageText)
            .filter(t -> !t.isBlank())
            .collect(Collectors.joining(" "))
            .replaceAll("\\s+", " ")
            .trim();

        return text.length() > MAX_TRANSCRIPT_CHARS
            ? text.substring(0, MAX_TRANSCRIPT_CHARS) + "…"
            : text;
    }

    /**
     * Extracts searchable text content from a message.
     * Translated from extractMessageText() in agenticSessionSearch.ts
     */
    @SuppressWarnings("unchecked")
    private String extractMessageText(Map<String, Object> message) {
        String type = (String) message.get("type");
        if (!"user".equals(type) && !"assistant".equals(type)) return "";

        Object content = message.get("content");
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof List<?> list) {
            return list.stream()
                .map(block -> {
                    if (block instanceof String s) return s;
                    if (block instanceof Map<?, ?> m) {
                        Object text = m.get("text");
                        return text instanceof String t ? t : "";
                    }
                    return "";
                })
                .filter(t -> !t.isBlank())
                .collect(Collectors.joining(" "));
        }
        return "";
    }

    // ── Prompt building ───────────────────────────────────────────────────────

    /**
     * Build the session list string for the model prompt.
     * Translated from the sessionList mapping in agenticSessionSearch().
     */
    private String buildSessionList(List<SessionStorageService.SessionMetadata> logs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logs.size(); i++) {
            SessionStorageService.SessionMetadata log = logs.get(i);
            List<String> parts = new ArrayList<>();
            parts.add(i + ":");

            // Title (display title: name or first prompt)
            String displayTitle = log.getName() != null ? log.getName() : "(untitled)";
            parts.add(displayTitle);

            // Tag
            if (log.getTag() != null) parts.add("[tag: " + log.getTag() + "]");

            // Git branch
            if (log.getGitBranch() != null) parts.add("[branch: " + log.getGitBranch() + "]");

            // Summary
            if (log.getSummary() != null) parts.add("- Summary: " + log.getSummary());

            // First prompt (truncated)
            if (log.getFirstPrompt() != null && !"No prompt".equals(log.getFirstPrompt())) {
                String fp = log.getFirstPrompt();
                parts.add("- First message: " + fp.substring(0, Math.min(300, fp.length())));
            }

            sb.append(String.join(" ", parts)).append("\n");
        }
        return sb.toString();
    }

    // ── Response parsing helpers ──────────────────────────────────────────────

    private String extractTextContent(AnthropicClient.MessageResponse response) {
        if (response.getContent() == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> block : response.getContent()) {
            if ("text".equals(block.get("type"))) {
                Object text = block.get("text");
                if (text != null) sb.append(text);
            }
        }
        return sb.toString().trim();
    }
}
