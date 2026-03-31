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
@Order(120)
public class HanxueReportInterpretationTool implements CustomAgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HanxueReportInterpretationService service;

    public HanxueReportInterpretationTool(HanxueReportInterpretationService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "hanxue_report_interpretation_service";
    }

    @Override
    public String getDescription() {
        return "消费当前学情报告上下文，输出事实解读，必要时承接到错因分析、学习规划或切换报告。";
    }

    @Override
    public JsonNode getParameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("action")
                .put("type", "string")
                .put("description", "固定为 interpret_current_report");
        properties.putObject("userMessage").put("type", "string");
        properties.putObject("reportId").put("type", "string");
        properties.putObject("reportType").put("type", "string");
        properties.putObject("currentReportContext").put("type", "object");
        properties.putObject("todaySessionSummary").put("type", "object");
        properties.putObject("progressSignals").put("type", "object");

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
            if (!"interpret_current_report".equals(action)) {
                return ToolResult.fail("不支持的 action: '" + action + "'。请使用 interpret_current_report。");
            }
            return ToolParamUtils.jsonResult(service.interpret(params));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("hanxue_report_interpretation_service 执行失败: " + e.getMessage());
        }
    }
}
