package com.anthropic.claudecode.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * JSON utility functions.
 *
 * Merges three TypeScript sources:
 * <ul>
 *   <li>{@code src/utils/json.ts} — safeParseJSON, safeParseJSONC, parseJSONL,
 *       readJSONLFile, addItemToJSONCArray</li>
 *   <li>{@code src/cli/ndjsonSafeStringify.ts} — NDJSON-safe serialisation that
 *       escapes U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR</li>
 *   <li>{@code src/utils/jsonRead.ts} — stripBOM</li>
 * </ul>
 *
 * Translated from src/utils/json.ts, src/cli/ndjsonSafeStringify.ts,
 * and src/utils/jsonRead.ts
 */
@Slf4j
public class JsonUtils {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JsonUtils.class);


    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // =========================================================================
    // NDJSON-safe serialisation  (src/cli/ndjsonSafeStringify.ts)
    // =========================================================================

    /**
     * Pattern matching U+2028 LINE SEPARATOR and U+2029 PARAGRAPH SEPARATOR.
     *
     * <p>JSON.stringify (and Jackson) emit these characters raw (valid per ECMA-404),
     * but any receiver that uses JavaScript / line-oriented line-terminator
     * semantics will split the stream on them, silently truncating the message.
     * Escaping them to {@code \u2028} / {@code \u2029} produces equivalent JSON
     * (parses to the same string) that can never be confused with a line break.
     *
     * <p>Translated from the {@code JS_LINE_TERMINATORS} regex in ndjsonSafeStringify.ts.
     */
    private static final Pattern JS_LINE_TERMINATORS = Pattern.compile("[\u2028\u2029]");

    /**
     * Escape U+2028 and U+2029 in a JSON string so that line-splitting receivers
     * cannot break the message.
     * Translated from {@code escapeJsLineTerminators()} in ndjsonSafeStringify.ts.
     */
    public static String escapeJsLineTerminators(String json) {
        if (json == null) return null;
        return JS_LINE_TERMINATORS.matcher(json)
            .replaceAll(mr -> {
                char c = mr.group().charAt(0);
                return c == '\u2028' ? "\\\\u2028" : "\\\\u2029";
            });
    }

    /**
     * JSON.stringify for one-message-per-line (NDJSON) transports.
     *
     * Serialises {@code value} to JSON and escapes U+2028 / U+2029 so the
     * output cannot be broken by a line-splitting receiver.  The result is
     * still valid JSON and parses back to the same value.
     *
     * Translated from {@code ndjsonSafeStringify(value)} in ndjsonSafeStringify.ts.
     */
    public static String ndjsonSafeStringify(Object value) {
        return escapeJsLineTerminators(jsonStringify(value));
    }

    // =========================================================================
    // General JSON helpers  (src/utils/json.ts)
    // =========================================================================

    /**
     * Safely parse a JSON string.
     * Translated from safeParseJSON() in json.ts.
     */
    public static Optional<Object> safeParseJSON(String json) {
        if (json == null || json.isBlank()) return Optional.empty();

        try {
            String stripped = stripBOM(json);
            Object value = OBJECT_MAPPER.readValue(stripped, Object.class);
            return Optional.ofNullable(value);
        } catch (JsonProcessingException e) {
            log.debug("JSON parse error: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Serialize a value to a JSON string.
     * Translated from jsonStringify() in slowOperations.ts.
     */
    public static String jsonStringify(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("JSON stringify error: {}", e.getMessage());
            return "null";
        }
    }

    /**
     * Pretty-print a value as a JSON string.
     */
    public static String jsonPrettyPrint(Object value) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("JSON pretty print error: {}", e.getMessage());
            return "null";
        }
    }

    /**
     * Strip BOM (Byte Order Mark) from a string.
     * Translated from stripBOM() in jsonRead.ts.
     */
    public static String stripBOM(String s) {
        if (s != null && s.startsWith("\uFEFF")) {
            return s.substring(1);
        }
        return s;
    }

    // =========================================================================
    // safeParseJSONC  (src/utils/json.ts)
    // =========================================================================

    /**
     * Safely parse JSON-with-comments (JSONC).
     *
     * <p>Useful for VS Code configuration files like {@code keybindings.json}
     * which support single-line ({@code //}) and block ({@code /* *\/}) comments.
     * In Java we strip comments with a regex before handing off to Jackson.</p>
     *
     * Translated from {@code safeParseJSONC()} in json.ts
     */
    public static Optional<Object> safeParseJSONC(String json) {
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            String stripped = stripBOM(json);
            // Strip single-line comments (//) and block comments (/* ... */)
            String withoutComments = stripJsonComments(stripped);
            Object value = OBJECT_MAPPER.readValue(withoutComments, Object.class);
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.debug("JSONC parse error: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Strip single-line ({@code //}) and block ({@code /* ... *\/}) comments
     * from a JSONC string while preserving string literal content.
     *
     * <p>This is a best-effort implementation that handles the common JSONC cases
     * found in VS Code configuration files. It does NOT handle edge cases like
     * comment markers inside regex literals (which don't occur in JSON).</p>
     */
    private static String stripJsonComments(String jsonc) {
        StringBuilder result = new StringBuilder(jsonc.length());
        int i = 0;
        int len = jsonc.length();
        while (i < len) {
            char c = jsonc.charAt(i);
            if (c == '"') {
                // String literal — copy verbatim, handling escape sequences
                result.append(c);
                i++;
                while (i < len) {
                    char sc = jsonc.charAt(i);
                    result.append(sc);
                    if (sc == '\\' && i + 1 < len) {
                        result.append(jsonc.charAt(i + 1));
                        i += 2;
                    } else {
                        i++;
                        if (sc == '"') break;
                    }
                }
            } else if (c == '/' && i + 1 < len) {
                char next = jsonc.charAt(i + 1);
                if (next == '/') {
                    // Single-line comment — skip to end of line
                    i += 2;
                    while (i < len && jsonc.charAt(i) != '\n') i++;
                } else if (next == '*') {
                    // Block comment — skip to */
                    i += 2;
                    while (i + 1 < len && !(jsonc.charAt(i) == '*' && jsonc.charAt(i + 1) == '/')) i++;
                    i += 2;
                } else {
                    result.append(c);
                    i++;
                }
            } else {
                result.append(c);
                i++;
            }
        }
        return result.toString();
    }

    // =========================================================================
    // JSONL parsing  (src/utils/json.ts)
    // =========================================================================

    /**
     * Maximum bytes read from a large JSONL file (100 MB).
     * Translated from {@code MAX_JSONL_READ_BYTES} in json.ts
     */
    private static final long MAX_JSONL_READ_BYTES = 100L * 1024 * 1024;

    /**
     * Parse JSONL data from a string, skipping malformed lines.
     *
     * @param data newline-delimited JSON string
     * @param type target type for each line
     * @return list of successfully parsed objects
     * Translated from {@code parseJSONL()} in json.ts
     */
    public static <T> List<T> parseJSONL(String data, Class<T> type) {
        if (data == null || data.isEmpty()) return Collections.emptyList();
        String stripped = stripBOM(data);
        List<T> results = new ArrayList<>();
        int start = 0;
        int len = stripped.length();
        while (start < len) {
            int end = stripped.indexOf('\n', start);
            if (end == -1) end = len;
            String line = stripped.substring(start, end).trim();
            start = end + 1;
            if (line.isEmpty()) continue;
            try {
                results.add(OBJECT_MAPPER.readValue(line, type));
            } catch (Exception e) {
                // Skip malformed lines — matches TypeScript behaviour
            }
        }
        return results;
    }

    /**
     * Parse JSONL data from a string into a list of generic objects (Maps / Lists).
     *
     * Translated from {@code parseJSONL()} in json.ts
     */
    public static List<Object> parseJSONL(String data) {
        return parseJSONL(data, Object.class);
    }

    /**
     * Reads and parses a JSONL file, reading at most the last 100 MB.
     *
     * <p>For files larger than 100 MB, reads the tail and skips the first
     * partial line — mirrors the TypeScript implementation exactly.</p>
     *
     * Translated from {@code readJSONLFile()} in json.ts
     */
    public static <T> CompletableFuture<List<T>> readJSONLFile(Path filePath, Class<T> type) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                long size = Files.size(filePath);
                if (size <= MAX_JSONL_READ_BYTES) {
                    byte[] bytes = Files.readAllBytes(filePath);
                    return parseJSONL(new String(bytes, StandardCharsets.UTF_8), type);
                }

                // File is larger than 100 MB: read the tail
                long fileOffset = size - MAX_JSONL_READ_BYTES;
                byte[] buf = new byte[(int) MAX_JSONL_READ_BYTES];
                try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
                    raf.seek(fileOffset);
                    int totalRead = 0;
                    while (totalRead < buf.length) {
                        int n = raf.read(buf, totalRead, buf.length - totalRead);
                        if (n == -1) break;
                        totalRead += n;
                    }
                    // Skip the first partial line
                    int newlineIndex = -1;
                    for (int i = 0; i < totalRead; i++) {
                        if (buf[i] == 0x0A) { newlineIndex = i; break; }
                    }
                    String content;
                    if (newlineIndex != -1 && newlineIndex < totalRead - 1) {
                        content = new String(buf, newlineIndex + 1, totalRead - newlineIndex - 1, StandardCharsets.UTF_8);
                    } else {
                        content = new String(buf, 0, totalRead, StandardCharsets.UTF_8);
                    }
                    return parseJSONL(content, type);
                }
            } catch (IOException e) {
                log.error("readJSONLFile error for {}: {}", filePath, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    // =========================================================================
    // addItemToJSONCArray  (src/utils/json.ts)
    // =========================================================================

    /**
     * Add a new item to a JSON(C) array string, preserving existing content.
     *
     * <p>When the content is empty or not a valid JSON array, a new array
     * containing only {@code newItem} is returned. When the content is a valid
     * array, the item is appended. Comments are stripped before parsing but the
     * output is plain JSON (no comment preservation — matches TypeScript fallback
     * behaviour when the jsonc-parser library is unavailable).</p>
     *
     * Translated from {@code addItemToJSONCArray()} in json.ts
     */
    @SuppressWarnings("unchecked")
    public static String addItemToJSONCArray(String content, Object newItem) {
        try {
            if (content == null || content.trim().isEmpty()) {
                List<Object> fresh = new ArrayList<>();
                fresh.add(newItem);
                return jsonPrettyPrint(fresh);
            }

            String clean = stripBOM(content);
            String withoutComments = stripJsonComments(clean);
            Object parsed = OBJECT_MAPPER.readValue(withoutComments, Object.class);

            if (parsed instanceof List<?> list) {
                List<Object> copy = new ArrayList<>((List<Object>) list);
                copy.add(newItem);
                return jsonPrettyPrint(copy);
            } else {
                // Not an array — replace entirely
                List<Object> fresh = new ArrayList<>();
                fresh.add(newItem);
                return jsonPrettyPrint(fresh);
            }
        } catch (Exception e) {
            log.error("addItemToJSONCArray error: {}", e.getMessage());
            List<Object> fresh = new ArrayList<>();
            fresh.add(newItem);
            return jsonPrettyPrint(fresh);
        }
    }

    private JsonUtils() {}
}
