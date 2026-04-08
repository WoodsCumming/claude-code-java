package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Teammate mailbox service — in-process message queue with subscriber notifications.
 * Translated from src/utils/mailbox.ts
 *
 * Provides a Mailbox abstraction: messages can be sent into the queue and
 * retrieved synchronously (poll) or asynchronously (receive). Subscribers
 * are notified on every mutation via a simple signal mechanism.
 */
@Slf4j
@Service
public class TeammateMailboxService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TeammateMailboxService.class);


    // =========================================================================
    // Message types
    // =========================================================================

    /**
     * Source of a mailbox message.
     * Translated from MessageSource union type in mailbox.ts
     */
    public enum MessageSource {
        USER, TEAMMATE, SYSTEM, TICK, TASK;

        public static MessageSource fromString(String s) {
            return switch (s == null ? "" : s.toLowerCase()) {
                case "user"     -> USER;
                case "teammate" -> TEAMMATE;
                case "system"   -> SYSTEM;
                case "tick"     -> TICK;
                case "task"     -> TASK;
                default -> throw new IllegalArgumentException("Unknown message source: " + s);
            };
        }
    }

    /**
     * A mailbox message.
     * Translated from Message type in mailbox.ts
     */
    @Data
    @lombok.NoArgsConstructor(force = true)
    @lombok.AllArgsConstructor
    public static class Message {
        private final String id;
        private final MessageSource source;
        private final String content;
        /** Optional: the agent name that sent the message. */
        private final String from;
        /** Optional: display colour hint. */
        private final String color;
        private final String timestamp;


        public String getId() { return id; }
        public MessageSource getSource() { return source; }
        public String getContent() { return content; }
        public String getFrom() { return from; }
        public String getColor() { return color; }
        public String getTimestamp() { return timestamp; }
    }

    // =========================================================================
    // Waiter (internal)
    // =========================================================================

    private record Waiter(Predicate<Message> fn, CompletableFuture<Message> future) {}

    // =========================================================================
    // Mailbox
    // =========================================================================

    /**
     * An in-process mailbox — a priority-less FIFO queue with async receive support.
     * Translated from Mailbox class in mailbox.ts
     */
    public static class Mailbox {

        private final List<Message> queue = new ArrayList<>();
        private final List<Waiter> waiters = new ArrayList<>();
        private final List<Runnable> subscribers = new CopyOnWriteArrayList<>();
        private final AtomicInteger revision = new AtomicInteger(0);

        /**
         * Current number of messages waiting in the queue (not yet consumed).
         */
        public int length() {
            synchronized (this) {
                return queue.size();
            }
        }

        /**
         * Monotonically increasing revision counter — incremented on every send().
         */
        public int revision() {
            return revision.get();
        }

        /**
         * Subscribe to queue-change notifications.
         * Returns a Runnable that unsubscribes when called.
         * Translated from subscribe (= changed.subscribe) in mailbox.ts
         */
        public Runnable subscribe(Runnable listener) {
            subscribers.add(listener);
            return () -> subscribers.remove(listener);
        }

        /**
         * Send a message. If a waiter whose predicate matches is found it is
         * resolved immediately; otherwise the message is enqueued.
         * Translated from send() in mailbox.ts
         */
        public void send(Message msg) {
            revision.incrementAndGet();
            synchronized (this) {
                // Try to satisfy a waiting receiver
                for (int i = 0; i < waiters.size(); i++) {
                    Waiter w = waiters.get(i);
                    if (w.fn().test(msg)) {
                        waiters.remove(i);
                        w.future().complete(msg);
                        notifyWaiters();
                        return;
                    }
                }
                // No waiter matched — enqueue
                queue.add(msg);
            }
            notifyWaiters();
        }

        /**
         * Synchronously retrieve and remove the first message matching {@code fn},
         * or return empty if no match exists.
         * Translated from poll() in mailbox.ts
         */
        public Optional<Message> poll(Predicate<Message> fn) {
            synchronized (this) {
                for (int i = 0; i < queue.size(); i++) {
                    if (fn.test(queue.get(i))) {
                        return Optional.of(queue.remove(i));
                    }
                }
                return Optional.empty();
            }
        }

        /** Poll with a pass-all predicate. */
        public Optional<Message> poll() {
            return poll(m -> true);
        }

        /**
         * Asynchronously receive the first message matching {@code fn}.
         * If a matching message is already in the queue it is returned immediately;
         * otherwise the caller is suspended until a matching message arrives.
         * Translated from receive() in mailbox.ts
         */
        public CompletableFuture<Message> receive(Predicate<Message> fn) {
            synchronized (this) {
                for (int i = 0; i < queue.size(); i++) {
                    if (fn.test(queue.get(i))) {
                        Message msg = queue.remove(i);
                        notifyWaiters();
                        return CompletableFuture.completedFuture(msg);
                    }
                }
                // No matching message yet — register a waiter
                CompletableFuture<Message> future = new CompletableFuture<>();
                waiters.add(new Waiter(fn, future));
                return future;
            }
        }

        /** Receive with a pass-all predicate. */
        public CompletableFuture<Message> receive() {
            return receive(m -> true);
        }

        // -----------------------------------------------------------------------
        // Helpers
        // -----------------------------------------------------------------------

        private void notifyWaiters() {
            subscribers.forEach(Runnable::run);
        }
    }

    // =========================================================================
    // Factory
    // =========================================================================

    /**
     * Create a new, empty Mailbox.
     */
    public Mailbox createMailbox() {
        return new Mailbox();
    }

    // =========================================================================
    // MailboxEntry - simplified message entry for inbound teammate messages
    // =========================================================================

    /**
     * A simple entry to write to a teammate's mailbox.
     */
    public record MailboxEntry(String text, String from, String color, String timestamp) {}

    /**
     * Write a message to a teammate's mailbox by agent name.
     * The teamName is used to route to the correct backend.
     */
    public void writeToMailbox(String agentName, MailboxEntry entry, String teamName) {
        log.debug("[TeammateMailboxService] writeToMailbox for {} in team {}: {}...",
            agentName, teamName, entry.text().substring(0, Math.min(50, entry.text().length())));
        // In a full implementation, this would route the message to the agent's mailbox
        // via an in-memory map keyed by (teamName, agentName).
    }

    /**
     * Extended mailbox message with additional metadata.
     * Used by TeammateInitService when sending structured notifications.
     */
    public static class MailboxMessage {
        private String id;
        private String from;
        private String content;
        private String color;
        private String type;
        private Object data;
        private String timestamp;
        private java.util.Map<String, Object> metadata;

        public MailboxMessage() {}

        public MailboxMessage(String id, String from, String content, String color,
                              String type, Object data) {
            this.id = id; this.from = from; this.content = content;
            this.color = color; this.type = type; this.data = data;
        }

        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getFrom() { return from; }
        public void setFrom(String v) { from = v; }
        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
        public String getColor() { return color; }
        public void setColor(String v) { color = v; }
        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public Object getData() { return data; }
        public void setData(Object v) { data = v; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String v) { timestamp = v; }
        public java.util.Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(java.util.Map<String, Object> v) { metadata = v; }

        public static MailboxMessageBuilder builder() { return new MailboxMessageBuilder(); }

        public static class MailboxMessageBuilder {
            private final MailboxMessage m = new MailboxMessage();
            public MailboxMessageBuilder id(String v) { m.id = v; return this; }
            public MailboxMessageBuilder from(String v) { m.from = v; return this; }
            public MailboxMessageBuilder content(String v) { m.content = v; return this; }
            public MailboxMessageBuilder color(String v) { m.color = v; return this; }
            public MailboxMessageBuilder type(String v) { m.type = v; return this; }
            public MailboxMessageBuilder data(Object v) { m.data = v; return this; }
            public MailboxMessageBuilder timestamp(String v) { m.timestamp = v; return this; }
            public MailboxMessageBuilder metadata(java.util.Map<String, Object> v) { m.metadata = v; return this; }
            public MailboxMessage build() { return m; }
        }
    }

    /** Check if a message text represents a tool-permission response. */
    public boolean isPermissionResponse(String messageText) {
        if (messageText == null) return false;
        String lower = messageText.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("permission") && (lower.contains("allow") || lower.contains("deny") || lower.contains("yes") || lower.contains("no"));
    }

    /** Check if a message text represents a sandbox/network-permission response. */
    public boolean isSandboxPermissionResponse(String messageText) {
        if (messageText == null) return false;
        String lower = messageText.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("sandbox") || lower.contains("network");
    }

    /**
     * Send a MailboxMessage to a recipient within a team.
     */
    public java.util.concurrent.CompletableFuture<Void> sendToMailbox(String teamName, String recipient, MailboxMessage msg) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            log.debug("[TeammateMailboxService] sendToMailbox to {}/{}: type={}", teamName, recipient, msg.getType());
            MailboxEntry entry = new MailboxEntry(msg.getContent(), msg.getFrom(), msg.getColor(),
                    java.time.Instant.now().toString());
            writeToMailbox(recipient, entry, teamName);
        });
    }
}
