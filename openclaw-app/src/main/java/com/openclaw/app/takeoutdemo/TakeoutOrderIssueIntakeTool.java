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
@Order(110)
public class TakeoutOrderIssueIntakeTool implements CustomAgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TakeoutDemoService takeoutDemoService;

    public TakeoutOrderIssueIntakeTool(TakeoutDemoService takeoutDemoService) {
        this.takeoutDemoService = takeoutDemoService;
    }

    @Override
    public String getName() {
        return "takeout_order_issue_intake";
    }

    @Override
    public String getDescription() {
        return "按时间范围筛查外卖订单异常，并根据问题类型分流到退款处理或配送安抚。";
    }

    @Override
    public JsonNode getParameterSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode action = properties.putObject("action");
        action.put("type", "string");
        action.put("description", "固定为 list_orders_in_range。");

        ObjectNode timeRangeKey = properties.putObject("timeRangeKey");
        timeRangeKey.put("type", "string");
        timeRangeKey.put("description", "演示时间范围：MORNING_OK、LUNCH_TODAY、DINNER_DELAY、LAST_3_DAYS。");

        schema.putArray("required").add("action").add("timeRangeKey");
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
            if (!"list_orders_in_range".equals(action)) {
                throw new IllegalArgumentException("不支持的 action: '" + action + "'。请使用 list_orders_in_range。");
            }
            String timeRangeKey = ToolParamUtils.readStringParam(params, "timeRangeKey", true);
            return ToolParamUtils.jsonResult(takeoutDemoService.buildIssueIntakePayload(timeRangeKey));
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail("takeout_order_issue_intake 执行失败: " + e.getMessage());
        }
    }
}
