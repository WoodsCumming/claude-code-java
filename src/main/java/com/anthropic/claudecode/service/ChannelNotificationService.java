package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.XmlUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Channel notification service.
 * Translated from src/services/mcp/channelNotification.ts
 *
 * Lets an MCP server push user messages into the conversation. A "channel"
 * (Discord, Slack, SMS, etc.) is just an MCP server that:
 *   - exposes tools for outbound messages (e.g. send_message) — standard MCP
 *   - sends notifications/claude/channel for inbound — handled here
 *
 * The notification handler wraps the content in a channel XML tag and enqueues it.
 */
@Slf4j
@Service
public class ChannelNotificationService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChannelNotificationService.class);


    public static final String CHANNEL_PERMISSION_METHOD = "notifications/claude/channel/permission";
    public static final String CHANNEL_PERMISSION_REQUEST_METHOD =
            "notifications/claude/channel/permission_request";

    /**
     * Channel tag name used to wrap inbound messages.
     * Mirrors CHANNEL_TAG constant from constants/xml.ts.
     */
    private static final String CHANNEL_TAG = "channel";

    /**
     * Meta keys become XML attribute NAMES — only accept keys that look like plain
     * identifiers to prevent attribute injection. This is stricter than the XML spec
     * but channel servers only send chat_id, user, thread_ts, message_id in practice.
     */
    private static final Pattern SAFE_META_KEY = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final List<Consumer<ChannelMessage>> messageListeners = new CopyOnWriteArrayList<>();

    private final ChannelAllowlistService channelAllowlistService;

    @Autowired
    public ChannelNotificationService(ChannelAllowlistService channelAllowlistService) {
        this.channelAllowlistService = channelAllowlistService;
    }

    // ============================================================================
    // Domain types
    // ============================================================================

    /** Inbound channel message after wrapping in the channel XML tag. */
    public record ChannelMessage(String serverName, String wrappedContent, Map<String, String> meta) {}

    /** Inbound permission notification from a channel server. */
    public record ChannelPermissionNotification(String requestId, String behavior) {}

    /** Outbound permission request parameters sent from CC to the channel server. */
    public record ChannelPermissionRequestParams(
            String requestId,
            String toolName,
            String description,
            /** JSON-stringified tool input, truncated to 200 chars. */
            String inputPreview) {}

    /** Gate outcome for gateChannelServer(). */
    public sealed interface ChannelGateResult {
        record Register() implements ChannelGateResult {}
        record Skip(String kind, String reason) implements ChannelGateResult {}
    }

    // ============================================================================
    // Core channel operations
    // ============================================================================

    /**
     * Wrap an inbound channel message in a &lt;channel&gt; XML tag.
     * Translated from wrapChannelMessage() in channelNotification.ts
     */
    public String wrapChannelMessage(String serverName, String content, Map<String, String> meta) {
        StringBuilder attrs = new StringBuilder();
        if (meta != null) {
            meta.entrySet().stream()
                    .filter(e -> SAFE_META_KEY.matcher(e.getKey()).matches())
                    .forEach(e -> attrs.append(" ")
                            .append(e.getKey())
                            .append("=\"")
                            .append(XmlUtils.escapeXmlAttr(e.getValue()))
                            .append("\""));
        }
        return "<" + CHANNEL_TAG + " source=\"" + XmlUtils.escapeXmlAttr(serverName) + "\""
                + attrs + ">\n" + content + "\n</" + CHANNEL_TAG + ">";
    }

    /**
     * Handle an inbound channel notification, wrapping its content and dispatching
     * to registered listeners.
     */
    public void handleChannelNotification(String serverName, String content, Map<String, String> meta) {
        String wrappedContent = wrapChannelMessage(serverName, content, meta);
        ChannelMessage message = new ChannelMessage(serverName, wrappedContent, meta);
        for (Consumer<ChannelMessage> listener : messageListeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                log.warn("Channel notification listener failed: {}", e.getMessage());
            }
        }
        log.debug("Received channel notification from {}", serverName);
    }

    /**
     * Subscribe to inbound channel messages.
     *
     * @return a Runnable that, when invoked, removes this subscription
     */
    public Runnable subscribe(Consumer<ChannelMessage> listener) {
        messageListeners.add(listener);
        return () -> messageListeners.remove(listener);
    }

    /**
     * Effective allowlist for the current session.
     * Team/enterprise orgs can set allowedChannelPlugins in managed settings —
     * when set, it REPLACES the GrowthBook ledger (admin owns the trust decision).
     * Unmanaged users always get the ledger.
     * Translated from getEffectiveChannelAllowlist() in channelNotification.ts
     *
     * @param subscriptionType the current user's subscription type
     * @param orgList          org-level allowlist from managed settings (may be null)
     */
    public EffectiveChannelAllowlist getEffectiveChannelAllowlist(
            String subscriptionType,
            List<ChannelAllowlistService.ChannelAllowlistEntry> orgList) {
        if (("team".equals(subscriptionType) || "enterprise".equals(subscriptionType))
                && orgList != null) {
            return new EffectiveChannelAllowlist(orgList, "org");
        }
        return new EffectiveChannelAllowlist(channelAllowlistService.getChannelAllowlist(), "ledger");
    }

    /** Result of getEffectiveChannelAllowlist. */
    public record EffectiveChannelAllowlist(
            List<ChannelAllowlistService.ChannelAllowlistEntry> entries,
            /** "org" when the org list is authoritative, "ledger" otherwise. */
            String source) {}
}
