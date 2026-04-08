package com.anthropic.claudecode.tool;

import com.anthropic.claudecode.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Brief tool for sending messages to the user with optional attachments.
 * Translated from src/tools/BriefTool/BriefTool.ts
 *
 * Used in agent mode to communicate updates and results back to the user.
 */
@Slf4j
@Component
@SuppressWarnings({"unchecked","rawtypes"})
public class BriefTool extends AbstractTool<java.util.Map<String, Object>, java.util.Map<String, Object>> {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BriefTool.class);


    public static final String BRIEF_TOOL_NAME = "Brief";
    public static final String LEGACY_BRIEF_TOOL_NAME = "NotifyUser";

    @Autowired
    public BriefTool() {
    }

    @Override
    public String getName() {
        return BRIEF_TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Send a message to the user with optional file attachments";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> messageProp = new LinkedHashMap<>();
        messageProp.put("type", "string");
        messageProp.put("description", "The message for the user. Supports markdown formatting.");
        properties.put("message", messageProp);

        Map<String, Object> attachmentsProp = new LinkedHashMap<>();
        attachmentsProp.put("type", "array");
        attachmentsProp.put("description",
            "Optional file paths (absolute or relative to cwd) to attach.");
        Map<String, Object> attachmentItems = new LinkedHashMap<>();
        attachmentItems.put("type", "string");
        attachmentsProp.put("items", attachmentItems);
        properties.put("attachments", attachmentsProp);

        Map<String, Object> statusProp = new LinkedHashMap<>();
        statusProp.put("type", "string");
        statusProp.put("enum", List.of("normal", "proactive"));
        statusProp.put("description",
            "Use 'proactive' when surfacing something the user hasn't asked for. " +
            "Use 'normal' when replying to something the user just said.");
        properties.put("status", statusProp);

        schema.put("properties", properties);
        schema.put("required", List.of("message", "status"));
        return schema;
    }

    @Override
    public CompletableFuture<ToolResult<Map<String, Object>>> call(
            Map<String, Object> args,
            ToolUseContext context,
            CanUseToolFn canUseTool,
            Message.AssistantMessage parentMessage,
            Consumer<ToolProgress> onProgress) {

        String message = (String) args.get("message");
        String status = (String) args.getOrDefault("status", "normal");
        List<String> attachments = (List<String>) args.get("attachments");

        if (message == null || message.isBlank()) {
            return CompletableFuture.completedFuture(
                ToolResult.error("No message provided")
            );
        }

        log.info("[Brief] [{}] {}", status, message);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("message", message);
        result.put("sentAt", Instant.now().toString());

        if (attachments != null && !attachments.isEmpty()) {
            List<Map<String, Object>> resolvedAttachments = new ArrayList<>();
            for (String path : attachments) {
                java.io.File file = new java.io.File(path);
                if (file.exists()) {
                    Map<String, Object> attachment = new LinkedHashMap<>();
                    attachment.put("path", path);
                    attachment.put("size", file.length());
                    attachment.put("isImage", isImageFile(path));
                    resolvedAttachments.add(attachment);
                }
            }
            result.put("attachments", resolvedAttachments);
        }

        return CompletableFuture.completedFuture(ToolResult.success(result));
    }

    private boolean isImageFile(String path) {
        String lower = path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg");
    }
}
