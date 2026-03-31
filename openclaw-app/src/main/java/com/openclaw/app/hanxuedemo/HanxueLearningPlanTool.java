package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openclaw.agent.tools.CustomAgentTool;
import com.openclaw.agent.tools.ToolParamUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Component
@Order(122)
public class HanxueLearningPlanTool implements CustomAgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> ALLOWED_ACTIONS = Set.of(
            "build_targeted_plan",
            "build_global_plan",
            "build_gentle_plan");

    private final HanxueLearningPlanService service;

    public HanxueLearningPlanTool(HanxueLearningPlanService service) {
        this.service = service;
    }

    @Override
    public String getName() {
        return "hanxue_learning_plan_service";
    }

    @Override
    public String getDescription() {
        return "输出定向、全局或温和版学习规划路径，区分事实依据与建议动作。";
    }

    @Override
    public JsonNode getParameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("action")
                .put("type", "string")
                .put("description", "可选：build_targeted_plan、build_global_plan、build_gentle_plan");
        properties.putObject("sourceScene").put("type", "string");
        properties.putObject("subject").put("type", "string");
        properties.putObject("childGrade").put("type", "string");
        properties.putObject("knowledgePoint").put("type", "string");
        properties.putObject("weakPoints").put("type", "array");
        properties.putObject("currentReportContext").put("type", "object");
        properties.putObject("recentLearningSummary").put("type", "object");

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
            if (!ALLOWED_ACTIONS.contains(action)) {
                return ToolResult.fail("不支持的 action: '" + action
                        + "'。请使用 build_targeted_plan、build_global_plan 或 build_gentle_plan。");
            }
            return ToolParamUtils.jsonResult(service.buildPlan(params));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("hanxue_learning_plan_service 执行失败: " + e.getMessage());
        }
    }
}
