package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;
import java.util.*;
import java.util.function.*;

/**
 * Command types for slash commands.
 * Translated from src/types/command.ts
 */
@Data
@lombok.NoArgsConstructor(force = true)
@lombok.AllArgsConstructor
public class Command {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Command.class);


    public enum CommandType {
        PROMPT,
        LOCAL,
        LOCAL_JSX
    }

    public enum CommandAvailability {
        CLAUDE_AI("claude-ai"),
        CONSOLE("console");

        private final String value;
        CommandAvailability(String value) { this.value = value; }
        public String getValue() { return value; }
    }

    private String name;
    private String description;
    private CommandType type;
    private List<String> aliases;
    private Boolean isMcp;
    private String argumentHint;
    private String whenToUse;
    private String version;
    private Boolean disableModelInvocation;
    private Boolean userInvocable;
    private String loadedFrom;
    private String kind;
    private Boolean immediate;
    private Boolean isSensitive;
    private List<CommandAvailability> availability;
    private Boolean isHidden;
    private Supplier<Boolean> isEnabled;
    private Supplier<String> userFacingName;

    // For prompt commands
    private String progressMessage;
    private List<String> argNames;
    private List<String> allowedTools;
    private String model;
    private String source;
    private String context; // "inline" | "fork"
    private String agent;
    private String effort;

    /**
     * Base directory for this skill's reference files.
     * Translated from skillRoot in PromptCommand (command.ts / bundledSkills.ts).
     * When set, the model can Read/Grep files relative to this directory.
     */
    private String skillRoot;

    /**
     * Gitignore-style path patterns that determine when a conditional skill is activated.
     * Translated from paths?: string[] in PromptCommand (loadSkillsDir.ts).
     * null / empty means the skill is always active (unconditional).
     */
    private List<String> paths;

    // Explicit getters/setters for fields that @Data may not generate
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public CommandType getType() { return type; }
    public void setType(CommandType v) { type = v; }
    public List<String> getAliases() { return aliases; }
    public void setAliases(List<String> v) { aliases = v; }
    public Boolean getIsMcp() { return isMcp; }
    public void setIsMcp(Boolean v) { isMcp = v; }
    public String getArgumentHint() { return argumentHint; }
    public void setArgumentHint(String v) { argumentHint = v; }
    public String getWhenToUse() { return whenToUse; }
    public void setWhenToUse(String v) { whenToUse = v; }
    public String getVersion() { return version; }
    public void setVersion(String v) { version = v; }
    public Boolean getDisableModelInvocation() { return disableModelInvocation; }
    public void setDisableModelInvocation(Boolean v) { disableModelInvocation = v; }
    public Boolean getUserInvocable() { return userInvocable; }
    public void setUserInvocable(Boolean v) { userInvocable = v; }
    public String getLoadedFrom() { return loadedFrom; }
    public void setLoadedFrom(String v) { loadedFrom = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { kind = v; }
    public Boolean getImmediate() { return immediate; }
    public void setImmediate(Boolean v) { immediate = v; }
    public Boolean getIsSensitive() { return isSensitive; }
    public void setIsSensitive(Boolean v) { isSensitive = v; }
    public List<CommandAvailability> getAvailability() { return availability; }
    public void setAvailability(List<CommandAvailability> v) { availability = v; }
    public Boolean getIsHidden() { return isHidden; }
    public void setIsHidden(Boolean v) { isHidden = v; }
    public Supplier<Boolean> getIsEnabled() { return isEnabled; }
    public void setIsEnabled(Supplier<Boolean> v) { isEnabled = v; }
    public Supplier<String> getUserFacingNameSupplier() { return userFacingName; }
    public void setUserFacingName(Supplier<String> v) { userFacingName = v; }
    public String getProgressMessage() { return progressMessage; }
    public void setProgressMessage(String v) { progressMessage = v; }
    public List<String> getArgNames() { return argNames; }
    public void setArgNames(List<String> v) { argNames = v; }
    public List<String> getAllowedTools() { return allowedTools; }
    public void setAllowedTools(List<String> v) { allowedTools = v; }
    public String getModel() { return model; }
    public void setModel(String v) { model = v; }
    public String getSource() { return source; }
    public void setSource(String v) { source = v; }
    public String getContext() { return context; }
    public void setContext(String v) { context = v; }
    public String getAgent() { return agent; }
    public void setAgent(String v) { agent = v; }
    public String getEffort() { return effort; }
    public void setEffort(String v) { effort = v; }
    public String getSkillRoot() { return skillRoot; }
    public void setSkillRoot(String v) { skillRoot = v; }
    public List<String> getPaths() { return paths; }
    public void setPaths(List<String> v) { paths = v; }

    /**
     * Get the user-facing name.
     * Translated from getCommandName() in command.ts
     */
    public String getUserFacingName() {
        if (userFacingName != null) return userFacingName.get();
        return name;
    }

    /**
     * Check if the command is enabled.
     * Translated from isCommandEnabled() in command.ts
     */
    public boolean isCommandEnabled() {
        if (isEnabled != null) return isEnabled.get();
        return true;
    }

    public static CommandBuilder builder() { return new CommandBuilder(); }
    public static class CommandBuilder {
        private final Command c = new Command();
        public CommandBuilder name(String v) { c.name = v; return this; }
        public CommandBuilder description(String v) { c.description = v; return this; }
        public CommandBuilder type(CommandType v) { c.type = v; return this; }
        public CommandBuilder aliases(List<String> v) { c.aliases = v; return this; }
        public CommandBuilder isMcp(Boolean v) { c.isMcp = v; return this; }
        public CommandBuilder argumentHint(String v) { c.argumentHint = v; return this; }
        public CommandBuilder whenToUse(String v) { c.whenToUse = v; return this; }
        public CommandBuilder version(String v) { c.version = v; return this; }
        public CommandBuilder disableModelInvocation(Boolean v) { c.disableModelInvocation = v; return this; }
        public CommandBuilder userInvocable(Boolean v) { c.userInvocable = v; return this; }
        public CommandBuilder loadedFrom(String v) { c.loadedFrom = v; return this; }
        public CommandBuilder kind(String v) { c.kind = v; return this; }
        public CommandBuilder immediate(Boolean v) { c.immediate = v; return this; }
        public CommandBuilder isSensitive(Boolean v) { c.isSensitive = v; return this; }
        public CommandBuilder availability(List<CommandAvailability> v) { c.availability = v; return this; }
        public CommandBuilder isHidden(Boolean v) { c.isHidden = v; return this; }
        public CommandBuilder isEnabled(Supplier<Boolean> v) { c.isEnabled = v; return this; }
        public CommandBuilder userFacingName(Supplier<String> v) { c.userFacingName = v; return this; }
        public CommandBuilder progressMessage(String v) { c.progressMessage = v; return this; }
        public CommandBuilder argNames(List<String> v) { c.argNames = v; return this; }
        public CommandBuilder allowedTools(List<String> v) { c.allowedTools = v; return this; }
        public CommandBuilder model(String v) { c.model = v; return this; }
        public CommandBuilder source(String v) { c.source = v; return this; }
        public CommandBuilder context(String v) { c.context = v; return this; }
        public CommandBuilder agent(String v) { c.agent = v; return this; }
        public CommandBuilder effort(String v) { c.effort = v; return this; }
        public CommandBuilder skillRoot(String v) { c.skillRoot = v; return this; }
        public CommandBuilder paths(List<String> v) { c.paths = v; return this; }
        public Command build() { return c; }
    }
}
