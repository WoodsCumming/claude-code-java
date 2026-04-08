package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.config.ClaudeCodeConfig;
import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.service.SettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Configuration management tool.
 * Translated from src/tools/ConfigTool/ConfigTool.ts
 *
 * Allows getting and setting Claude Code configuration values.
 */
@Slf4j
@Component
public class ConfigTool extends AbstractTool<ConfigTool.Input, ConfigTool.Output> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConfigTool.class);

    public static final String TOOL_NAME = "Config";

    private final ClaudeCodeConfig config;
    private final SettingsService settingsService;

    @Autowired
    public ConfigTool(ClaudeCodeConfig config, SettingsService settingsService) {
        this.config = config;
        this.settingsService = settingsService;
    }

    @Override
    public String getName() { return TOOL_NAME; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "setting", Map.of(
                    "type", "string",
                    "description", "The setting key (e.g., 'theme', 'model', 'permissions.defaultMode')"
                ),
                "value", Map.of(
                    "description", "The new value. Omit to get current value."
                )
            ),
            "required", List.of("setting")
        );
    }

    @Override
    public CompletableFuture<ToolResult<Output>> call(
            Input args,
            ToolUseContext context,
            Tool.CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<Tool.ToolProgress> onProgress) {

        String setting = args.getSetting();
        Object value = args.getValue();

        if (value == null) {
            // GET operation
            Object currentValue = getSettingValue(setting);
            return futureResult(Output.builder()
                .success(true)
                .operation("get")
                .setting(setting)
                .value(currentValue)
                .build());
        } else {
            // SET operation
            Object previousValue = getSettingValue(setting);
            setSettingValue(setting, value);
            return futureResult(Output.builder()
                .success(true)
                .operation("set")
                .setting(setting)
                .previousValue(previousValue)
                .newValue(value)
                .build());
        }
    }

    private Object getSettingValue(String setting) {
        return switch (setting) {
            case "model" -> config.getModel();
            case "verbose" -> config.isVerbose();
            case "permission-mode", "permissions.defaultMode" -> config.getPermissionMode();
            case "auto-compact-enabled" -> config.isAutoCompactEnabled();
            case "show-turn-duration" -> config.isShowTurnDuration();
            default -> null;
        };
    }

    private void setSettingValue(String setting, Object value) {
        switch (setting) {
            case "model" -> config.setModel(value.toString());
            case "verbose" -> config.setVerbose(Boolean.parseBoolean(value.toString()));
            case "permission-mode", "permissions.defaultMode" -> config.setPermissionMode(value.toString());
            case "auto-compact-enabled" -> config.setAutoCompactEnabled(Boolean.parseBoolean(value.toString()));
            case "show-turn-duration" -> config.setShowTurnDuration(Boolean.parseBoolean(value.toString()));
            default -> log.warn("Unknown setting: {}", setting);
        }
    }

    @Override
    public CompletableFuture<String> description(Input input, DescriptionOptions options) {
        String op = input.getValue() != null ? "Setting" : "Getting";
        return CompletableFuture.completedFuture(op + " config: " + input.getSetting());
    }

    @Override
    public boolean isReadOnly(Input input) {
        return input.getValue() == null; // GET is read-only, SET is not
    }

    @Override
    public Map<String, Object> mapToolResultToBlockParam(Output content, String toolUseId) {
        String text = "get".equals(content.getOperation())
            ? content.getSetting() + " = " + content.getValue()
            : "Set " + content.getSetting() + " = " + content.getNewValue()
                + " (was: " + content.getPreviousValue() + ")";
        return Map.of("type", "tool_result", "tool_use_id", toolUseId, "content", text);
    }

    public static class Input {
        private String setting;
        private Object value;
        public Input() {}
        public Input(String setting, Object value) { this.setting = setting; this.value = value; }
        public String getSetting() { return setting; }
        public void setSetting(String v) { this.setting = v; }
        public Object getValue() { return value; }
        public void setValue(Object v) { this.value = v; }
        public static InputBuilder builder() { return new InputBuilder(); }
        public static class InputBuilder {
            private String setting;
            private Object value;
            public InputBuilder setting(String v) { this.setting = v; return this; }
            public InputBuilder value(Object v) { this.value = v; return this; }
            public Input build() { return new Input(setting, value); }
        }
    }

    public static class Output {
        private boolean success;
        private String operation;
        private String setting;
        private Object value;
        private Object previousValue;
        private Object newValue;
        private String error;
        public Output() {}
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean v) { success = v; }
        public String getOperation() { return operation; }
        public void setOperation(String v) { operation = v; }
        public String getSetting() { return setting; }
        public void setSetting(String v) { setting = v; }
        public Object getValue() { return value; }
        public void setValue(Object v) { value = v; }
        public Object getPreviousValue() { return previousValue; }
        public void setPreviousValue(Object v) { previousValue = v; }
        public Object getNewValue() { return newValue; }
        public void setNewValue(Object v) { newValue = v; }
        public String getError() { return error; }
        public void setError(String v) { error = v; }
        public static OutputBuilder builder() { return new OutputBuilder(); }
        public static class OutputBuilder {
            private boolean success;
            private String operation;
            private String setting;
            private Object value;
            private Object previousValue;
            private Object newValue;
            private String error;
            public OutputBuilder success(boolean v) { this.success = v; return this; }
            public OutputBuilder operation(String v) { this.operation = v; return this; }
            public OutputBuilder setting(String v) { this.setting = v; return this; }
            public OutputBuilder value(Object v) { this.value = v; return this; }
            public OutputBuilder previousValue(Object v) { this.previousValue = v; return this; }
            public OutputBuilder newValue(Object v) { this.newValue = v; return this; }
            public OutputBuilder error(String v) { this.error = v; return this; }
            public Output build() {
                Output o = new Output();
                o.success = success; o.operation = operation; o.setting = setting;
                o.value = value; o.previousValue = previousValue; o.newValue = newValue;
                o.error = error;
                return o;
            }
        }
    }
}
