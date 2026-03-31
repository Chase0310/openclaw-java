package com.openclaw.app.businessdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openclaw.agent.tools.CustomAgentTool;
import com.openclaw.agent.tools.ToolParamUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 示例工具：把本地 Java 业务方法暴露给 Agent 运行时。
 */
@Component
@Order(100)
public class LocalBusinessLookupTool implements CustomAgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LocalBusinessDemoService businessService;

    public LocalBusinessLookupTool(LocalBusinessDemoService businessService) {
        this.businessService = businessService;
    }

    @Override
    public String getName() {
        return "local_business_lookup";
    }

    @Override
    public String getDescription() {
        return "通过本地 Java 服务方法查询示例业务记录。"
                + "支持的动作有：customer_profile、order_status、order_overview。";
    }

    @Override
    public JsonNode getParameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "可选值之一：customer_profile、order_status、order_overview");

        ObjectNode id = properties.putObject("id");
        id.put("type", "string");
        id.put("description", "业务标识，例如 CUST-1001 或 ORD-9001");

        schema.putArray("required").add("action").add("id");
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
            String id = ToolParamUtils.readStringParam(params, "id", true);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", action);
            payload.put("requestedId", id);

            Optional<?> result = switch (action) {
                case "customer_profile" -> businessService.findCustomer(id).map(LocalBusinessDemoService.CustomerProfile::toMap);
                case "order_status" -> businessService.findOrder(id).map(LocalBusinessDemoService.OrderSummary::toMap);
                case "order_overview" -> businessService.buildOrderOverview(id);
                default -> throw new IllegalArgumentException(
                        "不支持的 action: '" + action + "'。请使用 customer_profile、order_status 或 order_overview。");
            };

            if (result.isEmpty()) {
                payload.put("found", false);
                payload.put("message", "没有找到与该标识匹配的本地业务记录。");
                return ToolResult.ok(ToolParamUtils.toJsonString(payload), payload);
            }

            payload.put("found", true);
            payload.put("record", result.get());
            return ToolResult.ok(ToolParamUtils.toJsonString(payload), payload);
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("local_business_lookup 执行失败: " + e.getMessage());
        }
    }
}
