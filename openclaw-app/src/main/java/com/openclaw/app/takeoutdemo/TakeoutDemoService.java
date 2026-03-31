package com.openclaw.app.takeoutdemo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 外卖售后演示服务。
 * <p>
 * Skill 只负责描述流程，这里提供本地 Java 业务数据、分流规则和赔付建议，
 * 让 Tool 可以稳定输出给当前 runtime 能消费的 JSON payload。
 */
@Service
public class TakeoutDemoService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, TakeoutOrderRecord> ordersById;
    private final Map<String, TimeWindowScenario> scenariosByKey;
    private final Map<String, DeliveryFollowupPlan> deliveryPlansByOrderId;

    public TakeoutDemoService() {
        this.ordersById = Collections.unmodifiableMap(buildOrders());
        this.scenariosByKey = Collections.unmodifiableMap(buildScenarios());
        this.deliveryPlansByOrderId = Collections.unmodifiableMap(buildDeliveryPlans());
    }

    public Map<String, Object> buildIssueIntakePayload(String timeRangeKey) {
        String normalizedKey = normalizeKey(timeRangeKey);
        Map<String, Object> payload = basePayload("list_orders_in_range");
        payload.put("requestedTimeRangeKey", normalizedKey);

        TimeWindowScenario scenario = scenariosByKey.get(normalizedKey);
        if (scenario == null) {
            payload.put("status", "not_found");
            payload.put("found", false);
            payload.put("message", "没有找到这个时间范围的外卖演示数据。");
            payload.put("availableTimeRangeKeys", new ArrayList<>(scenariosByKey.keySet()));
            payload.put("recommendedNextAction", "请改用 MORNING_OK、LUNCH_TODAY、DINNER_DELAY 或 LAST_3_DAYS。");
            return payload;
        }

        List<TakeoutOrderRecord> windowOrders = scenario.orderIds().stream()
                .map(ordersById::get)
                .filter(Objects::nonNull)
                .toList();
        List<TakeoutOrderRecord> problemOrders = windowOrders.stream()
                .filter(TakeoutOrderRecord::hasIssue)
                .toList();

        TriageRoute route = decideRoute(problemOrders);
        Map<String, Object> summary = scenario.windowSummary(windowOrders.size(), problemOrders.size());

        payload.put("status", route.status());
        payload.put("found", true);
        payload.put("message", buildIntakeMessage(scenario, windowOrders.size(), problemOrders.size(), route));
        payload.put("record", buildIntakeRecord(scenario, route));
        payload.put("summary", summary);
        payload.put("records", windowOrders.stream().map(TakeoutOrderRecord::toMap).toList());
        payload.put("problemOrders", problemOrders.stream().map(TakeoutOrderRecord::toFlaggedOrderMap).toList());
        payload.put("facts", buildWindowFacts(scenario, windowOrders, problemOrders));
        payload.put("suggestions", buildIntakeSuggestions(route, problemOrders));
        payload.put("recommendedNextAction", buildIntakeNextAction(route));

        if (route == TriageRoute.REFUND_HANDOFF) {
            payload.put("handoff", buildRefundHandoff(scenario, summary, problemOrders));
        } else if (route == TriageRoute.DELIVERY_HANDOFF) {
            payload.put("handoff", buildDeliveryHandoff(problemOrders.get(0)));
        }

        return payload;
    }

    public Map<String, Object> buildDeliveryFollowupPayload(String orderId) {
        String normalizedOrderId = normalizeKey(orderId);
        Map<String, Object> payload = basePayload("followup_plan");
        payload.put("requestedOrderId", normalizedOrderId);

        TakeoutOrderRecord order = ordersById.get(normalizedOrderId);
        DeliveryFollowupPlan plan = deliveryPlansByOrderId.get(normalizedOrderId);
        if (order == null || plan == null) {
            payload.put("status", "not_found");
            payload.put("found", false);
            payload.put("message", "没有对应的配送安抚演示数据。");
            payload.put("recommendedNextAction", "请确认 orderId，或先通过 takeout_order_issue_intake 做订单分诊。");
            return payload;
        }

        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("message", "已为延迟配送订单 " + order.orderId() + " 生成安抚方案。");
        payload.put("record", buildFollowupRecord(order, plan));
        payload.put("order", buildFollowupRecord(order, plan));
        payload.put("facts", buildFollowupFacts(order, plan));
        payload.put("suggestions", buildFollowupSuggestions(plan));
        payload.put("followupPlan", plan.toFollowupPlanMap());
        payload.put("recommendedCompensation", plan.recommendedCompensation());
        payload.put("recommendedNextAction", "先向用户致歉并同步最新 ETA，如仍继续超时，再升级人工跟进。");
        return payload;
    }

    public RefundResolutionContext readRefundContext(JsonNode params) {
        if (params == null || params.isNull() || params.isMissingNode()) {
            return RefundResolutionContext.empty();
        }
        return new RefundResolutionContext(
                readText(params, "orderId"),
                readText(params, "customerId"),
                readText(params, "timeRangeKey"),
                readMap(params.get("windowSummary")),
                readFlaggedOrders(params.get("flaggedOrders")),
                readStringList(params.get("orderIds")),
                readMap(params.get("resolutionPolicyContext")),
                readMap(params.get("customerAfterSalesProfile")));
    }

    public Map<String, Object> buildRefundSummaryPayload(RefundResolutionContext context) {
        return buildRefundPayload("resolution_summary", context, false);
    }

    public Map<String, Object> buildRefundDecisionPayload(RefundResolutionContext context) {
        return buildRefundPayload("resolution_decision", context, true);
    }

    private Map<String, Object> buildRefundPayload(
            String action,
            RefundResolutionContext context,
            boolean includeDecisions) {
        Map<String, Object> payload = basePayload(action);

        if (!context.hasComplexContext()) {
            payload.put("status", "needs_input");
            payload.put("found", false);
            payload.put("message", buildNeedsInputMessage(context));
            payload.put("warnings", buildMissingContextWarnings(context));
            payload.put("recommendedNextAction",
                    "请先通过 takeout_order_issue_intake 获取 flaggedOrders、windowSummary、resolutionPolicyContext 和 customerAfterSalesProfile。");
            return payload;
        }

        List<FlaggedOrderInput> flaggedOrders = normalizeFlaggedOrders(context);
        if (flaggedOrders.isEmpty()) {
            payload.put("status", "not_found");
            payload.put("found", false);
            payload.put("message", "没有可处理的异常订单演示数据。");
            payload.put("record", buildRefundRecord(context, 0));
            payload.put("recommendedNextAction", "请确认 handoff.params 中是否带入了 flaggedOrders 或 orderIds。");
            return payload;
        }

        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("record", buildRefundRecord(context, flaggedOrders.size()));
        payload.put("flaggedOrders", flaggedOrders.stream().map(FlaggedOrderInput::toMap).toList());
        payload.put("facts", buildRefundFacts(context, flaggedOrders));

        if (!includeDecisions) {
            payload.put("message", "已整理 " + flaggedOrders.size() + " 笔异常订单，建议继续生成逐单赔付建议。");
            payload.put("records", flaggedOrders.stream().map(FlaggedOrderInput::toMap).toList());
            payload.put("suggestions", buildRefundSummarySuggestions(context, flaggedOrders));
            payload.put("recommendedNextAction", "继续调用 resolution_decision，生成逐单赔付建议。");
            return payload;
        }

        List<Map<String, Object>> decisions = flaggedOrders.stream()
                .map(order -> buildRefundDecision(order, context))
                .toList();
        payload.put("message", "已生成 " + decisions.size() + " 笔异常订单的逐单处理建议。");
        payload.put("records", decisions);
        payload.put("resolutions", decisions);
        payload.put("suggestions", buildRefundDecisionSuggestions(decisions));
        payload.put("finalSummary", buildFinalSummary(decisions));
        payload.put("recommendedNextAction", "按逐单建议处理；若用户仍继续争议，再转人工复核。");
        return payload;
    }

    private Map<String, TakeoutOrderRecord> buildOrders() {
        Map<String, TakeoutOrderRecord> orders = new LinkedHashMap<>();
        orders.put("FOOD-1001", new TakeoutOrderRecord(
                "FOOD-1001", "USER-3001", "MORNING_OK", "今日早餐",
                "deliver_complete", "delivered", 22.0,
                null, null, "早餐准时送达，无异常。", "08:20", "08:18"));
        orders.put("FOOD-1002", new TakeoutOrderRecord(
                "FOOD-1002", "USER-3001", "LUNCH_TODAY", "今日午餐",
                "deliver_complete", "delivered", 29.0,
                "missing_item", "partial_refund", "套餐缺少一份小食。", "12:35", "12:31"));
        orders.put("FOOD-1003", new TakeoutOrderRecord(
                "FOOD-1003", "USER-3001", "LAST_3_DAYS", "最近三天",
                "deliver_complete", "delivered", 19.0,
                "spilled_drink", "full_refund", "饮品严重洒漏。", "19:05", "19:02"));
        orders.put("FOOD-1004", new TakeoutOrderRecord(
                "FOOD-1004", "USER-3001", "DINNER_DELAY", "今晚晚餐",
                "deliver_delayed", "delayed", 46.0,
                "late_delivery", "coupon_compensation", "骑手晚高峰拥堵，预计延迟 27 分钟送达。", "18:30", null));
        orders.put("FOOD-1005", new TakeoutOrderRecord(
                "FOOD-1005", "USER-3001", "LAST_3_DAYS", "最近三天",
                "deliver_complete", "delivered", 35.0,
                "wrong_item", "partial_refund_plus_coupon", "主食出错，用户收到错误菜品。", "20:10", "20:08"));
        orders.put("FOOD-1006", new TakeoutOrderRecord(
                "FOOD-1006", "USER-3001", "LAST_3_DAYS", "最近三天",
                "deliver_complete", "delivered", 26.0,
                null, null, "订单正常完成。", "11:45", "11:41"));
        orders.put("FOOD-1007", new TakeoutOrderRecord(
                "FOOD-1007", "USER-3001", "LAST_3_DAYS", "最近三天",
                "deliver_complete", "delivered", 18.0,
                null, null, "订单正常完成。", "13:10", "13:06"));
        orders.put("FOOD-1008", new TakeoutOrderRecord(
                "FOOD-1008", "USER-3001", "MORNING_OK", "今日早餐",
                "deliver_complete", "delivered", 16.5,
                null, null, "早餐准时送达，无异常。", "08:42", "08:39"));
        return orders;
    }

    private Map<String, TimeWindowScenario> buildScenarios() {
        Map<String, TimeWindowScenario> scenarios = new LinkedHashMap<>();

        Map<String, Object> standardPolicy = linkedMap();
        standardPolicy.put("maxAutoRefundPerOrder", 12.0);
        standardPolicy.put("allowCouponForLateDelivery", true);

        scenarios.put("MORNING_OK", new TimeWindowScenario(
                "MORNING_OK", "今日早餐", "USER-3001",
                List.of("FOOD-1001", "FOOD-1008"),
                standardPolicy,
                buildAfterSalesProfile("USER-3001", 0, false)));

        scenarios.put("LUNCH_TODAY", new TimeWindowScenario(
                "LUNCH_TODAY", "今日午餐", "USER-3001",
                List.of("FOOD-1002"),
                standardPolicy,
                buildAfterSalesProfile("USER-3001", 1, false)));

        scenarios.put("DINNER_DELAY", new TimeWindowScenario(
                "DINNER_DELAY", "今晚晚餐", "USER-3001",
                List.of("FOOD-1004"),
                standardPolicy,
                buildAfterSalesProfile("USER-3001", 1, false)));

        scenarios.put("LAST_3_DAYS", new TimeWindowScenario(
                "LAST_3_DAYS", "最近三天", "USER-3001",
                List.of("FOOD-1002", "FOOD-1003", "FOOD-1005", "FOOD-1006", "FOOD-1007"),
                standardPolicy,
                buildAfterSalesProfile("USER-3001", 2, false)));

        return scenarios;
    }

    private Map<String, DeliveryFollowupPlan> buildDeliveryPlans() {
        Map<String, DeliveryFollowupPlan> plans = new LinkedHashMap<>();
        plans.put("FOOD-1004", new DeliveryFollowupPlan(
                "FOOD-1004", 27, "18:30", "18:57", "晚高峰骑手拥堵，骑手已接近用户定位点。", "approaching_customer",
                "抱歉这单配送晚了一些，我们已经记录本次超时并持续跟进骑手状态。",
                "当前晚高峰路段拥堵，骑手已在您附近，预计很快送达。",
                "先致歉并同步最新 ETA，如超过 10 分钟仍未送达则升级人工。",
                "delivery_coupon", 5.0, "5 元配送补偿券"));
        return plans;
    }

    private Map<String, Object> buildAfterSalesProfile(
            String customerId,
            int recentAfterSalesCount,
            boolean repeatedIssues) {
        Map<String, Object> profile = linkedMap();
        profile.put("customerId", customerId);
        profile.put("recentAfterSalesCount", recentAfterSalesCount);
        profile.put("hasRepeatedIssues", repeatedIssues);
        return profile;
    }

    private TriageRoute decideRoute(List<TakeoutOrderRecord> problemOrders) {
        if (problemOrders.isEmpty()) {
            return TriageRoute.ALL_CLEAR;
        }
        if (problemOrders.size() == 1 && "late_delivery".equals(problemOrders.get(0).issueType())) {
            return TriageRoute.DELIVERY_HANDOFF;
        }
        return TriageRoute.REFUND_HANDOFF;
    }

    private Map<String, Object> buildIntakeRecord(TimeWindowScenario scenario, TriageRoute route) {
        Map<String, Object> record = linkedMap();
        record.put("customerId", scenario.customerId());
        record.put("timeRangeKey", scenario.timeRangeKey());
        record.put("timeRangeLabel", scenario.timeRangeLabel());

        Map<String, Object> triageDecision = linkedMap();
        triageDecision.put("decision", route.decision());
        triageDecision.put("reason", route.reason());
        record.put("triageDecision", triageDecision);
        return record;
    }

    private Map<String, Object> buildWindowFacts(
            TimeWindowScenario scenario,
            List<TakeoutOrderRecord> windowOrders,
            List<TakeoutOrderRecord> problemOrders) {
        Map<String, Object> facts = linkedMap();
        facts.put("customerId", scenario.customerId());
        facts.put("timeRangeKey", scenario.timeRangeKey());
        facts.put("timeRangeLabel", scenario.timeRangeLabel());
        facts.put("totalOrders", windowOrders.size());
        facts.put("problemOrders", problemOrders.size());
        facts.put("issueTypes", problemOrders.stream()
                .map(TakeoutOrderRecord::issueType)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return facts;
    }

    private Map<String, Object> buildIntakeSuggestions(TriageRoute route, List<TakeoutOrderRecord> problemOrders) {
        Map<String, Object> suggestions = linkedMap();
        suggestions.put("routeHint", route.name().toLowerCase(Locale.ROOT));
        if (route == TriageRoute.ALL_CLEAR) {
            suggestions.put("summaryReply", "直接汇总这一时间段内的正常订单情况即可。");
            return suggestions;
        }
        suggestions.put("problemOrderIds", problemOrders.stream().map(TakeoutOrderRecord::orderId).toList());
        suggestions.put("shouldReuseHandoffParams", true);
        return suggestions;
    }

    private String buildIntakeNextAction(TriageRoute route) {
        return switch (route) {
            case ALL_CLEAR -> "直接汇总这一时间段的订单情况，无需进入售后处理。";
            case DELIVERY_HANDOFF -> "切换到配送安抚，并复用 handoff.params 中的 orderId。";
            case REFUND_HANDOFF -> "切换到退款处理，并复用 handoff.params 中的复杂上下文。";
        };
    }

    private String buildIntakeMessage(
            TimeWindowScenario scenario,
            int totalOrders,
            int problemOrderCount,
            TriageRoute route) {
        return switch (route) {
            case ALL_CLEAR -> scenario.timeRangeLabel() + "共 " + totalOrders + " 笔订单，未发现异常订单。";
            case DELIVERY_HANDOFF -> scenario.timeRangeLabel() + "共 " + totalOrders + " 笔订单，其中 1 笔为延迟配送，建议进入配送安抚。";
            case REFUND_HANDOFF -> scenario.timeRangeLabel() + "共 " + totalOrders + " 笔订单，其中 " + problemOrderCount + " 笔需要退款或赔付处理。";
        };
    }

    private Map<String, Object> buildRefundHandoff(
            TimeWindowScenario scenario,
            Map<String, Object> summary,
            List<TakeoutOrderRecord> problemOrders) {
        Map<String, Object> params = linkedMap();
        params.put("customerId", scenario.customerId());
        params.put("timeRangeKey", scenario.timeRangeKey());
        params.put("windowSummary", summary);
        params.put("flaggedOrders", problemOrders.stream().map(TakeoutOrderRecord::toFlaggedOrderMap).toList());
        params.put("orderIds", problemOrders.stream().map(TakeoutOrderRecord::orderId).toList());
        params.put("resolutionPolicyContext", scenario.resolutionPolicyContext());
        params.put("customerAfterSalesProfile", scenario.customerAfterSalesProfile());

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "takeout-refund-resolution-demo");
        handoff.put("reason",
                problemOrders.size() > 1 ? "multiple_orders_need_refund_review" : "single_refundable_issue");
        handoff.put("params", params);
        return handoff;
    }

    private Map<String, Object> buildDeliveryHandoff(TakeoutOrderRecord problemOrder) {
        Map<String, Object> params = linkedMap();
        params.put("orderId", problemOrder.orderId());

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "takeout-delivery-followup-demo");
        handoff.put("reason", "single_late_delivery_followup");
        handoff.put("params", params);
        return handoff;
    }

    private Map<String, Object> buildFollowupRecord(TakeoutOrderRecord order, DeliveryFollowupPlan plan) {
        Map<String, Object> record = new LinkedHashMap<>(order.toMap());
        record.put("delayMinutes", plan.delayMinutes());
        record.put("promisedAt", plan.promisedAt());
        record.put("latestEta", plan.latestEta());
        record.put("courierStatus", plan.courierStatus());
        record.put("issueSummary", plan.issueSummary());
        return record;
    }

    private Map<String, Object> buildFollowupFacts(TakeoutOrderRecord order, DeliveryFollowupPlan plan) {
        Map<String, Object> facts = linkedMap();
        facts.put("orderId", order.orderId());
        facts.put("issueType", order.issueType());
        facts.put("deliveryStatus", order.deliveryStatus());
        facts.put("delayMinutes", plan.delayMinutes());
        facts.put("promisedAt", plan.promisedAt());
        facts.put("latestEta", plan.latestEta());
        facts.put("courierStatus", plan.courierStatus());
        return facts;
    }

    private Map<String, Object> buildFollowupSuggestions(DeliveryFollowupPlan plan) {
        Map<String, Object> suggestions = linkedMap();
        suggestions.put("customerReply", plan.customerReply());
        suggestions.put("explanation", plan.explanation());
        suggestions.put("internalAction", plan.internalAction());
        suggestions.put("recommendedCompensation", plan.recommendedCompensation());
        return suggestions;
    }

    private String buildNeedsInputMessage(RefundResolutionContext context) {
        if (notBlank(context.simpleOrderId())) {
            return "当前只有简单的 orderId=" + context.simpleOrderId() + "，缺少退款处理所需的复杂上下文。";
        }
        return "缺少退款处理所需的复杂上下文。";
    }

    private List<String> buildMissingContextWarnings(RefundResolutionContext context) {
        List<String> warnings = new ArrayList<>();
        if (!notBlank(context.customerId())) {
            warnings.add("customerId 缺失");
        }
        if (!notBlank(context.timeRangeKey())) {
            warnings.add("timeRangeKey 缺失");
        }
        if (context.windowSummary() == null || context.windowSummary().isEmpty()) {
            warnings.add("windowSummary 缺失");
        }
        if (context.flaggedOrders() == null || context.flaggedOrders().isEmpty()) {
            warnings.add("flaggedOrders 缺失");
        }
        if (context.orderIds() == null || context.orderIds().isEmpty()) {
            warnings.add("orderIds 缺失");
        }
        if (context.resolutionPolicyContext() == null || context.resolutionPolicyContext().isEmpty()) {
            warnings.add("resolutionPolicyContext 缺失");
        }
        if (context.customerAfterSalesProfile() == null || context.customerAfterSalesProfile().isEmpty()) {
            warnings.add("customerAfterSalesProfile 缺失");
        }
        return warnings;
    }

    private List<FlaggedOrderInput> normalizeFlaggedOrders(RefundResolutionContext context) {
        Map<String, FlaggedOrderInput> deduped = new LinkedHashMap<>();

        if (context.flaggedOrders() != null) {
            for (FlaggedOrderInput flaggedOrder : context.flaggedOrders()) {
                if (flaggedOrder == null || !notBlank(flaggedOrder.orderId())) {
                    continue;
                }
                String orderId = normalizeKey(flaggedOrder.orderId());
                TakeoutOrderRecord knownOrder = ordersById.get(orderId);
                String issueType = firstNonBlank(flaggedOrder.issueType(), knownOrder != null ? knownOrder.issueType() : null);
                String deliveryStatus = firstNonBlank(flaggedOrder.deliveryStatus(),
                        knownOrder != null ? knownOrder.deliveryStatus() : null,
                        "delivered");
                Double amount = flaggedOrder.amount() != null
                        ? roundMoney(flaggedOrder.amount())
                        : knownOrder != null ? roundMoney(knownOrder.amount()) : null;
                String suggestedAction = firstNonBlank(flaggedOrder.suggestedAction(), defaultSuggestedAction(issueType));

                if (notBlank(issueType) && amount != null) {
                    deduped.put(orderId, new FlaggedOrderInput(orderId, issueType, deliveryStatus, amount, suggestedAction));
                }
            }
        }

        if (deduped.isEmpty() && context.orderIds() != null) {
            for (String rawOrderId : context.orderIds()) {
                String orderId = normalizeKey(rawOrderId);
                TakeoutOrderRecord knownOrder = ordersById.get(orderId);
                if (knownOrder == null || !knownOrder.hasIssue()) {
                    continue;
                }
                deduped.put(orderId, new FlaggedOrderInput(
                        knownOrder.orderId(),
                        knownOrder.issueType(),
                        knownOrder.deliveryStatus(),
                        roundMoney(knownOrder.amount()),
                        knownOrder.suggestedAction()));
            }
        }

        return new ArrayList<>(deduped.values());
    }

    private Map<String, Object> buildRefundRecord(RefundResolutionContext context, int problemOrderCount) {
        Map<String, Object> record = linkedMap();
        record.put("customerId", context.customerId());
        record.put("timeRangeKey", context.timeRangeKey());
        record.put("windowSummary", context.windowSummary());
        record.put("batchMode", problemOrderCount > 1 ? "batch" : "single");
        record.put("problemOrderCount", problemOrderCount);
        return record;
    }

    private Map<String, Object> buildRefundFacts(
            RefundResolutionContext context,
            List<FlaggedOrderInput> flaggedOrders) {
        Map<String, Object> facts = linkedMap();
        facts.put("customerId", context.customerId());
        facts.put("timeRangeKey", context.timeRangeKey());
        facts.put("flaggedOrderCount", flaggedOrders.size());
        facts.put("issueTypes", flaggedOrders.stream()
                .map(FlaggedOrderInput::issueType)
                .distinct()
                .toList());
        facts.put("amount", roundMoney(flaggedOrders.stream()
                .mapToDouble(order -> order.amount() != null ? order.amount() : 0.0)
                .sum()));
        facts.put("deliveryStatuses", flaggedOrders.stream()
                .map(FlaggedOrderInput::deliveryStatus)
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return facts;
    }

    private Map<String, Object> buildRefundSummarySuggestions(
            RefundResolutionContext context,
            List<FlaggedOrderInput> flaggedOrders) {
        Map<String, Object> suggestions = linkedMap();
        suggestions.put("priority", resolveBatchPriority(flaggedOrders));
        suggestions.put("policyNote", buildPolicyNote(context.resolutionPolicyContext()));
        suggestions.put("nextStep", "如果用户继续问每单怎么赔，继续调用 resolution_decision。");
        return suggestions;
    }

    private Map<String, Object> buildRefundDecisionSuggestions(List<Map<String, Object>> decisions) {
        Map<String, Object> suggestions = linkedMap();
        suggestions.put("manualReviewOrderIds", decisions.stream()
                .filter(decision -> Boolean.TRUE.equals(decision.get("requiresManualReview")))
                .map(decision -> String.valueOf(decision.get("orderId")))
                .toList());
        suggestions.put("executionHint", "先按建议金额与券补偿执行，再对 requiresManualReview=true 的订单转人工。");
        return suggestions;
    }

    private String buildPolicyNote(Map<String, Object> policyContext) {
        double maxAutoRefund = readDouble(policyContext, "maxAutoRefundPerOrder", 12.0);
        boolean allowCoupon = readBoolean(policyContext, "allowCouponForLateDelivery", true);
        return "单笔自动退款上限 " + trimTrailingZero(maxAutoRefund) + " 元；"
                + (allowCoupon ? "晚到场景允许发放补偿券。" : "晚到场景不发补偿券。");
    }

    private Map<String, Object> buildRefundDecision(FlaggedOrderInput order, RefundResolutionContext context) {
        double maxAutoRefund = readDouble(context.resolutionPolicyContext(), "maxAutoRefundPerOrder", 12.0);
        boolean repeatedIssues = readBoolean(context.customerAfterSalesProfile(), "hasRepeatedIssues", false);

        Map<String, Object> decision = linkedMap();
        decision.put("orderId", order.orderId());
        decision.put("issueType", order.issueType());
        decision.put("deliveryStatus", order.deliveryStatus());
        decision.put("amount", order.amount());

        switch (order.issueType()) {
            case "missing_item" -> {
                double suggestedAmount = roundMoney(Math.min(maxAutoRefund, Math.max(8.0, order.amount() * 0.45)));
                decision.put("resolutionType", "partial_refund");
                decision.put("suggestedAmount", suggestedAmount);
                decision.put("requiresManualReview", false);
                decision.put("agentNote", "缺餐问题建议先退 " + trimTrailingZero(suggestedAmount) + " 元，并说明已记录商家漏餐。");
            }
            case "spilled_drink" -> {
                boolean manualReview = order.amount() > maxAutoRefund;
                decision.put("resolutionType", manualReview ? "manual_full_refund_review" : "full_refund");
                decision.put("suggestedAmount", order.amount());
                decision.put("requiresManualReview", manualReview);
                decision.put("agentNote", manualReview
                        ? "饮品洒漏建议全额退款，但金额超过自动退款上限，需人工确认。"
                        : "饮品洒漏建议直接全额退款。");
            }
            case "wrong_item" -> {
                double suggestedAmount = roundMoney(Math.min(maxAutoRefund, 12.0));
                double couponAmount = 6.0;
                decision.put("resolutionType", "partial_refund_plus_coupon");
                decision.put("suggestedAmount", suggestedAmount);
                decision.put("suggestedCouponAmount", couponAmount);
                decision.put("requiresManualReview", repeatedIssues);
                decision.put("agentNote", repeatedIssues
                        ? "错单建议退 " + trimTrailingZero(suggestedAmount) + " 元并补发 6 元券，同时因售后频次偏高建议人工复核。"
                        : "错单建议退 " + trimTrailingZero(suggestedAmount) + " 元并补发 6 元券。");
            }
            case "late_delivery" -> {
                decision.put("resolutionType", "delivery_coupon");
                decision.put("suggestedAmount", 0.0);
                decision.put("suggestedCouponAmount", 5.0);
                decision.put("requiresManualReview", false);
                decision.put("agentNote", "延迟配送优先补发配送补偿券，不直接走现金退款。");
            }
            default -> {
                decision.put("resolutionType", "manual_review");
                decision.put("suggestedAmount", 0.0);
                decision.put("requiresManualReview", true);
                decision.put("agentNote", "问题类型不在演示规则内，建议人工复核。");
            }
        }

        return decision;
    }

    private String buildFinalSummary(List<Map<String, Object>> decisions) {
        Map<String, Long> counts = decisions.stream()
                .collect(Collectors.groupingBy(
                        decision -> String.valueOf(decision.get("resolutionType")),
                        LinkedHashMap::new,
                        Collectors.counting()));
        String joined = counts.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .collect(Collectors.joining("，"));
        return "共 " + decisions.size() + " 笔异常订单，建议处理结构为：" + joined + "。";
    }

    private String resolveBatchPriority(List<FlaggedOrderInput> flaggedOrders) {
        Set<String> issueTypes = flaggedOrders.stream()
                .map(FlaggedOrderInput::issueType)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (issueTypes.contains("spilled_drink") || issueTypes.contains("wrong_item")) {
            return "high";
        }
        return flaggedOrders.size() > 1 ? "medium" : "normal";
    }

    private Map<String, Object> basePayload(String action) {
        Map<String, Object> payload = linkedMap();
        payload.put("action", action);
        return payload;
    }

    private static Map<String, Object> linkedMap() {
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> readMap(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isTextual()) {
            String value = node.asText().trim();
            return value.isEmpty() ? List.of() : List.of(normalizeKey(value));
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && notBlank(item.asText())) {
                values.add(normalizeKey(item.asText()));
            }
        }
        return values;
    }

    private static List<FlaggedOrderInput> readFlaggedOrders(JsonNode node) {
        if (node == null || node.isNull() || !node.isArray()) {
            return List.of();
        }
        List<FlaggedOrderInput> results = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                continue;
            }
            results.add(new FlaggedOrderInput(
                    readText(item, "orderId"),
                    readText(item, "issueType"),
                    readText(item, "deliveryStatus"),
                    readDoubleObject(item.get("amount")),
                    readText(item, "suggestedAction")));
        }
        return results;
    }

    private static String readText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field) == null) {
            return null;
        }
        JsonNode child = node.get(field);
        if (!child.isTextual()) {
            return null;
        }
        String value = child.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static Double readDoubleObject(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return roundMoney(node.asDouble());
        }
        if (!node.isTextual()) {
            return null;
        }
        try {
            return roundMoney(Double.parseDouble(node.asText().trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double readDouble(Map<String, Object> map, String key, double defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value instanceof Number number) {
            return roundMoney(number.doubleValue());
        }
        if (value instanceof String text && notBlank(text)) {
            try {
                return roundMoney(Double.parseDouble(text.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static boolean readBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text.trim());
        }
        return defaultValue;
    }

    private static String defaultSuggestedAction(String issueType) {
        return switch (issueType) {
            case "missing_item" -> "partial_refund";
            case "spilled_drink" -> "full_refund";
            case "wrong_item" -> "partial_refund_plus_coupon";
            case "late_delivery" -> "coupon_compensation";
            default -> "manual_review";
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String normalizeKey(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private static double roundMoney(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private static String trimTrailingZero(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.00001d) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private enum TriageRoute {
        ALL_CLEAR("ok", "all_clear", "no_problem_orders"),
        DELIVERY_HANDOFF("handoff", "handoff_delivery_followup", "single_late_delivery"),
        REFUND_HANDOFF("handoff", "handoff_refund_resolution", "refundable_issue_detected");

        private final String status;
        private final String decision;
        private final String reason;

        TriageRoute(String status, String decision, String reason) {
            this.status = status;
            this.decision = decision;
            this.reason = reason;
        }

        public String status() {
            return status;
        }

        public String decision() {
            return decision;
        }

        public String reason() {
            return reason;
        }
    }

    private record TimeWindowScenario(
            String timeRangeKey,
            String timeRangeLabel,
            String customerId,
            List<String> orderIds,
            Map<String, Object> resolutionPolicyContext,
            Map<String, Object> customerAfterSalesProfile) {

        private Map<String, Object> windowSummary(int totalOrders, int problemOrders) {
            Map<String, Object> summary = linkedMap();
            summary.put("timeRangeKey", timeRangeKey);
            summary.put("timeRangeLabel", timeRangeLabel);
            summary.put("customerId", customerId);
            summary.put("totalOrders", totalOrders);
            summary.put("problemOrders", problemOrders);
            return summary;
        }
    }

    private record TakeoutOrderRecord(
            String orderId,
            String customerId,
            String timeRangeKey,
            String timeRangeLabel,
            String orderStatus,
            String deliveryStatus,
            double amount,
            String issueType,
            String suggestedAction,
            String issueSummary,
            String promisedAt,
            String deliveredAt) {

        private boolean hasIssue() {
            return notBlank(issueType);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> result = linkedMap();
            result.put("orderId", orderId);
            result.put("customerId", customerId);
            result.put("timeRangeKey", timeRangeKey);
            result.put("timeRangeLabel", timeRangeLabel);
            result.put("orderStatus", orderStatus);
            result.put("deliveryStatus", deliveryStatus);
            result.put("amount", roundMoney(amount));
            putIfNotNull(result, "issueType", issueType);
            putIfNotNull(result, "suggestedAction", suggestedAction);
            putIfNotNull(result, "issueSummary", issueSummary);
            putIfNotNull(result, "promisedAt", promisedAt);
            putIfNotNull(result, "deliveredAt", deliveredAt);
            return result;
        }

        private Map<String, Object> toFlaggedOrderMap() {
            Map<String, Object> result = linkedMap();
            result.put("orderId", orderId);
            result.put("issueType", issueType);
            result.put("deliveryStatus", deliveryStatus);
            result.put("amount", roundMoney(amount));
            result.put("suggestedAction", suggestedAction);
            return result;
        }
    }

    private record DeliveryFollowupPlan(
            String orderId,
            int delayMinutes,
            String promisedAt,
            String latestEta,
            String issueSummary,
            String courierStatus,
            String customerReply,
            String explanation,
            String internalAction,
            String compensationType,
            double compensationAmount,
            String compensationLabel) {

        private Map<String, Object> recommendedCompensation() {
            Map<String, Object> result = linkedMap();
            result.put("type", compensationType);
            result.put("amount", roundMoney(compensationAmount));
            result.put("label", compensationLabel);
            return result;
        }

        private Map<String, Object> toFollowupPlanMap() {
            Map<String, Object> result = linkedMap();
            result.put("customerReply", customerReply);
            result.put("explanation", explanation);
            result.put("internalAction", internalAction);
            result.put("recommendedCompensation", recommendedCompensation());
            return result;
        }
    }

    public record RefundResolutionContext(
            String simpleOrderId,
            String customerId,
            String timeRangeKey,
            Map<String, Object> windowSummary,
            List<FlaggedOrderInput> flaggedOrders,
            List<String> orderIds,
            Map<String, Object> resolutionPolicyContext,
            Map<String, Object> customerAfterSalesProfile) {

        public static RefundResolutionContext empty() {
            return new RefundResolutionContext(null, null, null, null, List.of(), List.of(), null, null);
        }

        public boolean hasComplexContext() {
            return notBlank(customerId)
                    && notBlank(timeRangeKey)
                    && windowSummary != null && !windowSummary.isEmpty()
                    && flaggedOrders != null && !flaggedOrders.isEmpty()
                    && orderIds != null && !orderIds.isEmpty()
                    && resolutionPolicyContext != null && !resolutionPolicyContext.isEmpty()
                    && customerAfterSalesProfile != null && !customerAfterSalesProfile.isEmpty();
        }
    }

    public record FlaggedOrderInput(
            String orderId,
            String issueType,
            String deliveryStatus,
            Double amount,
            String suggestedAction) {

        private Map<String, Object> toMap() {
            Map<String, Object> result = linkedMap();
            result.put("orderId", orderId);
            result.put("issueType", issueType);
            result.put("deliveryStatus", deliveryStatus);
            result.put("amount", amount);
            result.put("suggestedAction", suggestedAction);
            return result;
        }
    }

    private static void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        if (value instanceof Collection<?> collection && collection.isEmpty()) {
            return;
        }
        target.put(key, value);
    }
}
