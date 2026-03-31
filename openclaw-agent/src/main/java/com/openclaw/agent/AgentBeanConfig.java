package com.openclaw.agent;

import com.openclaw.agent.models.AnthropicProvider;
import com.openclaw.agent.models.ModelProvider;
import com.openclaw.agent.models.ModelProviderRegistry;
import com.openclaw.agent.models.OpenAICompatibleProvider;
import com.openclaw.agent.runtime.AgentRunner;
import com.openclaw.agent.tools.CustomAgentTool;
import com.openclaw.agent.tools.OpenClawToolFactory;
import com.openclaw.agent.tools.ToolRegistry;
import com.openclaw.agent.tools.builtin.ExecTool;
import com.openclaw.agent.tools.builtin.FileTools;
import com.openclaw.common.config.ConfigService;
import com.openclaw.common.config.OpenClawConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 运行时相关 Bean 的 Spring 配置。
 */
@Slf4j
@Configuration
public class AgentBeanConfig {

    private final ConfigService configService;

    public AgentBeanConfig(ConfigService configService) {
        this.configService = configService;
    }

    @Bean
    public ToolRegistry toolRegistry(
            ModelProviderRegistry modelProviderRegistry,
            ObjectProvider<CustomAgentTool> customToolsProvider) {
        ToolRegistry registry = new ToolRegistry();

        // 注册内置编码工具
        registry.register(new ExecTool());
        registry.register(FileTools.readFile());
        registry.register(FileTools.writeFile());
        registry.register(FileTools.listDir());
        registry.register(FileTools.grepSearch());

        // 注册项目自定义的工具 Bean，让工作区里的业务代码可以把本地 Java
        // 方法直接暴露给 Agent，而不需要继续改动核心运行时代码。
        customToolsProvider.orderedStream().forEach(tool -> {
            if (registry.get(tool.getName()).isPresent()) {
                log.warn("跳过自定义工具 '{}'，因为同名工具已经注册", tool.getName());
                return;
            }
            registry.register(tool);
            log.info("已注册自定义工具 Bean: {}", tool.getName());
        });

        // 注册 OpenClaw 扩展工具（浏览器、网页、记忆、消息等）
        try {
            OpenClawConfig config = configService.loadConfig();
            var extensionTools = OpenClawToolFactory.createTools(
                    OpenClawToolFactory.OpenClawToolOptions.builder()
                            .config(config)
                            .modelProviderRegistry(modelProviderRegistry)
                            .build());
            registry.registerAll(extensionTools);
            log.info("已注册 {} 个扩展工具", extensionTools.size());
        } catch (Exception e) {
            log.warn("扩展工具注册失败: {}", e.getMessage());
        }

        log.info("总计已注册 {} 个工具", registry.size());
        return registry;
    }

    @Bean
    public ModelProviderRegistry modelProviderRegistry() {
        ModelProviderRegistry registry = new ModelProviderRegistry();

        // 从配置中加载模型别名和 provider
        try {
            OpenClawConfig config = configService.loadConfig();
            registry.loadAliasesFromConfig(config);
            registerProvidersFromConfig(registry, config);
        } catch (Exception e) {
            log.debug("未能从配置中加载模型别名: {}", e.getMessage());
        }

        // 从环境变量注册 provider（可覆盖配置中的同名 provider）
        registerProvidersFromEnv(registry);

        log.info("已注册 {} 个模型 provider、{} 个别名",
                registry.size(), registry.getAliases().size());
        return registry;
    }

    @Bean
    public AgentRunner agentRunner(ModelProviderRegistry modelProviderRegistry, ToolRegistry toolRegistry) {
        return new AgentRunner(modelProviderRegistry, toolRegistry);
    }

    private void registerProvidersFromConfig(ModelProviderRegistry registry, OpenClawConfig config) {
        if (config.getModels() == null || config.getModels().getProviders() == null) {
            return;
        }
        config.getModels().getProviders().forEach((id, pc) -> {
            if (!pc.isEnabled()) return;
            String apiKey = pc.getApiKey();
            if (apiKey == null || apiKey.isBlank()) return;

            String baseUrl = resolveBaseUrl(pc);
            ModelProvider provider = createProvider(id, apiKey, baseUrl);
            registry.register(provider);
            log.info("已从配置注册 provider: {} (baseUrl={})", id, provider.getApiBaseUrl());
        });
    }

    private void registerProvidersFromEnv(ModelProviderRegistry registry) {
        registerEnvProvider(registry, "anthropic", "ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL");
        registerEnvProvider(registry, "openai", "OPENAI_API_KEY", "OPENAI_BASE_URL");

        // Ollama（无需 API Key）
        String ollamaUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://127.0.0.1:11434/v1");
        registry.register(new OpenAICompatibleProvider("ollama", null, ollamaUrl));
    }

    private void registerEnvProvider(ModelProviderRegistry registry, String id, String keyEnv, String urlEnv) {
        String apiKey = System.getenv(keyEnv);
        if (apiKey == null || apiKey.isBlank()) return;

        ModelProvider provider = createProvider(id, apiKey, System.getenv(urlEnv));
        registry.register(provider);
        log.info("已从环境变量注册 provider: {} (baseUrl={})", id, provider.getApiBaseUrl());
    }

    private static String resolveBaseUrl(OpenClawConfig.ProviderConfig pc) {
        String url = pc.getBaseUrl();
        return (url != null && !url.isBlank()) ? url : pc.getApiBaseUrl();
    }

    private static ModelProvider createProvider(String id, String apiKey, String baseUrl) {
        return "anthropic".equals(id)
                ? new AnthropicProvider(apiKey, baseUrl)
                : new OpenAICompatibleProvider(id, apiKey, baseUrl);
    }
}
