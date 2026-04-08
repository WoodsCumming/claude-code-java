package com.anthropic.claudecode.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates agent configurations by calling the Claude API.
 *
 * <p>Translated from TypeScript {@code src/components/agents/generateAgent.ts}.
 *
 * <p>Design decisions:
 * <ul>
 *   <li>The TypeScript function is {@code async}; here it returns a {@link CompletableFuture}.</li>
 *   <li>{@link ClaudeApiClient} is an interface stub — replace with the real Spring
 *       {@code @FeignClient} or {@code WebClient}-based implementation.</li>
 *   <li>JSON parsing uses Jackson instead of the TS {@code jsonParse} helper.</li>
 *   <li>Analytics logging uses SLF4J; plug in the real analytics service if needed.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerateAgentService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GenerateAgentService.class);


    private static final String AGENT_TOOL_NAME = "Agent";

    // ---------------------------------------------------------------------------
    // System prompts (verbatim from TS source)
    // ---------------------------------------------------------------------------

    private static final String AGENT_CREATION_SYSTEM_PROMPT =
            "You are an elite AI agent architect specializing in crafting high-performance agent "
            + "configurations. Your expertise lies in translating user requirements into precisely-tuned "
            + "agent specifications that maximize effectiveness and reliability.\n\n"
            + "**Important Context**: You may have access to project-specific instructions from CLAUDE.md "
            + "files and other context that may include coding standards, project structure, and custom "
            + "requirements. Consider this context when creating agents to ensure they align with the "
            + "project's established patterns and practices.\n\n"
            + "When a user describes what they want an agent to do, you will:\n\n"
            + "1. **Extract Core Intent**: Identify the fundamental purpose, key responsibilities, and "
            + "success criteria for the agent. Look for both explicit requirements and implicit needs. "
            + "Consider any project-specific context from CLAUDE.md files. For agents that are meant to "
            + "review code, you should assume that the user is asking to review recently written code and "
            + "not the whole codebase, unless the user has explicitly instructed you otherwise.\n\n"
            + "2. **Design Expert Persona**: Create a compelling expert identity that embodies deep domain "
            + "knowledge relevant to the task. The persona should inspire confidence and guide the agent's "
            + "decision-making approach.\n\n"
            + "3. **Architect Comprehensive Instructions**: Develop a system prompt that:\n"
            + "   - Establishes clear behavioral boundaries and operational parameters\n"
            + "   - Provides specific methodologies and best practices for task execution\n"
            + "   - Anticipates edge cases and provides guidance for handling them\n"
            + "   - Incorporates any specific requirements or preferences mentioned by the user\n"
            + "   - Defines output format expectations when relevant\n"
            + "   - Aligns with project-specific coding standards and patterns from CLAUDE.md\n\n"
            + "4. **Optimize for Performance**: Include:\n"
            + "   - Decision-making frameworks appropriate to the domain\n"
            + "   - Quality control mechanisms and self-verification steps\n"
            + "   - Efficient workflow patterns\n"
            + "   - Clear escalation or fallback strategies\n\n"
            + "5. **Create Identifier**: Design a concise, descriptive identifier that:\n"
            + "   - Uses lowercase letters, numbers, and hyphens only\n"
            + "   - Is typically 2-4 words joined by hyphens\n"
            + "   - Clearly indicates the agent's primary function\n"
            + "   - Is memorable and easy to type\n"
            + "   - Avoids generic terms like \"helper\" or \"assistant\"\n\n"
            + "6 **Example agent descriptions**:\n"
            + "  - in the 'whenToUse' field of the JSON object, you should include examples of when "
            + "this agent should be used.\n"
            + "  - examples should be of the form:\n"
            + "    - <example>\n"
            + "      Context: The user is creating a test-runner agent that should be called after a "
            + "logical chunk of code is written.\n"
            + "      user: \"Please write a function that checks if a number is prime\"\n"
            + "      assistant: \"Here is the relevant function: \"\n"
            + "      <function call omitted for brevity only for this example>\n"
            + "      <commentary>\n"
            + "      Since a significant piece of code was written, use the " + AGENT_TOOL_NAME
            + " tool to launch the test-runner agent to run the tests.\n"
            + "      </commentary>\n"
            + "      assistant: \"Now let me use the test-runner agent to run the tests\"\n"
            + "    </example>\n"
            + "    - <example>\n"
            + "      Context: User is creating an agent to respond to the word \"hello\" with a friendly jok.\n"
            + "      user: \"Hello\"\n"
            + "      assistant: \"I'm going to use the " + AGENT_TOOL_NAME
            + " tool to launch the greeting-responder agent to respond with a friendly joke\"\n"
            + "      <commentary>\n"
            + "      Since the user is greeting, use the greeting-responder agent to respond with a friendly joke.\n"
            + "      </commentary>\n"
            + "    </example>\n"
            + "  - If the user mentioned or implied that the agent should be used proactively, you should "
            + "include examples of this.\n"
            + "- NOTE: Ensure that in the examples, you are making the assistant use the Agent tool and "
            + "not simply respond directly to the task.\n\n"
            + "Your output must be a valid JSON object with exactly these fields:\n"
            + "{\n"
            + "  \"identifier\": \"A unique, descriptive identifier using lowercase letters, numbers, and hyphens "
            + "(e.g., 'test-runner', 'api-docs-writer', 'code-formatter')\",\n"
            + "  \"whenToUse\": \"A precise, actionable description starting with 'Use this agent when...' that "
            + "clearly defines the triggering conditions and use cases. Ensure you include examples as described above.\",\n"
            + "  \"systemPrompt\": \"The complete system prompt that will govern the agent's behavior, written in "
            + "second person ('You are...', 'You will...') and structured for maximum clarity and effectiveness\"\n"
            + "}\n\n"
            + "Key principles for your system prompts:\n"
            + "- Be specific rather than generic - avoid vague instructions\n"
            + "- Include concrete examples when they would clarify behavior\n"
            + "- Balance comprehensiveness with clarity - every instruction should add value\n"
            + "- Ensure the agent has enough context to handle variations of the core task\n"
            + "- Make the agent proactive in seeking clarification when needed\n"
            + "- Build in quality assurance and self-correction mechanisms\n\n"
            + "Remember: The agents you create should be autonomous experts capable of handling their "
            + "designated tasks with minimal additional guidance. Your system prompts are their complete "
            + "operational manual.\n";

    private static final String AGENT_MEMORY_INSTRUCTIONS =
            "\n7. **Agent Memory Instructions**: If the user mentions \"memory\", \"remember\", \"learn\", "
            + "\"persist\", or similar concepts, OR if the agent would benefit from building up knowledge "
            + "across conversations (e.g., code reviewers learning patterns, architects learning codebase "
            + "structure, etc.), include domain-specific memory update instructions in the systemPrompt.\n\n"
            + "   Add a section like this to the systemPrompt, tailored to the agent's specific domain:\n\n"
            + "   \"**Update your agent memory** as you discover [domain-specific items]. This builds up "
            + "institutional knowledge across conversations. Write concise notes about what you found and where.\n\n"
            + "   Examples of what to record:\n"
            + "   - [domain-specific item 1]\n"
            + "   - [domain-specific item 2]\n"
            + "   - [domain-specific item 3]\"\n\n"
            + "   Examples of domain-specific memory instructions:\n"
            + "   - For a code-reviewer: \"Update your agent memory as you discover code patterns, style "
            + "conventions, common issues, and architectural decisions in this codebase.\"\n"
            + "   - For a test-runner: \"Update your agent memory as you discover test patterns, common "
            + "failure modes, flaky tests, and testing best practices.\"\n"
            + "   - For an architect: \"Update your agent memory as you discover codepaths, library "
            + "locations, key architectural decisions, and component relationships.\"\n"
            + "   - For a documentation writer: \"Update your agent memory as you discover documentation "
            + "patterns, API structures, and terminology conventions.\"\n\n"
            + "   The memory instructions should be specific to what the agent would naturally learn "
            + "while performing its core tasks.\n";

    // ---------------------------------------------------------------------------
    // Dependencies
    // ---------------------------------------------------------------------------

    private final ClaudeApiClient claudeApiClient;
    private final ObjectMapper    objectMapper;

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /**
     * Generates a new agent configuration from a free-form user prompt.
     *
     * <p>TypeScript signature:
     * <pre>
     * export async function generateAgent(
     *   userPrompt: string,
     *   model: ModelName,
     *   existingIdentifiers: string[],
     *   abortSignal: AbortSignal,
     * ): Promise&lt;GeneratedAgent&gt;
     * </pre>
     *
     * @param userPrompt          free-form description of the desired agent
     * @param model               model name to use for generation
     * @param existingIdentifiers identifiers already in use (to avoid collisions)
     * @param autoMemoryEnabled   whether the auto-memory feature is currently active
     * @return a {@link CompletableFuture} resolving to a {@link GeneratedAgent}
     */
    public CompletableFuture<GeneratedAgent> generateAgent(
            String userPrompt,
            String model,
            List<String> existingIdentifiers,
            boolean autoMemoryEnabled
    ) {
        String existingList = (existingIdentifiers != null && !existingIdentifiers.isEmpty())
                ? "\n\nIMPORTANT: The following identifiers already exist and must NOT be used: "
                  + String.join(", ", existingIdentifiers)
                : "";

        String prompt = "Create an agent configuration based on this request: \""
                + userPrompt + "\"." + existingList
                + "\n  Return ONLY the JSON object, no other text.";

        String systemPrompt = autoMemoryEnabled
                ? AGENT_CREATION_SYSTEM_PROMPT + AGENT_MEMORY_INSTRUCTIONS
                : AGENT_CREATION_SYSTEM_PROMPT;

        return claudeApiClient
                .queryWithoutStreaming(prompt, systemPrompt, model)
                .thenApply(responseText -> parseGeneratedAgent(responseText.trim()))
                .thenApply(agent -> {
                    log.info("tengu_agent_definition_generated agent_identifier={}",
                            agent.identifier());
                    return agent;
                });
    }

    // ---------------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------------

    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{[\\s\\S]*\\}");

    private GeneratedAgent parseGeneratedAgent(String responseText) {
        try {
            return tryParseGeneratedAgent(responseText);
        } catch (Exception firstEx) {
            Matcher matcher = JSON_OBJECT_PATTERN.matcher(responseText);
            if (!matcher.find()) {
                throw new RuntimeException("No JSON object found in response", firstEx);
            }
            try {
                return tryParseGeneratedAgent(matcher.group());
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse agent configuration from response", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private GeneratedAgent tryParseGeneratedAgent(String json) throws Exception {
        var map = objectMapper.readValue(json, java.util.Map.class);

        String identifier   = (String) map.get("identifier");
        String whenToUse    = (String) map.get("whenToUse");
        String systemPrompt = (String) map.get("systemPrompt");

        if (identifier == null || identifier.isBlank()
                || whenToUse == null || whenToUse.isBlank()
                || systemPrompt == null || systemPrompt.isBlank()) {
            throw new RuntimeException("Invalid agent configuration generated");
        }

        return new GeneratedAgent(identifier, whenToUse, systemPrompt);
    }

    // ---------------------------------------------------------------------------
    // Result record
    // ---------------------------------------------------------------------------

    /**
     * Immutable value object representing a fully-generated agent definition.
     *
     * <p>Mirrors the TypeScript {@code GeneratedAgent} type.
     */
    public record GeneratedAgent(
            String identifier,
            String whenToUse,
            String systemPrompt
    ) {}

    // ---------------------------------------------------------------------------
    // ClaudeApiClient stub interface
    // ---------------------------------------------------------------------------

    /**
     * Minimal abstraction over the Claude API call needed by this service.
     *
     * <p>Implement this interface (e.g., via Spring {@code @FeignClient} or {@code WebClient})
     * to wire up the real HTTP transport.
     */
    public interface ClaudeApiClient {
        /**
         * Sends a user message to the Claude API and returns the text response.
         *
         * @param userMessage  the user message content
         * @param systemPrompt the system prompt
         * @param model        the model name
         * @return the raw response text
         */
        CompletableFuture<String> queryWithoutStreaming(
                String userMessage,
                String systemPrompt,
                String model
        );
    }
}
