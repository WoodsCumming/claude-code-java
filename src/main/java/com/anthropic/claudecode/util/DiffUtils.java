package com.anthropic.claudecode.util;

import java.util.*;
import lombok.Data;

/**
 * Diff utility functions.
 * Translated from src/utils/diff.ts
 *
 * Provides diff/patch generation for file edits.
 */
public class DiffUtils {

    public static final int CONTEXT_LINES = 3;

    /**
     * Generate a unified diff between two strings.
     * Simplified version of structuredPatch from the diff library.
     */
    public static List<Hunk> generatePatch(
            String oldContent,
            String newContent,
            String oldFilename,
            String newFilename) {

        if (oldContent == null) oldContent = "";
        if (newContent == null) newContent = "";

        String[] oldLines = oldContent.split("\n", -1);
        String[] newLines = newContent.split("\n", -1);

        return computeHunks(oldLines, newLines);
    }

    /**
     * Count lines added and removed in a patch.
     * Translated from countLinesChanged() in diff.ts
     */
    public static LineChanges countLinesChanged(List<Hunk> patch, String newFileContent) {
        int additions = 0;
        int removals = 0;

        if (patch.isEmpty() && newFileContent != null) {
            additions = newFileContent.split("\n", -1).length;
        } else {
            for (Hunk hunk : patch) {
                for (HunkLine line : hunk.getLines()) {
                    if ("+".equals(line.getType())) additions++;
                    else if ("-".equals(line.getType())) removals++;
                }
            }
        }

        return new LineChanges(additions, removals);
    }

    private static List<Hunk> computeHunks(String[] oldLines, String[] newLines) {
        // Simple LCS-based diff
        int[][] lcs = computeLCS(oldLines, newLines);
        List<DiffOp> ops = extractOps(oldLines, newLines, lcs);
        return groupIntoHunks(ops, oldLines, newLines);
    }

    private static int[][] computeLCS(String[] a, String[] b) {
        int m = a.length, n = b.length;
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a[i-1].equals(b[j-1])) {
                    dp[i][j] = dp[i-1][j-1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i-1][j], dp[i][j-1]);
                }
            }
        }
        return dp;
    }

    private static List<DiffOp> extractOps(String[] a, String[] b, int[][] lcs) {
        List<DiffOp> ops = new ArrayList<>();
        int i = a.length, j = b.length;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a[i-1].equals(b[j-1])) {
                ops.add(0, new DiffOp("=", i-1, j-1, a[i-1]));
                i--; j--;
            } else if (j > 0 && (i == 0 || lcs[i][j-1] >= lcs[i-1][j])) {
                ops.add(0, new DiffOp("+", i, j-1, b[j-1]));
                j--;
            } else {
                ops.add(0, new DiffOp("-", i-1, j, a[i-1]));
                i--;
            }
        }
        return ops;
    }

    private static List<Hunk> groupIntoHunks(List<DiffOp> ops, String[] oldLines, String[] newLines) {
        List<Hunk> hunks = new ArrayList<>();
        List<HunkLine> currentHunkLines = null;
        int hunkOldStart = -1, hunkNewStart = -1;
        int oldLineNum = 0, newLineNum = 0;

        for (int idx = 0; idx < ops.size(); idx++) {
            DiffOp op = ops.get(idx);

            if (!op.getType().equals("=")) {
                // Start new hunk if needed
                if (currentHunkLines == null) {
                    currentHunkLines = new ArrayList<>();
                    // Add context before
                    int contextStart = Math.max(0, idx - CONTEXT_LINES);
                    hunkOldStart = oldLineNum - (idx - contextStart);
                    hunkNewStart = newLineNum - (idx - contextStart);
                    for (int ci = contextStart; ci < idx; ci++) {
                        DiffOp ctx = ops.get(ci);
                        if (ctx.getType().equals("=")) {
                            currentHunkLines.add(new HunkLine(" ", ctx.getContent()));
                        }
                    }
                }
                currentHunkLines.add(new HunkLine(op.getType(), op.getContent()));
            } else if (currentHunkLines != null) {
                // Context line after change
                currentHunkLines.add(new HunkLine(" ", op.getContent()));

                // Check if we're far enough from next change to close hunk
                boolean hasNearbyChange = false;
                for (int look = idx + 1; look < Math.min(ops.size(), idx + CONTEXT_LINES + 1); look++) {
                    if (!ops.get(look).getType().equals("=")) {
                        hasNearbyChange = true;
                        break;
                    }
                }

                if (!hasNearbyChange) {
                    // Close current hunk
                    hunks.add(new Hunk(hunkOldStart + 1, hunkNewStart + 1, currentHunkLines));
                    currentHunkLines = null;
                }
            }

            if (!op.getType().equals("+")) oldLineNum++;
            if (!op.getType().equals("-")) newLineNum++;
        }

        if (currentHunkLines != null) {
            hunks.add(new Hunk(hunkOldStart + 1, hunkNewStart + 1, currentHunkLines));
        }

        return hunks;
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class Hunk {
        private int oldStart;
        private int newStart;
        private List<HunkLine> lines;

        public int getOldStart() { return oldStart; }
        public void setOldStart(int v) { oldStart = v; }
        public int getNewStart() { return newStart; }
        public void setNewStart(int v) { newStart = v; }
        public List<HunkLine> getLines() { return lines; }
        public void setLines(List<HunkLine> v) { lines = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class HunkLine {
        private String type; // "+" | "-" | " "
        private String content;

        public String getType() { return type; }
        public void setType(String v) { type = v; }
        public String getContent() { return content; }
        public void setContent(String v) { content = v; }
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class DiffOp {
        private String type; // "+" | "-" | "="
        private int oldIdx;
        private int newIdx;
        private String content;
    }

    public record LineChanges(int additions, int removals) {}

    private DiffUtils() {}
}
