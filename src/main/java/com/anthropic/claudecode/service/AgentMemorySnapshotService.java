package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Agent memory snapshot service.
 * Translated from src/tools/AgentTool/agentMemorySnapshot.ts
 *
 * Manages snapshots of agent memory that can be shared across sessions via the project repo.
 */
@Slf4j
@Service
public class AgentMemorySnapshotService {



    private static final String SNAPSHOT_BASE = "agent-memory-snapshots";
    private static final String SNAPSHOT_JSON = "snapshot.json";
    private static final String SYNCED_JSON = ".snapshot-synced.json";

    private final AgentMemoryService agentMemoryService;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentMemorySnapshotService(AgentMemoryService agentMemoryService,
                                      ObjectMapper objectMapper) {
        this.agentMemoryService = agentMemoryService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Metadata records — mirror the Zod schemas from TypeScript
    // -------------------------------------------------------------------------

    /** Mirrors snapshotMetaSchema: { updatedAt: string } */
    public record SnapshotMeta(String updatedAt) {}

    /** Mirrors syncedMetaSchema: { syncedFrom: string } */
    public record SyncedMeta(String syncedFrom) {}

    // -------------------------------------------------------------------------
    // Result type — mirrors the return type of checkAgentMemorySnapshot()
    // -------------------------------------------------------------------------

    public enum SnapshotAction { NONE, INITIALIZE, PROMPT_UPDATE }

    public record SnapshotCheckResult(SnapshotAction action, String snapshotTimestamp) {
        public static SnapshotCheckResult none() {
            return new SnapshotCheckResult(SnapshotAction.NONE, null);
        }
    }

    // -------------------------------------------------------------------------
    // Path helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the path to the snapshot directory for an agent in the current project.
     * e.g. {@code <cwd>/.claude/agent-memory-snapshots/<agentType>/}
     * Translated from getSnapshotDirForAgent().
     */
    public Path getSnapshotDirForAgent(String agentType) {
        String cwd = System.getProperty("user.dir");
        return Path.of(cwd, ".claude", SNAPSHOT_BASE, agentType);
    }

    private Path getSnapshotJsonPath(String agentType) {
        return getSnapshotDirForAgent(agentType).resolve(SNAPSHOT_JSON);
    }

    private Path getSyncedJsonPath(String agentType, AgentMemoryService.AgentMemoryScope scope) {
        return Path.of(agentMemoryService.getAgentMemoryDir(agentType, scope)).resolve(SYNCED_JSON);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private <T> T readJsonFile(Path path, Class<T> type) {
        try {
            String content = Files.readString(path);
            return objectMapper.readValue(content, type);
        } catch (Exception e) {
            log.debug("Could not read or parse {}: {}", path, e.getMessage());
            return null;
        }
    }

    /**
     * Copy memory files from the snapshot directory to the local agent memory directory.
     * Translated from copySnapshotToLocal().
     */
    private void copySnapshotToLocal(String agentType, AgentMemoryService.AgentMemoryScope scope)
            throws IOException {
        Path snapshotMemDir = getSnapshotDirForAgent(agentType);
        Path localMemDir = Path.of(agentMemoryService.getAgentMemoryDir(agentType, scope));
        Files.createDirectories(localMemDir);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(snapshotMemDir)) {
            for (Path entry : stream) {
                if (!Files.isRegularFile(entry)) continue;
                String name = entry.getFileName().toString();
                if (SNAPSHOT_JSON.equals(name)) continue;
                Files.copy(entry, localMemDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (NoSuchFileException e) {
            log.debug("Snapshot directory does not exist yet for agent {}: {}", agentType, e.getMessage());
        } catch (IOException e) {
            log.debug("Failed to copy snapshot to local agent memory: {}", e.getMessage());
        }
    }

    private void saveSyncedMeta(String agentType,
                                AgentMemoryService.AgentMemoryScope scope,
                                String snapshotTimestamp) {
        Path syncedPath = getSyncedJsonPath(agentType, scope);
        Path localMemDir = Path.of(agentMemoryService.getAgentMemoryDir(agentType, scope));
        try {
            Files.createDirectories(localMemDir);
            SyncedMeta meta = new SyncedMeta(snapshotTimestamp);
            Files.writeString(syncedPath, objectMapper.writeValueAsString(meta));
        } catch (IOException e) {
            log.debug("Failed to save snapshot sync metadata: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Check if a snapshot exists and whether it's newer than what we last synced.
     * Translated from checkAgentMemorySnapshot().
     */
    public CompletableFuture<SnapshotCheckResult> checkAgentMemorySnapshot(
            String agentType,
            AgentMemoryService.AgentMemoryScope scope) {

        return CompletableFuture.supplyAsync(() -> {
            SnapshotMeta snapshotMeta = readJsonFile(getSnapshotJsonPath(agentType), SnapshotMeta.class);
            if (snapshotMeta == null) {
                return SnapshotCheckResult.none();
            }

            Path localMemDir = Path.of(agentMemoryService.getAgentMemoryDir(agentType, scope));
            boolean hasLocalMemory = false;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(localMemDir)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".md")) {
                        hasLocalMemory = true;
                        break;
                    }
                }
            } catch (NoSuchFileException ignored) {
                // Directory doesn't exist — no local memory
            } catch (IOException e) {
                log.debug("Error checking local memory dir {}: {}", localMemDir, e.getMessage());
            }

            if (!hasLocalMemory) {
                return new SnapshotCheckResult(SnapshotAction.INITIALIZE, snapshotMeta.updatedAt());
            }

            SyncedMeta syncedMeta = readJsonFile(getSyncedJsonPath(agentType, scope), SyncedMeta.class);
            if (syncedMeta == null ||
                    Instant.parse(snapshotMeta.updatedAt()).isAfter(Instant.parse(syncedMeta.syncedFrom()))) {
                return new SnapshotCheckResult(SnapshotAction.PROMPT_UPDATE, snapshotMeta.updatedAt());
            }

            return SnapshotCheckResult.none();
        });
    }

    /**
     * Initialize local agent memory from a snapshot (first-time setup).
     * Translated from initializeFromSnapshot().
     */
    public CompletableFuture<Void> initializeFromSnapshot(
            String agentType,
            AgentMemoryService.AgentMemoryScope scope,
            String snapshotTimestamp) {

        return CompletableFuture.runAsync(() -> {
            log.debug("Initializing agent memory for {} from project snapshot", agentType);
            try {
                copySnapshotToLocal(agentType, scope);
            } catch (IOException e) {
                log.warn("Failed to copy snapshot to local for agent {}: {}", agentType, e.getMessage());
            }
            saveSyncedMeta(agentType, scope, snapshotTimestamp);
        });
    }

    /**
     * Replace local agent memory with the snapshot.
     * Translated from replaceFromSnapshot().
     */
    public CompletableFuture<Void> replaceFromSnapshot(
            String agentType,
            AgentMemoryService.AgentMemoryScope scope,
            String snapshotTimestamp) {

        return CompletableFuture.runAsync(() -> {
            log.debug("Replacing agent memory for {} with project snapshot", agentType);

            // Remove existing .md files before copying to avoid orphans
            Path localMemDir = Path.of(agentMemoryService.getAgentMemoryDir(agentType, scope));
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(localMemDir)) {
                for (Path entry : stream) {
                    if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".md")) {
                        Files.delete(entry);
                    }
                }
            } catch (NoSuchFileException ignored) {
                // Directory may not exist yet — that's fine
            } catch (IOException e) {
                log.debug("Error removing old .md files for agent {}: {}", agentType, e.getMessage());
            }

            try {
                copySnapshotToLocal(agentType, scope);
            } catch (IOException e) {
                log.warn("Failed to copy snapshot to local for agent {}: {}", agentType, e.getMessage());
            }
            saveSyncedMeta(agentType, scope, snapshotTimestamp);
        });
    }

    /**
     * Mark the current snapshot as synced without changing local memory.
     * Translated from markSnapshotSynced().
     */
    public CompletableFuture<Void> markSnapshotSynced(
            String agentType,
            AgentMemoryService.AgentMemoryScope scope,
            String snapshotTimestamp) {

        return CompletableFuture.runAsync(() ->
            saveSyncedMeta(agentType, scope, snapshotTimestamp)
        );
    }
}
