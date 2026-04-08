package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

/**
 * Heap dump command service — exposes the /heapdump slash-command action.
 * Translated from src/commands/heapdump/heapdump.ts
 *
 * <p>Performs the heap dump internally (logic merged from heapDumpService.ts)
 * and returns a text result matching the
 * {@code { type: 'text'; value: string }} shape from the TypeScript source.
 */
@Slf4j
@Service
public class HeapdumpService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(HeapdumpService.class);


    /**
     * Result record returned by {@link #call()}.
     * Translated from: { type: 'text'; value: string } in heapdump.ts
     */
    public record TextResult(String type, String value) {
        /** Convenience factory. */
        public static TextResult of(String value) {
            return new TextResult("text", value);
        }
    }

    /**
     * Perform a heap dump and return a text result.
     * Translated from call() in heapdump.ts
     *
     * <p>On success the value contains two newline-separated paths:
     * the heap snapshot path and the diagnostics report path (matching
     * the {@code `${result.heapPath}\n${result.diagPath}`} template in TS).
     * On failure the value contains a human-readable error message.
     *
     * @return A future resolving to a {@link TextResult}.
     */
    public CompletableFuture<TextResult> call() {
        return performHeapDump()
            .thenApply(result -> {
                if (!result.success()) {
                    return TextResult.of("Failed to create heap dump: " + result.error());
                }
                return TextResult.of(result.heapPath() + "\n" + result.diagPath());
            });
    }

    // =========================================================================
    // HeapDumpService logic (merged from src/utils/heapDumpService.ts)
    // =========================================================================

    /**
     * Result of a heap dump operation.
     * Translated from HeapDumpResult in heapDumpService.ts
     */
    public record HeapDumpResult(
            boolean success,
            String heapPath,
            String diagPath,
            String error
    ) {}

    /**
     * Perform a heap dump (heap snapshot + diagnostics report).
     * Translated from performHeapDump() in heapDumpService.ts
     *
     * @return a future resolving to a {@link HeapDumpResult}
     */
    public CompletableFuture<HeapDumpResult> performHeapDump() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                String baseDir = getClaudeConfigDir();
                new File(baseDir).mkdirs();

                String heapPath = baseDir + File.separator + "heap-" + timestamp + ".heapsnapshot";
                String diagPath = baseDir + File.separator + "diag-" + timestamp + ".json";

                // Write JVM diagnostics report
                String diagContent = buildDiagnosticReport();
                java.nio.file.Files.writeString(Path.of(diagPath), diagContent);

                // Attempt JVM heap dump via HotSpot MXBean if available
                try {
                    Class<?> beanClass = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
                    Object bean = ManagementFactory.getPlatformMXBean(
                            (Class<? extends java.lang.management.PlatformManagedObject>) beanClass);
                    beanClass.getMethod("dumpHeap", String.class, boolean.class)
                             .invoke(bean, heapPath, true);
                } catch (Exception e) {
                    // HotSpot bean unavailable — write an empty placeholder
                    java.nio.file.Files.writeString(Path.of(heapPath),
                            "{\"heapDump\":\"not available on this JVM: " + e.getMessage() + "\"}");
                }

                log.info("[heapDump] Heap snapshot: {}", heapPath);
                log.info("[heapDump] Diagnostics:   {}", diagPath);

                return new HeapDumpResult(true, heapPath, diagPath, null);

            } catch (Exception e) {
                log.error("[heapDump] Failed to create heap dump", e);
                return new HeapDumpResult(false, null, null, e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static String getClaudeConfigDir() {
        String dir = System.getenv("CLAUDE_CONFIG_HOME");
        if (dir != null && !dir.isBlank()) return dir;
        return System.getProperty("user.home") + File.separator + ".claude";
    }

    private static String buildDiagnosticReport() {
        Runtime rt = Runtime.getRuntime();
        long totalMem = rt.totalMemory();
        long freeMem = rt.freeMemory();
        long maxMem = rt.maxMemory();
        return String.format(
                "{\"timestamp\":\"%s\",\"javaVersion\":\"%s\",\"maxHeapMb\":%d,\"totalHeapMb\":%d,\"usedHeapMb\":%d}",
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").format(new Date()),
                System.getProperty("java.version"),
                maxMem / (1024 * 1024),
                totalMem / (1024 * 1024),
                (totalMem - freeMem) / (1024 * 1024));
    }
}
