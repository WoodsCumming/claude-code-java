package com.anthropic.claudecode.util.bash;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tree-sitter AST analysis utilities for bash command security validation.
 * Translated from src/utils/bash/treeSitterAnalysis.ts
 *
 * Extracts security-relevant information from tree-sitter parse trees, providing
 * more accurate analysis than regex/shell-quote parsing. The native NAPI parser
 * returns plain JS objects — in Java we model these as plain data records.
 */
public final class TreeSitterAnalysis {

    private TreeSitterAnalysis() {}

    // =========================================================================
    // Public data types (translated from export type declarations)
    // =========================================================================

    /**
     * Quote context extracted from the AST.
     * Translated from QuoteContext in treeSitterAnalysis.ts
     */
    public record QuoteContext(
        /** Command text with single-quoted content removed (double-quoted content preserved). */
        String withDoubleQuotes,
        /** Command text with all quoted content removed. */
        String fullyUnquoted,
        /** Like fullyUnquoted but preserves quote characters (', "). */
        String unquotedKeepQuoteChars
    ) {}

    /**
     * Compound command structure extracted from the AST.
     * Translated from CompoundStructure in treeSitterAnalysis.ts
     */
    public record CompoundStructure(
        /** Whether the command has compound operators (&&, ||, ;) at the top level. */
        boolean hasCompoundOperators,
        /** Whether the command has pipelines. */
        boolean hasPipeline,
        /** Whether the command has subshells. */
        boolean hasSubshell,
        /** Whether the command has command groups ({...}). */
        boolean hasCommandGroup,
        /** Top-level compound operator types found. */
        List<String> operators,
        /** Individual command segments split by compound operators. */
        List<String> segments
    ) {}

    /**
     * Dangerous pattern flags extracted from the AST.
     * Translated from DangerousPatterns in treeSitterAnalysis.ts
     */
    public record DangerousPatterns(
        boolean hasCommandSubstitution,
        boolean hasProcessSubstitution,
        boolean hasParameterExpansion,
        boolean hasHeredoc,
        boolean hasComment
    ) {}

    /**
     * Complete tree-sitter analysis result.
     * Translated from TreeSitterAnalysis in treeSitterAnalysis.ts
     */
    public record Analysis(
        QuoteContext quoteContext,
        CompoundStructure compoundStructure,
        /** Whether actual operator nodes (;, &&, ||) exist in the AST. */
        boolean hasActualOperatorNodes,
        DangerousPatterns dangerousPatterns
    ) {}

    // =========================================================================
    // Tree-sitter node model
    // =========================================================================

    /**
     * Minimal representation of a tree-sitter node.
     * Translated from TreeSitterNode in treeSitterAnalysis.ts
     */
    public interface TreeSitterNode {
        String type();
        String text();
        int startIndex();
        int endIndex();
        List<TreeSitterNode> children();
    }

    // =========================================================================
    // Quote span collection (collectQuoteSpans)
    // =========================================================================

    private record QuoteSpans(
        List<int[]> raw,    // raw_string (single-quoted)
        List<int[]> ansiC,  // ansi_c_string ($'...')
        List<int[]> dbl,    // string (double-quoted)
        List<int[]> heredoc // quoted heredoc_redirect
    ) {
        static QuoteSpans empty() {
            return new QuoteSpans(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    /**
     * Single-pass collection of all quote-related spans.
     * Translated from collectQuoteSpans() in treeSitterAnalysis.ts
     */
    private static void collectQuoteSpans(TreeSitterNode node, QuoteSpans out, boolean inDouble) {
        switch (node.type()) {
            case "raw_string" -> {
                out.raw().add(new int[]{node.startIndex(), node.endIndex()});
                return; // literal body, no nested quotes
            }
            case "ansi_c_string" -> {
                out.ansiC().add(new int[]{node.startIndex(), node.endIndex()});
                return; // literal body
            }
            case "string" -> {
                if (!inDouble) out.dbl().add(new int[]{node.startIndex(), node.endIndex()});
                for (TreeSitterNode child : node.children()) {
                    if (child != null) collectQuoteSpans(child, out, true);
                }
                return;
            }
            case "heredoc_redirect" -> {
                boolean isQuoted = false;
                for (TreeSitterNode child : node.children()) {
                    if (child != null && "heredoc_start".equals(child.type())) {
                        String first = child.text().isEmpty() ? "" : child.text().substring(0, 1);
                        isQuoted = "'".equals(first) || "\"".equals(first) || "\\".equals(first);
                        break;
                    }
                }
                if (isQuoted) {
                    out.heredoc().add(new int[]{node.startIndex(), node.endIndex()});
                    return; // literal body
                }
                // Unquoted heredoc: recurse to find inner quote nodes
            }
            default -> { /* fall through to recurse */ }
        }

        for (TreeSitterNode child : node.children()) {
            if (child != null) collectQuoteSpans(child, out, inDouble);
        }
    }

    // =========================================================================
    // Span utilities
    // =========================================================================

    /**
     * Returns spans with fully-contained spans removed (keep outermost only).
     * Translated from dropContainedSpans() in treeSitterAnalysis.ts
     */
    private static List<int[]> dropContainedSpans(List<int[]> spans) {
        List<int[]> result = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            int[] s = spans.get(i);
            boolean contained = false;
            for (int j = 0; j < spans.size(); j++) {
                if (j == i) continue;
                int[] other = spans.get(j);
                if (other[0] <= s[0] && other[1] >= s[1]
                        && (other[0] < s[0] || other[1] > s[1])) {
                    contained = true;
                    break;
                }
            }
            if (!contained) result.add(s);
        }
        return result;
    }

    /**
     * Removes character ranges from a string.
     * Translated from removeSpans() in treeSitterAnalysis.ts
     */
    private static String removeSpans(String command, List<int[]> spans) {
        if (spans.isEmpty()) return command;
        List<int[]> sorted = dropContainedSpans(spans);
        sorted.sort((a, b) -> b[0] - a[0]); // descending by start
        String result = command;
        for (int[] span : sorted) {
            result = result.substring(0, span[0]) + result.substring(span[1]);
        }
        return result;
    }

    /** A span with associated quote delimiter characters: [start, end, openChar, closeChar]. */
    private record SpanWithQuotes(int start, int end, String open, String close) {}

    /**
     * Replaces span content with just the quote delimiters.
     * Translated from replaceSpansKeepQuotes() in treeSitterAnalysis.ts
     */
    private static String replaceSpansKeepQuotes(String command, List<SpanWithQuotes> spans) {
        if (spans.isEmpty()) return command;
        List<SpanWithQuotes> sorted = new ArrayList<>(spans);
        sorted.sort((a, b) -> b.start() - a.start()); // descending
        String result = command;
        for (SpanWithQuotes span : sorted) {
            result = result.substring(0, span.start())
                   + span.open() + span.close()
                   + result.substring(span.end());
        }
        return result;
    }

    // =========================================================================
    // Public analysis functions
    // =========================================================================

    /**
     * Extracts quote context from the tree-sitter AST.
     * Translated from extractQuoteContext() in treeSitterAnalysis.ts
     */
    public static QuoteContext extractQuoteContext(TreeSitterNode rootNode, String command) {
        QuoteSpans spans = QuoteSpans.empty();
        collectQuoteSpans(rootNode, spans, false);

        List<int[]> allQuoteSpans = new ArrayList<>();
        allQuoteSpans.addAll(spans.raw());
        allQuoteSpans.addAll(spans.ansiC());
        allQuoteSpans.addAll(spans.dbl());
        allQuoteSpans.addAll(spans.heredoc());

        // withDoubleQuotes: remove single-quoted, ansiC, heredoc; keep double-quoted content
        Set<Integer> singleQuotePositions = buildPositionSet(spans.raw());
        singleQuotePositions.addAll(buildPositionSet(spans.ansiC()));
        singleQuotePositions.addAll(buildPositionSet(spans.heredoc()));

        Set<Integer> doubleQuoteDelimPositions = new java.util.HashSet<>();
        for (int[] span : spans.dbl()) {
            doubleQuoteDelimPositions.add(span[0]);     // opening "
            doubleQuoteDelimPositions.add(span[1] - 1); // closing "
        }

        StringBuilder withDoubleQuotes = new StringBuilder();
        for (int i = 0; i < command.length(); i++) {
            if (singleQuotePositions.contains(i)) continue;
            if (doubleQuoteDelimPositions.contains(i)) continue;
            withDoubleQuotes.append(command.charAt(i));
        }

        String fullyUnquoted = removeSpans(command, allQuoteSpans);

        List<SpanWithQuotes> spansWithQuoteChars = new ArrayList<>();
        for (int[] s : spans.raw())    spansWithQuoteChars.add(new SpanWithQuotes(s[0], s[1], "'",  "'"));
        for (int[] s : spans.ansiC())  spansWithQuoteChars.add(new SpanWithQuotes(s[0], s[1], "$'", "'"));
        for (int[] s : spans.dbl())    spansWithQuoteChars.add(new SpanWithQuotes(s[0], s[1], "\"", "\""));
        for (int[] s : spans.heredoc())spansWithQuoteChars.add(new SpanWithQuotes(s[0], s[1], "",   ""));
        String unquotedKeepQuoteChars = replaceSpansKeepQuotes(command, spansWithQuoteChars);

        return new QuoteContext(withDoubleQuotes.toString(), fullyUnquoted, unquotedKeepQuoteChars);
    }

    /**
     * Extracts compound command structure from the AST.
     * Translated from extractCompoundStructure() in treeSitterAnalysis.ts
     */
    public static CompoundStructure extractCompoundStructure(TreeSitterNode rootNode, String command) {
        List<String> operators = new ArrayList<>();
        List<String> segments  = new ArrayList<>();
        boolean[] flags = {false, false, false}; // hasPipeline, hasSubshell, hasCommandGroup

        walkTopLevel(rootNode, operators, segments, flags);

        if (segments.isEmpty()) segments.add(command);

        return new CompoundStructure(
            !operators.isEmpty(), flags[0], flags[1], flags[2],
            List.copyOf(operators), List.copyOf(segments)
        );
    }

    private static final Set<String> CONTROL_FLOW_TYPES = Set.of(
        "if_statement", "while_statement", "for_statement", "case_statement", "function_definition"
    );

    private static void walkTopLevel(
            TreeSitterNode node,
            List<String> operators,
            List<String> segments,
            boolean[] flags) { // [hasPipeline, hasSubshell, hasCommandGroup]
        for (TreeSitterNode child : node.children()) {
            if (child == null) continue;
            switch (child.type()) {
                case "list" -> {
                    for (TreeSitterNode lc : child.children()) {
                        if (lc == null) continue;
                        if ("&&".equals(lc.type()) || "||".equals(lc.type())) {
                            operators.add(lc.type());
                        } else if ("list".equals(lc.type()) || "redirected_statement".equals(lc.type())) {
                            walkTopLevel(singleChildNode(child, lc), operators, segments, flags);
                        } else if ("pipeline".equals(lc.type())) {
                            flags[0] = true; segments.add(lc.text());
                        } else if ("subshell".equals(lc.type())) {
                            flags[1] = true; segments.add(lc.text());
                        } else if ("compound_statement".equals(lc.type())) {
                            flags[2] = true; segments.add(lc.text());
                        } else {
                            segments.add(lc.text());
                        }
                    }
                }
                case ";" -> operators.add(";");
                case "pipeline" -> { flags[0] = true; segments.add(child.text()); }
                case "subshell" -> { flags[1] = true; segments.add(child.text()); }
                case "compound_statement" -> { flags[2] = true; segments.add(child.text()); }
                case "command", "declaration_command", "variable_assignment" -> segments.add(child.text());
                case "redirected_statement" -> {
                    boolean foundInner = false;
                    for (TreeSitterNode inner : child.children()) {
                        if (inner == null || "file_redirect".equals(inner.type())) continue;
                        foundInner = true;
                        walkTopLevel(singleChildNode(child, inner), operators, segments, flags);
                    }
                    if (!foundInner) segments.add(child.text());
                }
                case "negated_command" -> {
                    segments.add(child.text());
                    walkTopLevel(child, operators, segments, flags);
                }
                default -> {
                    if (CONTROL_FLOW_TYPES.contains(child.type())) {
                        segments.add(child.text());
                        walkTopLevel(child, operators, segments, flags);
                    }
                }
            }
        }
    }

    /** Creates a synthetic parent node exposing only a single child — used to recurse into nested list/redirected. */
    private static TreeSitterNode singleChildNode(TreeSitterNode parent, TreeSitterNode single) {
        return new TreeSitterNode() {
            public String type()         { return parent.type(); }
            public String text()         { return parent.text(); }
            public int startIndex()      { return parent.startIndex(); }
            public int endIndex()        { return parent.endIndex(); }
            public List<TreeSitterNode> children() { return List.of(single); }
        };
    }

    /**
     * Checks whether the AST contains actual operator nodes (;, &&, ||).
     * Key function for eliminating find -exec \\; false positives.
     * Translated from hasActualOperatorNodes() in treeSitterAnalysis.ts
     */
    public static boolean hasActualOperatorNodes(TreeSitterNode rootNode) {
        return walkForOperators(rootNode);
    }

    private static boolean walkForOperators(TreeSitterNode node) {
        switch (node.type()) {
            case ";", "&&", "||", "list" -> { return true; }
        }
        for (TreeSitterNode child : node.children()) {
            if (child != null && walkForOperators(child)) return true;
        }
        return false;
    }

    /**
     * Extracts dangerous pattern information from the AST.
     * Translated from extractDangerousPatterns() in treeSitterAnalysis.ts
     */
    public static DangerousPatterns extractDangerousPatterns(TreeSitterNode rootNode) {
        boolean[] flags = new boolean[5]; // [cmdSub, procSub, paramExp, heredoc, comment]
        walkForPatterns(rootNode, flags);
        return new DangerousPatterns(flags[0], flags[1], flags[2], flags[3], flags[4]);
    }

    private static void walkForPatterns(TreeSitterNode node, boolean[] flags) {
        switch (node.type()) {
            case "command_substitution"  -> flags[0] = true;
            case "process_substitution"  -> flags[1] = true;
            case "expansion"             -> flags[2] = true;
            case "heredoc_redirect"      -> flags[3] = true;
            case "comment"               -> flags[4] = true;
        }
        for (TreeSitterNode child : node.children()) {
            if (child != null) walkForPatterns(child, flags);
        }
    }

    /**
     * Performs complete tree-sitter analysis of a command in a single pass.
     * Translated from analyzeCommand() in treeSitterAnalysis.ts
     */
    public static Analysis analyzeCommand(TreeSitterNode rootNode, String command) {
        return new Analysis(
            extractQuoteContext(rootNode, command),
            extractCompoundStructure(rootNode, command),
            hasActualOperatorNodes(rootNode),
            extractDangerousPatterns(rootNode)
        );
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private static Set<Integer> buildPositionSet(List<int[]> spans) {
        Set<Integer> set = new java.util.HashSet<>();
        for (int[] span : spans) {
            for (int i = span[0]; i < span[1]; i++) set.add(i);
        }
        return set;
    }
}
