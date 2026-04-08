package com.anthropic.claudecode.service;

import com.anthropic.claudecode.util.PlatformUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Computer use app names service.
 * Translated from src/utils/computerUse/appNames.ts
 *
 * Filters and sanitizes installed-app data for computer use.
 */
@Slf4j
@Service
public class ComputerUseAppNamesService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ComputerUseAppNamesService.class);


    private static final Pattern INJECTION_PATTERN = Pattern.compile(
        "(?i)\\b(grant|allow|permit|enable|disable|ignore|skip|bypass)\\b.*\\b(all|every|any)\\b"
    );

    /**
     * Filter and sanitize app names for tool description.
     * Translated from filterAppsForDescription() in appNames.ts
     */
    public List<AppInfo> filterAppsForDescription(List<AppInfo> apps) {
        if (apps == null) return List.of();

        return apps.stream()
            .filter(app -> isUserFacingApp(app))
            .filter(app -> !hasInjectionRisk(app.getDisplayName()))
            .sorted(Comparator.comparing(AppInfo::getDisplayName))
            .collect(Collectors.toList());
    }

    private boolean isUserFacingApp(AppInfo app) {
        if (app.getBundleId() == null || app.getDisplayName() == null) return false;

        // Filter out XPC helpers, daemons, input methods
        String bundleId = app.getBundleId().toLowerCase();
        return !bundleId.contains(".xpc")
            && !bundleId.contains(".helper")
            && !bundleId.contains(".daemon")
            && !bundleId.contains(".inputmethod")
            && app.getDisplayName().length() > 1;
    }

    private boolean hasInjectionRisk(String displayName) {
        return INJECTION_PATTERN.matcher(displayName).find();
    }

    /**
     * List installed apps on macOS.
     */
    public List<AppInfo> listInstalledApps() {
        if (!PlatformUtils.isMacOS()) return List.of();

        List<AppInfo> apps = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "mdfind", "-onlyin", "/Applications", "kMDItemKind == 'Application'"
            );
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());

            for (String path : output.split("\n")) {
                path = path.trim();
                if (path.isEmpty()) continue;
                String name = path.substring(path.lastIndexOf('/') + 1).replace(".app", "");
                apps.add(new AppInfo(name, name, path));
            }
        } catch (Exception e) {
            log.debug("Could not list installed apps: {}", e.getMessage());
        }

        return apps;
    }

    @Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class AppInfo {
        private String bundleId;
        private String displayName;
        private String path;

        public String getBundleId() { return bundleId; }
        public void setBundleId(String v) { bundleId = v; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String v) { displayName = v; }
        public String getPath() { return path; }
        public void setPath(String v) { path = v; }
    }
}
