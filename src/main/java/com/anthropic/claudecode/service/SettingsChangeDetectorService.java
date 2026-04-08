package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Settings change detector service.
 * Translated from src/utils/settings/changeDetector.ts
 *
 * Watches settings files for changes and triggers reloads.
 */
@Slf4j
@Service
public class SettingsChangeDetectorService {



    private final SettingsService settingsService;
    private final List<Consumer<String>> changeListeners = new CopyOnWriteArrayList<>();
    private final Map<String, WatchService> watchers = new ConcurrentHashMap<>();

    @Autowired
    public SettingsChangeDetectorService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Watch a settings file for changes.
     * Translated from watchSettingsFile() in changeDetector.ts
     */
    public void watchSettingsFile(String filePath, String source) {
        try {
            Path path = Paths.get(filePath).getParent();
            if (path == null) return;

            WatchService watchService = FileSystems.getDefault().newWatchService();
            path.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
            watchers.put(filePath, watchService);

            // Start watching in background
            CompletableFuture.runAsync(() -> {
                while (true) {
                    try {
                        WatchKey key = watchService.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                                notifyListeners(source);
                            }
                        }
                        if (!key.reset()) break;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ClosedWatchServiceException e) {
                        break;
                    }
                }
            });

        } catch (Exception e) {
            log.debug("Could not watch settings file {}: {}", filePath, e.getMessage());
        }
    }

    /**
     * Subscribe to settings changes.
     */
    public Runnable subscribe(Consumer<String> listener) {
        changeListeners.add(listener);
        return () -> changeListeners.remove(listener);
    }

    private void notifyListeners(String source) {
        changeListeners.forEach(l -> {
            try { l.accept(source); } catch (Exception ignored) {}
        });
    }

    /**
     * Stop watching all settings files.
     */
    public void stopWatching() {
        watchers.values().forEach(w -> {
            try { w.close(); } catch (Exception ignored) {}
        });
        watchers.clear();
    }
}
