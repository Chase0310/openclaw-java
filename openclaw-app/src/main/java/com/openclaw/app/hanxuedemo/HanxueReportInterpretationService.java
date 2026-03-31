package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openclaw.app.hanxuedemo.HanxueServiceSupport.*;

@Service
public class HanxueReportInterpretationService {

    private final HanxueDemoDataService demoDataService;

    public HanxueReportInterpretationService(HanxueDemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    public Map<String, Object> interpret(JsonNode params) {
        Map<String, Object> payload = linkedMap();
        payload.put("action", "interpret_current_report");

        String userMessage = firstNonBlank(readText(params, "userMessage"), readText(params, "question"), "");
        Map<String, Object> currentContext = resolveCurrentContext(params);
        String reportId = firstNonBlank(
                readText(params, "reportId"),
                asString(currentContext, "matchedReportId"),
                asString(currentContext, "reportId"));
        String reportType = firstNonBlank(
                readText(params, "reportType"),
                asString(currentContext, "reportType"),
                demoDataService.resolveReportTypeById(reportId));

        Map<String, Object> reportData = resolveReportData(currentContext, reportId, reportType);
        if (reportData == null || reportData.isEmpty()) {
            payload.put("status", "not_found");
            payload.put("found", false);
            payload.put("message", "当前报告里没有找到可解读的数据。");
            payload.put("recommendedNextAction", "可以换一个报告继续看，或告诉我想了解哪一部分。 ");
            return payload;
        }

        if (shouldHandoffToSwitch(userMessage, reportType)) {
            return buildReportSwitchHandoff(payload, userMessage, currentContext);
        }
        if (shouldHandoffToErrorAnalysis(userMessage)) {
            return buildErrorAnalysisHandoff(payload, userMessage, currentContext, reportData, reportId, reportType);
        }
        if (shouldHandoffToLearningPlan(userMessage)) {
            return buildLearningPlanHandoff(payload, currentContext, reportData, reportId, reportType);
        }

        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("message", buildInterpretationMessage(reportType, reportData));
        payload.put("record", buildRecord(reportId, reportType, currentContext));
        payload.put("facts", buildFacts(reportId, reportType, reportData));
        payload.put("suggestions", buildSuggestions(reportType, reportData));
        payload.put("recommendedNextAction", "如果您想继续深挖错因或接下来怎么学，我可以继续接着讲。 ");
        return payload;
    }

    private Map<String, Object> resolveCurrentContext(JsonNode params) {
        Map<String, Object> context = readMap(params, "currentReportContext");
        if (context != null && !context.isEmpty()) {
            return context;
        }
        Map<String, Object> handoffParams = readMap(params, "handoffParams");
        if (handoffParams != null && !handoffParams.isEmpty()) {
            Map<String, Object> nested = castMap(handoffParams);
            Map<String, Object> fromNested = castMap((Map<?, ?>) nested.getOrDefault("currentReportContext", Map.of()));
            if (fromNested != null && !fromNested.isEmpty()) {
                return fromNested;
            }
        }
        return demoDataService.getDefaultCurrentReportContext();
    }

    private Map<String, Object> resolveReportData(
            Map<String, Object> currentContext,
            String reportId,
            String reportType) {
        Object contextData = currentContext.get("reportData");
        if (contextData instanceof Map<?, ?> map && !map.isEmpty()) {
            return castMap(map);
        }

        if (!isBlank(reportId)) {
            Map<String, Object> byId = demoDataService.getReportById(reportId);
            if (byId != null) {
                return byId;
            }
        }

        if (containsAny(reportType, "数学学情日报", "日报")) {
            return demoDataService.getDailyMathReport();
        }
        return null;
    }

    private boolean shouldHandoffToSwitch(String userMessage, String reportType) {
        if (containsAny(userMessage, "看看昨天", "上周", "上上周", "想看报告", "看看报告", "今天的数学", "哪节课")) {
            return true;
        }
        return containsAny(reportType, "数学学情日报") && containsAny(userMessage, "具体哪节课", "哪节课");
    }

    private boolean shouldHandoffToErrorAnalysis(String userMessage) {
        return containsAny(userMessage, "为什么错", "哪里错", "错在哪", "为啥错", "怎么错的");
    }

    private boolean shouldHandoffToLearningPlan(String userMessage) {
        return containsAny(userMessage, "怎么学", "怎么练", "怎么提升", "接下来", "学习计划");
    }

    private Map<String, Object> buildReportSwitchHandoff(
            Map<String, Object> payload,
            String userMessage,
            Map<String, Object> currentContext) {
        payload.put("status", "handoff");
        payload.put("found", true);
        payload.put("message", "您是在看其他报告，我先帮您定位到那一份。 ");

        Map<String, Object> params = linkedMap();
        params.put("userMessage", userMessage);
        params.put("todayDate", readTodayDate(currentContext));
        params.put("todayReportList", demoDataService.getTodayReportList());
        params.put("currentReportContext", currentContext);

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "hanxue-report-switch");
        handoff.put("reason", "user_wants_another_report");
        handoff.put("params", params);

        payload.put("handoff", handoff);
        payload.put("recommendedNextAction", "先定位目标报告，再继续解读。 ");
        return payload;
    }

    private Map<String, Object> buildErrorAnalysisHandoff(
            Map<String, Object> payload,
            String userMessage,
            Map<String, Object> currentContext,
            Map<String, Object> reportData,
            String reportId,
            String reportType) {
        payload.put("status", "handoff");
        payload.put("found", true);
        payload.put("message", "这个问题更适合做错因深挖，我把当前题目上下文带过去。 ");

        Map<String, Object> qa = extractQuestionData(reportType, reportData);
        Map<String, Object> params = linkedMap();
        params.put("reportId", reportId);
        params.put("reportType", reportType);
        params.put("subject", firstNonBlank(asString(currentContext, "subject"), asString(reportData, "学科"), "数学"));
        params.put("questionContent", asString(qa, "questionContent"));
        params.put("studentAnswer", asString(qa, "studentAnswer"));
        params.put("correctAnswer", asString(qa, "correctAnswer"));
        params.put("knowledgePoint", asString(qa, "knowledgePoint"));
        params.put("userMessage", userMessage);
        params.put("currentReportContext", currentContext);

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "hanxue-error-analysis");
        handoff.put("reason", "user_asks_why_wrong");
        handoff.put("params", params);

        payload.put("handoff", handoff);
        payload.put("recommendedNextAction", "进入错因分析后，我会先说事实再解释可能原因。 ");
        return payload;
    }

    private Map<String, Object> buildLearningPlanHandoff(
            Map<String, Object> payload,
            Map<String, Object> currentContext,
            Map<String, Object> reportData,
            String reportId,
            String reportType) {
        payload.put("status", "handoff");
        payload.put("found", true);
        payload.put("message", "您在问接下来怎么学，我把薄弱点和报告上下文一起承接到学习规划。 ");

        List<String> weakPoints = extractWeakPoints(reportType, reportData);

        Map<String, Object> params = linkedMap();
        params.put("sourceScene", "A");
        params.put("reportId", reportId);
        params.put("reportType", reportType);
        params.put("knowledgePoint", weakPoints.isEmpty() ? null : weakPoints.get(0));
        params.put("weakPoints", weakPoints);
        params.put("childGrade", firstNonBlank(asString(currentContext, "childGrade"), asString(reportData, "学生年级"), "7年级"));
        params.put("currentReportContext", currentContext);
        params.put("recentLearningSummary", demoDataService.getRecentLearningSummary());

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "hanxue-learning-plan");
        handoff.put("reason", "user_asks_learning_plan");
        handoff.put("params", params);

        payload.put("handoff", handoff);
        payload.put("recommendedNextAction", "下一步会基于当前报告给出更具体的练习路径。 ");
        return payload;
    }

    private String buildInterpretationMessage(String reportType, Map<String, Object> reportData) {
        if (containsAny(reportType, "数学一对一辅学营")) {
            List<String> weak = extractWeakPoints(reportType, reportData);
            int mastered = asInt(castMap((Map<?, ?>) reportData.getOrDefault("汇总", Map.of())), "课后_熟练掌握数", 0);
            return "这节数学课里，孩子有 " + mastered + " 个考点达到掌握，当前更需要关注 " + weak.size()
                    + " 个还在巩固中的点。";
        }
        if (containsAny(reportType, "语文一对一", "英语一对一")) {
            Map<String, Object> summary = castMap((Map<?, ?>) reportData.getOrDefault("学习总结", Map.of()));
            int done = asInt(summary, "通关PT数", 0);
            int total = asInt(summary, "该lesson总PT数", 0);
            return "这节课当前通关进度是 " + done + "/" + total + "，已完成部分比较稳定，未通关项可以继续追问我展开。";
        }
        if (containsAny(reportType, "高中一对一", "高中全科")) {
            Map<String, Object> overview = castMap((Map<?, ?>) reportData.getOrDefault("今日成果总览", Map.of()));
            int total = asInt(overview, "答题量", 0);
            double rate = asDouble(overview, "正确率", 0.0);
            return "今天这节课一共做了 " + total + " 道题，当前正确率约 " + Math.round(rate * 100) + "% ，我可以继续展开薄弱知识点。";
        }
        if (containsAny(reportType, "数学学情日报", "日报")) {
            int total = asInt(reportData, "今日做题量", 0);
            List<Map<String, Object>> ongoing = safeMapList(reportData.get("突破中PT"));
            return "今天数学一共做了 " + total + " 道题，目前有 " + ongoing.size() + " 个题型还在练。";
        }
        return "我先把这份报告的核心数据整理出来了，您可以继续点具体问题我再展开。";
    }

    private Map<String, Object> buildRecord(String reportId, String reportType, Map<String, Object> currentContext) {
        Map<String, Object> record = linkedMap();
        record.put("reportId", reportId);
        record.put("reportType", reportType);
        record.put("reportDate", firstNonBlank(asString(currentContext, "reportDate"), readTodayDate(currentContext)));
        record.put("entryScene", firstNonBlank(asString(currentContext, "entryScene"), "A"));
        record.put("childName", firstNonBlank(asString(currentContext, "childName"), "小鉴"));
        return record;
    }

    private Map<String, Object> buildFacts(String reportId, String reportType, Map<String, Object> reportData) {
        Map<String, Object> facts = linkedMap();
        facts.put("reportId", reportId);
        facts.put("reportType", reportType);
        facts.put("weakPoints", extractWeakPoints(reportType, reportData));

        if (containsAny(reportType, "数学学情日报", "日报")) {
            facts.put("newlyMastered", safeMapList(reportData.get("新掌握PT")));
            facts.put("ongoingPT", safeMapList(reportData.get("突破中PT")));
            facts.put("todayMathSessions", safeMapList(reportData.get("今日数学课次报告列表")));
        }

        if (containsAny(reportType, "周报")) {
            facts.put("progressSignals", List.of(
                    Map.of("signal", "ranking_up", "subject", "数学", "from", "60%", "to", "75%"),
                    Map.of("signal", "learning_days_up", "from", 3, "to", 5)));
        }

        return facts;
    }

    private Map<String, Object> buildSuggestions(String reportType, Map<String, Object> reportData) {
        Map<String, Object> suggestions = linkedMap();
        suggestions.put("nextOptions", buildNextOptions(reportType, reportData));
        suggestions.put("shouldKeepCurrentReport", true);
        return suggestions;
    }

    private List<String> buildNextOptions(String reportType, Map<String, Object> reportData) {
        if (containsAny(reportType, "数学一对一辅学营")) {
            return List.of("哪些知识点还需要练？", "错题是什么情况？", "今天掌握了哪些新内容？", "跟上次比有进步吗？", "接下来怎么学比较好？");
        }
        if (containsAny(reportType, "语文一对一", "英语一对一")) {
            return List.of("相关的知识点进度怎么样？", "哪些知识点还薄弱？", "今天学了什么内容？", "跟上次比有进步吗？", "接下来怎么学比较好？");
        }
        if (containsAny(reportType, "高中一对一", "高中全科")) {
            return List.of("哪些知识点还需要练？", "错题是什么情况？", "今天掌握了哪些新内容？", "跟上次比有进步吗？", "接下来怎么学比较好？");
        }
        if (containsAny(reportType, "数学学情日报", "日报")) {
            List<String> options = new ArrayList<>();
            if (!safeMapList(reportData.get("突破中PT")).isEmpty()) {
                options.add("哪些题型还没掌握？");
            }
            if (!safeMapList(reportData.get("新掌握PT")).isEmpty()) {
                options.add("今天新掌握了什么？");
            }
            options.add("错题是什么情况？");
            options.add("想看具体哪节课的情况？");
            options.add("接下来怎么学比较好？");
            return options;
        }
        if (containsAny(reportType, "周报")) {
            return List.of("这周哪些科目进步了？", "有哪些需要关注的地方？", "错题情况怎么样？", "跟上周对比怎么样？", "接下来怎么安排学习？");
        }
        return List.of("继续看这份报告", "接下来怎么学比较好？");
    }

    private List<String> extractWeakPoints(String reportType, Map<String, Object> reportData) {
        List<String> weak = new ArrayList<>();
        if (containsAny(reportType, "数学一对一辅学营")) {
            for (Map<String, Object> item : safeMapList(reportData.get("母题列表"))) {
                String state = asString(item, "课后掌握状态");
                if (!containsAny(state, "熟练掌握")) {
                    String point = asString(item, "考点名称");
                    if (!isBlank(point)) {
                        weak.add(point);
                    }
                }
            }
            return weak;
        }

        if (containsAny(reportType, "语文一对一", "英语一对一")) {
            for (Map<String, Object> item : safeMapList(reportData.get("PT列表"))) {
                if (!containsAny(asString(item, "通关状态"), "已通关")) {
                    String point = asString(item, "PT名称");
                    if (!isBlank(point)) {
                        weak.add(point);
                    }
                }
            }
            return weak;
        }

        if (containsAny(reportType, "高中一对一", "高中全科")) {
            Map<String, Object> learning = castMap((Map<?, ?>) reportData.getOrDefault("知识学习情况", Map.of()));
            for (Map<String, Object> item : safeMapList(learning.get("知识点列表"))) {
                if (containsAny(asString(item, "掌握状态"), "薄弱", "不熟练")) {
                    String point = asString(item, "知识点名称");
                    if (!isBlank(point)) {
                        weak.add(point);
                    }
                }
            }
            return weak;
        }

        if (containsAny(reportType, "作业批改")) {
            weak.addAll(safeStringList(reportData.get("薄弱维度")));
            return weak;
        }

        if (containsAny(reportType, "数学学情日报", "日报")) {
            for (Map<String, Object> item : safeMapList(reportData.get("突破中PT"))) {
                String point = asString(item, "PT名");
                if (!isBlank(point)) {
                    weak.add(point);
                }
            }
            return weak;
        }

        return weak;
    }

    private Map<String, Object> extractQuestionData(String reportType, Map<String, Object> reportData) {
        if (containsAny(reportType, "语文一对一", "英语一对一")) {
            for (Map<String, Object> pt : safeMapList(reportData.get("PT列表"))) {
                List<Map<String, Object>> wrong = safeMapList(pt.get("失败尝试中的错题"));
                if (wrong.isEmpty()) {
                    continue;
                }
                Map<String, Object> one = wrong.get(0);
                Map<String, Object> result = linkedMap();
                result.put("questionContent", asString(one, "题目内容"));
                result.put("studentAnswer", asString(one, "学生答案"));
                result.put("correctAnswer", asString(one, "正确答案"));
                result.put("knowledgePoint", asString(pt, "PT名称"));
                return result;
            }
        }

        Map<String, Object> fallback = linkedMap();
        fallback.put("questionContent", "已知方程 x²-2kx+k+6=0 有两个不相等的实数根，求 k 的取值范围。");
        fallback.put("studentAnswer", "k > 9/4");
        fallback.put("correctAnswer", "k < -2 或 k > 3");
        List<String> weak = extractWeakPoints(reportType, reportData);
        fallback.put("knowledgePoint", weak.isEmpty() ? "判别式应用" : weak.get(0));
        return fallback;
    }

    private String readTodayDate(Map<String, Object> currentContext) {
        String date = asString(currentContext, "todayDate");
        if (!isBlank(date)) {
            return date;
        }
        return LocalDate.now().toString();
    }
}
