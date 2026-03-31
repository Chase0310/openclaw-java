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
@Order(126)
public class HanxueFallbackTool implements CustomAgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HanxueFallbackService service;

    public HanxueFallbackTool(HanxueFallbackService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "hanxue_fallback_service";
    }

    @Override
    public String getDescription() {
        return "兜底处理模糊意图、闲聊、越界问题与转人工分流，并在可承接时切换到对应 skill。";
    }

    @Override
    public JsonNode getParameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("action")
                .put("type", "string")
                .put("description", "固定为 generate_fallback_response");
        properties.putObject("userMessage").put("type", "string");
        properties.putObject("childName").put("type", "string");
        properties.putObject("childGrade").put("type", "string");
        properties.putObject("currentReportContext").put("type", "object");
        properties.putObject("todayReportList").put("type", "array");
        properties.putObject("recentLearningSummary").put("type", "object");
        properties.putObject("todaySessionSummary").put("type", "object");
        properties.putObject("conversationHistory").put("type", "array");

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
            if (!"generate_fallback_response".equals(action)) {
                return ToolResult.fail("不支持的 action: '" + action + "'。请使用 generate_fallback_response。");
            }
            return ToolParamUtils.jsonResult(service.generate(params));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("hanxue_fallback_service 执行失败: " + e.getMessage());
        }
    }
}
