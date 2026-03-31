package com.openclaw.app.takeoutdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openclaw.agent.tools.CustomAgentTool;
import com.openclaw.agent.tools.ToolParamUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@Order(111)
public class TakeoutDeliveryFollowupTool implements CustomAgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TakeoutDemoService takeoutDemoService;

    public TakeoutDeliveryFollowupTool(TakeoutDemoService takeoutDemoService) {
        this.takeoutDemoService = takeoutDemoService;
    }

    @Override
    public String getName() {
        return "takeout_delivery_followup";
    }

    @Override
    public String getDescription() {
        return "针对延迟配送订单生成安抚话术、补偿建议和下一步动作。";
    }

    @Override
    public JsonNode getParameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "固定为 followup_plan。");

        ObjectNode orderId = properties.putObject("orderId");
        orderId.put("type", "string");
        orderId.put("description", "需要安抚处理的订单号，例如 FOOD-1004。");

        schema.putArray("required").add("action").add("orderId");
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
            if (!"followup_plan".equals(action)) {
                throw new IllegalArgumentException("不支持的 action: '" + action + "'。请使用 followup_plan。");
            }
            String orderId = ToolParamUtils.readStringParam(params, "orderId", true);
            return ToolParamUtils.jsonResult(takeoutDemoService.buildDeliveryFollowupPayload(orderId));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("takeout_delivery_followup 执行失败: " + e.getMessage());
        }
    }
}
