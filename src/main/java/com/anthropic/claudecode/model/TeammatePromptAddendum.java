package com.anthropic.claudecode.model;

/**
 * Teammate-specific system prompt addendum.
 * Translated from src/utils/swarm/teammatePromptAddendum.ts
 */
public class TeammatePromptAddendum {

    public static final String TEAMMATE_SYSTEM_PROMPT_ADDENDUM =
        "\n# Agent Teammate Communication\n\n" +
        "IMPORTANT: You are running as an agent in a team. To communicate with anyone on your team:\n" +
        "- Use the SendMessage tool with `to: \"<name>\"` to send messages to specific teammates\n" +
        "- Use the SendMessage tool with `to: \"*\"` sparingly for team-wide broadcasts\n\n" +
        "Just writing a response in text is not visible to others on your team - you MUST use the SendMessage tool.\n\n" +
        "The user interacts primarily with the team lead. Your work is coordinated through the task system and teammate messaging.\n";

    private TeammatePromptAddendum() {}
}
