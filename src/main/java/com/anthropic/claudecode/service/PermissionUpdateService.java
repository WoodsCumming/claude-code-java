package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.*;
import com.anthropic.claudecode.util.PermissionModeUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Permission update service.
 * Translated from src/utils/permissions/PermissionUpdate.ts
 *
 * Applies permission updates to the tool permission context.
 */
@Slf4j
@Service
public class PermissionUpdateService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PermissionUpdateService.class);


    /**
     * Apply a permission update to the context.
     * Translated from applyPermissionUpdate() in PermissionUpdate.ts
     */
    public ToolPermissionContext applyPermissionUpdate(
            ToolPermissionContext context,
            PermissionResult.PermissionUpdate update) {

        if (update == null) return context;

        return switch (update) {
            case PermissionResult.PermissionUpdate.SetMode setMode -> {
                PermissionMode newMode = PermissionModeUtils.permissionModeFromString(
                    setMode.getMode() != null ? setMode.getMode().getValue() : "default"
                );
                yield context.toBuilder().mode(newMode).build();
            }
            case PermissionResult.PermissionUpdate.AddRules addRules -> {
                Map<String, List<String>> updatedRules = updateRules(
                    context, addRules.getBehavior(), addRules.getRules(), "add"
                );
                yield applyRulesToContext(context, addRules.getBehavior(), updatedRules);
            }
            case PermissionResult.PermissionUpdate.RemoveRules removeRules -> {
                Map<String, List<String>> updatedRules = updateRules(
                    context, removeRules.getBehavior(), removeRules.getRules(), "remove"
                );
                yield applyRulesToContext(context, removeRules.getBehavior(), updatedRules);
            }
            default -> context;
        };
    }

    /**
     * Apply multiple permission updates.
     * Translated from applyPermissionUpdates() in PermissionUpdate.ts
     */
    public ToolPermissionContext applyPermissionUpdates(
            ToolPermissionContext context,
            List<PermissionResult.PermissionUpdate> updates) {

        if (updates == null || updates.isEmpty()) return context;

        ToolPermissionContext result = context;
        for (PermissionResult.PermissionUpdate update : updates) {
            result = applyPermissionUpdate(result, update);
        }
        return result;
    }

    private Map<String, List<String>> updateRules(
            ToolPermissionContext context,
            PermissionResult.PermissionBehavior behavior,
            List<PermissionResult.PermissionRuleValue> rules,
            String operation) {

        Map<String, List<String>> existingRules = switch (behavior) {
            case ALLOW -> context.getAlwaysAllowRules();
            case DENY -> context.getAlwaysDenyRules();
            case ASK -> context.getAlwaysAskRules();
        };

        Map<String, List<String>> updated = new HashMap<>(existingRules);

        List<String> sessionRules = new ArrayList<>(
            updated.getOrDefault("session", List.of())
        );

        if ("add".equals(operation)) {
            for (PermissionResult.PermissionRuleValue rule : rules) {
                String ruleStr = rule.getToolName();
                if (rule.getRuleContent() != null) {
                    ruleStr += "(" + rule.getRuleContent() + ")";
                }
                sessionRules.add(ruleStr);
            }
        } else if ("remove".equals(operation)) {
            for (PermissionResult.PermissionRuleValue rule : rules) {
                String ruleStr = rule.getToolName();
                sessionRules.remove(ruleStr);
            }
        }

        updated.put("session", sessionRules);
        return updated;
    }

    private ToolPermissionContext applyRulesToContext(
            ToolPermissionContext context,
            PermissionResult.PermissionBehavior behavior,
            Map<String, List<String>> rules) {

        return switch (behavior) {
            case ALLOW -> context.toBuilder().alwaysAllowRules(rules).build();
            case DENY -> context.toBuilder().alwaysDenyRules(rules).build();
            case ASK -> context.toBuilder().alwaysAskRules(rules).build();
        };
    }
}
