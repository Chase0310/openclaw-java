package com.openclaw.app.takeoutdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openclaw.agent.tools.CustomAgentTool;
import com.openclaw.agent.tools.ToolParamUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@Order(112)
public class TakeoutRefundResolutionTool implements CustomAgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TakeoutDemoService takeoutDemoService;

    public TakeoutRefundResolutionTool(TakeoutDemoService takeoutDemoService) {
        this.takeoutDemoService = takeoutDemoService;
    }

    @Override
    public String getName() {
        return "takeout_refund_resolution";
    }

    @Override
    public String getDescription() {
        return "基于订单分诊阶段整理好的复杂上下文，输出退款概览或逐单赔付建议。";
    }

    @Override
    public JsonNode getParameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "可选值：resolution_summary、resolution_decision。");

        ObjectNode orderId = properties.putObject("orderId");
        orderId.put("type", "string");
        orderId.put("description", "如果只传简单 orderId，tool 会返回 needs_input。");

        ObjectNode customerId = properties.putObject("customerId");
        customerId.put("type", "string");
        customerId.put("description", "客户标识，例如 USER-3001。");

        ObjectNode timeRangeKey = properties.putObject("timeRangeKey");
        timeRangeKey.put("type", "string");
        timeRangeKey.put("description", "订单分诊阶段使用的时间窗口标识。");

        ObjectNode windowSummary = properties.putObject("windowSummary");
        windowSummary.put("type", "object");
        windowSummary.put("description", "订单窗口摘要，例如 totalOrders 与 problemOrders。");

        ObjectNode flaggedOrders = properties.putObject("flaggedOrders");
        flaggedOrders.put("type", "array");
        flaggedOrders.put("description", "异常订单列表。");
        ObjectNode flaggedOrderItem = flaggedOrders.putObject("items");
        flaggedOrderItem.put("type", "object");
        ObjectNode flaggedOrderProps = flaggedOrderItem.putObject("properties");
        flaggedOrderProps.putObject("orderId").put("type", "string");
        flaggedOrderProps.putObject("issueType").put("type", "string");
        flaggedOrderProps.putObject("deliveryStatus").put("type", "string");
        flaggedOrderProps.putObject("amount").put("type", "number");
        flaggedOrderProps.putObject("suggestedAction").put("type", "string");

        ObjectNode orderIds = properties.putObject("orderIds");
        orderIds.put("type", "array");
        ArrayNode orderIdItems = orderIds.putArray("examples");
        orderIdItems.add("FOOD-1002");
        orderIds.put("description", "异常订单号列表。");

        ObjectNode resolutionPolicyContext = properties.putObject("resolutionPolicyContext");
        resolutionPolicyContext.put("type", "object");
        resolutionPolicyContext.put("description", "赔付策略上下文，例如 maxAutoRefundPerOrder。");

        ObjectNode customerAfterSalesProfile = properties.putObject("customerAfterSalesProfile");
        customerAfterSalesProfile.put("type", "object");
        customerAfterSalesProfile.put("description", "客户售后画像，例如 recentAfterSalesCount。");

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
            TakeoutDemoService.RefundResolutionContext refundContext = takeoutDemoService.readRefundContext(params);
            return switch (action) {
                case "resolution_summary" -> ToolParamUtils.jsonResult(
                        takeoutDemoService.buildRefundSummaryPayload(refundContext));
                case "resolution_decision" -> ToolParamUtils.jsonResult(
                        takeoutDemoService.buildRefundDecisionPayload(refundContext));
                default -> ToolResult.fail(
                        "不支持的 action: '" + action + "'。请使用 resolution_summary 或 resolution_decision。");
            };
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("takeout_refund_resolution 执行失败: " + e.getMessage());
        }
    }
}
