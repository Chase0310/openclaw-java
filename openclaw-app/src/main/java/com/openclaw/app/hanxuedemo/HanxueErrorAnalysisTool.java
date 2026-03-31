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
@Order(121)
public class HanxueErrorAnalysisTool implements CustomAgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HanxueErrorAnalysisService service;

    public HanxueErrorAnalysisTool(HanxueErrorAnalysisService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "hanxue_error_analysis_service";
    }

    @Override
    public String getDescription() {
        return "基于题目、学生答案和正确答案输出保守错因分析，并在需要时承接到学习规划。";
    }

    @Override
    public JsonNode getParameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("action")
                .put("type", "string")
                .put("description", "固定为 analyze_error_reason");
        properties.putObject("userMessage").put("type", "string");
        properties.putObject("reportId").put("type", "string");
        properties.putObject("subject").put("type", "string");
        properties.putObject("questionContent").put("type", "string");
        properties.putObject("studentAnswer").put("type", "string");
        properties.putObject("correctAnswer").put("type", "string");
        properties.putObject("knowledgePoint").put("type", "string");
        properties.putObject("negativeAbilityTag").put("type", "string");

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
            if (!"analyze_error_reason".equals(action)) {
                return ToolResult.fail("不支持的 action: '" + action + "'。请使用 analyze_error_reason。");
            }
            return ToolParamUtils.jsonResult(service.analyze(params));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("hanxue_error_analysis_service 执行失败: " + e.getMessage());
        }
    }
}
