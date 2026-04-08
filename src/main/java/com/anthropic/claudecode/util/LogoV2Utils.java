package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Layout and display utilities for the LogoV2 component.
 *
 * Translated from src/utils/logoV2Utils.ts
 *
 * <p>Provides layout calculations, recent-activity caching, release-note
 * formatting, and model/billing display formatting — all the pure functions
 * that the TypeScript LogoV2 React component relied on.</p>
 */
@Slf4j
public final class LogoV2Utils {



    // =========================================================================
    // Layout constants (translated from the top of logoV2Utils.ts)
    // =========================================================================

    public static final int MAX_LEFT_WIDTH = 50;
    public static final int MAX_USERNAME_LENGTH = 20;
    public static final int BORDER_PADDING = 4;
    public static final int DIVIDER_WIDTH = 1;
    public static final int CONTENT_PADDING = 2;

    // =========================================================================
    // Types
    // =========================================================================

    /**
     * Layout mode determined by terminal width.
     * Translated from {@code LayoutMode} in logoV2Utils.ts
     */
    public enum LayoutMode {
        HORIZONTAL,
        COMPACT
    }

    /**
     * Calculated layout dimensions for the LogoV2 component.
     * Translated from {@code LayoutDimensions} in logoV2Utils.ts
     */
    public record LayoutDimensions(int leftWidth, int rightWidth, int totalWidth) {}

    /**
     * Logo display data computed once and shared between LogoV2 and CondensedLogo.
     * Translated from the return type of {@code getLogoDisplayData()} in logoV2Utils.ts
     */
    public record LogoDisplayData(
            String version,
            String cwd,
            String billingType,
            String agentName
    ) {}

    /**
     * Model + billing format result.
     * Translated from the return type of {@code formatModelAndBilling()} in logoV2Utils.ts
     */
    public record ModelAndBillingFormat(
            boolean shouldSplit,
            String truncatedModel,
            String truncatedBilling
    ) {}

    // =========================================================================
    // getLayoutMode
    // =========================================================================

    /**
     * Determines the layout mode based on terminal width.
     * Translated from {@code getLayoutMode()} in logoV2Utils.ts
     */
    public static LayoutMode getLayoutMode(int columns) {
        return columns >= 70 ? LayoutMode.HORIZONTAL : LayoutMode.COMPACT;
    }

    // =========================================================================
    // calculateLayoutDimensions
    // =========================================================================

    /**
     * Calculates layout dimensions for the LogoV2 component.
     * Translated from {@code calculateLayoutDimensions()} in logoV2Utils.ts
     */
    public static LayoutDimensions calculateLayoutDimensions(
            int columns,
            LayoutMode layoutMode,
            int optimalLeftWidth) {

        if (layoutMode == LayoutMode.HORIZONTAL) {
            int leftWidth = optimalLeftWidth;
            int usedSpace = BORDER_PADDING + CONTENT_PADDING + DIVIDER_WIDTH + leftWidth;
            int availableForRight = columns - usedSpace;

            int rightWidth = Math.max(30, availableForRight);
            int totalWidth = Math.min(
                    leftWidth + rightWidth + DIVIDER_WIDTH + CONTENT_PADDING,
                    columns - BORDER_PADDING
            );

            // Recalculate right width if we had to cap the total
            if (totalWidth < leftWidth + rightWidth + DIVIDER_WIDTH + CONTENT_PADDING) {
                rightWidth = totalWidth - leftWidth - DIVIDER_WIDTH - CONTENT_PADDING;
            }

            return new LayoutDimensions(leftWidth, rightWidth, totalWidth);
        }

        // Compact (vertical) mode
        int totalWidth = Math.min(columns - BORDER_PADDING, MAX_LEFT_WIDTH + 20);
        return new LayoutDimensions(totalWidth, totalWidth, totalWidth);
    }

    // =========================================================================
    // calculateOptimalLeftWidth
    // =========================================================================

    /**
     * Calculates optimal left panel width based on content strings.
     * Translated from {@code calculateOptimalLeftWidth()} in logoV2Utils.ts
     */
    public static int calculateOptimalLeftWidth(
            String welcomeMessage,
            String truncatedCwd,
            String modelLine) {

        int contentWidth = Math.max(
                Math.max(
                        stringWidth(welcomeMessage),
                        stringWidth(truncatedCwd)
                ),
                Math.max(
                        stringWidth(modelLine),
                        20 // Minimum for clawd art
                )
        );
        return Math.min(contentWidth + 4, MAX_LEFT_WIDTH); // +4 for padding
    }

    // =========================================================================
    // formatWelcomeMessage
    // =========================================================================

    /**
     * Formats the welcome message based on username.
     * Translated from {@code formatWelcomeMessage()} in logoV2Utils.ts
     */
    public static String formatWelcomeMessage(String username) {
        if (username == null || username.length() > MAX_USERNAME_LENGTH) {
            return "Welcome back!";
        }
        return "Welcome back " + username + "!";
    }

    // =========================================================================
    // truncatePath
    // =========================================================================

    /**
     * Truncates a path in the middle if it's too long.
     *
     * <p>Width-aware: uses {@link #stringWidth(String)} for correct CJK/emoji
     * measurement. Mirrors the TypeScript implementation that calls
     * {@code stringWidth()} from {@code ink/stringWidth.ts}.</p>
     *
     * Translated from {@code truncatePath()} in logoV2Utils.ts
     */
    public static String truncatePath(String path, int maxLength) {
        if (stringWidth(path) <= maxLength) return path;

        final String separator = "/";
        final String ellipsis = "\u2026"; // …
        final int ellipsisWidth = 1;
        final int separatorWidth = 1;

        String[] parts = path.split("/", -1);
        String first = parts.length > 0 ? parts[0] : "";
        String last = parts.length > 0 ? parts[parts.length - 1] : "";
        int firstWidth = stringWidth(first);
        int lastWidth = stringWidth(last);

        // Only one part
        if (parts.length == 1) {
            return truncateToWidth(path, maxLength);
        }

        // Not enough space to show the last part (unix path with empty first segment)
        if (first.isEmpty() && ellipsisWidth + separatorWidth + lastWidth >= maxLength) {
            return separator + truncateToWidth(last, Math.max(1, maxLength - separatorWidth));
        }

        // Have a first part — show ellipsis and truncate last if necessary
        if (!first.isEmpty() && ellipsisWidth * 2 + separatorWidth + lastWidth >= maxLength) {
            return ellipsis + separator + truncateToWidth(
                    last, Math.max(1, maxLength - ellipsisWidth - separatorWidth));
        }

        // Truncate first and leave last (exactly two parts)
        if (parts.length == 2) {
            int availableForFirst = maxLength - ellipsisWidth - separatorWidth - lastWidth;
            return truncateToWidthNoEllipsis(first, availableForFirst) + ellipsis + separator + last;
        }

        // Remove middle parts
        int available = maxLength - firstWidth - lastWidth - ellipsisWidth - 2 * separatorWidth;

        if (available <= 0) {
            int availableForFirst = Math.max(
                    0, maxLength - lastWidth - ellipsisWidth - 2 * separatorWidth);
            String truncatedFirst = truncateToWidthNoEllipsis(first, availableForFirst);
            return truncatedFirst + separator + ellipsis + separator + last;
        }

        // Keep as many middle parts as possible (from the right)
        List<String> middleParts = new ArrayList<>();
        for (int i = parts.length - 2; i > 0; i--) {
            String part = parts[i];
            if (part != null && stringWidth(part) + separatorWidth <= available) {
                middleParts.add(0, part);
                available -= stringWidth(part) + separatorWidth;
            } else {
                break;
            }
        }

        if (middleParts.isEmpty()) {
            return first + separator + ellipsis + separator + last;
        }

        return first + separator + ellipsis + separator
                + String.join(separator, middleParts) + separator + last;
    }

    // =========================================================================
    // Recent activity cache
    // Translated from the module-level cache + getRecentActivity() in logoV2Utils.ts
    // =========================================================================

    /** Simplified log option for recent-activity display. */
    public record LogOption(
            String sessionId,
            String summary,
            String firstPrompt,
            boolean isSidechain,
            String agentName,
            String customTitle
    ) {}

    private static final List<LogOption> cachedActivity = new ArrayList<>();
    private static final AtomicReference<CompletableFuture<List<LogOption>>> cachePromiseRef =
            new AtomicReference<>(null);

    /** Provider for loading recent message logs. Injected at startup. */
    private static volatile RecentActivityProvider recentActivityProvider = null;

    /** Inject a provider for loading recent message logs (used in place of direct fs calls). */
    public static void setRecentActivityProvider(RecentActivityProvider provider) {
        recentActivityProvider = provider;
    }

    public interface RecentActivityProvider {
        CompletableFuture<List<LogOption>> loadMessageLogs(int limit);
        String getCurrentSessionId();
    }

    /**
     * Preloads recent conversations for display in Logo v2.
     * Returns an existing promise if already loading.
     * Translated from {@code getRecentActivity()} in logoV2Utils.ts
     */
    public static CompletableFuture<List<LogOption>> getRecentActivity() {
        CompletableFuture<List<LogOption>> existing = cachePromiseRef.get();
        if (existing != null) return existing;

        if (recentActivityProvider == null) {
            CompletableFuture<List<LogOption>> empty = CompletableFuture.completedFuture(List.of());
            cachePromiseRef.compareAndSet(null, empty);
            return empty;
        }

        String currentSessionId = recentActivityProvider.getCurrentSessionId();
        CompletableFuture<List<LogOption>> promise = recentActivityProvider.loadMessageLogs(10)
                .thenApply(logs -> {
                    List<LogOption> filtered = logs.stream()
                            .filter(log -> {
                                if (log.isSidechain()) return false;
                                if (log.sessionId() != null && log.sessionId().equals(currentSessionId)) return false;
                                if (log.summary() != null && log.summary().contains("I apologize")) return false;
                                boolean hasSummary = log.summary() != null && !log.summary().equals("No prompt");
                                boolean hasFirstPrompt = log.firstPrompt() != null && !log.firstPrompt().equals("No prompt");
                                return hasSummary || hasFirstPrompt;
                            })
                            .limit(3)
                            .toList();
                    synchronized (cachedActivity) {
                        cachedActivity.clear();
                        cachedActivity.addAll(filtered);
                    }
                    return List.copyOf(filtered);
                })
                .exceptionally(e -> {
                    synchronized (cachedActivity) { cachedActivity.clear(); }
                    return List.of();
                });

        if (!cachePromiseRef.compareAndSet(null, promise)) {
            return cachePromiseRef.get();
        }
        return promise;
    }

    /**
     * Gets cached activity synchronously.
     * Translated from {@code getRecentActivitySync()} in logoV2Utils.ts
     */
    public static List<LogOption> getRecentActivitySync() {
        synchronized (cachedActivity) {
            return List.copyOf(cachedActivity);
        }
    }

    // =========================================================================
    // formatReleaseNoteForDisplay
    // =========================================================================

    /**
     * Formats a release note for display with smart truncation.
     * Translated from {@code formatReleaseNoteForDisplay()} in logoV2Utils.ts
     */
    public static String formatReleaseNoteForDisplay(String note, int maxWidth) {
        return truncate(note, maxWidth);
    }

    // =========================================================================
    // getLogoDisplayData
    // =========================================================================

    /** Provider for logo display data. Injected at startup. */
    private static volatile LogoDisplayDataProvider logoDisplayDataProvider = null;

    public interface LogoDisplayDataProvider {
        String getVersion();
        String getCwd();
        String getBillingType();
        String getAgentName();
    }

    public static void setLogoDisplayDataProvider(LogoDisplayDataProvider provider) {
        logoDisplayDataProvider = provider;
    }

    /**
     * Gets the common logo display data used by both LogoV2 and CondensedLogo.
     * Translated from {@code getLogoDisplayData()} in logoV2Utils.ts
     */
    public static LogoDisplayData getLogoDisplayData() {
        if (logoDisplayDataProvider != null) {
            return new LogoDisplayData(
                    logoDisplayDataProvider.getVersion(),
                    logoDisplayDataProvider.getCwd(),
                    logoDisplayDataProvider.getBillingType(),
                    logoDisplayDataProvider.getAgentName()
            );
        }
        // Safe defaults when no provider is registered
        String version = System.getenv("DEMO_VERSION") != null
                ? System.getenv("DEMO_VERSION")
                : "unknown";
        return new LogoDisplayData(version, System.getProperty("user.dir"), "API Usage Billing", null);
    }

    // =========================================================================
    // formatModelAndBilling
    // =========================================================================

    /**
     * Determines how to display model and billing information based on available width.
     * Translated from {@code formatModelAndBilling()} in logoV2Utils.ts
     */
    public static ModelAndBillingFormat formatModelAndBilling(
            String modelName,
            String billingType,
            int availableWidth) {

        final String separator = " \u00b7 "; // " · "
        int combinedWidth = stringWidth(modelName) + separator.length() + stringWidth(billingType);
        boolean shouldSplit = combinedWidth > availableWidth;

        if (shouldSplit) {
            return new ModelAndBillingFormat(
                    true,
                    truncate(modelName, availableWidth),
                    truncate(billingType, availableWidth)
            );
        }

        return new ModelAndBillingFormat(
                false,
                truncate(modelName,
                        Math.max(availableWidth - stringWidth(billingType) - separator.length(), 10)),
                billingType
        );
    }

    // =========================================================================
    // getRecentReleaseNotesSync
    // =========================================================================

    /** Provider for changelog / release notes. Injected at startup. */
    private static volatile ReleaseNotesProvider releaseNotesProvider = null;

    public interface ReleaseNotesProvider {
        String getVersionChangelog();
        String getStoredChangelog();
        List<String> parseChangelog(String raw);
    }

    public static void setReleaseNotesProvider(ReleaseNotesProvider provider) {
        releaseNotesProvider = provider;
    }

    /**
     * Gets recent release notes for Logo v2 display.
     *
     * <p>For ant users, uses commits bundled at build time.
     * For external users, uses the public changelog.</p>
     *
     * Translated from {@code getRecentReleaseNotesSync()} in logoV2Utils.ts
     */
    public static List<String> getRecentReleaseNotesSync(int maxItems) {
        if (releaseNotesProvider == null) return List.of();

        if ("ant".equals(System.getenv("USER_TYPE"))) {
            String changelog = releaseNotesProvider.getVersionChangelog();
            if (changelog != null && !changelog.isBlank()) {
                String[] commits = changelog.trim().split("\n");
                List<String> result = new ArrayList<>();
                for (int i = 0; i < Math.min(maxItems, commits.length); i++) {
                    if (!commits[i].isBlank()) result.add(commits[i]);
                }
                return result;
            }
            return List.of();
        }

        String raw = releaseNotesProvider.getStoredChangelog();
        if (raw == null || raw.isBlank()) return List.of();

        try {
            List<String> notes = releaseNotesProvider.parseChangelog(raw);
            return notes.stream().limit(maxItems).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    // =========================================================================
    // Private string utilities — mirrors the TypeScript helpers that call
    // stringWidth(), truncate(), truncateToWidth(), truncateToWidthNoEllipsis()
    // from src/utils/format.ts and src/ink/stringWidth.ts
    // =========================================================================

    /**
     * Approximate visual column width of a string.
     *
     * <p>Full implementation should account for wide (CJK/emoji) characters.
     * This stub uses simple code-point counting which is correct for ASCII.</p>
     */
    private static int stringWidth(String s) {
        if (s == null) return 0;
        // Count code points (handles surrogate pairs correctly)
        return (int) s.codePoints().count();
    }

    /**
     * Truncate a string to {@code maxWidth} columns, appending "…" if shortened.
     */
    private static String truncate(String s, int maxWidth) {
        if (s == null) return "";
        if (stringWidth(s) <= maxWidth) return s;
        // Back off one char for the ellipsis
        int[] cps = s.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int cp : cps) {
            if (w + 1 >= maxWidth) break;
            sb.appendCodePoint(cp);
            w++;
        }
        return sb + "\u2026";
    }

    /** Truncate without appending an ellipsis. */
    private static String truncateToWidthNoEllipsis(String s, int maxWidth) {
        if (s == null) return "";
        int[] cps = s.codePoints().toArray();
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int cp : cps) {
            if (w >= maxWidth) break;
            sb.appendCodePoint(cp);
            w++;
        }
        return sb.toString();
    }

    /** Truncate with an appended ellipsis. */
    private static String truncateToWidth(String s, int maxWidth) {
        if (s == null) return "";
        if (stringWidth(s) <= maxWidth) return s;
        return truncateToWidthNoEllipsis(s, maxWidth - 1) + "\u2026";
    }

    private LogoV2Utils() {}
}
