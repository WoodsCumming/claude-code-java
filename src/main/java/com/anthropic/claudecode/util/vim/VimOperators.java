package com.anthropic.claudecode.util.vim;

import com.anthropic.claudecode.util.vim.VimTypes.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Vim Operator Functions.
 *
 * <p>Pure functions for executing vim operators (delete, change, yank, etc.).
 * These functions modify text and cursor state through the {@link OperatorContext}
 * callback interface — they do not hold any mutable state themselves.
 *
 * <p>Translated from: src/vim/operators.ts
 */
public final class VimOperators {

    private VimOperators() {}

    // =========================================================================
    // OperatorContext
    // =========================================================================

    /**
     * Context for operator execution.
     * All side effects (text mutation, cursor movement, register writes, …)
     * are expressed through this interface.
     */
    public interface OperatorContext {
        /** The current cursor. */
        Cursor cursor();

        /** The full text being edited. */
        String text();

        /** Replace the full text with {@code newText}. */
        void setText(String newText);

        /** Move the cursor to the given absolute offset. */
        void setOffset(int offset);

        /** Enter INSERT mode with the cursor at {@code offset}. */
        void enterInsert(int offset);

        /** Read the current register contents. */
        String getRegister();

        /** Write to the register. {@code linewise} signals linewise content. */
        void setRegister(String content, boolean linewise);

        /** Returns the last find operation, or {@code null} if none. */
        PersistentState.LastFind getLastFind();

        /** Records a find operation for future {@code ;} / {@code ,} repeats. */
        void setLastFind(FindType type, String character);

        /** Records a change for future {@code .} repeats. */
        void recordChange(RecordedChange change);
    }

    // =========================================================================
    // Public operator functions
    // =========================================================================

    /**
     * Execute an operator with a simple motion (e.g., {@code dw}, {@code cj}).
     */
    public static void executeOperatorMotion(
            Operator op, String motion, int count, OperatorContext ctx) {

        VimMotions.Cursor target = VimMotions.resolveMotion(motion, ctx.cursor(), count);
        if (target.equals(ctx.cursor())) return;

        OperatorRange range = getOperatorRange(ctx.cursor(), target, motion, op, count, ctx);
        applyOperator(op, range.from(), range.to(), ctx, range.linewise());
        ctx.recordChange(new RecordedChange.OperatorChange(op, motion, count));
    }

    /**
     * Execute an operator with a find motion (e.g., {@code dft}).
     */
    public static void executeOperatorFind(
            Operator op, FindType findType, String character, int count, OperatorContext ctx) {

        Integer targetOffset = ctx.cursor().findCharacter(character, findType, count);
        if (targetOffset == null) return;

        VimMotions.Cursor target = ctx.cursor().atOffset(targetOffset);
        OperatorRange range = getOperatorRangeForFind(ctx.cursor(), target);

        applyOperator(op, range.from(), range.to(), ctx, false);
        ctx.setLastFind(findType, character);
        ctx.recordChange(new RecordedChange.OperatorFindChange(op, findType, character, count));
    }

    /**
     * Execute an operator with a text object (e.g., {@code diw}, {@code ca"}).
     */
    public static void executeOperatorTextObj(
            Operator op, TextObjScope scope, String objType, int count, OperatorContext ctx) {

        TextRange range = findTextObject(ctx.text(), ctx.cursor().offset(), objType,
                scope == TextObjScope.INNER);
        if (range == null) return;

        applyOperator(op, range.start(), range.end(), ctx, false);
        ctx.recordChange(new RecordedChange.OperatorTextObjChange(op, objType, scope, count));
    }

    /**
     * Execute a line operation ({@code dd}, {@code cc}, {@code yy}).
     */
    public static void executeLineOp(Operator op, int count, OperatorContext ctx) {
        String text = ctx.text();
        String[] lines = text.split("\n", -1);

        // Count logical lines before the cursor offset
        String textBeforeCursor = text.substring(0, ctx.cursor().offset());
        int currentLine = countChar(textBeforeCursor, '\n');

        int linesToAffect = Math.min(count, lines.length - currentLine);
        int lineStart = ctx.cursor().startOfLogicalLine().offset();
        int lineEnd = lineStart;
        for (int i = 0; i < linesToAffect; i++) {
            int nextNewline = text.indexOf('\n', lineEnd);
            lineEnd = nextNewline == -1 ? text.length() : nextNewline + 1;
        }

        String content = text.substring(lineStart, lineEnd);
        if (!content.endsWith("\n")) {
            content = content + "\n";
        }
        ctx.setRegister(content, true);

        if (op == Operator.YANK) {
            ctx.setOffset(lineStart);
        } else if (op == Operator.DELETE) {
            int deleteStart = lineStart;
            int deleteEnd = lineEnd;

            if (deleteEnd == text.length() && deleteStart > 0
                    && text.charAt(deleteStart - 1) == '\n') {
                deleteStart--;
            }

            String newText = text.substring(0, deleteStart) + text.substring(deleteEnd);
            ctx.setText(newText.isEmpty() ? "" : newText);
            String lastGr = lastGrapheme(newText);
            int maxOff = Math.max(0, newText.length() - (lastGr.isEmpty() ? 1 : lastGr.length()));
            ctx.setOffset(Math.min(deleteStart, maxOff));
        } else if (op == Operator.CHANGE) {
            if (lines.length == 1) {
                ctx.setText("");
                ctx.enterInsert(0);
            } else {
                List<String> before = listOf(lines, 0, currentLine);
                List<String> after = listOf(lines, currentLine + linesToAffect, lines.length);
                List<String> newLinesList = new ArrayList<>(before);
                newLinesList.add("");
                newLinesList.addAll(after);
                String newText = String.join("\n", newLinesList);
                ctx.setText(newText);
                ctx.enterInsert(lineStart);
            }
        }

        ctx.recordChange(new RecordedChange.OperatorChange(op, op.key().substring(0, 1), count));
    }

    /**
     * Execute delete-character ({@code x} command).
     */
    public static void executeX(int count, OperatorContext ctx) {
        int from = ctx.cursor().offset();
        if (from >= ctx.text().length()) return;

        VimMotions.Cursor endCursor = ctx.cursor();
        for (int i = 0; i < count && !endCursor.isAtEnd(); i++) {
            endCursor = endCursor.right();
        }
        int to = endCursor.offset();

        String deleted = ctx.text().substring(from, to);
        String newText = ctx.text().substring(0, from) + ctx.text().substring(to);

        ctx.setRegister(deleted, false);
        ctx.setText(newText);
        String lastGr = lastGrapheme(newText);
        int maxOff = Math.max(0, newText.length() - (lastGr.isEmpty() ? 1 : lastGr.length()));
        ctx.setOffset(Math.min(from, maxOff));
        ctx.recordChange(new RecordedChange.X(count));
    }

    /**
     * Execute replace-character ({@code r} command).
     */
    public static void executeReplace(String character, int count, OperatorContext ctx) {
        int offset = ctx.cursor().offset();
        String newText = ctx.text();

        for (int i = 0; i < count && offset < newText.length(); i++) {
            String grapheme = firstGrapheme(newText.substring(offset));
            int graphemeLen = grapheme.isEmpty() ? 1 : grapheme.length();
            newText = newText.substring(0, offset) + character + newText.substring(offset + graphemeLen);
            offset += character.length();
        }

        ctx.setText(newText);
        ctx.setOffset(Math.max(0, offset - character.length()));
        ctx.recordChange(new RecordedChange.ReplaceChange(character, count));
    }

    /**
     * Execute toggle-case ({@code ~} command).
     */
    public static void executeToggleCase(int count, OperatorContext ctx) {
        int startOffset = ctx.cursor().offset();
        if (startOffset >= ctx.text().length()) return;

        String newText = ctx.text();
        int offset = startOffset;
        int toggled = 0;

        while (offset < newText.length() && toggled < count) {
            String grapheme = firstGrapheme(newText.substring(offset));
            int graphemeLen = grapheme.isEmpty() ? 1 : grapheme.length();

            String toggledGrapheme = grapheme.equals(grapheme.toUpperCase())
                    ? grapheme.toLowerCase()
                    : grapheme.toUpperCase();

            newText = newText.substring(0, offset) + toggledGrapheme + newText.substring(offset + graphemeLen);
            offset += toggledGrapheme.length();
            toggled++;
        }

        ctx.setText(newText);
        ctx.setOffset(offset);
        ctx.recordChange(new RecordedChange.ToggleCase(count));
    }

    /**
     * Execute join-lines ({@code J} command).
     */
    public static void executeJoin(int count, OperatorContext ctx) {
        String text = ctx.text();
        String[] lines = text.split("\n", -1);
        int currentLine = ctx.cursor().getPosition().line();

        if (currentLine >= lines.length - 1) return;

        int linesToJoin = Math.min(count, lines.length - currentLine - 1);
        String joinedLine = lines[currentLine];
        int cursorPos = joinedLine.length();

        for (int i = 1; i <= linesToJoin; i++) {
            String nextLine = (lines[currentLine + i]).stripLeading();
            if (!nextLine.isEmpty()) {
                if (!joinedLine.endsWith(" ") && !joinedLine.isEmpty()) {
                    joinedLine += " ";
                }
                joinedLine += nextLine;
            }
        }

        List<String> newLinesList = new ArrayList<>(listOf(lines, 0, currentLine));
        newLinesList.add(joinedLine);
        newLinesList.addAll(listOf(lines, currentLine + linesToJoin + 1, lines.length));

        String newText = String.join("\n", newLinesList);
        ctx.setText(newText);
        ctx.setOffset(getLineStartOffset(newLinesList, currentLine) + cursorPos);
        ctx.recordChange(new RecordedChange.Join(count));
    }

    /**
     * Execute paste ({@code p} / {@code P} command).
     */
    public static void executePaste(boolean after, int count, OperatorContext ctx) {
        String register = ctx.getRegister();
        if (register == null || register.isEmpty()) return;

        boolean isLinewise = register.endsWith("\n");
        String content = isLinewise ? register.substring(0, register.length() - 1) : register;

        if (isLinewise) {
            String text = ctx.text();
            String[] lines = text.split("\n", -1);
            int currentLine = ctx.cursor().getPosition().line();

            int insertLine = after ? currentLine + 1 : currentLine;
            String[] contentLines = content.split("\n", -1);
            List<String> repeatedLines = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                for (String l : contentLines) {
                    repeatedLines.add(l);
                }
            }

            List<String> newLinesList = new ArrayList<>(listOf(lines, 0, insertLine));
            newLinesList.addAll(repeatedLines);
            newLinesList.addAll(listOf(lines, insertLine, lines.length));

            String newText = String.join("\n", newLinesList);
            ctx.setText(newText);
            ctx.setOffset(getLineStartOffset(newLinesList, insertLine));
        } else {
            String textToInsert = content.repeat(count);
            int insertPoint;
            if (after && ctx.cursor().offset() < ctx.text().length()) {
                insertPoint = ctx.cursor().nextOffset(ctx.cursor().offset());
            } else {
                insertPoint = ctx.cursor().offset();
            }

            String newText = ctx.text().substring(0, insertPoint)
                    + textToInsert
                    + ctx.text().substring(insertPoint);
            String lastGr = lastGrapheme(textToInsert);
            int newOffset = insertPoint + textToInsert.length() - (lastGr.isEmpty() ? 1 : lastGr.length());

            ctx.setText(newText);
            ctx.setOffset(Math.max(insertPoint, newOffset));
        }
    }

    /**
     * Execute indent ({@code >>} / {@code <<} command).
     */
    public static void executeIndent(String dir, int count, OperatorContext ctx) {
        String text = ctx.text();
        String[] linesArr = text.split("\n", -1);
        List<String> lines = new ArrayList<>(List.of(linesArr));
        int currentLine = ctx.cursor().getPosition().line();
        int linesToAffect = Math.min(count, lines.size() - currentLine);
        String indent = "  "; // Two spaces

        for (int i = 0; i < linesToAffect; i++) {
            int lineIdx = currentLine + i;
            String line = lines.get(lineIdx);

            if (">".equals(dir)) {
                lines.set(lineIdx, indent + line);
            } else if (line.startsWith(indent)) {
                lines.set(lineIdx, line.substring(indent.length()));
            } else if (line.startsWith("\t")) {
                lines.set(lineIdx, line.substring(1));
            } else {
                // Remove as much leading whitespace as possible up to indent.length()
                int removed = 0;
                int idx = 0;
                while (idx < line.length() && removed < indent.length()
                        && Character.isWhitespace(line.charAt(idx))) {
                    removed++;
                    idx++;
                }
                lines.set(lineIdx, line.substring(idx));
            }
        }

        String newText = String.join("\n", lines);
        String currentLineText = lines.get(currentLine);
        int firstNonBlank = 0;
        while (firstNonBlank < currentLineText.length()
                && Character.isWhitespace(currentLineText.charAt(firstNonBlank))) {
            firstNonBlank++;
        }

        ctx.setText(newText);
        ctx.setOffset(getLineStartOffset(lines, currentLine) + firstNonBlank);
        ctx.recordChange(new RecordedChange.IndentChange(dir, count));
    }

    /**
     * Execute open-line ({@code o} / {@code O} command).
     */
    public static void executeOpenLine(String direction, OperatorContext ctx) {
        String text = ctx.text();
        String[] linesArr = text.split("\n", -1);
        List<String> lines = new ArrayList<>(List.of(linesArr));
        int currentLine = ctx.cursor().getPosition().line();

        int insertLine = "below".equals(direction) ? currentLine + 1 : currentLine;
        lines.add(insertLine, "");

        String newText = String.join("\n", lines);
        ctx.setText(newText);
        ctx.enterInsert(getLineStartOffset(lines, insertLine));
        ctx.recordChange(new RecordedChange.OpenLine(direction));
    }

    /**
     * Execute G-based operator (e.g., {@code dG}, {@code yG}).
     */
    public static void executeOperatorG(Operator op, int count, OperatorContext ctx) {
        VimMotions.Cursor target = count == 1
                ? ctx.cursor().startOfLastLine()
                : ctx.cursor().goToLine(count);

        if (target.equals(ctx.cursor())) return;

        OperatorRange range = getOperatorRange(ctx.cursor(), target, "G", op, count, ctx);
        applyOperator(op, range.from(), range.to(), ctx, range.linewise());
        ctx.recordChange(new RecordedChange.OperatorChange(op, "G", count));
    }

    /**
     * Execute gg-based operator (e.g., {@code dgg}, {@code ygg}).
     */
    public static void executeOperatorGg(Operator op, int count, OperatorContext ctx) {
        VimMotions.Cursor target = count == 1
                ? ctx.cursor().startOfFirstLine()
                : ctx.cursor().goToLine(count);

        if (target.equals(ctx.cursor())) return;

        OperatorRange range = getOperatorRange(ctx.cursor(), target, "gg", op, count, ctx);
        applyOperator(op, range.from(), range.to(), ctx, range.linewise());
        ctx.recordChange(new RecordedChange.OperatorChange(op, "gg", count));
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /** Calculates the character offset where a given line begins. */
    public static int getLineStartOffset(List<String> lines, int lineIndex) {
        int offset = 0;
        for (int i = 0; i < lineIndex; i++) {
            offset += lines.get(i).length() + 1; // +1 for the '\n'
        }
        return offset;
    }

    private static OperatorRange getOperatorRange(
            VimMotions.Cursor cursor,
            VimMotions.Cursor target,
            String motion,
            Operator op,
            int count,
            OperatorContext ctx) {

        int from = Math.min(cursor.offset(), target.offset());
        int to   = Math.max(cursor.offset(), target.offset());
        boolean linewise = false;

        // cw / cW: change to end of word, not start of next word
        if (op == Operator.CHANGE && ("w".equals(motion) || "W".equals(motion))) {
            VimMotions.Cursor wordCursor = cursor;
            for (int i = 0; i < count - 1; i++) {
                wordCursor = "w".equals(motion) ? wordCursor.nextVimWord() : wordCursor.nextWORD();
            }
            VimMotions.Cursor wordEnd = "w".equals(motion)
                    ? wordCursor.endOfVimWord()
                    : wordCursor.endOfWORD();
            to = cursor.nextOffset(wordEnd.offset());
        } else if (VimMotions.isLinewiseMotion(motion)) {
            linewise = true;
            String text = ctx.text();
            int nextNewline = text.indexOf('\n', to);
            if (nextNewline == -1) {
                to = text.length();
                if (from > 0 && text.charAt(from - 1) == '\n') {
                    from--;
                }
            } else {
                to = nextNewline + 1;
            }
        } else if (VimMotions.isInclusiveMotion(motion) && cursor.offset() <= target.offset()) {
            to = cursor.nextOffset(to);
        }

        // Snap out of image reference chips (if applicable)
        from = cursor.snapOutOfImageRef(from, "start");
        to   = cursor.snapOutOfImageRef(to,   "end");

        return new OperatorRange(from, to, linewise);
    }

    private static OperatorRange getOperatorRangeForFind(
            VimMotions.Cursor cursor, VimMotions.Cursor target) {

        int from = Math.min(cursor.offset(), target.offset());
        int maxOffset = Math.max(cursor.offset(), target.offset());
        int to = cursor.nextOffset(maxOffset);
        return new OperatorRange(from, to, false);
    }

    private static void applyOperator(
            Operator op, int from, int to, OperatorContext ctx, boolean linewise) {

        String content = ctx.text().substring(from, to);
        if (linewise && !content.endsWith("\n")) {
            content = content + "\n";
        }
        ctx.setRegister(content, linewise);

        if (op == Operator.YANK) {
            ctx.setOffset(from);
        } else if (op == Operator.DELETE) {
            String newText = ctx.text().substring(0, from) + ctx.text().substring(to);
            ctx.setText(newText);
            String lastGr = lastGrapheme(newText);
            int maxOff = Math.max(0, newText.length() - (lastGr.isEmpty() ? 1 : lastGr.length()));
            ctx.setOffset(Math.min(from, maxOff));
        } else if (op == Operator.CHANGE) {
            String newText = ctx.text().substring(0, from) + ctx.text().substring(to);
            ctx.setText(newText);
            ctx.enterInsert(from);
        }
    }

    // =========================================================================
    // Grapheme / string helpers
    // =========================================================================

    /**
     * Returns the first grapheme cluster of {@code s}, or an empty string if
     * {@code s} is empty.  For ASCII text this is simply the first character.
     */
    static String firstGrapheme(String s) {
        if (s == null || s.isEmpty()) return "";
        // Full Unicode grapheme-cluster segmentation is complex; for common text
        // we treat each code point as a grapheme (acceptable approximation).
        int cp = s.codePointAt(0);
        return s.substring(0, Character.charCount(cp));
    }

    /**
     * Returns the last grapheme cluster of {@code s}, or an empty string if
     * {@code s} is empty.
     */
    static String lastGrapheme(String s) {
        if (s == null || s.isEmpty()) return "";
        // Walk from the end to find the last code point.
        int idx = s.length();
        // Handle surrogate pairs
        if (idx >= 2 && Character.isLowSurrogate(s.charAt(idx - 1))
                && Character.isHighSurrogate(s.charAt(idx - 2))) {
            return s.substring(idx - 2);
        }
        return s.substring(idx - 1);
    }

    /** Counts occurrences of {@code ch} in {@code s}. */
    private static int countChar(String s, char ch) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ch) count++;
        }
        return count;
    }

    private static List<String> listOf(String[] arr, int from, int to) {
        List<String> result = new ArrayList<>();
        for (int i = from; i < to; i++) {
            result.add(arr[i]);
        }
        return result;
    }

    /**
     * Stub implementation of findTextObject.  Full text-object parsing logic
     * (matching brackets, quotes, word boundaries, etc.) is implementation-
     * specific; this returns {@code null} by default.
     */
    private static TextRange findTextObject(String text, int offset, String objType, boolean inner) {
        // TODO: implement full text-object parsing
        return null;
    }

    // =========================================================================
    // Supporting types
    // =========================================================================

    /** Internal range record used by operator functions. */
    private record OperatorRange(int from, int to, boolean linewise) {}

    /** A text range with start (inclusive) and end (exclusive) offsets. */
    public record TextRange(int start, int end) {}

    /**
     * Minimal cursor extension required by operator functions — adds methods
     * beyond the basic {@link VimMotions.Cursor} interface.
     */
    public interface Cursor extends VimMotions.Cursor {
        /** Returns the offset after the grapheme at {@code offset}. */
        int nextOffset(int offset);

        /** Returns a new cursor positioned at {@code offset}. */
        Cursor atOffset(int offset);

        /**
         * Finds the next occurrence of {@code character} in the given direction.
         *
         * @return the target offset, or {@code null} if not found
         */
        Integer findCharacter(String character, FindType direction, int count);

        /** Returns the current line/column position. */
        Position getPosition();

        /**
         * Snaps {@code offset} out of an embedded image-reference chip.
         *
         * @param side either "start" or "end"
         * @return adjusted offset
         */
        int snapOutOfImageRef(int offset, String side);
    }

    /** Cursor position expressed as line + column. */
    public record Position(int line, int column) {}
}
