package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent color manager.
 * Translated from src/tools/AgentTool/agentColorManager.ts
 *
 * Manages color assignments for agents in multi-agent swarms.
 */
@Slf4j
@Component
public class AgentColorManager {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AgentColorManager.class);


    public static final List<String> AGENT_COLORS = List.of(
        "red", "blue", "green", "yellow", "purple", "orange", "pink", "cyan"
    );

    private final Map<String, String> agentColorMap = new ConcurrentHashMap<>();
    private int colorIndex = 0;

    /**
     * Get the color for an agent.
     * Translated from getAgentColor() in agentColorManager.ts
     */
    public String getAgentColor(String agentType) {
        if ("general-purpose".equals(agentType)) return null;
        return agentColorMap.get(agentType);
    }

    /**
     * Set the color for an agent.
     * Translated from setAgentColor() in agentColorManager.ts
     */
    public String setAgentColor(String agentId, String agentType) {
        if ("general-purpose".equals(agentType)) return null;

        // Assign next color in rotation
        String color = AGENT_COLORS.get(colorIndex % AGENT_COLORS.size());
        colorIndex++;
        agentColorMap.put(agentId, color);
        return color;
    }

    /**
     * Clear all agent colors.
     */
    public void clearColors() {
        agentColorMap.clear();
        colorIndex = 0;
    }
}
