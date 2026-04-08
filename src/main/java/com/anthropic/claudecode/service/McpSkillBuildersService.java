package com.anthropic.claudecode.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Write-once registry for the two loadSkillsDir functions that MCP skill discovery needs.
 * Translated from src/skills/mcpSkillBuilders.ts
 *
 * This class is a dependency-graph leaf: it imports no other service types, so both
 * McpSkillsService and SkillsLoaderService can depend on it without forming a cycle.
 *
 * Rationale from the TypeScript source:
 *  - A non-literal dynamic import fails at runtime in bundled binaries.
 *  - A static import of loadSkillsDir would fan out into many cycle violations.
 *  - Solution: register the two functions here at module-init time (Spring @PostConstruct),
 *    long before any MCP server connects.
 *
 * Registration happens from SkillsLoaderService (the equivalent of loadSkillsDir.ts),
 * which is eagerly initialised at startup via its @Component dependency chain — mirroring
 * the static import chain commands.ts → loadSkillsDir.ts → mcpSkillBuilders.ts.
 */
@Slf4j
@Service
public class McpSkillBuildersService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpSkillBuildersService.class);


    // ---------------------------------------------------------------------------
    // Functional interfaces (mirrors the typeof imports in mcpSkillBuilders.ts)
    // ---------------------------------------------------------------------------

    /**
     * Functional interface matching createSkillCommand from loadSkillsDir.ts.
     * Takes (name, frontmatterFields, content) and returns a Command-like object.
     */
    @FunctionalInterface
    public interface CreateSkillCommandFn {
        Object apply(String name, Object frontmatterFields, String content);
    }

    /**
     * Functional interface matching parseSkillFrontmatterFields from loadSkillsDir.ts.
     * Takes raw frontmatter text and returns the parsed fields object.
     */
    @FunctionalInterface
    public interface ParseSkillFrontmatterFieldsFn {
        Object apply(String rawFrontmatter);
    }

    // ---------------------------------------------------------------------------
    // Registry state (mirrors `let builders: MCPSkillBuilders | null = null`)
    // ---------------------------------------------------------------------------

    private volatile Builders builders = null;

    /**
     * Holds the two registered builder functions.
     * Translated from MCPSkillBuilders in mcpSkillBuilders.ts
     */
    public record Builders(
        CreateSkillCommandFn createSkillCommand,
        ParseSkillFrontmatterFieldsFn parseSkillFrontmatterFields
    ) {}

    // ---------------------------------------------------------------------------
    // API
    // ---------------------------------------------------------------------------

    /**
     * Register the skill builder functions.
     * Must be called exactly once, during SkillsLoaderService initialisation.
     * Translated from registerMCPSkillBuilders() in mcpSkillBuilders.ts
     *
     * @param createSkillCommand          Function that creates a skill command object
     * @param parseSkillFrontmatterFields Function that parses skill frontmatter
     */
    public void registerMCPSkillBuilders(
            CreateSkillCommandFn createSkillCommand,
            ParseSkillFrontmatterFieldsFn parseSkillFrontmatterFields) {

        this.builders = new Builders(createSkillCommand, parseSkillFrontmatterFields);
        log.debug("[McpSkillBuilders] MCP skill builders registered");
    }

    /**
     * Retrieve the registered skill builders.
     * Throws if registerMCPSkillBuilders() has not been called yet.
     * Translated from getMCPSkillBuilders() in mcpSkillBuilders.ts
     *
     * @return The registered Builders instance
     * @throws IllegalStateException if builders have not been registered
     */
    public Builders getMCPSkillBuilders() {
        Builders snapshot = builders;
        if (snapshot == null) {
            throw new IllegalStateException(
                "MCP skill builders not registered — SkillsLoaderService has not been initialized yet");
        }
        return snapshot;
    }

    /**
     * Check whether builders have been registered.
     * Useful for defensive checks without triggering an exception.
     */
    public boolean isInitialized() {
        return builders != null;
    }
}
