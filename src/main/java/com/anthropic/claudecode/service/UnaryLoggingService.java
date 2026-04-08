package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Unary logging service for tracking single-turn completions.
 * Translated from src/utils/unaryLogging.ts
 *
 * Logs accept/reject events for unary (single-turn) completions.
 */
@Slf4j
@Service
public class UnaryLoggingService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UnaryLoggingService.class);


    private final AnalyticsService analyticsService;

    @Autowired
    public UnaryLoggingService(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    public enum CompletionType {
        STR_REPLACE_SINGLE("str_replace_single"),
        STR_REPLACE_MULTI("str_replace_multi"),
        WRITE_FILE_SINGLE("write_file_single"),
        TOOL_USE_SINGLE("tool_use_single"),
        UNKNOWN("unknown");

        private final String value;
        CompletionType(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    /**
     * Log a unary event.
     * Translated from logUnaryEvent() in unaryLogging.ts
     */
    public CompletableFuture<Void> logUnaryEvent(
            CompletionType completionType,
            String event,
            String languageName,
            String messageId,
            String platform) {

        return CompletableFuture.runAsync(() -> {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("event", event);
            props.put("completion_type", completionType.getValue());
            props.put("language_name", languageName);
            props.put("message_id", messageId);
            props.put("platform", platform);

            analyticsService.logEvent("tengu_unary_event", props);
        });
    }

    /**
     * Overload without platform parameter.
     */
    public CompletableFuture<Void> logUnaryEvent(
            CompletionType completionType,
            String event,
            String languageName,
            String messageId) {
        return logUnaryEvent(completionType, event, languageName, messageId, null);
    }

    /**
     * Overload accepting String completionType.
     */
    public CompletableFuture<Void> logUnaryEvent(
            String completionType,
            String event,
            String languageName,
            String messageId) {
        CompletionType ct;
        try {
            ct = CompletionType.valueOf(completionType != null ? completionType.toUpperCase() : "UNKNOWN");
        } catch (Exception e) {
            ct = CompletionType.UNKNOWN;
        }
        return logUnaryEvent(ct, event, languageName, messageId, null);
    }
}
