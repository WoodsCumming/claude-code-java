package com.anthropic.claudecode.model;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Builder;

/**
 * Output style configuration.
 * Translated from OutputStyleConfig in src/constants/outputStyles.ts
 */
@Data
@lombok.Builder

public class OutputStyleConfig {

    public static final String DEFAULT_OUTPUT_STYLE_NAME = "default";

    private String name;
    private String description;
    private String prompt;
    private String source; // "built-in" | "plugin" | SettingSource value
    private Boolean keepCodingInstructions;
    private Boolean forceForPlugin;

    public OutputStyleConfig() {}

    public static OutputStyleConfigBuilder builder() { return new OutputStyleConfigBuilder(); }
    public static class OutputStyleConfigBuilder {
        private String name; private String description; private String prompt; private String source;
        private Boolean keepCodingInstructions; private Boolean forceForPlugin;
        public OutputStyleConfigBuilder name(String v) { this.name = v; return this; }
        public OutputStyleConfigBuilder description(String v) { this.description = v; return this; }
        public OutputStyleConfigBuilder prompt(String v) { this.prompt = v; return this; }
        public OutputStyleConfigBuilder source(String v) { this.source = v; return this; }
        public OutputStyleConfigBuilder keepCodingInstructions(Boolean v) { this.keepCodingInstructions = v; return this; }
        public OutputStyleConfigBuilder forceForPlugin(Boolean v) { this.forceForPlugin = v; return this; }
        public OutputStyleConfig build() {
            OutputStyleConfig o = new OutputStyleConfig();
            o.name = name; o.description = description; o.prompt = prompt; o.source = source;
            o.keepCodingInstructions = keepCodingInstructions; o.forceForPlugin = forceForPlugin;
            return o;
        }
    }

    public static OutputStyleConfig defaultStyle() {
        return OutputStyleConfig.builder()
            .name(DEFAULT_OUTPUT_STYLE_NAME)
            .description("Default output style")
            .prompt("")
            .source("built-in")
            .build();
    }
}
