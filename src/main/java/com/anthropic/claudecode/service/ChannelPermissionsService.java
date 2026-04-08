package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Permission prompts over channels (Telegram, iMessage, Discord).
 * Translated from src/services/mcp/channelPermissions.ts
 *
 * Mirrors bridge permission callbacks — when CC hits a permission dialog,
 * it ALSO sends the prompt via active channels and races the reply against
 * local UI / bridge / hooks / classifier. First resolver wins via resolve().
 *
 * Inbound is a structured event: the server parses the user's "yes tbxkq" reply
 * and emits notifications/claude/channel/permission with {request_id, behavior}.
 * CC never sees the reply as text — approval requires the server to deliberately
 * emit that specific event. Servers opt in by declaring
 * capabilities.experimental['claude/channel/permission'].
 */
@Slf4j
@Service
public class ChannelPermissionsService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChannelPermissionsService.class);


    /**
     * Reply format spec for channel servers to implement.
     * 5 lowercase letters, no 'l' (looks like 1/I). Case-insensitive (phone autocorrect).
     * No bare yes/no (conversational). No prefix/suffix chatter.
     *
     * CC generates the ID and sends the prompt. The SERVER parses the user's reply and
     * emits notifications/claude/channel/permission with {request_id, behavior} — CC
     * doesn't regex-match text anymore.
     */
    public static final Pattern PERMISSION_REPLY_RE =
            Pattern.compile("^\\s*(y|yes|n|no)\\s+([a-km-z]{5})\\s*$", Pattern.CASE_INSENSITIVE);

    // 25-letter alphabet: a-z minus 'l' (looks like 1/I). 25^5 ≈ 9.8M space.
    private static final String ID_ALPHABET = "abcdefghijkmnopqrstuvwxyz";

    // Substring blocklist — 5 random letters can spell things you don't want in a
    // text message. Non-exhaustive, covers the send-to-your-boss-by-accident tier.
    private static final String[] ID_AVOID_SUBSTRINGS = {
        "fuck", "shit", "cunt", "cock", "dick", "twat", "piss", "crap",
        "bitch", "whore", "ass", "tit", "cum", "fag", "dyke", "nig",
        "kike", "rape", "nazi", "damn", "poo", "pee", "wank", "anus"
    };

    private final GrowthBookService growthBookService;

    @Autowired
    public ChannelPermissionsService(GrowthBookService growthBookService) {
        this.growthBookService = growthBookService;
    }

    // ============================================================================
    // Domain types
    // ============================================================================

    /** A resolved permission response from a channel server. */
    public record ChannelPermissionResponse(
            String behavior,
            /** Which channel server the reply came from (e.g., "plugin:telegram:tg"). */
            String fromServer) {}

    /** Callbacks object for channel permission relay. Created once per session. */
    public interface ChannelPermissionCallbacks {
        /**
         * Register a resolver for a request ID.
         *
         * @return a Runnable that, when called, removes this subscription
         */
        Runnable onResponse(String requestId, Consumer<ChannelPermissionResponse> handler);

        /**
         * Resolve a pending request from a structured channel event
         * (notifications/claude/channel/permission). Returns true if the ID was pending.
         */
        boolean resolve(String requestId, String behavior, String fromServer);
    }

    // ============================================================================
    // Feature gate
    // ============================================================================

    /**
     * GrowthBook runtime gate — separate from the channels gate (tengu_harbor) so
     * channels can ship without permission-relay riding along. Default false.
     * Checked once at session start — mid-session flag changes don't apply until restart.
     */
    public boolean isChannelPermissionRelayEnabled() {
        return growthBookService.getFeatureValueCached("tengu_harbor_permissions", false);
    }

    // ============================================================================
    // ID generation
    // ============================================================================

    /**
     * FNV-1a hash → 5 base-25 letters. Not crypto, just a stable short letters-only ID.
     */
    private static String hashToId(String input) {
        int h = 0x811c9dc5;
        for (int i = 0; i < input.length(); i++) {
            h ^= input.charAt(i);
            h = (int) (((long) h * 0x01000193L) & 0xFFFFFFFFL);
        }
        h = h & 0x7FFFFFFF; // keep positive
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(ID_ALPHABET.charAt(h % 25));
            h = h / 25;
        }
        return sb.toString();
    }

    /**
     * Short ID from a toolUseID. 5 letters from a 25-char alphabet (a-z minus 'l').
     * 25^5 ≈ 9.8M space. Re-hashes with a salt suffix if the result contains a
     * blocklisted substring — 5 random letters can spell things you don't want in
     * a text message to your phone.
     * Translated from shortRequestId() in channelPermissions.ts
     */
    public static String shortRequestId(String toolUseId) {
        String candidate = hashToId(toolUseId);
        for (int salt = 0; salt < 10; salt++) {
            final String c = candidate;
            boolean blocked = false;
            for (String bad : ID_AVOID_SUBSTRINGS) {
                if (c.contains(bad)) { blocked = true; break; }
            }
            if (!blocked) return candidate;
            candidate = hashToId(toolUseId + ":" + salt);
        }
        return candidate;
    }

    /**
     * Truncate tool input to a phone-sized JSON preview. 200 chars is roughly 3 lines
     * on a narrow phone screen. Full input is in the local terminal dialog; the channel
     * gets a summary so Write(5KB-file) doesn't flood your texts.
     * Translated from truncateForPreview() in channelPermissions.ts
     */
    public static String truncateForPreview(Object input) {
        try {
            String s = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                    .writeValueAsString(input);
            return s.length() > 200 ? s.substring(0, 200) + "…" : s;
        } catch (Exception e) {
            return "(unserializable)";
        }
    }

    // ============================================================================
    // Factory
    // ============================================================================

    /**
     * Factory for the callbacks object. The pending Map is closed over — NOT
     * module-level, NOT in AppState (functions-in-state causes issues with
     * equality/serialization). Constructed once per session inside a component,
     * stable reference stored in AppState.
     *
     * resolve() is called from the dedicated notification handler
     * (notifications/claude/channel/permission) with the structured payload.
     * The server already parsed "yes tbxkq" → {request_id, behavior}; we just
     * match against the pending map. No regex on CC's side.
     * Translated from createChannelPermissionCallbacks() in channelPermissions.ts
     */
    public static ChannelPermissionCallbacks createChannelPermissionCallbacks() {
        Map<String, Consumer<ChannelPermissionResponse>> pending = new HashMap<>();
        return new ChannelPermissionCallbacks() {
            @Override
            public Runnable onResponse(String requestId,
                    Consumer<ChannelPermissionResponse> handler) {
                // Normalize to lowercase for symmetry with resolve().
                String key = requestId.toLowerCase();
                pending.put(key, handler);
                return () -> pending.remove(key);
            }

            @Override
            public boolean resolve(String requestId, String behavior, String fromServer) {
                String key = requestId.toLowerCase();
                Consumer<ChannelPermissionResponse> resolver = pending.get(key);
                if (resolver == null) return false;
                // Delete BEFORE calling — if resolver throws or re-enters, the
                // entry is already gone. Also handles duplicate events (second
                // emission falls through — server bug or network dup, ignore).
                pending.remove(key);
                resolver.accept(new ChannelPermissionResponse(behavior, fromServer));
                return true;
            }
        };
    }
}
