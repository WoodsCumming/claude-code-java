package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.concurrent.CompletableFuture;

/**
 * Notification service — sends OS-level or terminal notifications.
 * Translated from src/services/notifier.ts
 *
 * Supports multiple channels: iTerm2, Kitty, Ghostty, terminal bell,
 * and auto-detection based on the current terminal emulator.
 */
@Slf4j
@Service
public class NotificationService {



    private static final String DEFAULT_TITLE = "Claude Code";

    // -------------------------------------------------------------------------
    // Domain types
    // -------------------------------------------------------------------------

    /**
     * Options for sending a notification.
     * Translated from NotificationOptions in notifier.ts
     */
    public record NotificationOptions(
        String message,
        String title,           // nullable — falls back to DEFAULT_TITLE
        String notificationType
    ) {
        public NotificationOptions(String message, String notificationType) {
            this(message, null, notificationType);
        }

        public String effectiveTitle() {
            return title != null && !title.isBlank() ? title : DEFAULT_TITLE;
        }
    }

    /**
     * Notification channels.
     * Translated from the channel switch in sendToChannel() in notifier.ts
     */
    public enum NotificationChannel {
        AUTO,
        ITERM2,
        ITERM2_WITH_BELL,
        KITTY,
        GHOSTTY,
        TERMINAL_BELL,
        NOTIFICATIONS_DISABLED,
        NONE
    }

    // -------------------------------------------------------------------------
    // Collaborators
    // -------------------------------------------------------------------------

    private final GlobalConfigService globalConfigService;

    @Autowired
    public NotificationService(GlobalConfigService globalConfigService) {
        this.globalConfigService = globalConfigService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Send a notification using the configured channel.
     * Translated from sendNotification() in notifier.ts
     *
     * @param notif notification to send
     * @return CompletableFuture that resolves to the channel method used
     */
    public CompletableFuture<String> sendNotification(NotificationOptions notif) {
        return CompletableFuture.supplyAsync(() -> {
            // Execute notification hooks first (fire-and-forget in TS; best-effort here)
            executeNotificationHooks(notif);

            String channel = globalConfigService.getPreferredNotifChannel();
            String methodUsed = sendToChannel(channel, notif);

            log.debug("[notification] method_used={} configured_channel={}", methodUsed, channel);
            return methodUsed;
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Dispatch to the correct channel implementation.
     * Translated from sendToChannel() in notifier.ts
     */
    private String sendToChannel(String channel, NotificationOptions opts) {
        if (channel == null) channel = "auto";
        try {
            return switch (channel) {
                case "auto" -> sendAuto(opts);
                case "iterm2" -> {
                    notifyITerm2(opts);
                    yield "iterm2";
                }
                case "iterm2_with_bell" -> {
                    notifyITerm2(opts);
                    notifyBell();
                    yield "iterm2_with_bell";
                }
                case "kitty" -> {
                    notifyKitty(opts);
                    yield "kitty";
                }
                case "ghostty" -> {
                    notifyGhostty(opts);
                    yield "ghostty";
                }
                case "terminal_bell" -> {
                    notifyBell();
                    yield "terminal_bell";
                }
                case "notifications_disabled" -> "disabled";
                default -> "none";
            };
        } catch (Exception e) {
            log.debug("[notification] sendToChannel failed: {}", e.getMessage());
            return "error";
        }
    }

    /**
     * Auto-select channel based on the current terminal emulator.
     * Translated from sendAuto() in notifier.ts
     */
    private String sendAuto(NotificationOptions opts) {
        String terminal = detectTerminal();
        return switch (terminal) {
            case "Apple_Terminal" -> {
                if (isAppleTerminalBellDisabled()) {
                    notifyBell();
                    yield "terminal_bell";
                }
                yield "no_method_available";
            }
            case "iTerm.app" -> {
                notifyITerm2(opts);
                yield "iterm2";
            }
            case "kitty" -> {
                notifyKitty(opts);
                yield "kitty";
            }
            case "ghostty" -> {
                notifyGhostty(opts);
                yield "ghostty";
            }
            default -> "no_method_available";
        };
    }

    // -------------------------------------------------------------------------
    // Terminal-specific senders
    // -------------------------------------------------------------------------

    /**
     * Send an iTerm2 notification via OSC 9 escape sequence.
     */
    private void notifyITerm2(NotificationOptions opts) {
        // iTerm2 uses ESC ] 9 ; <message> BEL
        String msg = opts.message().replace("\n", " ");
        System.out.print("\u001b]9;" + msg + "\u0007");
        System.out.flush();
        log.debug("[notification] iTerm2 notification sent");
    }

    /**
     * Send a Kitty terminal notification via OSC 99 escape sequence.
     */
    private void notifyKitty(NotificationOptions opts) {
        int id = generateKittyId();
        String title = opts.effectiveTitle();
        String body = opts.message();
        // Kitty: ESC ] 99 ; i=<id>:d=0:p=title ; <title> BEL  +  body part
        System.out.printf("\u001b]99;i=%d:d=0:p=title;%s\u0007\u001b]99;i=%d:d=1:p=body;%s\u0007",
                          id, title, id, body);
        System.out.flush();
        log.debug("[notification] Kitty notification sent (id={})", id);
    }

    /**
     * Send a Ghostty notification via OSC 777.
     */
    private void notifyGhostty(NotificationOptions opts) {
        String title = opts.effectiveTitle();
        String body = opts.message();
        System.out.printf("\u001b]777;notify;%s;%s\u0007", title, body);
        System.out.flush();
        log.debug("[notification] Ghostty notification sent");
    }

    /**
     * Ring the terminal bell (BEL character).
     */
    public void notifyBell() {
        System.out.print("\007");
        System.out.flush();
    }

    // -------------------------------------------------------------------------
    // Utility helpers
    // -------------------------------------------------------------------------

    private int generateKittyId() {
        return (int) (Math.random() * 10_000);
    }

    /**
     * Detect the current terminal emulator from env vars.
     * Mirrors env.terminal in notifier.ts
     */
    private String detectTerminal() {
        String termProgram = System.getenv("TERM_PROGRAM");
        if (termProgram == null) termProgram = "";
        return switch (termProgram) {
            case "Apple_Terminal" -> "Apple_Terminal";
            case "iTerm.app" -> "iTerm.app";
            case "kitty" -> "kitty";
            case "ghostty" -> "ghostty";
            default -> {
                String term = System.getenv("TERM");
                yield term != null ? term : "unknown";
            }
        };
    }

    /**
     * Check whether the current Apple Terminal profile has Bell disabled.
     * Translated from isAppleTerminalBellDisabled() in notifier.ts
     *
     * In the TypeScript implementation this calls osascript + defaults export +
     * plist parsing. Here we use a simpler heuristic: assume bell is enabled
     * unless the user explicitly sets CLAUDE_BELL_DISABLED=1.
     */
    private boolean isAppleTerminalBellDisabled() {
        return "1".equals(System.getenv("CLAUDE_BELL_DISABLED"));
    }

    /**
     * Execute any registered notification hooks (fire-and-forget).
     * Mirrors executeNotificationHooks() from utils/hooks.ts
     */
    private void executeNotificationHooks(NotificationOptions notif) {
        // In a full implementation this would invoke hook scripts.
        log.debug("[notification] executing notification hooks for type={}", notif.notificationType());
    }
}
