package com.anthropic.claudecode.service;

import com.anthropic.claudecode.service.AgentColorManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Teammate layout manager service.
 * Translated from src/utils/swarm/teammateLayoutManager.ts
 *
 * Manages color assignments and layout for teammates.
 */
@Slf4j
@Service
public class TeammateLayoutManagerService {



    private final Map<String, String> teammateColorAssignments = new ConcurrentHashMap<>();
    private final AtomicInteger colorIndex = new AtomicInteger(0);

    private final SwarmBackendRegistryService backendRegistryService;

    @Autowired
    public TeammateLayoutManagerService(SwarmBackendRegistryService backendRegistryService) {
        this.backendRegistryService = backendRegistryService;
    }

    /**
     * Assign a unique color to a teammate.
     * Translated from assignTeammateColor() in teammateLayoutManager.ts
     */
    public String assignTeammateColor(String teammateId) {
        return teammateColorAssignments.computeIfAbsent(teammateId, id -> {
            List<String> colors = AgentColorManager.AGENT_COLORS;
            int idx = colorIndex.getAndIncrement();
            return colors.get(idx % colors.size());
        });
    }

    /**
     * Get the current color assignment for a teammate.
     */
    public Optional<String> getTeammateColor(String teammateId) {
        return Optional.ofNullable(teammateColorAssignments.get(teammateId));
    }

    /**
     * Remove a teammate's color assignment.
     */
    public void removeTeammateColor(String teammateId) {
        teammateColorAssignments.remove(teammateId);
    }
}
