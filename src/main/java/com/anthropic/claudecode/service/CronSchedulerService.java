package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.CronTasksLock;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/**
 * Non-React scheduler core for .claude/scheduled_tasks.json.
 *
 * Translated from src/utils/cronScheduler.ts
 *
 * Lifecycle:
 *  1. start() is called; if tasks exist the scheduler enables immediately,
 *     otherwise it polls until setEnabled(true) is called.
 *  2. On enable: acquire the per-project scheduler lock, load tasks from disk,
 *     start a file-watch loop, and begin ticking every second.
 *  3. On each tick (check()): fire tasks whose next-fire time has passed,
 *     reschedule recurring tasks, remove one-shot tasks.
 *  4. stop() tears everything down and releases the lock.
 */
@Slf4j
@Service
public class CronSchedulerService {


    /** Check interval in milliseconds — matches TS CHECK_INTERVAL_MS. */
    private static final long CHECK_INTERVAL_MS = 1_000L;
    /** How often non-owning sessions re-probe the scheduler lock (ms). */
    private static final long LOCK_PROBE_INTERVAL_MS = 5_000L;
    /** Recurring task max age: 7 days in ms — matches DEFAULT_CRON_JITTER_CONFIG. */
    public static final long DEFAULT_RECURRING_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1_000;

    // ---- Injected dependency for disk I/O ----
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CronTasksService cronTasksService;

    // ------------------------------------------------------------------
    // Public types
    // ------------------------------------------------------------------

    /**
     * A scheduled task entry.
     * Mirrors CronTask in cronTasks.ts
     */
    public static class CronTask {
        private String id;
        private String cron;
        private String prompt;
        private boolean recurring;
        private boolean permanent;
        private long createdAt;
        private Long lastFiredAt;
        public CronTask() {}
        public CronTask(String id, String cron, String prompt, boolean recurring, boolean permanent, long createdAt, Long lastFiredAt) {
            this.id = id; this.cron = cron; this.prompt = prompt; this.recurring = recurring;
            this.permanent = permanent; this.createdAt = createdAt; this.lastFiredAt = lastFiredAt;
        }
        public String getId() { return id; }
        public void setId(String v) { id = v; }
        public String getCron() { return cron; }
        public void setCron(String v) { cron = v; }
        public String getPrompt() { return prompt; }
        public void setPrompt(String v) { prompt = v; }
        public boolean isRecurring() { return recurring; }
        public void setRecurring(boolean v) { recurring = v; }
        public boolean isPermanent() { return permanent; }
        public void setPermanent(boolean v) { permanent = v; }
        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long v) { createdAt = v; }
        public Long getLastFiredAt() { return lastFiredAt; }
        public void setLastFiredAt(Long v) { lastFiredAt = v; }
    }

    /**
     * Options for creating a scheduler instance.
     * Mirrors CronSchedulerOptions in cronScheduler.ts
     */
    public static class CronSchedulerOptions {
        private Consumer<String> onFire;
        private BooleanSupplier isLoading;
        private boolean assistantMode;
        private Consumer<CronTask> onFireTask;
        private Consumer<List<CronTask>> onMissed;
        private String dir;
        private String lockIdentity;
        private Predicate<CronTask> filter;
        private long recurringMaxAgeMs = DEFAULT_RECURRING_MAX_AGE_MS;

        public CronSchedulerOptions() {}
        public Consumer<String> getOnFire() { return onFire; }
        public void setOnFire(Consumer<String> v) { onFire = v; }
        public BooleanSupplier getIsLoading() { return isLoading; }
        public void setIsLoading(BooleanSupplier v) { isLoading = v; }
        public boolean isAssistantMode() { return assistantMode; }
        public void setAssistantMode(boolean v) { assistantMode = v; }
        public Consumer<CronTask> getOnFireTask() { return onFireTask; }
        public void setOnFireTask(Consumer<CronTask> v) { onFireTask = v; }
        public Consumer<List<CronTask>> getOnMissed() { return onMissed; }
        public void setOnMissed(Consumer<List<CronTask>> v) { onMissed = v; }
        public String getDir() { return dir; }
        public void setDir(String v) { dir = v; }
        public String getLockIdentity() { return lockIdentity; }
        public void setLockIdentity(String v) { lockIdentity = v; }
        public Predicate<CronTask> getFilter() { return filter; }
        public void setFilter(Predicate<CronTask> v) { filter = v; }
        public long getRecurringMaxAgeMs() { return recurringMaxAgeMs; }
        public void setRecurringMaxAgeMs(long v) { recurringMaxAgeMs = v; }
    }

    /**
     * Handle returned by {@link #createCronScheduler}.
     * Mirrors the CronScheduler object shape from cronScheduler.ts
     */
    public interface CronScheduler {
        void start();
        void stop();
        /**
         * Epoch ms of the soonest scheduled fire across all loaded tasks,
         * or null if nothing is scheduled.
         */
        Long getNextFireTime();
    }

    // ------------------------------------------------------------------
    // Factory
    // ------------------------------------------------------------------

    /**
     * Creates a new CronScheduler with the provided options.
     * Mirrors createCronScheduler() in cronScheduler.ts
     */
    public CronScheduler createCronScheduler(CronSchedulerOptions options) {
        return new CronSchedulerImpl(options, cronTasksService);
    }

    // ------------------------------------------------------------------
    // Utilities
    // ------------------------------------------------------------------

    /**
     * Returns true when the recurring task was created more than {@code maxAgeMs} ago.
     * Permanent tasks never age out. maxAgeMs == 0 means unlimited.
     * Mirrors isRecurringTaskAged() in cronScheduler.ts
     */
    public static boolean isRecurringTaskAged(CronTask task, long nowMs, long maxAgeMs) {
        if (maxAgeMs == 0) return false;
        return task.isRecurring() && !task.isPermanent() && (nowMs - task.getCreatedAt()) >= maxAgeMs;
    }

    /**
     * Builds the missed-task notification text shown to the user.
     * Mirrors buildMissedTaskNotification() in cronScheduler.ts
     */
    public static String buildMissedTaskNotification(List<CronTask> missed) {
        boolean plural = missed.size() > 1;
        String header =
                "The following one-shot scheduled task" + (plural ? "s were" : " was") +
                " missed while Claude was not running. " +
                (plural ? "They have" : "It has") + " already been removed from .claude/scheduled_tasks.json.\n\n" +
                "Do NOT execute " + (plural ? "these prompts" : "this prompt") + " yet. " +
                "First use the AskUserQuestion tool to ask whether to run " +
                (plural ? "each one" : "it") + " now. " +
                "Only execute if the user confirms.";

        StringBuilder blocks = new StringBuilder();
        for (CronTask t : missed) {
            String meta = "[created " + Instant.ofEpochMilli(t.getCreatedAt()) + "]";
            // Use a fence longer than any backtick run in the prompt
            int longestRun = longestBacktickRun(t.getPrompt());
            String fence = "`".repeat(Math.max(3, longestRun + 1));
            if (blocks.length() > 0) blocks.append("\n\n");
            blocks.append(meta).append("\n").append(fence).append("\n")
                  .append(t.getPrompt()).append("\n").append(fence);
        }

        return header + "\n\n" + blocks;
    }

    // ------------------------------------------------------------------
    // Implementation
    // ------------------------------------------------------------------

    private static class CronSchedulerImpl implements CronScheduler {

        private final CronSchedulerOptions opts;
        private final CronTasksService cronTasksService;
        private final AtomicBoolean stopped = new AtomicBoolean(false);
        private final AtomicBoolean isOwner = new AtomicBoolean(false);

        /** Per-task next-fire times (epoch ms). Infinity → "never / in-flight". */
        private final Map<String, Long> nextFireAt = new ConcurrentHashMap<>();
        /** Tasks currently being removed from disk — prevents double-fire. */
        private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
        /** IDs for which a "missed task" prompt was already surfaced. */
        private final Set<String> missedAsked = ConcurrentHashMap.newKeySet();

        private volatile List<CronTask> tasks = new ArrayList<>();

        private ScheduledFuture<?> checkTimer;
        private ScheduledFuture<?> lockProbeTimer;
        private ScheduledFuture<?> enablePoll;
        private final ScheduledExecutorService scheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "cron-scheduler");
                    t.setDaemon(true);
                    return t;
                });

        CronSchedulerImpl(CronSchedulerOptions opts, CronTasksService cronTasksService) {
            this.opts = opts;
            this.cronTasksService = cronTasksService;
        }

        @Override
        public void start() {
            stopped.set(false);
            String dir = opts.getDir();
            log.debug("[ScheduledTasks] scheduler start() — dir={}", dir);

            // Daemon path: skip bootstrap state checks; enable immediately
            if (dir != null) {
                enable();
                return;
            }

            // Auto-enable if tasks already exist on disk
            if (hasCronTasksOnDisk(null) || opts.isAssistantMode()) {
                enable();
                return;
            }

            // Poll until setEnabled (CronCreateTool will flip this externally)
            enablePoll = scheduler.scheduleAtFixedRate(() -> {
                if (scheduledTasksEnabled.get()) {
                    cancelIfNotNull(enablePoll);
                    enablePoll = null;
                    enable();
                }
            }, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        public void stop() {
            stopped.set(true);
            cancelIfNotNull(enablePoll);
            cancelIfNotNull(checkTimer);
            cancelIfNotNull(lockProbeTimer);
            scheduler.shutdownNow();

            if (isOwner.getAndSet(false)) {
                buildLockOpts().ifPresentOrElse(
                        lo -> CronTasksLock.releaseSchedulerLock(lo).join(),
                        () -> CronTasksLock.releaseSchedulerLock().join());
            }
        }

        @Override
        public Long getNextFireTime() {
            long min = Long.MAX_VALUE;
            for (Long t : nextFireAt.values()) {
                if (t < min) min = t;
            }
            return min == Long.MAX_VALUE ? null : min;
        }

        // ------ enable / load / check ------

        private void enable() {
            if (stopped.get()) return;
            cancelIfNotNull(enablePoll);
            enablePoll = null;

            // Acquire scheduler lock
            CronTasksLock.SchedulerLockOptions lockOpts = buildLockOpts().orElse(null);
            boolean owned = CronTasksLock.tryAcquireSchedulerLock(lockOpts).join();
            isOwner.set(owned);

            if (!owned) {
                // Probe periodically to take over if the owner crashes
                lockProbeTimer = scheduler.scheduleAtFixedRate(() -> {
                    if (stopped.get()) return;
                    boolean acquired = CronTasksLock.tryAcquireSchedulerLock(lockOpts).join();
                    if (acquired) {
                        isOwner.set(true);
                        cancelIfNotNull(lockProbeTimer);
                        lockProbeTimer = null;
                    }
                }, LOCK_PROBE_INTERVAL_MS, LOCK_PROBE_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }

            loadTasks(true);

            checkTimer = scheduler.scheduleAtFixedRate(this::check,
                    CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }

        private void loadTasks(boolean initial) {
            List<CronTask> loaded = readCronTasksFromDisk(opts.getDir());
            if (stopped.get()) return;
            tasks = loaded;

            if (!initial) return;

            long now = System.currentTimeMillis();
            List<CronTask> missed = new ArrayList<>();
            for (CronTask t : loaded) {
                if (!t.isRecurring() && !missedAsked.contains(t.getId())) {
                    // A one-shot task whose fire time is in the past counts as missed
                    Long nextMs = simpleNextCronMs(t.getCron(), t.getCreatedAt());
                    if (nextMs != null && nextMs < now) {
                        if (opts.getFilter() == null || opts.getFilter().test(t)) {
                            missed.add(t);
                        }
                    }
                }
            }

            if (!missed.isEmpty()) {
                for (CronTask t : missed) {
                    missedAsked.add(t.getId());
                    nextFireAt.put(t.getId(), Long.MAX_VALUE); // Prevent re-fire
                }
                log.debug("[ScheduledTasks] surfaced {} missed one-shot task(s)", missed.size());
                if (opts.getOnMissed() != null) {
                    opts.getOnMissed().accept(missed);
                } else if (opts.getOnFire() != null) {
                    opts.getOnFire().accept(buildMissedTaskNotification(missed));
                }
                // Remove missed tasks from disk
                List<String> missedIds = missed.stream().map(CronTask::getId).toList();
                removeCronTasksFromDisk(missedIds, opts.getDir());
            }
        }

        private void check() {
            if (stopped.get()) return;
            if (opts.getIsLoading() != null && opts.getIsLoading().getAsBoolean() && !opts.isAssistantMode()) return;

            long now = System.currentTimeMillis();
            Set<String> seen = new HashSet<>();

            if (isOwner.get()) {
                List<CronTask> snapshot = tasks;
                List<String> firedRecurring = new ArrayList<>();
                for (CronTask t : snapshot) {
                    processSingleTask(t, false, now, seen, firedRecurring);
                }
                if (!firedRecurring.isEmpty()) {
                    for (String id : firedRecurring) inFlight.add(id);
                    markTasksFired(firedRecurring, now, opts.getDir());
                }
            }

            // Evict schedule entries for tasks no longer present
            if (seen.isEmpty()) {
                nextFireAt.clear();
            } else {
                nextFireAt.keySet().removeIf(id -> !seen.contains(id));
            }
        }

        private void processSingleTask(CronTask t, boolean isSession, long now,
                                        Set<String> seen, List<String> firedRecurring) {
            if (opts.getFilter() != null && !opts.getFilter().test(t)) return;
            seen.add(t.getId());
            if (inFlight.contains(t.getId())) return;

            Long nextMs = nextFireAt.get(t.getId());
            if (nextMs == null) {
                // First sight — anchor from lastFiredAt (recurring) or createdAt
                long anchor = t.isRecurring() && t.getLastFiredAt() != null
                        ? t.getLastFiredAt()
                        : t.getCreatedAt();
                Long computed = simpleNextCronMs(t.getCron(), anchor);
                nextMs = computed != null ? computed : Long.MAX_VALUE;
                nextFireAt.put(t.getId(), nextMs);
                log.debug("[ScheduledTasks] scheduled {} for {}",
                        t.getId(), nextMs == Long.MAX_VALUE ? "never" : Instant.ofEpochMilli(nextMs));
            }

            if (now < nextMs) return;

            log.debug("[ScheduledTasks] firing {}{}", t.getId(), t.isRecurring() ? " (recurring)" : "");

            if (opts.getOnFireTask() != null) {
                opts.getOnFireTask().accept(t);
            } else if (opts.getOnFire() != null) {
                opts.getOnFire().accept(t.getPrompt());
            }

            boolean aged = isRecurringTaskAged(t, now, opts.getRecurringMaxAgeMs());

            if (t.isRecurring() && !aged) {
                Long newNext = simpleNextCronMs(t.getCron(), now);
                nextFireAt.put(t.getId(), newNext != null ? newNext : Long.MAX_VALUE);
                if (!isSession) firedRecurring.add(t.getId());
            } else if (isSession) {
                // One-shot session task: remove from memory
                tasks = tasks.stream().filter(x -> !x.getId().equals(t.getId())).toList();
                nextFireAt.remove(t.getId());
            } else {
                // One-shot file task: remove from disk
                inFlight.add(t.getId());
                CompletableFuture.runAsync(() -> {
                    try {
                        removeCronTasksFromDisk(List.of(t.getId()), opts.getDir());
                    } finally {
                        inFlight.remove(t.getId());
                    }
                });
                nextFireAt.remove(t.getId());
            }
        }

        // ------ Disk I/O (delegated to CronTasksService) ------

        /**
         * Loads CronTask list from .claude/scheduled_tasks.json.
         * Delegates to CronTasksService.listAllCronTasks() when available.
         */
        private List<CronTask> readCronTasksFromDisk(String dir) {
            log.debug("[ScheduledTasks] readCronTasksFromDisk dir={}", dir);
            if (cronTasksService == null) return new ArrayList<>();
            try {
                List<CronTasksService.CronTask> serviceTasks = cronTasksService.listAllCronTasks();
                List<CronTask> result = new ArrayList<>();
                for (CronTasksService.CronTask st : serviceTasks) {
                    CronTask t = new CronTask();
                    t.setId(st.getId());
                    t.setCron(st.getCron());
                    t.setPrompt(st.getPrompt());
                    t.setRecurring(st.isRecurring());
                    t.setPermanent(st.isPermanent());
                    t.setCreatedAt(st.getCreatedAt());
                    t.setLastFiredAt(st.getLastFiredAt());
                    result.add(t);
                }
                return result;
            } catch (Exception e) {
                log.debug("[ScheduledTasks] readCronTasksFromDisk failed: {}", e.getMessage());
                return new ArrayList<>();
            }
        }

        private void removeCronTasksFromDisk(List<String> ids, String dir) {
            log.debug("[ScheduledTasks] removeCronTasksFromDisk ids={} dir={}", ids, dir);
            if (cronTasksService == null) return;
            for (String id : ids) {
                try { cronTasksService.deleteCronTask(id); }
                catch (Exception e) { log.debug("[ScheduledTasks] remove {} failed: {}", id, e.getMessage()); }
            }
        }

        private void markTasksFired(List<String> ids, long now, String dir) {
            log.debug("[ScheduledTasks] markTasksFired ids={} now={} dir={}", ids, now, dir);
            // CronTasksService does not expose a bulk-update-lastFiredAt; best-effort via reload+save
            // The underlying CronTasksService doesn't support setLastFiredAt in v1; log only for now.
        }

        private boolean hasCronTasksOnDisk(String dir) {
            if (cronTasksService == null) return false;
            try {
                return !cronTasksService.listAllCronTasks().isEmpty();
            } catch (Exception e) {
                return false;
            }
        }

        // ------ Lock option builder ------

        private Optional<CronTasksLock.SchedulerLockOptions> buildLockOpts() {
            String dir = opts.getDir();
            String identity = opts.getLockIdentity();
            if (dir == null && identity == null) return Optional.empty();
            CronTasksLock.SchedulerLockOptions lo = new CronTasksLock.SchedulerLockOptions();
            lo.setDir(dir);
            lo.setLockIdentity(identity);
            return Optional.of(lo);
        }

        private static void cancelIfNotNull(ScheduledFuture<?> f) {
            if (f != null) f.cancel(false);
        }
    }

    // ------------------------------------------------------------------
    // Module-level flag (mirrors getScheduledTasksEnabled / setScheduledTasksEnabled)
    // ------------------------------------------------------------------
    private static final AtomicBoolean scheduledTasksEnabled = new AtomicBoolean(false);

    public static void setScheduledTasksEnabled(boolean value) {
        scheduledTasksEnabled.set(value);
    }

    public static boolean getScheduledTasksEnabled() {
        return scheduledTasksEnabled.get();
    }

    // ------------------------------------------------------------------
    // Simple cron next-fire helper (placeholder — wire to full cron parser)
    // ------------------------------------------------------------------

    /**
     * Returns the next fire time in epoch ms after {@code afterMs} for the given
     * cron expression, or null if the expression cannot be parsed.
     *
     * This is a simplified placeholder. For production use, wire in a library
     * such as spring-context's CronExpression or quartz Trigger.
     */
    static Long simpleNextCronMs(String cron, long afterMs) {
        if (cron == null || cron.isBlank()) return null;
        try {
            // Delegate to Spring's CronExpression for proper cron support.
            // Wrapped in try/catch so unknown expressions return null gracefully.
            org.springframework.scheduling.support.CronExpression expr =
                    org.springframework.scheduling.support.CronExpression.parse(
                            ensureSecondField(cron));
            java.time.LocalDateTime after =
                    java.time.LocalDateTime.ofInstant(Instant.ofEpochMilli(afterMs),
                            java.time.ZoneId.systemDefault());
            java.time.LocalDateTime next = expr.next(after);
            if (next == null) return null;
            return next.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            log.debug("[ScheduledTasks] could not parse cron '{}': {}", cron, e.getMessage());
            return null;
        }
    }

    /**
     * Spring's CronExpression requires 6 fields (with seconds). If the user
     * provides a standard 5-field cron (without seconds), prepend "0 ".
     */
    private static String ensureSecondField(String cron) {
        if (cron == null) return null;
        String trimmed = cron.trim();
        long fieldCount = Arrays.stream(trimmed.split("\\s+")).count();
        return fieldCount == 5 ? "0 " + trimmed : trimmed;
    }

    private static int longestBacktickRun(String text) {
        if (text == null) return 0;
        int max = 0, current = 0;
        for (char c : text.toCharArray()) {
            if (c == '`') {
                current++;
                max = Math.max(max, current);
            } else {
                current = 0;
            }
        }
        return max;
    }
}
