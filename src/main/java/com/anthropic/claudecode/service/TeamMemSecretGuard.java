package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Team memory secret guard.
 * Translated from src/services/teamMemorySync/teamMemSecretGuard.ts
 *
 * Prevents writing secrets to team memory files. This is called from
 * file write/edit validation to stop the model from writing secrets into
 * team memory files that would be synced to all repository collaborators.
 */
@Slf4j
@Service
public class TeamMemSecretGuard {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TeamMemSecretGuard.class);


    private final SecretScannerService secretScannerService;
    private final TeamMemPathsService teamMemPathsService;

    @Autowired
    public TeamMemSecretGuard(
            SecretScannerService secretScannerService,
            TeamMemPathsService teamMemPathsService) {
        this.secretScannerService = secretScannerService;
        this.teamMemPathsService = teamMemPathsService;
    }

    /**
     * Check if a file write/edit to a team memory path contains secrets.
     * Returns an error message if secrets are detected, or null if safe.
     *
     * This is called from FileWriteTool and FileEditTool validateInput to
     * prevent the model from writing secrets into team memory files, which
     * would be synced to all repository collaborators.
     *
     * Returns null when the TEAMMEM feature flag is off or the path is not
     * a team memory path.
     *
     * Translated from checkTeamMemSecrets() in teamMemSecretGuard.ts
     */
    public String checkTeamMemSecrets(String filePath, String content) {
        if (!teamMemPathsService.isTeamMemFile(filePath)) {
            return null;
        }

        List<SecretScannerService.SecretMatch> matches = secretScannerService.scanForSecrets(content);
        if (matches.isEmpty()) {
            return null;
        }

        String labels = matches.stream()
            .map(SecretScannerService.SecretMatch::getLabel)
            .collect(Collectors.joining(", "));

        return "Content contains potential secrets (" + labels + ") and cannot be written to team memory. "
            + "Team memory is shared with all repository collaborators. "
            + "Remove the sensitive content and try again.";
    }
}
