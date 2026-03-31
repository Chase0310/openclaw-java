package com.openclaw.app.takeoutdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openclaw.agent.tools.AgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TakeoutDemoToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TakeoutDemoService service;
    private AgentTool intakeTool;
    private AgentTool deliveryTool;
    private AgentTool refundTool;

    @BeforeEach
    void setUp() {
        service = new TakeoutDemoService();
        intakeTool = new TakeoutOrderIssueIntakeTool(service);
        deliveryTool = new TakeoutDeliveryFollowupTool(service);
        refundTool = new TakeoutRefundResolutionTool(service);
    }

    @Test
    void intakeMorningOk_returnsAllClear() throws Exception {
        JsonNode payload = executeAndRead(intakeTool, objectParams(
                "action", "list_orders_in_range",
                "timeRangeKey", "MORNING_OK"));

        assertEquals("ok", payload.path("status").asText());
        assertTrue(payload.path("found").asBoolean());
        assertEquals("all_clear", payload.at("/record/triageDecision/decision").asText());
        assertEquals(2, payload.path("records").size());
        assertFalse(payload.has("handoff"));
    }

    @Test
    void intakeLast3Days_returnsRefundHandoff() throws Exception {
        JsonNode payload = executeAndRead(intakeTool, objectParams(
                "action", "list_orders_in_range",
                "timeRangeKey", "LAST_3_DAYS"));

        assertEquals("handoff", payload.path("status").asText());
        assertTrue(payload.path("found").asBoolean());
        assertEquals("takeout-refund-resolution-demo", payload.at("/handoff/targetSkill").asText());
        assertEquals(3, payload.at("/handoff/params/flaggedOrders").size());
        assertEquals(5, payload.at("/handoff/params/windowSummary/totalOrders").asInt());
        assertEquals(3, payload.at("/handoff/params/windowSummary/problemOrders").asInt());
    }

    @Test
    void intakeUnknownTimeRange_returnsNotFound() throws Exception {
        JsonNode payload = executeAndRead(intakeTool, objectParams(
                "action", "list_orders_in_range",
                "timeRangeKey", "NEXT_WEEK"));

        assertEquals("not_found", payload.path("status").asText());
        assertFalse(payload.path("found").asBoolean());
        assertTrue(payload.path("availableTimeRangeKeys").isArray());
    }

    @Test
    void deliveryFollowupFood1004_returnsPlan() throws Exception {
        JsonNode payload = executeAndRead(deliveryTool, objectParams(
                "action", "followup_plan",
                "orderId", "FOOD-1004"));

        assertEquals("ok", payload.path("status").asText());
        assertTrue(payload.path("found").asBoolean());
        assertEquals("late_delivery", payload.at("/facts/issueType").asText());
        assertEquals(27, payload.at("/facts/delayMinutes").asInt());
        assertEquals("coupon", payload.at("/recommendedCompensation/type").asText().replace("delivery_", ""));
        assertTrue(payload.at("/suggestions/customerReply").asText().contains("抱歉"));
    }

    @Test
    void refundSummary_withHandoffParams_returnsOverview() throws Exception {
        JsonNode intakePayload = executeAndRead(intakeTool, objectParams(
                "action", "list_orders_in_range",
                "timeRangeKey", "LAST_3_DAYS"));
        ObjectNode refundParams = (ObjectNode) intakePayload.path("handoff").path("params").deepCopy();
        refundParams.put("action", "resolution_summary");

        JsonNode payload = executeAndRead(refundTool, refundParams);

        assertEquals("ok", payload.path("status").asText());
        assertTrue(payload.path("found").asBoolean());
        assertEquals(3, payload.path("flaggedOrders").size());
        assertEquals("high", payload.at("/suggestions/priority").asText());
        assertTrue(payload.path("recommendedNextAction").asText().contains("resolution_decision"));
    }

    @Test
    void refundDecision_withHandoffParams_returnsPerOrderSuggestions() throws Exception {
        JsonNode intakePayload = executeAndRead(intakeTool, objectParams(
                "action", "list_orders_in_range",
                "timeRangeKey", "LAST_3_DAYS"));
        ObjectNode refundParams = (ObjectNode) intakePayload.path("handoff").path("params").deepCopy();
        refundParams.put("action", "resolution_decision");

        JsonNode payload = executeAndRead(refundTool, refundParams);

        assertEquals("ok", payload.path("status").asText());
        assertTrue(payload.path("found").asBoolean());
        assertEquals(3, payload.path("resolutions").size());
        assertEquals("manual_full_refund_review", findDecision(payload, "FOOD-1003").path("resolutionType").asText());
        assertEquals(12.0, findDecision(payload, "FOOD-1005").path("suggestedAmount").asDouble());
        assertTrue(payload.path("finalSummary").asText().contains("3 笔"));
    }

    @Test
    void refundSummary_withSimpleOrderOnly_returnsNeedsInput() throws Exception {
        JsonNode payload = executeAndRead(refundTool, objectParams(
                "action", "resolution_summary",
                "orderId", "FOOD-1004"));

        assertEquals("needs_input", payload.path("status").asText());
        assertFalse(payload.path("found").asBoolean());
        assertTrue(payload.path("warnings").isArray());
    }

    private JsonNode executeAndRead(AgentTool tool, ObjectNode params) throws Exception {
        AgentTool.ToolResult result = tool.execute(AgentTool.ToolContext.builder()
                .parameters(params)
                .cwd(System.getProperty("user.dir"))
                .build()).get();

        assertTrue(result.isSuccess(), result.getError());
        return MAPPER.readTree(result.getOutput());
    }

    private ObjectNode objectParams(String key1, String value1, String key2, String value2) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put(key1, value1);
        params.put(key2, value2);
        return params;
    }

    private JsonNode findDecision(JsonNode payload, String orderId) {
        for (JsonNode decision : payload.path("resolutions")) {
            if (orderId.equals(decision.path("orderId").asText())) {
                return decision;
            }
        }
        fail("Decision not found for " + orderId);
        return null;
    }
}
