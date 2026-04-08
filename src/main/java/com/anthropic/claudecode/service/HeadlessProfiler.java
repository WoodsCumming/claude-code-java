package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.lang.management.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for heap dump / memory diagnostics capture.
 * Used by the /heapdump command.
 * Translated from src/utils/heapDumpService.ts
 */
@Slf4j
@Service
public class HeadlessProfiler {



    // =========================================================================
    // Types
    // =========================================================================

    /**
     * Result of a heap dump operation.
     * Translated from HeapDumpResult in heapDumpService.ts
     */
    public record HeapDumpResult(
        boolean success,
        String heapPath,     // nullable
        String diagPath,     // nullable
        String error         // nullable
    ) {
        public static HeapDumpResult success(String heapPath, String diagPath) {
            return new HeapDumpResult(true, heapPath, diagPath, null);
        }

        public static HeapDumpResult failure(String error) {
            return new HeapDumpResult(false, null, null, error);
        }
    }

    /**
     * Memory diagnostics trigger type.
     * Translated from trigger: 'manual' | 'auto-1.5GB' in heapDumpService.ts
     */
    public enum Trigger {
        MANUAL("manual"),
        AUTO_1_5GB("auto-1.5GB");

        private final String value;

        Trigger(String value) { this.value = value; }

        public String getValue() { return value; }
    }

    /**
     * V8 heap space equivalent for JVM memory pools.
     * Translated from v8HeapSpaces entry in MemoryDiagnostics
     */
    public record MemoryPoolInfo(String name, long size, long used, long available) {}

    /**
     * Memory diagnostics captured alongside a heap dump.
     * Helps identify whether the leak is in heap or off-heap (native) memory.
     * Translated from MemoryDiagnostics in heapDumpService.ts
     */
    public record MemoryDiagnostics(
        String timestamp,
        String sessionId,
        String trigger,
        int dumpNumber,
        double uptimeSeconds,
        MemoryUsageInfo memoryUsage,
        MemoryGrowthRate memoryGrowthRate,
        HeapStats heapStats,
        List<MemoryPoolInfo> memoryPools,
        ResourceUsageInfo resourceUsage,
        int activeThreads,
        Optional<String> platform,
        String jvmVersion,
        AnalysisInfo analysis
    ) {}

    public record MemoryUsageInfo(
        long heapUsed, long heapTotal, long nonHeap, long rss
    ) {}

    public record MemoryGrowthRate(double bytesPerSecond, double mbPerHour) {}

    public record HeapStats(
        long heapSizeLimit,
        long gcCount,
        long gcTime
    ) {}

    public record ResourceUsageInfo(long maxRss) {}

    public record AnalysisInfo(List<String> potentialLeaks, String recommendation) {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Capture memory diagnostics.
     * Translated from captureMemoryDiagnostics() in heapDumpService.ts
     */
    public MemoryDiagnostics captureMemoryDiagnostics(Trigger trigger, int dumpNumber) {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memMx = ManagementFactory.getMemoryMXBean();
        RuntimeMXBean runtimeMx = ManagementFactory.getRuntimeMXBean();

        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapTotal = runtime.totalMemory();
        long nonHeap = memMx.getNonHeapMemoryUsage().getUsed();
        double uptimeSeconds = runtimeMx.getUptime() / 1000.0;

        // Approximate RSS: heap + non-heap + off-heap
        long rss = heapUsed + nonHeap;

        double bytesPerSecond = uptimeSeconds > 0 ? rss / uptimeSeconds : 0;
        double mbPerHour = (bytesPerSecond * 3600) / (1024.0 * 1024);

        // GC statistics
        long gcCount = 0, gcTime = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() >= 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() >= 0) gcTime += gc.getCollectionTime();
        }

        // Memory pools (equivalent to V8 heap spaces)
        List<MemoryPoolInfo> pools = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage != null) {
                pools.add(new MemoryPoolInfo(
                    pool.getName(),
                    usage.getMax() < 0 ? usage.getCommitted() : usage.getMax(),
                    usage.getUsed(),
                    usage.getMax() < 0 ? 0 : usage.getMax() - usage.getUsed()
                ));
            }
        }

        // Potential leak indicators
        List<String> potentialLeaks = new ArrayList<>();
        int activeThreads = Thread.activeCount();
        if (activeThreads > 500) {
            potentialLeaks.add(activeThreads + " active threads — possible thread leak");
        }
        if (mbPerHour > 100) {
            potentialLeaks.add(String.format("High memory growth rate: %.1f MB/hour", mbPerHour));
        }

        String recommendation = potentialLeaks.isEmpty()
            ? "No obvious leak indicators. Check heap dump for retained objects."
            : "WARNING: " + potentialLeaks.size() + " potential leak indicator(s) found.";

        return new MemoryDiagnostics(
            Instant.now().toString(),
            "",  // sessionId — fill from context if available
            trigger.getValue(),
            dumpNumber,
            uptimeSeconds,
            new MemoryUsageInfo(heapUsed, heapTotal, nonHeap, rss),
            new MemoryGrowthRate(bytesPerSecond, mbPerHour),
            new HeapStats(runtime.maxMemory(), gcCount, gcTime),
            pools,
            new ResourceUsageInfo(rss),
            activeThreads,
            Optional.of(System.getProperty("os.name", "unknown")),
            System.getProperty("java.version", "unknown"),
            new AnalysisInfo(potentialLeaks, recommendation)
        );
    }

    /**
     * Core heap dump function — captures JVM heap dump + diagnostics to ~/Desktop.
     *
     * Diagnostics are written BEFORE the heap dump is captured, mirroring the
     * TS implementation's strategy of preserving useful info even if the dump fails.
     * Translated from performHeapDump() in heapDumpService.ts
     */
    public HeapDumpResult performHeapDump(Trigger trigger, int dumpNumber) {
        try {
            MemoryDiagnostics diagnostics = captureMemoryDiagnostics(trigger, dumpNumber);

            double heapGb = diagnostics.memoryUsage().heapUsed() / (1024.0 * 1024 * 1024);
            log.debug("[HeapDump] Memory state: heapUsed={} GB  {}",
                String.format("%.3f", heapGb),
                diagnostics.analysis().recommendation());

            String dumpDir = getDesktopPath();
            Files.createDirectories(Paths.get(dumpDir));

            String sessionId = "jvm-heapdump";
            String suffix = dumpNumber > 0 ? "-dump" + dumpNumber : "";
            String heapFilename = sessionId + suffix + ".hprof";
            String diagFilename = sessionId + suffix + "-diagnostics.json";
            String heapPath = Paths.get(dumpDir, heapFilename).toString();
            String diagPath = Paths.get(dumpDir, diagFilename).toString();

            // Write diagnostics first
            Files.writeString(Paths.get(diagPath), toJson(diagnostics),
                StandardCharsets.UTF_8);
            log.debug("[HeapDump] Diagnostics written to {}", diagPath);

            // Attempt JVM heap dump via HotSpot MBean
            writeHeapDump(heapPath);
            log.debug("[HeapDump] Heap dump written to {}", heapPath);

            return HeapDumpResult.success(heapPath, diagPath);

        } catch (Exception e) {
            log.error("[HeapDump] Failed: {}", e.getMessage(), e);
            return HeapDumpResult.failure(e.getMessage());
        }
    }

    /**
     * Convenience overload: manual trigger, dumpNumber 0.
     */
    public HeapDumpResult performHeapDump() {
        return performHeapDump(Trigger.MANUAL, 0);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Write a JVM heap dump (.hprof) using the HotSpot diagnostic MBean.
     * Falls back to a no-op with a warning if the MBean is unavailable.
     */
    private void writeHeapDump(String filePath) throws Exception {
        try {
            // com.sun.management.HotSpotDiagnosticMXBean is available on HotSpot/OpenJDK
            javax.management.MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            Object hotspotBean = ManagementFactory.newPlatformMXBeanProxy(
                server,
                "com.sun.management:type=HotSpotDiagnostic",
                Class.forName("com.sun.management.HotSpotDiagnosticMXBean")
                    .asSubclass(java.lang.management.PlatformManagedObject.class)
            );
            // Invoke dumpHeap(String outputFile, boolean live) via reflection
            hotspotBean.getClass()
                .getMethod("dumpHeap", String.class, boolean.class)
                .invoke(hotspotBean, filePath, true);
        } catch (Exception e) {
            log.warn("[HeapDump] HotSpot diagnostic MBean unavailable: {}. " +
                "Heap dump skipped; diagnostics file was still written.", e.getMessage());
        }
    }

    private String getDesktopPath() {
        String home = System.getProperty("user.home", "");
        return Paths.get(home, "Desktop").toString();
    }

    /**
     * Minimal JSON serialisation for MemoryDiagnostics.
     * In production you would use Jackson; this keeps the dependency surface small.
     */
    private String toJson(MemoryDiagnostics d) {
        return "{\n" +
            "  \"timestamp\": \"" + d.timestamp() + "\",\n" +
            "  \"trigger\": \"" + d.trigger() + "\",\n" +
            "  \"dumpNumber\": " + d.dumpNumber() + ",\n" +
            "  \"uptimeSeconds\": " + d.uptimeSeconds() + ",\n" +
            "  \"memoryUsage\": {\n" +
            "    \"heapUsed\": " + d.memoryUsage().heapUsed() + ",\n" +
            "    \"heapTotal\": " + d.memoryUsage().heapTotal() + ",\n" +
            "    \"nonHeap\": " + d.memoryUsage().nonHeap() + ",\n" +
            "    \"rss\": " + d.memoryUsage().rss() + "\n" +
            "  },\n" +
            "  \"memoryGrowthRate\": {\n" +
            "    \"bytesPerSecond\": " + d.memoryGrowthRate().bytesPerSecond() + ",\n" +
            "    \"mbPerHour\": " + d.memoryGrowthRate().mbPerHour() + "\n" +
            "  },\n" +
            "  \"jvmVersion\": \"" + d.jvmVersion() + "\",\n" +
            "  \"platform\": \"" + d.platform().orElse("unknown") + "\",\n" +
            "  \"activeThreads\": " + d.activeThreads() + ",\n" +
            "  \"analysis\": {\n" +
            "    \"recommendation\": \"" + d.analysis().recommendation() + "\"\n" +
            "  }\n" +
            "}";
    }
}
