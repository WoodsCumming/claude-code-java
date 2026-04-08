package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * VCR (Video Cassette Recorder) service for test fixture management.
 * Translated from src/services/vcr.ts
 *
 * Caches and replays API responses for testing.
 * When VCR is enabled (NODE_ENV=test or USER_TYPE=ant + FORCE_VCR=1), responses
 * are read from fixture files on disk. Missing fixtures in CI fail loudly unless
 * VCR_RECORD=1 is set.
 */
@Slf4j
@Service
public class VcrService {



    private final ObjectMapper objectMapper;

    @Autowired
    public VcrService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Whether VCR mode is currently active.
     * Translated from shouldUseVCR() in vcr.ts
     */
    public boolean shouldUseVCR() {
        if ("test".equals(System.getenv("NODE_ENV"))) return true;
        if ("ant".equals(System.getenv("USER_TYPE"))
                && isTruthy(System.getenv("FORCE_VCR"))) return true;
        return false;
    }

    /**
     * Generic fixture management helper.
     * Handles caching, reading, writing fixtures for any data type.
     * Translated from withFixture() in vcr.ts
     */
    public <T> T withFixture(Object input, String fixtureName, Class<T> type,
                              Callable<T> fn) throws Exception {
        if (!shouldUseVCR()) return fn.call();

        String hash = sha1Hex(jsonStringify(input)).substring(0, 12);
        Path filename = fixturesRoot()
                .resolve("fixtures/" + fixtureName + "-" + hash + ".json");

        // Fetch cached fixture
        if (Files.exists(filename)) {
            try {
                String content = Files.readString(filename);
                log.debug("[VCR] Cache hit: {}", filename);
                return objectMapper.readValue(content, type);
            } catch (Exception e) {
                log.debug("[VCR] Cache read failed ({}): {}", filename, e.getMessage());
            }
        }

        // In CI without VCR_RECORD → fail loudly
        boolean isCI = isTruthy(System.getenv("CI"));
        boolean vcrRecord = isTruthy(System.getenv("VCR_RECORD"));
        if (isCI && !vcrRecord) {
            throw new IllegalStateException(
                "Fixture missing: " + filename +
                ". Re-run tests with VCR_RECORD=1, then commit the result.");
        }

        // Execute and write new fixture
        T result = fn.call();
        Files.createDirectories(filename.getParent());
        Files.writeString(filename, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(result));
        log.debug("[VCR] Cache written: {}", filename);
        return result;
    }

    /**
     * VCR wrapper for API call results represented as lists of messages.
     * Translated from withVCR() in vcr.ts
     */
    public <T> List<T> withVCR(List<?> messages, Class<T> messageType,
                                Callable<List<T>> fn) throws Exception {
        if (!shouldUseVCR()) return fn.call();

        // Dehydrate message content for deterministic fixture keys
        String dehydrated = dehydrateValue(jsonStringify(messages));
        String[] parts = dehydratedHashParts(dehydrated);
        String hashSegment = Arrays.stream(parts)
                .map(p -> sha1Hex(jsonStringify(p)).substring(0, 6))
                .collect(Collectors.joining("-"));
        Path filename = fixturesRoot().resolve("fixtures/" + hashSegment + ".json");

        // Fetch cached fixture
        if (Files.exists(filename)) {
            try {
                String content = Files.readString(filename);
                log.debug("[VCR] Cache hit: {}", filename);
                @SuppressWarnings("unchecked")
                Map<String, Object> cached = objectMapper.readValue(content, Map.class);
                @SuppressWarnings("unchecked")
                List<?> output = (List<?>) cached.get("output");
                if (output != null) {
                    return output.stream()
                            .map(item -> objectMapper.convertValue(item, messageType))
                            .collect(Collectors.toList());
                }
            } catch (Exception e) {
                log.debug("[VCR] Cache read failed ({}): {}", filename, e.getMessage());
            }
        }

        boolean isCI = isTruthy(System.getenv("CI"));
        boolean vcrRecord = isTruthy(System.getenv("VCR_RECORD"));
        if (isCI && !vcrRecord) {
            throw new IllegalStateException(
                "Anthropic API fixture missing: " + filename +
                ". Re-run tests with VCR_RECORD=1, then commit the result.");
        }

        List<T> results = fn.call();

        Files.createDirectories(filename.getParent());
        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("input", objectMapper.readValue(dehydrated, Object.class));
        fixture.put("output", results);
        Files.writeString(filename,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(fixture));
        return results;
    }

    /**
     * VCR wrapper for token count calls.
     * Translated from withTokenCountVCR() in vcr.ts
     */
    public Integer withTokenCountVCR(Object messages, Object tools,
                                      Callable<Integer> fn) throws Exception {
        // Dehydrate and normalize for stable fixture keys
        String raw = jsonStringify(Map.of("messages", messages, "tools", tools));
        String cwd = getCwd();
        String cwdSlug = cwd.replaceAll("[^a-zA-Z0-9]", "-");
        String dehydrated = dehydrateValue(raw)
                .replaceAll(Pattern.quote(cwdSlug), "[CWD_SLUG]")
                .replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                        "[UUID]")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z?",
                        "[TIMESTAMP]");

        return withFixture(dehydrated, "token-count",
                objectMapper.getTypeFactory()
                        .constructType(Map.class),
                () -> {
                    Integer count = fn.call();
                    return count;
                },
                result -> ((Map<?, ?>) result).get("tokenCount") != null
                        ? ((Number)((Map<?, ?>) result).get("tokenCount")).intValue()
                        : null,
                count -> Map.of("tokenCount", count));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Fixture helper that stores/retrieves via a wrap/unwrap function pair.
     */
    private <T> T withFixture(Object input, String fixtureName,
                               com.fasterxml.jackson.databind.JavaType storedType,
                               Callable<T> fn,
                               java.util.function.Function<Object, T> unwrap,
                               java.util.function.Function<T, Object> wrap) throws Exception {
        if (!shouldUseVCR()) return fn.call();

        String hash = sha1Hex(jsonStringify(input)).substring(0, 12);
        Path filename = fixturesRoot()
                .resolve("fixtures/" + fixtureName + "-" + hash + ".json");

        if (Files.exists(filename)) {
            try {
                String content = Files.readString(filename);
                Object stored = objectMapper.readValue(content, storedType);
                return unwrap.apply(stored);
            } catch (Exception e) {
                log.debug("[VCR] Cache read failed ({}): {}", filename, e.getMessage());
            }
        }

        boolean isCI = isTruthy(System.getenv("CI"));
        boolean vcrRecord = isTruthy(System.getenv("VCR_RECORD"));
        if (isCI && !vcrRecord) {
            throw new IllegalStateException(
                "Fixture missing: " + filename +
                ". Re-run tests with VCR_RECORD=1, then commit the result.");
        }

        T result = fn.call();
        Files.createDirectories(filename.getParent());
        Files.writeString(filename,
                objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(wrap.apply(result)));
        return result;
    }

    /**
     * Dehydrate a string value by replacing cwd, config home, and dynamic values
     * with stable placeholders.
     * Translated from dehydrateValue() in vcr.ts
     */
    String dehydrateValue(String s) {
        if (s == null) return null;
        String cwd = getCwd();
        String configHome = getClaudeConfigHomeDir();
        String result = s
                .replaceAll("num_files=\"\\d+\"", "num_files=\"[NUM]\"")
                .replaceAll("duration_ms=\"\\d+\"", "duration_ms=\"[DURATION]\"")
                .replaceAll("cost_usd=\"\\d+\"", "cost_usd=\"[COST]\"")
                .replace(configHome, "[CONFIG_HOME]")
                .replace(cwd, "[CWD]")
                .replaceAll("Available commands:.+", "Available commands: [COMMANDS]");
        if (result.contains("Files modified by user:")) {
            return "Files modified by user: [FILES]";
        }
        return result;
    }

    /**
     * Hydrate a VCR fixture string by restoring real paths.
     * Translated from hydrateValue() in vcr.ts
     */
    String hydrateValue(String s) {
        if (s == null) return s;
        return s
                .replace("[NUM]", "1")
                .replace("[DURATION]", "100")
                .replace("[CONFIG_HOME]", getClaudeConfigHomeDir())
                .replace("[CWD]", getCwd());
    }

    private String[] dehydratedHashParts(String dehydrated) {
        // Split messages array for hashing individual entries
        try {
            Object parsed = objectMapper.readValue(dehydrated, Object.class);
            if (parsed instanceof List<?> list) {
                return list.stream()
                        .map(this::jsonStringify)
                        .toArray(String[]::new);
            }
        } catch (Exception ignored) {}
        return new String[]{dehydrated};
    }

    private String jsonStringify(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private String sha1Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private Path fixturesRoot() {
        String root = System.getenv("CLAUDE_CODE_TEST_FIXTURES_ROOT");
        return Paths.get(root != null ? root : getCwd());
    }

    private String getCwd() {
        return System.getProperty("user.dir");
    }

    private String getClaudeConfigHomeDir() {
        String home = System.getenv("CLAUDE_CONFIG_HOME");
        if (home != null) return home;
        String userHome = System.getProperty("user.home");
        return userHome + "/.claude";
    }

    private boolean isTruthy(String val) {
        if (val == null) return false;
        return "1".equals(val) || "true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val);
    }
}
