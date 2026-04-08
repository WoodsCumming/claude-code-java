package com.anthropic.claudecode.util.ink;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Terminal output utilities — progress reporting, capability detection, and
 * frame diff writing.
 *
 * <p>Translated from {@code src/ink/terminal.ts}. The TypeScript module wraps
 * Node.js {@code process.env} and streams; here we use system properties and
 * a plain {@link OutputStream}.
 */
public final class InkTerminal {

    // =========================================================================
    // ANSI escape sequences (from termio/dec.ts)
    // =========================================================================

    /** Begin Synchronized Update (DEC mode 2026). */
    public static final String BSU = "\u001b[?2026h";
    /** End Synchronized Update. */
    public static final String ESU = "\u001b[?2026l";
    /** Hide cursor. */
    public static final String HIDE_CURSOR = "\u001b[?25l";
    /** Show cursor. */
    public static final String SHOW_CURSOR = "\u001b[?25h";

    // =========================================================================
    // Progress state
    // =========================================================================

    /** Terminal progress state values (maps to OSC 9;4 state codes). */
    public enum ProgressState {
        RUNNING       (1),
        COMPLETED     (0),
        ERROR         (2),
        INDETERMINATE (3);

        public final int code;
        ProgressState(int code) { this.code = code; }
    }

    /** Progress descriptor passed to {@link #setProgress}. */
    public record Progress(ProgressState state, Integer percentage) {
        public Progress(ProgressState state) { this(state, null); }
    }

    // =========================================================================
    // Diff patch types (mirrors frame.ts Diff)
    // =========================================================================

    /** A single patch in a frame diff. */
    public sealed interface Patch
        permits Stdout, Clear, ClearTerminal,
                CursorHide, CursorShow, CursorMove,
                CursorTo, CarriageReturn, Hyperlink,
                StyleStr {}

    public record Stdout(String content)                    implements Patch {}
    public record Clear(int count)                          implements Patch {}
    public record ClearTerminal()                           implements Patch {}
    public record CursorHide()                              implements Patch {}
    public record CursorShow()                              implements Patch {}
    public record CursorMove(int x, int y)                  implements Patch {}
    public record CursorTo(int col)                         implements Patch {}
    public record CarriageReturn()                          implements Patch {}
    public record Hyperlink(String uri)                     implements Patch {}
    public record StyleStr(String str)                      implements Patch {}

    // =========================================================================
    // Terminal capability detection
    // =========================================================================

    /**
     * Cached result of {@link #isSynchronizedOutputSupported()}.
     * Evaluated once at class-load time; terminal capabilities don't change
     * mid-session.
     */
    public static final boolean SYNC_OUTPUT_SUPPORTED = isSynchronizedOutputSupported();

    /**
     * Returns {@code true} if the terminal supports OSC 9;4 progress
     * reporting.
     *
     * <p>Supported terminals:
     * <ul>
     *   <li>ConEmu (Windows) — all versions</li>
     *   <li>Ghostty 1.2.0+</li>
     *   <li>iTerm2 3.6.6+</li>
     * </ul>
     * Windows Terminal is explicitly excluded (it interprets OSC 9;4 as
     * notifications, not progress indicators).
     */
    public static boolean isProgressReportingAvailable() {
        // Windows Terminal interprets OSC 9;4 as notifications
        if (env("WT_SESSION") != null) return false;

        // ConEmu supports progress (all versions)
        if (env("ConEmuANSI") != null
                || env("ConEmuPID") != null
                || env("ConEmuTask") != null) return true;

        String version = env("TERM_PROGRAM_VERSION");
        if (version == null) return false;

        String termProgram = env("TERM_PROGRAM");

        // Ghostty 1.2.0+
        if ("ghostty".equals(termProgram)) {
            return gte(version, "1.2.0");
        }
        // iTerm2 3.6.6+
        if ("iTerm.app".equals(termProgram)) {
            return gte(version, "3.6.6");
        }
        return false;
    }

    /**
     * Returns {@code true} if the terminal supports DEC mode 2026
     * (synchronized output). When supported, BSU/ESU sequences prevent
     * visible flicker during redraws.
     */
    public static boolean isSynchronizedOutputSupported() {
        // tmux proxies bytes but doesn't implement DEC 2026 — skip
        if (env("TMUX") != null) return false;

        String termProgram = env("TERM_PROGRAM");
        String term        = env("TERM");

        if ("iTerm.app".equals(termProgram)
                || "WezTerm".equals(termProgram)
                || "WarpTerminal".equals(termProgram)
                || "ghostty".equals(termProgram)
                || "contour".equals(termProgram)
                || "vscode".equals(termProgram)
                || "alacritty".equals(termProgram)) return true;

        if (term != null && (term.contains("kitty") || "xterm-ghostty".equals(term)
                || term.startsWith("foot") || term.contains("alacritty"))) return true;

        if (env("KITTY_WINDOW_ID") != null) return true;
        if (env("ZED_TERM")        != null) return true;
        if (env("WT_SESSION")      != null) return true;

        String vteVersion = env("VTE_VERSION");
        if (vteVersion != null) {
            try {
                if (Integer.parseInt(vteVersion) >= 6800) return true;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    /**
     * Returns {@code true} if the terminal may yank the viewport when cursor-up
     * sequences reach above the visible area (affects Windows + WSL-in-WT).
     */
    public static boolean hasCursorUpViewportYankBug() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("windows") || env("WT_SESSION") != null;
    }

    // =========================================================================
    // XTVERSION detection
    // =========================================================================

    private static volatile String xtversionName;

    /**
     * Record the XTVERSION terminal name reply. Called once when the DCS
     * response arrives on stdin. No-op if already set.
     */
    public static void setXtversionName(String name) {
        if (xtversionName == null) xtversionName = name;
    }

    /**
     * Returns {@code true} if running in an xterm.js-based terminal (VS Code,
     * Cursor, Windsurf integrated terminals).
     */
    public static boolean isXtermJs() {
        if ("vscode".equals(env("TERM_PROGRAM"))) return true;
        String name = xtversionName;
        return name != null && name.startsWith("xterm.js");
    }

    // =========================================================================
    // Frame diff writing
    // =========================================================================

    /**
     * Write a list of diff patches to the terminal's stdout stream as a single
     * buffered write.
     *
     * @param out             the output stream (typically terminal stdout)
     * @param diff            ordered list of patches to apply
     * @param skipSyncMarkers when {@code true} omit BSU/ESU wrappers (e.g.
     *                        when the terminal doesn't support DEC 2026)
     */
    public static void writeDiffToTerminal(
            OutputStream out, List<Patch> diff, boolean skipSyncMarkers)
            throws IOException {

        if (diff == null || diff.isEmpty()) return;

        boolean useSync = !skipSyncMarkers;
        StringBuilder buffer = new StringBuilder(useSync ? BSU : "");

        for (Patch patch : diff) {
            switch (patch) {
                case Stdout s          -> buffer.append(s.content());
                case Clear c           -> { if (c.count() > 0) buffer.append(eraseLines(c.count())); }
                case ClearTerminal ignored -> buffer.append(getClearTerminalSequence());
                case CursorHide ignored -> buffer.append(HIDE_CURSOR);
                case CursorShow ignored -> buffer.append(SHOW_CURSOR);
                case CursorMove cm     -> buffer.append(cursorMove(cm.x(), cm.y()));
                case CursorTo ct       -> buffer.append(cursorTo(ct.col()));
                case CarriageReturn ignored -> buffer.append('\r');
                case Hyperlink h       -> buffer.append(link(h.uri()));
                case StyleStr ss       -> buffer.append(ss.str());
            }
        }

        if (useSync) buffer.append(ESU);
        out.write(buffer.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Convenience overload that honours {@link #SYNC_OUTPUT_SUPPORTED}.
     */
    public static void writeDiffToTerminal(OutputStream out, List<Patch> diff)
            throws IOException {
        writeDiffToTerminal(out, diff, false);
    }

    // =========================================================================
    // Low-level sequence generators (mirrors termio/csi.ts, dec.ts, osc.ts)
    // =========================================================================

    private static final String ESC = "\u001b";
    private static final String CSI = ESC + "[";
    private static final String OSC = ESC + "]";
    private static final String ST  = ESC + "\\";

    /** Generate a CSI sequence for erasing {@code count} lines above the cursor. */
    public static String eraseLines(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(CSI).append("2K");     // Erase entire line
            if (i < count - 1) sb.append(CSI).append("1A"); // Cursor up 1
        }
        return sb.toString();
    }

    /** Move cursor by {@code (x, y)} relative to the current position. */
    public static String cursorMove(int x, int y) {
        StringBuilder sb = new StringBuilder();
        if (y < 0) sb.append(CSI).append(-y).append("A"); // Up
        else if (y > 0) sb.append(CSI).append(y).append("B"); // Down
        if (x < 0) sb.append(CSI).append(-x).append("D"); // Back
        else if (x > 0) sb.append(CSI).append(x).append("C"); // Forward
        return sb.toString();
    }

    /** Move cursor to column {@code col} (1-indexed). */
    public static String cursorTo(int col) {
        return CSI + col + "G";
    }

    /** Generate an OSC 8 hyperlink open/close sequence. */
    public static String link(String uri) {
        if (uri == null || uri.isEmpty()) {
            return OSC + "8;;" + ST;     // close hyperlink
        }
        return OSC + "8;;" + uri + ST;  // open hyperlink
    }

    /** Full clear-terminal sequence (clear screen + reset scroll region). */
    public static String getClearTerminalSequence() {
        return CSI + "2J" + CSI + "H"; // ED 2 + CUP 1;1
    }

    // =========================================================================
    // OSC 9;4 progress reporting
    // =========================================================================

    /**
     * Generate an OSC 9;4 progress sequence.
     *
     * @param state      progress state code (0=done, 1=running, 2=error, 3=indeterminate)
     * @param percentage 0–100 or {@code null}
     * @return the OSC string, or empty if progress reporting is unavailable
     */
    public static String progressSequence(int state, Integer percentage) {
        if (!isProgressReportingAvailable()) return "";
        if (percentage != null) {
            return OSC + "9;4;" + state + ";" + percentage + "\u0007";
        }
        return OSC + "9;4;" + state + "\u0007";
    }

    /** Update the terminal's progress indicator. */
    public static void setProgress(OutputStream out, Progress progress) throws IOException {
        int stateCode = progress.state().code;
        String seq = progressSequence(stateCode, progress.percentage());
        if (!seq.isEmpty()) {
            out.write(seq.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
        }
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static String env(String key) {
        return System.getenv(key);
    }

    /**
     * Naive semver greater-than-or-equal comparison for version strings of the
     * form {@code "MAJOR.MINOR.PATCH"}.
     */
    static boolean gte(String a, String b) {
        int[] va = parseSemver(a);
        int[] vb = parseSemver(b);
        for (int i = 0; i < 3; i++) {
            if (va[i] > vb[i]) return true;
            if (va[i] < vb[i]) return false;
        }
        return true; // equal
    }

    private static int[] parseSemver(String v) {
        if (v == null) return new int[]{0, 0, 0};
        String[] parts = v.split("[.\\-]", 4);
        int[] result = new int[3];
        for (int i = 0; i < 3 && i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) {}
        }
        return result;
    }

    private InkTerminal() {}
}
