package com.anthropic.claudecode.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formatting utilities for LSP (Language Server Protocol) tool results.
 * Translated from src/tools/LSPTool/formatters.ts
 *
 * <p>The Java counterpart uses plain data structures (records/maps) to mirror the
 * vscode-languageserver-types shapes used in the TypeScript source.</p>
 */
@Slf4j
public final class LspFormatters {



    private LspFormatters() {}

    // -----------------------------------------------------------------------
    // LSP data types (mirrors vscode-languageserver-types)
    // -----------------------------------------------------------------------

    public record Position(int line, int character) {}

    public record Range(Position start, Position end) {}

    public record Location(String uri, Range range) {}

    public record LocationLink(String targetUri, Range targetRange, Range targetSelectionRange) {}

    /**
     * Hover result from the LSP server.
     * {@code contents} may hold a String, a {@link MarkedString}, a list of
     * {@link MarkedString}, or a {@link MarkupContent}.
     */
    public record Hover(Object contents, Range range) {}

    public record MarkedString(String language, String value) {}

    public record MarkupContent(String kind, String value) {}

    public record DocumentSymbol(
            String name,
            String detail,
            int kind,
            Range range,
            Range selectionRange,
            List<DocumentSymbol> children) {}

    public record SymbolInformation(
            String name,
            int kind,
            Location location,
            String containerName) {}

    public record CallHierarchyItem(
            String name,
            int kind,
            String uri,
            Range range,
            Range selectionRange,
            String detail) {}

    public record CallHierarchyIncomingCall(
            CallHierarchyItem from,
            List<Range> fromRanges) {}

    public record CallHierarchyOutgoingCall(
            CallHierarchyItem to,
            List<Range> fromRanges) {}

    // -----------------------------------------------------------------------
    // URI / path helpers
    // -----------------------------------------------------------------------

    /**
     * Formats a URI as a human-readable file path, optionally making it relative
     * to {@code cwd}.  Falls back gracefully on malformed input.
     */
    static String formatUri(String uri, String cwd) {
        if (uri == null || uri.isEmpty()) {
            log.warn("formatUri called with null/empty URI — indicates malformed LSP server response");
            return "<unknown location>";
        }

        // Strip file:// protocol
        String filePath = uri.replaceFirst("^file://", "");

        // On Windows: file:///C:/path becomes /C:/path — strip leading slash
        if (filePath.matches("^/[A-Za-z]:.*")) {
            filePath = filePath.substring(1);
        }

        // Decode percent-encoding
        try {
            filePath = URLDecoder.decode(filePath, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Failed to decode LSP URI '{}': {}. Using un-decoded path: {}", uri, e.getMessage(), filePath);
        }

        // Make relative if cwd is provided and the result is shorter
        if (cwd != null && !cwd.isEmpty()) {
            try {
                Path cwdPath  = Paths.get(cwd);
                Path filePt   = Paths.get(filePath);
                String rel    = cwdPath.relativize(filePt).toString().replace('\\', '/');
                if (rel.length() < filePath.length() && !rel.startsWith("../../")) {
                    return rel;
                }
            } catch (Exception ignored) {
                // Fall through to absolute path
            }
        }

        return filePath.replace('\\', '/');
    }

    /**
     * Formats a {@link Location} as {@code path:line:character}.
     */
    private static String formatLocation(Location location, String cwd) {
        String filePath  = formatUri(location.uri(), cwd);
        int line         = location.range().start().line() + 1;
        int character    = location.range().start().character() + 1;
        return filePath + ":" + line + ":" + character;
    }

    /**
     * Converts a {@link LocationLink} to a {@link Location}.
     */
    private static Location locationLinkToLocation(LocationLink link) {
        Range range = link.targetSelectionRange() != null
                ? link.targetSelectionRange()
                : link.targetRange();
        return new Location(link.targetUri(), range);
    }

    /**
     * Groups items by their file URI.  Accepts {@link Location} or
     * {@link SymbolInformation} instances.
     */
    private static <T> Map<String, List<T>> groupByFile(List<T> items, String cwd) {
        Map<String, List<T>> byFile = new LinkedHashMap<>();
        for (T item : items) {
            String uri;
            if (item instanceof Location loc) {
                uri = loc.uri();
            } else if (item instanceof SymbolInformation sym) {
                uri = sym.location().uri();
            } else if (item instanceof CallHierarchyIncomingCall call) {
                uri = call.from() != null ? call.from().uri() : null;
            } else if (item instanceof CallHierarchyOutgoingCall call) {
                uri = call.to() != null ? call.to().uri() : null;
            } else {
                continue;
            }
            String fp = formatUri(uri, cwd);
            byFile.computeIfAbsent(fp, k -> new ArrayList<>()).add(item);
        }
        return byFile;
    }

    // -----------------------------------------------------------------------
    // formatGoToDefinitionResult
    // -----------------------------------------------------------------------

    /**
     * Formats a go-to-definition result.  {@code result} may be a single
     * {@link Location}, a single {@link LocationLink}, a {@code List} of either,
     * or {@code null}.
     */
    public static String formatGoToDefinitionResult(Object result, String cwd) {
        final String noDefMsg =
                "No definition found. This may occur if the cursor is not on a symbol, "
                + "or if the definition is in an external library not indexed by the LSP server.";

        if (result == null) {
            return noDefMsg;
        }

        List<Location> locations = new ArrayList<>();

        if (result instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof LocationLink ll) {
                    locations.add(locationLinkToLocation(ll));
                } else if (item instanceof Location loc) {
                    locations.add(loc);
                }
            }
        } else if (result instanceof LocationLink ll) {
            locations.add(locationLinkToLocation(ll));
        } else if (result instanceof Location loc) {
            locations.add(loc);
        }

        // Filter invalid
        long invalidCount = locations.stream().filter(l -> l == null || l.uri() == null).count();
        if (invalidCount > 0) {
            log.warn("formatGoToDefinitionResult: Filtering out {} invalid location(s)", invalidCount);
        }
        locations.removeIf(l -> l == null || l.uri() == null);

        if (locations.isEmpty()) {
            return noDefMsg;
        }
        if (locations.size() == 1) {
            return "Defined in " + formatLocation(locations.get(0), cwd);
        }

        StringBuilder sb = new StringBuilder("Found " + locations.size() + " definitions:\n");
        for (Location loc : locations) {
            sb.append("  ").append(formatLocation(loc, cwd)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    // -----------------------------------------------------------------------
    // formatFindReferencesResult
    // -----------------------------------------------------------------------

    /**
     * Formats a find-references result.
     */
    public static String formatFindReferencesResult(List<Location> result, String cwd) {
        final String noRefMsg =
                "No references found. This may occur if the symbol has no usages, "
                + "or if the LSP server has not fully indexed the workspace.";

        if (result == null || result.isEmpty()) {
            return noRefMsg;
        }

        long invalidCount = result.stream().filter(l -> l == null || l.uri() == null).count();
        if (invalidCount > 0) {
            log.warn("formatFindReferencesResult: Filtering out {} invalid location(s)", invalidCount);
        }
        List<Location> valid = result.stream().filter(l -> l != null && l.uri() != null).toList();

        if (valid.isEmpty()) {
            return noRefMsg;
        }
        if (valid.size() == 1) {
            return "Found 1 reference:\n  " + formatLocation(valid.get(0), cwd);
        }

        Map<String, List<Location>> byFile = new LinkedHashMap<>();
        for (Location loc : valid) {
            byFile.computeIfAbsent(formatUri(loc.uri(), cwd), k -> new ArrayList<>()).add(loc);
        }

        List<String> lines = new ArrayList<>();
        lines.add("Found " + valid.size() + " references across " + byFile.size() + " files:");
        for (Map.Entry<String, List<Location>> entry : byFile.entrySet()) {
            lines.add("\n" + entry.getKey() + ":");
            for (Location loc : entry.getValue()) {
                int line      = loc.range().start().line() + 1;
                int character = loc.range().start().character() + 1;
                lines.add("  Line " + line + ":" + character);
            }
        }
        return String.join("\n", lines);
    }

    // -----------------------------------------------------------------------
    // formatHoverResult
    // -----------------------------------------------------------------------

    /**
     * Formats a hover result.
     */
    public static String formatHoverResult(Hover result, String cwd) {
        if (result == null) {
            return "No hover information available. This may occur if the cursor is not on a symbol, "
                    + "or if the LSP server has not fully indexed the file.";
        }

        String content = extractMarkupText(result.contents());

        if (result.range() != null) {
            int line      = result.range().start().line() + 1;
            int character = result.range().start().character() + 1;
            return "Hover info at " + line + ":" + character + ":\n\n" + content;
        }

        return content;
    }

    @SuppressWarnings("unchecked")
    private static String extractMarkupText(Object contents) {
        if (contents instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) {
                    parts.add(s);
                } else if (item instanceof MarkedString ms) {
                    parts.add(ms.value());
                }
            }
            return String.join("\n\n", parts);
        }
        if (contents instanceof String s) {
            return s;
        }
        if (contents instanceof MarkupContent mc) {
            return mc.value();
        }
        if (contents instanceof MarkedString ms) {
            return ms.value();
        }
        return contents != null ? contents.toString() : "";
    }

    // -----------------------------------------------------------------------
    // SymbolKind mapping
    // -----------------------------------------------------------------------

    private static String symbolKindToString(int kind) {
        return switch (kind) {
            case 1  -> "File";
            case 2  -> "Module";
            case 3  -> "Namespace";
            case 4  -> "Package";
            case 5  -> "Class";
            case 6  -> "Method";
            case 7  -> "Property";
            case 8  -> "Field";
            case 9  -> "Constructor";
            case 10 -> "Enum";
            case 11 -> "Interface";
            case 12 -> "Function";
            case 13 -> "Variable";
            case 14 -> "Constant";
            case 15 -> "String";
            case 16 -> "Number";
            case 17 -> "Boolean";
            case 18 -> "Array";
            case 19 -> "Object";
            case 20 -> "Key";
            case 21 -> "Null";
            case 22 -> "EnumMember";
            case 23 -> "Struct";
            case 24 -> "Event";
            case 25 -> "Operator";
            case 26 -> "TypeParameter";
            default -> "Unknown";
        };
    }

    // -----------------------------------------------------------------------
    // formatDocumentSymbolResult
    // -----------------------------------------------------------------------

    /**
     * Formats a document-symbol result.  Handles both
     * {@code DocumentSymbol[]} (hierarchical, with range) and
     * {@code SymbolInformation[]} (flat, with location.range) per LSP spec.
     */
    public static String formatDocumentSymbolResult(List<?> result, String cwd) {
        if (result == null || result.isEmpty()) {
            return "No symbols found in document. This may occur if the file is empty, "
                    + "not supported by the LSP server, or if the server has not fully indexed the file.";
        }

        Object first = result.get(0);
        if (first instanceof SymbolInformation) {
            @SuppressWarnings("unchecked")
            List<SymbolInformation> syms = (List<SymbolInformation>) result;
            return formatWorkspaceSymbolResult(syms, cwd);
        }

        // DocumentSymbol format
        List<String> lines = new ArrayList<>();
        lines.add("Document symbols:");
        for (Object item : result) {
            if (item instanceof DocumentSymbol ds) {
                lines.addAll(formatDocumentSymbolNode(ds, 0));
            }
        }
        return String.join("\n", lines);
    }

    private static List<String> formatDocumentSymbolNode(DocumentSymbol symbol, int indent) {
        List<String> lines = new ArrayList<>();
        String prefix = "  ".repeat(indent);
        String kind   = symbolKindToString(symbol.kind());

        String line = prefix + symbol.name() + " (" + kind + ")";
        if (symbol.detail() != null && !symbol.detail().isEmpty()) {
            line += " " + symbol.detail();
        }
        int symbolLine = symbol.range().start().line() + 1;
        line += " - Line " + symbolLine;
        lines.add(line);

        if (symbol.children() != null) {
            for (DocumentSymbol child : symbol.children()) {
                lines.addAll(formatDocumentSymbolNode(child, indent + 1));
            }
        }
        return lines;
    }

    // -----------------------------------------------------------------------
    // formatWorkspaceSymbolResult
    // -----------------------------------------------------------------------

    /**
     * Formats a workspace-symbol result (flat list).
     */
    public static String formatWorkspaceSymbolResult(List<SymbolInformation> result, String cwd) {
        if (result == null || result.isEmpty()) {
            return "No symbols found in workspace. This may occur if the workspace is empty, "
                    + "or if the LSP server has not finished indexing the project.";
        }

        long invalidCount = result.stream()
                .filter(s -> s == null || s.location() == null || s.location().uri() == null)
                .count();
        if (invalidCount > 0) {
            log.warn("formatWorkspaceSymbolResult: Filtering out {} invalid symbol(s)", invalidCount);
        }
        List<SymbolInformation> valid = result.stream()
                .filter(s -> s != null && s.location() != null && s.location().uri() != null)
                .toList();

        if (valid.isEmpty()) {
            return "No symbols found in workspace. This may occur if the workspace is empty, "
                    + "or if the LSP server has not finished indexing the project.";
        }

        List<String> lines = new ArrayList<>();
        lines.add("Found " + valid.size() + " " + plural(valid.size(), "symbol") + " in workspace:");

        Map<String, List<SymbolInformation>> byFile = new LinkedHashMap<>();
        for (SymbolInformation sym : valid) {
            byFile.computeIfAbsent(formatUri(sym.location().uri(), cwd), k -> new ArrayList<>()).add(sym);
        }

        for (Map.Entry<String, List<SymbolInformation>> entry : byFile.entrySet()) {
            lines.add("\n" + entry.getKey() + ":");
            for (SymbolInformation sym : entry.getValue()) {
                String kind   = symbolKindToString(sym.kind());
                int lineNum   = sym.location().range().start().line() + 1;
                String symLine = "  " + sym.name() + " (" + kind + ") - Line " + lineNum;
                if (sym.containerName() != null && !sym.containerName().isEmpty()) {
                    symLine += " in " + sym.containerName();
                }
                lines.add(symLine);
            }
        }
        return String.join("\n", lines);
    }

    // -----------------------------------------------------------------------
    // Call-hierarchy formatters
    // -----------------------------------------------------------------------

    private static String formatCallHierarchyItem(CallHierarchyItem item, String cwd) {
        if (item.uri() == null) {
            log.warn("formatCallHierarchyItem: CallHierarchyItem has undefined URI");
            return item.name() + " (" + symbolKindToString(item.kind()) + ") - <unknown location>";
        }
        String filePath = formatUri(item.uri(), cwd);
        int line        = item.range().start().line() + 1;
        String kind     = symbolKindToString(item.kind());
        String result   = item.name() + " (" + kind + ") - " + filePath + ":" + line;
        if (item.detail() != null && !item.detail().isEmpty()) {
            result += " [" + item.detail() + "]";
        }
        return result;
    }

    /**
     * Formats a prepareCallHierarchy result.
     */
    public static String formatPrepareCallHierarchyResult(List<CallHierarchyItem> result, String cwd) {
        if (result == null || result.isEmpty()) {
            return "No call hierarchy item found at this position";
        }
        if (result.size() == 1) {
            return "Call hierarchy item: " + formatCallHierarchyItem(result.get(0), cwd);
        }
        List<String> lines = new ArrayList<>();
        lines.add("Found " + result.size() + " call hierarchy items:");
        for (CallHierarchyItem item : result) {
            lines.add("  " + formatCallHierarchyItem(item, cwd));
        }
        return String.join("\n", lines);
    }

    /**
     * Formats incomingCalls result (callers of the target).
     */
    public static String formatIncomingCallsResult(List<CallHierarchyIncomingCall> result, String cwd) {
        if (result == null || result.isEmpty()) {
            return "No incoming calls found (nothing calls this function)";
        }

        List<String> lines = new ArrayList<>();
        lines.add("Found " + result.size() + " incoming " + plural(result.size(), "call") + ":");

        Map<String, List<CallHierarchyIncomingCall>> byFile = new LinkedHashMap<>();
        for (CallHierarchyIncomingCall call : result) {
            if (call.from() == null) {
                log.warn("formatIncomingCallsResult: CallHierarchyIncomingCall has undefined from field");
                continue;
            }
            byFile.computeIfAbsent(formatUri(call.from().uri(), cwd), k -> new ArrayList<>()).add(call);
        }

        for (Map.Entry<String, List<CallHierarchyIncomingCall>> entry : byFile.entrySet()) {
            lines.add("\n" + entry.getKey() + ":");
            for (CallHierarchyIncomingCall call : entry.getValue()) {
                if (call.from() == null) continue;
                String kind    = symbolKindToString(call.from().kind());
                int lineNum    = call.from().range().start().line() + 1;
                String callLine = "  " + call.from().name() + " (" + kind + ") - Line " + lineNum;
                if (call.fromRanges() != null && !call.fromRanges().isEmpty()) {
                    String sites = call.fromRanges().stream()
                            .map(r -> (r.start().line() + 1) + ":" + (r.start().character() + 1))
                            .reduce((a, b) -> a + ", " + b).orElse("");
                    callLine += " [calls at: " + sites + "]";
                }
                lines.add(callLine);
            }
        }
        return String.join("\n", lines);
    }

    /**
     * Formats outgoingCalls result (callees of the target).
     */
    public static String formatOutgoingCallsResult(List<CallHierarchyOutgoingCall> result, String cwd) {
        if (result == null || result.isEmpty()) {
            return "No outgoing calls found (this function calls nothing)";
        }

        List<String> lines = new ArrayList<>();
        lines.add("Found " + result.size() + " outgoing " + plural(result.size(), "call") + ":");

        Map<String, List<CallHierarchyOutgoingCall>> byFile = new LinkedHashMap<>();
        for (CallHierarchyOutgoingCall call : result) {
            if (call.to() == null) {
                log.warn("formatOutgoingCallsResult: CallHierarchyOutgoingCall has undefined to field");
                continue;
            }
            byFile.computeIfAbsent(formatUri(call.to().uri(), cwd), k -> new ArrayList<>()).add(call);
        }

        for (Map.Entry<String, List<CallHierarchyOutgoingCall>> entry : byFile.entrySet()) {
            lines.add("\n" + entry.getKey() + ":");
            for (CallHierarchyOutgoingCall call : entry.getValue()) {
                if (call.to() == null) continue;
                String kind    = symbolKindToString(call.to().kind());
                int lineNum    = call.to().range().start().line() + 1;
                String callLine = "  " + call.to().name() + " (" + kind + ") - Line " + lineNum;
                if (call.fromRanges() != null && !call.fromRanges().isEmpty()) {
                    String sites = call.fromRanges().stream()
                            .map(r -> (r.start().line() + 1) + ":" + (r.start().character() + 1))
                            .reduce((a, b) -> a + ", " + b).orElse("");
                    callLine += " [called from: " + sites + "]";
                }
                lines.add(callLine);
            }
        }
        return String.join("\n", lines);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Returns "word" for n==1 and "words" otherwise. */
    private static String plural(int n, String word) {
        return n == 1 ? word : word + "s";
    }
}
