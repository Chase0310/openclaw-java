package com.openclaw.agent.tools;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for agent tools.
 * Corresponds to TypeScript's tool resolution in pi-tools.ts /
 * openclaw-tools.ts.
 */
@Slf4j
public class ToolRegistry {

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();
    private final Map<String, String> aliases = new ConcurrentHashMap<>();

    /**
     * Register a tool. Overwrites any existing tool with the same name.
     */
    public void register(AgentTool tool) {
        tools.put(tool.getName(), tool);
        log.debug("Registered tool: {}", tool.getName());
    }

    public void registerAlias(String aliasName, String canonicalToolName) {
        if (aliasName == null || aliasName.isBlank() || canonicalToolName == null || canonicalToolName.isBlank()) {
            return;
        }
        if (!tools.containsKey(canonicalToolName)) {
            throw new IllegalArgumentException("Cannot alias missing tool: " + canonicalToolName);
        }
        aliases.put(aliasName, canonicalToolName);
        log.debug("Registered tool alias: {} -> {}", aliasName, canonicalToolName);
    }

    /**
     * Register multiple tools, skipping names that already exist.
     */
    public void registerAll(Collection<AgentTool> toolList) {
        for (AgentTool tool : toolList) {
            tools.putIfAbsent(tool.getName(), tool);
        }
    }

    /**
     * Register a plugin-resolved tool by wrapping it as an AgentTool.
     * The handler from ResolvedPluginTool is adapted to the AgentTool interface.
     */
    public void registerPluginTool(
            com.openclaw.plugin.tools.PluginToolResolver.ResolvedPluginTool pluginTool) {
        if (tools.containsKey(pluginTool.getName())) {
            log.warn("Plugin tool '{}' conflicts with existing tool, skipping",
                    pluginTool.getName());
            return;
        }
        AgentTool wrapped = new PluginToolAdapter(pluginTool);
        tools.put(pluginTool.getName(), wrapped);
        log.info("Registered plugin tool: {} (plugin: {})",
                pluginTool.getName(), pluginTool.getPluginId());
    }

    /**
     * Get a tool by name.
     */
    public Optional<AgentTool> get(String name) {
        AgentTool direct = tools.get(name);
        if (direct != null) {
            return Optional.of(direct);
        }
        String canonical = aliases.get(name);
        if (canonical == null) {
            return Optional.empty();
        }
        AgentTool delegate = tools.get(canonical);
        return delegate == null ? Optional.empty() : Optional.of(new AliasTool(name, delegate));
    }

    public String resolveCanonicalName(String name) {
        if (name == null) {
            return null;
        }
        return aliases.getOrDefault(name, name);
    }

    /**
     * List all registered tool names.
     */
    public Set<String> getToolNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>(tools.keySet());
        names.addAll(aliases.keySet());
        return Collections.unmodifiableSet(names);
    }

    /**
     * List all registered tools.
     */
    public List<AgentTool> listAll() {
        return new ArrayList<>(tools.values());
    }

    /**
     * Convert all tools to LLM-compatible definitions (name + description +
     * schema).
     */
    public List<Map<String, Object>> toDefinitions() {
        List<AgentTool> defs = new ArrayList<>(tools.values());
        aliases.forEach((alias, canonical) -> {
            AgentTool target = tools.get(canonical);
            if (target != null) {
                defs.add(new AliasTool(alias, target));
            }
        });
        return defs.stream().map(this::toDefinition).collect(Collectors.toList());
    }

    /**
     * Returns the number of registered tools.
     */
    public int size() {
        return tools.size();
    }

    /**
     * Filter tools by a ToolPolicy, returning a new list of allowed tools.
     */
    public List<AgentTool> filterByPolicy(ToolPolicy policy) {
        if (policy == null || policy == ToolPolicy.ALLOW_ALL) {
            return listAll();
        }
        List<AgentTool> filtered = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            if (policy.isAllowed(tool.getName())) {
                filtered.add(tool);
            }
        }
        return filtered;
    }

    /**
     * Convert tools to provider-specific format (via ToolDefinitionAdapter).
     *
     * @param provider provider name (anthropic, openai, google)
     * @return formatted tool definitions
     */
    public List<Map<String, Object>> toProviderDefinitions(String provider) {
        return ToolDefinitionAdapter.toProviderFormat(listAll(), provider);
    }

    private Map<String, Object> toDefinition(AgentTool tool) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("name", tool.getName());
        def.put("description", tool.getDescription());
        def.put("input_schema", tool.getParameterSchema());
        return def;
    }

    private static final class AliasTool implements AgentTool {
        private final String aliasName;
        private final AgentTool delegate;

        private AliasTool(String aliasName, AgentTool delegate) {
            this.aliasName = aliasName;
            this.delegate = delegate;
        }

        @Override
        public String getName() {
            return aliasName;
        }

        @Override
        public String getDescription() {
            return delegate.getDescription();
        }

        @Override
        public com.fasterxml.jackson.databind.JsonNode getParameterSchema() {
            return delegate.getParameterSchema();
        }

        @Override
        public java.util.concurrent.CompletableFuture<ToolResult> execute(ToolContext context) {
            return delegate.execute(context);
        }
    }
}
