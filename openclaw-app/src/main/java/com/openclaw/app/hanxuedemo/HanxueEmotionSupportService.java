package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.openclaw.app.hanxuedemo.HanxueServiceSupport.*;

@Service
public class HanxueEmotionSupportService {

    private final HanxueDemoDataService demoDataService;

    public HanxueEmotionSupportService(HanxueDemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    public Map<String, Object> support(JsonNode params) {
        Map<String, Object> payload = linkedMap();
        payload.put("action", "support_parent_emotion");

        String userMessage = firstNonBlank(readText(params, "userMessage"), "");
        Map<String, Object> recentSummary = readMap(params, "recentLearningSummary");
        if (recentSummary == null || recentSummary.isEmpty()) {
            recentSummary = demoDataService.getRecentLearningSummary();
        }

        Map<String, Object> behaviorSignals = Map.of(
                "learningDays", asInt(recentSummary, "学习天数", 0),
                "totalDurationMinutes", asInt(recentSummary, "总时长_分钟", 0),
                "streakDays", asInt(recentSummary, "已坚持天数", 0));

        String emotionType = detectEmotionType(userMessage);
        String emotionLevel = detectEmotionLevel(userMessage, emotionType);
        boolean shouldTransfer = shouldTransferToHuman(userMessage, emotionType);

        if (shouldTransfer) {
            payload.put("status", "ok");
            payload.put("found", true);
            payload.put("message", "我理解您现在很着急，这类问题让顾问老师直接跟进会更高效。 ");
            payload.put("facts", Map.of(
                    "emotionType", emotionType,
                    "emotionLevel", emotionLevel,
                    "behaviorSignals", behaviorSignals,
                    "isTransferScenario", true));
            payload.put("suggestions", Map.of(
                    "shouldTransferToHuman", true,
                    "transferHint", "您可以在微信里联系顾问老师处理。"));
            payload.put("recommendedNextAction", "请联系顾问老师进一步处理。 ");
            return payload;
        }

        if (containsAny(userMessage, "找回兴趣", "试试什么", "怎么帮他", "可以试试")) {
            return buildLearningPlanHandoff(payload, emotionType, behaviorSignals);
        }
        if (containsAny(userMessage, "最近他学得还好吗", "最近学得怎么样", "学得还行吗")) {
            return buildReportInterpretationHandoff(payload, behaviorSignals);
        }

        String empathy = buildEmpathy(emotionType, emotionLevel);
        String behavior = buildBehaviorSupport(behaviorSignals);
        String gentleSuggestion = buildGentleSuggestion(emotionType);

        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("message", empathy + behavior + gentleSuggestion);
        payload.put("record", Map.of(
                "emotionType", emotionType,
                "emotionLevel", emotionLevel,
                "behaviorSignals", behaviorSignals));
        payload.put("facts", Map.of(
                "emotionType", emotionType,
                "emotionLevel", emotionLevel,
                "behaviorSignals", behaviorSignals,
                "hasRecentLearningSummary", true));
        payload.put("suggestions", Map.of(
                "shouldTransferToHuman", false,
                "exitOptions", List.of("可以试试什么帮他找回兴趣？", "最近他学得还好吗？", "好的")));
        payload.put("recommendedNextAction", "可以先按温和节奏试两三天，我再陪您看变化。 ");
        return payload;
    }

    private Map<String, Object> buildLearningPlanHandoff(
            Map<String, Object> payload,
            String emotionType,
            Map<String, Object> behaviorSignals) {
        payload.put("status", "handoff");
        payload.put("found", true);
        payload.put("message", "我给您接到‘找回学习节奏’的轻量规划里，先从低压力方案开始。 ");

        Map<String, Object> params = linkedMap();
        params.put("sourceScene", "C");
        params.put("emotionType", emotionType);
        params.put("recentLearningSummary", demoDataService.getRecentLearningSummary());
        params.put("currentReportContext", demoDataService.getDefaultCurrentReportContext());
        params.put("behaviorSignals", behaviorSignals);

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "hanxue-learning-plan");
        handoff.put("reason", "emotion_scene_gentle_plan");
        handoff.put("params", params);

        payload.put("handoff", handoff);
        payload.put("recommendedNextAction", "下一步会给出每次 5 分钟左右的轻量练习路径。 ");
        return payload;
    }

    private Map<String, Object> buildReportInterpretationHandoff(
            Map<String, Object> payload,
            Map<String, Object> behaviorSignals) {
        payload.put("status", "handoff");
        payload.put("found", true);
        payload.put("message", "我把您带回学情解读，先用报告事实回答‘最近学得怎么样’。 ");

        Map<String, Object> params = linkedMap();
        params.put("currentReportContext", demoDataService.getDefaultCurrentReportContext());
        params.put("todaySessionSummary", Map.of("fromEmotionScene", true));
        params.put("progressSignals", behaviorSignals);

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "hanxue-report-interpretation");
        handoff.put("reason", "emotion_to_data_view");
        handoff.put("params", params);

        payload.put("handoff", handoff);
        payload.put("recommendedNextAction", "我先回到报告事实层，帮您看孩子最近学习状态。 ");
        return payload;
    }

    private String detectEmotionType(String message) {
        if (containsAny(message, "退货", "退款", "客服", "骗钱", "垃圾", "破机器")) {
            return "product_dissatisfaction";
        }
        if (containsAny(message, "不想学", "没效果", "担心", "焦虑", "跟不上")) {
            return "learning_anxiety";
        }
        if (containsAny(message, "太懒", "不肯学", "不听", "操碎了心")) {
            return "disappointed_with_child";
        }
        if (containsAny(message, "气死", "受不了", "你们就是")) {
            return "aggressive_emotion";
        }
        return "mild_worry";
    }

    private String detectEmotionLevel(String message, String emotionType) {
        if (containsAny(message, "!!!", "根本", "完全", "一点没", "连续")) {
            return "high";
        }
        if (containsAny(emotionType, "aggressive_emotion", "product_dissatisfaction")) {
            return "medium_high";
        }
        return "medium";
    }

    private boolean shouldTransferToHuman(String message, String emotionType) {
        if (containsAny(message, "退货", "退款", "转人工", "找客服", "换手机号登不上", "绑定失败")) {
            return true;
        }
        return containsAny(emotionType, "aggressive_emotion") && containsAny(message, "骗钱", "破机器", "什么玩意");
    }

    private String buildEmpathy(String emotionType, String emotionLevel) {
        return switch (emotionType) {
            case "learning_anxiety" -> "我能理解您现在的焦虑，明明投入了很多心思，却暂时没看到理想变化。";
            case "product_dissatisfaction" -> "您这种失望我理解，花了钱却没看到明显变化，确实会很挫败。";
            case "disappointed_with_child" -> "您一直在为孩子操心，这份着急和压力我能感受到。";
            default -> "您的担心很真实，先别急，我们一步一步来。";
        };
    }

    private String buildBehaviorSupport(Map<String, Object> behaviorSignals) {
        int days = asInt(behaviorSignals, "learningDays", 0);
        int minutes = asInt(behaviorSignals, "totalDurationMinutes", 0);
        int streak = asInt(behaviorSignals, "streakDays", 0);

        if (days <= 0 && minutes <= 0) {
            return "最近确实没有看到学习记录，这种时候先把节奏找回来会更重要。";
        }
        return "从行为上看，最近学了 " + days + " 天、累计 " + minutes + " 分钟，也已经坚持了 " + streak + " 天，这说明孩子不是完全停下来了。";
    }

    private String buildGentleSuggestion(String emotionType) {
        if (containsAny(emotionType, "product_dissatisfaction")) {
            return "我们先别拉太满，先看一个可落地的小目标，比如每天 5 分钟，先把状态稳住。";
        }
        if (containsAny(emotionType, "disappointed_with_child")) {
            return "先把要求降一点，从短时、低压力任务开始，更容易让孩子重新进入状态。";
        }
        return "不妨先从每次 5 分钟的小任务开始，先把节奏找回来，再慢慢加量。";
    }
}
