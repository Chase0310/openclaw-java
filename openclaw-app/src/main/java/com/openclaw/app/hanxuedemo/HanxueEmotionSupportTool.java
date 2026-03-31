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
@Order(123)
public class HanxueEmotionSupportTool implements CustomAgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HanxueEmotionSupportService service;

    public HanxueEmotionSupportTool(HanxueEmotionSupportService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "hanxue_emotion_support_service";
    }

    @Override
    public String getDescription() {
        return "先共情、再引用行为事实、最后给出温和下一步，并在需要时承接到学习规划或学情解读。";
    }

    @Override
    public JsonNode getParameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("action")
                .put("type", "string")
                .put("description", "固定为 support_parent_emotion");
        properties.putObject("userMessage").put("type", "string");
        properties.putObject("recentLearningSummary").put("type", "object");
        properties.putObject("todaySessionSummary").put("type", "object");
        properties.putObject("currentReportContext").put("type", "object");

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
            if (!"support_parent_emotion".equals(action)) {
                return ToolResult.fail("不支持的 action: '" + action + "'。请使用 support_parent_emotion。");
            }
            return ToolParamUtils.jsonResult(service.support(params));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("hanxue_emotion_support_service 执行失败: " + e.getMessage());
        }
    }
}
