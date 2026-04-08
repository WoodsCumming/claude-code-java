package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * File changed watcher service.
 * Translated from src/utils/hooks/fileChangedWatcher.ts
 *
 * Watches for file system changes and triggers hooks.
 */
@Slf4j
@Service
public class FileChangedWatcherService {

    private volatile WatchService watchService;
    private volatile boolean initialized = false;
    private volatile String currentCwd;
    private final List<String> watchPaths = new CopyOnWriteArrayList<>();
    private volatile BiConsumer<String, Boolean> notifyCallback;
    private Thread watchThread;

    /**
     * Set the notification callback.
     * Translated from setEnvHookNotifier() in fileChangedWatcher.ts
     */
    public void setEnvHookNotifier(BiConsumer<String, Boolean> callback) {
        this.notifyCallback = callback;
    }

    /**
     * Initialize the file watcher.
     * Translated from initializeFileChangedWatcher() in fileChangedWatcher.ts
     */
    public void initialize(String cwd) {
        if (initialized) return;
        initialized = true;
        currentCwd = cwd;
        log.debug("File watcher initialized for: {}", cwd);
    }

    /**
     * Update the watched paths.
     * Translated from updateWatchPaths() in fileChangedWatcher.ts
     */
    public void updateWatchPaths(List<String> paths) {
        watchPaths.clear();
        if (paths != null) {
            watchPaths.addAll(paths);
        }
        log.debug("Watch paths updated: {}", watchPaths);
    }

    /**
     * Start watching.
     */
    public void startWatching() {
        if (watchPaths.isEmpty()) return;

        try {
            watchService = FileSystems.getDefault().newWatchService();

            for (String path : watchPaths) {
                Path p = Paths.get(path);
                if (Files.exists(p)) {
                    p.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                }
            }

            watchThread = new Thread(this::watchLoop);
            watchThread.setDaemon(true);
            watchThread.start();

        } catch (Exception e) {
            log.debug("Could not start file watcher: {}", e.getMessage());
        }
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    log.debug("File changed: {}", changed);

                    BiConsumer<String, Boolean> cb = notifyCallback;
                    if (cb != null) {
                        cb.accept(changed.toString(), false);
                    }
                }

                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("Watch error: {}", e.getMessage());
            }
        }
    }

    /**
     * Dispose the watcher.
     */
    public void dispose() {
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception e) {
                // Ignore
            }
        }
        initialized = false;
    }
}
