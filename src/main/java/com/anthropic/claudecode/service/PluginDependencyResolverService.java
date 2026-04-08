package com.anthropic.claudecode.service;

import com.anthropic.claudecode.model.PluginTypes;
import com.anthropic.claudecode.util.PluginIdentifier;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * Plugin dependency resolver service.
 * Translated from src/utils/plugins/dependencyResolver.ts
 *
 * Resolves plugin dependencies using apt-style semantics.
 */
@Slf4j
@Service
public class PluginDependencyResolverService {



    /**
     * Find reverse dependents of a plugin.
     * Translated from findReverseDependents() in dependencyResolver.ts
     */
    public List<String> findReverseDependents(
            String pluginId,
            List<PluginTypes.LoadedPlugin> installedPlugins) {

        List<String> dependents = new ArrayList<>();
        for (PluginTypes.LoadedPlugin plugin : installedPlugins) {
            if (plugin.getDependencies() != null && plugin.getDependencies().contains(pluginId)) {
                dependents.add(plugin.getName());
            }
        }
        return dependents;
    }

    /**
     * Format reverse dependents suffix for display.
     * Translated from formatReverseDependentsSuffix() in dependencyResolver.ts
     */
    public String formatReverseDependentsSuffix(List<String> dependents) {
        if (dependents.isEmpty()) return "";
        if (dependents.size() == 1) return " (required by " + dependents.get(0) + ")";
        return " (required by " + String.join(", ", dependents) + ")";
    }

    /**
     * Resolve the dependency closure for a plugin.
     * Translated from resolveDependencyClosure() in dependencyResolver.ts
     */
    public DependencyResolutionResult resolveDependencyClosure(
            String pluginId,
            List<PluginTypes.LoadedPlugin> availablePlugins) {

        Set<String> resolved = new LinkedHashSet<>();
        Set<String> visited = new HashSet<>();
        List<String> errors = new ArrayList<>();

        resolveRecursive(pluginId, availablePlugins, resolved, visited, errors);

        return new DependencyResolutionResult(new ArrayList<>(resolved), errors);
    }

    private void resolveRecursive(
            String pluginId,
            List<PluginTypes.LoadedPlugin> available,
            Set<String> resolved,
            Set<String> visited,
            List<String> errors) {

        if (visited.contains(pluginId)) {
            errors.add("Circular dependency detected: " + pluginId);
            return;
        }

        visited.add(pluginId);

        // Find the plugin
        PluginTypes.LoadedPlugin plugin = available.stream()
            .filter(p -> pluginId.equals(p.getName()))
            .findFirst()
            .orElse(null);

        if (plugin == null) {
            errors.add("Plugin not found: " + pluginId);
            return;
        }

        // Resolve dependencies first
        if (plugin.getDependencies() != null) {
            for (String dep : plugin.getDependencies()) {
                resolveRecursive(dep, available, resolved, visited, errors);
            }
        }

        resolved.add(pluginId);
    }

    @Data
    public static class DependencyResolutionResult {
        private List<String> resolved;
        private List<String> errors;

        public DependencyResolutionResult() {}
        public DependencyResolutionResult(List<String> resolved, List<String> errors) {
            this.resolved = resolved; this.errors = errors;
        }
        public List<String> getResolved() { return resolved; }
        public void setResolved(List<String> v) { resolved = v; }
        public List<String> getErrors() { return errors; }
        public void setErrors(List<String> v) { errors = v; }
    }
}
