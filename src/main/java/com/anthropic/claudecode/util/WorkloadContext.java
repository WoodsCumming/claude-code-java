package com.anthropic.claudecode.util;

/**
 * Workload context for tracking turn-scoped workload tags.
 * Translated from src/utils/workloadContext.ts
 */
public class WorkloadContext {

    public static final String WORKLOAD_CRON = "cron";

    private static final ThreadLocal<String> workloadStorage = new ThreadLocal<>();

    /**
     * Get the current workload.
     * Translated from getWorkload() in workloadContext.ts
     */
    public static String getWorkload() {
        return workloadStorage.get();
    }

    /**
     * Run a function with a workload context.
     * Translated from runWithWorkload() in workloadContext.ts
     */
    public static <T> T runWithWorkload(String workload, java.util.concurrent.Callable<T> fn) throws Exception {
        String previous = workloadStorage.get();
        workloadStorage.set(workload);
        try {
            return fn.call();
        } finally {
            if (previous != null) workloadStorage.set(previous);
            else workloadStorage.remove();
        }
    }

    private WorkloadContext() {}
}
