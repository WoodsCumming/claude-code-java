package com.anthropic.claudecode.service;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Teleport environment selection service.
 * Translated from src/utils/teleport/environmentSelection.ts
 *
 * Gets information about available environments and the currently selected one.
 */
@Slf4j
@Service
public class TeleportEnvironmentSelectionService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TeleportEnvironmentSelectionService.class);


    private final TeleportEnvironmentsService environmentsService;
    private final SettingsService settingsService;

    @Autowired
    public TeleportEnvironmentSelectionService(TeleportEnvironmentsService environmentsService,
                                                SettingsService settingsService) {
        this.environmentsService = environmentsService;
        this.settingsService = settingsService;
    }

    /**
     * Get environment selection info.
     * Translated from getEnvironmentSelectionInfo() in environmentSelection.ts
     */
    public CompletableFuture<EnvironmentSelectionInfo> getEnvironmentSelectionInfo() {
        return environmentsService.fetchEnvironments().thenApply(environments -> {
            if (environments.isEmpty()) {
                return new EnvironmentSelectionInfo(List.of(), null, null);
            }

            // Check settings for a configured default environment
            Map<String, Object> settings = settingsService.getMergedSettings(null);
            String configuredId = (String) settings.get("defaultEnvironmentId");
            String source = null;

            TeleportEnvironmentsService.EnvironmentResource selected = null;

            if (configuredId != null) {
                final String finalConfiguredId = configuredId;
                selected = environments.stream()
                    .filter(e -> finalConfiguredId.equals(e.getEnvironmentId()))
                    .findFirst()
                    .orElse(null);
                if (selected != null) source = "userSettings";
            }

            if (selected == null) {
                selected = environments.get(0);
            }

            return new EnvironmentSelectionInfo(environments, selected, source);
        });
    }

    public static class EnvironmentSelectionInfo {
        private List<TeleportEnvironmentsService.EnvironmentResource> availableEnvironments;
        private TeleportEnvironmentsService.EnvironmentResource selectedEnvironment;
        private String selectedEnvironmentSource;

        public String getSelectedEnvironmentSource() { return selectedEnvironmentSource; }
        public void setSelectedEnvironmentSource(String v) { selectedEnvironmentSource = v; }
    

        public EnvironmentSelectionInfo() {}
        @SuppressWarnings("unchecked")
        public EnvironmentSelectionInfo(List<?> availableEnvironments, TeleportEnvironmentsService.EnvironmentResource selectedEnvironment, String selectedEnvironmentSource) {
            this.availableEnvironments = (List<TeleportEnvironmentsService.EnvironmentResource>) availableEnvironments;
            this.selectedEnvironment = selectedEnvironment;
            this.selectedEnvironmentSource = selectedEnvironmentSource;
        }
        public List<TeleportEnvironmentsService.EnvironmentResource> getAvailableEnvironments() { return availableEnvironments; }
        public void setAvailableEnvironments(List<TeleportEnvironmentsService.EnvironmentResource> v) { availableEnvironments = v; }
        public TeleportEnvironmentsService.EnvironmentResource getSelectedEnvironment() { return selectedEnvironment; }
        public void setSelectedEnvironment(TeleportEnvironmentsService.EnvironmentResource v) { selectedEnvironment = v; }
    }
}
