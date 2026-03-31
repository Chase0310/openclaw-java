package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openclaw.agent.tools.CustomAgentTool;
import com.openclaw.agent.tools.ToolParamUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@Order(124)
public class HanxueFeatureGuideTool implements CustomAgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HanxueFeatureGuideService service;

    public HanxueFeatureGuideTool(HanxueFeatureGuideService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "hanxue_feature_guide_service";
    }

    @Override
    public String getDescription() {
        return "查询小程序功能知识库并返回固定跳转入口或特殊场景处理建议。";
    }

    @Override
    public JsonNode getParameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("action")
                .put("type", "string")
                .put("description", "固定为 lookup_feature_guide");
        properties.putObject("userMessage").put("type", "string");
        properties.putObject("featureKey").put("type", "string");
        properties.putObject("pageKey").put("type", "string");

        schema.putArray("required").add("action");
        return schema;
    }

    @Override
    public CompletableFuture<ToolResult> execute(ToolContext context) {
        return CompletableFuture.supplyAsync(() -> doExecute(context));
    }

    private ToolResult doExecute(ToolContext context) {
        try {
            JsonNode params = context.getParameters();
            String action = ToolParamUtils.readStringParam(params, "action", true);
            if (!"lookup_feature_guide".equals(action)) {
                return ToolResult.fail("不支持的 action: '" + action + "'。请使用 lookup_feature_guide。");
            }
            return ToolParamUtils.jsonResult(service.lookup(params));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("hanxue_feature_guide_service 执行失败: " + e.getMessage());
        }
    }
}
