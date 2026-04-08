package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PluginDirectories;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;

/**
 * Orphaned plugin filter service.
 * Translated from src/utils/plugins/orphanedPluginFilter.ts
 *
 * Provides exclusion patterns for orphaned plugin versions.
 */
@Slf4j
@Service
public class OrphanedPluginFilterService {



    private volatile List<String> cachedExclusionPatterns;

    /**
     * Get glob exclusion patterns for orphaned plugin versions.
     * Translated from getOrphanedPluginGlobExclusions() in orphanedPluginFilter.ts
     */
    public List<String> getOrphanedPluginGlobExclusions() {
        if (cachedExclusionPatterns != null) return cachedExclusionPatterns;

        List<String> patterns = new ArrayList<>();
        String pluginsDir = PluginDirectories.getPluginsBaseDir();

        // Find .orphaned_at markers
        findOrphanedMarkers(new File(pluginsDir), patterns);

        cachedExclusionPatterns = patterns;
        return patterns;
    }

    private void findOrphanedMarkers(File dir, List<String> patterns) {
        if (!dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isFile() && ".orphaned_at".equals(file.getName())) {
                patterns.add("!" + dir.getAbsolutePath() + "/**");
            } else if (file.isDirectory()) {
                findOrphanedMarkers(file, patterns);
            }
        }
    }

    /**
     * Clear the cached exclusion patterns.
     */
    public void clearCache() {
        cachedExclusionPatterns = null;
    }
}
