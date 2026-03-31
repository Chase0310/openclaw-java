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
@Order(125)
public class HanxueReportSwitchTool implements CustomAgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HanxueReportSwitchService service;

    public HanxueReportSwitchTool(HanxueReportSwitchService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "hanxue_report_switch_service";
    }

    @Override
    public String getDescription() {
        return "根据日期、学科和报告列表定位目标报告，并在命中后承接到学情解读。";
    }

    @Override
    public JsonNode getParameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("action")
                .put("type", "string")
                .put("description", "固定为 locate_report");
        properties.putObject("userMessage").put("type", "string");
        properties.putObject("reportId").put("type", "string");
        properties.putObject("subject").put("type", "string");
        properties.putObject("targetDate").put("type", "string");
        properties.putObject("todayDate").put("type", "string");
        properties.putObject("todayReportList").put("type", "array");
        properties.putObject("filter").put("type", "string");
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
            if (!"locate_report".equals(action)) {
                return ToolResult.fail("不支持的 action: '" + action + "'。请使用 locate_report。");
            }
            return ToolParamUtils.jsonResult(service.locate(params));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("hanxue_report_switch_service 执行失败: " + e.getMessage());
        }
    }
}
