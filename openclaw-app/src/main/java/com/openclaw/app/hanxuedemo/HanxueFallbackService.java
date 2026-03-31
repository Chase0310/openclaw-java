package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.openclaw.app.hanxuedemo.HanxueServiceSupport.*;

@Service
public class HanxueFallbackService {

    private final HanxueDemoDataService demoDataService;

    public HanxueFallbackService(HanxueDemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    public Map<String, Object> generate(JsonNode params) {
        Map<String, Object> payload = linkedMap();
        payload.put("action", "generate_fallback_response");

        String userMessage = firstNonBlank(readText(params, "userMessage"), "");
        String childName = firstNonBlank(readText(params, "childName"), "孩子");
        String childGrade = firstNonBlank(readText(params, "childGrade"), "7年级");

        Map<String, Object> currentContext = readMap(params, "currentReportContext");
        if (currentContext == null || currentContext.isEmpty()) {
            currentContext = demoDataService.getDefaultCurrentReportContext();
        }

        Map<String, Object> recentSummary = readMap(params, "recentLearningSummary");
        if (recentSummary == null || recentSummary.isEmpty()) {
            recentSummary = demoDataService.getRecentLearningSummary();
        }

        List<Map<String, Object>> todayReports = readMapList(params, "todayReportList");
        if (todayReports.isEmpty()) {
            todayReports = demoDataService.getTodayReportList();
        }

        if (containsAny(userMessage, "转人工", "找客服", "退货", "退款", "屏幕坏", "硬件", "维修")) {
            return buildTransferCase(payload);
        }

        if (containsAny(userMessage, "怎么设置", "管控", "密码", "答案", "功能", "小程序")) {
            return buildFeatureHandoff(payload, userMessage);
        }

        if (containsAny(userMessage, "看看昨天", "想看报告", "看看报告", "哪节课", "上周")) {
            return buildReportSwitchHandoff(payload, userMessage, currentContext, todayReports);
        }

        if (containsAny(userMessage, "怎么学", "怎么练", "学习计划")) {
            return buildLearningPlanHandoff(payload, currentContext, recentSummary);
        }

        if (containsAny(userMessage, "这个报告", "这份报告", "最近学得") && currentContext != null) {
            return buildInterpretationHandoff(payload, currentContext);
        }

        if (containsAny(userMessage, "鸡兔同笼", "牛顿第二定律", "韦达定理", "电磁感应")) {
            return buildSubjectKnowledgeCase(payload, userMessage, childName, childGrade, todayReports);
        }

        if (containsAny(userMessage, "你多大", "讲个笑话", "今天天气", "你是谁", "真人还是机器人")) {
            payload.put("status", "ok");
            payload.put("found", true);
            payload.put("message", "我是 " + childName + " 的寒雪老师呀～ 有什么关于学习的问题随时问我。");
            payload.put("facts", Map.of("caseType", "chitchat", "pullbackContext", "learning_focus"));
            payload.put("suggestions", Map.of("pullback", "可以先看今天的学习情况"));
            payload.put("recommendedNextAction", "您也可以直接问我某一科最近学得怎么样。 ");
            return payload;
        }

        if (containsAny(userMessage, "不当内容", "脏话", "辱骂", "色情")) {
            payload.put("status", "ok");
            payload.put("found", true);
            payload.put("message", "我们还是来关注孩子的学习情况吧～");
            payload.put("facts", Map.of("caseType", "unsafe_content"));
            payload.put("recommendedNextAction", "您可以告诉我想看哪科或哪份报告。 ");
            return payload;
        }

        if (containsAny(userMessage, "嗯", "好的", "不用了", "知道了") && userMessage.length() <= 4) {
            payload.put("status", "ok");
            payload.put("found", true);
            payload.put("message", "好的，有什么想了解的随时找我～");
            payload.put("facts", Map.of("caseType", "close_confirmation"));
            payload.put("recommendedNextAction", "您随时继续问都可以。 ");
            return payload;
        }

        if (isVagueMessage(userMessage)) {
            return buildNeedsInputCase(payload, todayReports);
        }

        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("message", "我先接住您的问题，如果您愿意，我可以先从最近学习数据里挑最关键的一点给您看。 ");
        payload.put("facts", Map.of("caseType", "generic_pullback"));
        payload.put("suggestions", Map.of("pullback", "优先看最近 7 天学习情况"));
        payload.put("recommendedNextAction", "您可以直接说“看数学”或“看周报”。 ");
        return payload;
    }

    private Map<String, Object> buildTransferCase(Map<String, Object> payload) {
        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("message", "这个情况我建议您直接联系顾问老师，他们可以更快帮您处理。 ");
        payload.put("facts", Map.of("caseType", "transfer_human"));
        payload.put("suggestions", Map.of("shouldTransferToHuman", true));
        payload.put("recommendedNextAction", "请在微信里联系顾问老师。 ");
        return payload;
    }

    private Map<String, Object> buildFeatureHandoff(Map<String, Object> payload, String userMessage) {
        payload.put("status", "handoff");
        payload.put("found", true);
        payload.put("message", "这是功能使用问题，我帮您切到功能引导。 ");

        Map<String, Object> params = linkedMap();
        params.put("userMessage", userMessage);

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "hanxue-feature-guide");
        handoff.put("reason", "feature_usage_question");
        handoff.put("params", params);

        payload.put("handoff", handoff);
        payload.put("recommendedNextAction", "功能引导会给您固定跳转入口。 ");
        return payload;
    }

    private Map<String, Object> buildReportSwitchHandoff(
            Map<String, Object> payload,
            String userMessage,
            Map<String, Object> currentContext,
            List<Map<String, Object>> todayReports) {
        payload.put("status", "handoff");
        payload.put("found", true);
        payload.put("message", "您想看别的报告，我先帮您定位到目标报告。 ");

        Map<String, Object> params = linkedMap();
        params.put("userMessage", userMessage);
        params.put("todayDate", demoDataService.getBaseToday().toString());
        params.put("todayReportList", todayReports);
        params.put("currentReportContext", currentContext);

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "hanxue-report-switch");
        handoff.put("reason", "report_navigation_requested");
        handoff.put("params", params);

        payload.put("handoff", handoff);
        payload.put("recommendedNextAction", "先定位报告，再继续解读。 ");
        return payload;
    }

    private Map<String, Object> buildLearningPlanHandoff(
            Map<String, Object> payload,
            Map<String, Object> currentContext,
            Map<String, Object> recentSummary) {
        payload.put("status", "handoff");
        payload.put("found", true);
        payload.put("message", "您在问接下来怎么学，我帮您切到学习规划并复用已有上下文。 ");

        Map<String, Object> params = linkedMap();
        params.put("sourceScene", "G");
        params.put("currentReportContext", currentContext);
        params.put("recentLearningSummary", recentSummary);

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "hanxue-learning-plan");
        handoff.put("reason", "learning_plan_requested_in_fallback");
        handoff.put("params", params);

        payload.put("handoff", handoff);
        payload.put("recommendedNextAction", "学习规划会给出分步路径建议。 ");
        return payload;
    }

    private Map<String, Object> buildInterpretationHandoff(Map<String, Object> payload, Map<String, Object> currentContext) {
        payload.put("status", "handoff");
        payload.put("found", true);
        payload.put("message", "我先回到当前报告解读，按事实给您说明。 ");

        Map<String, Object> params = linkedMap();
        params.put("currentReportContext", currentContext);

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "hanxue-report-interpretation");
        handoff.put("reason", "return_to_report_interpretation");
        handoff.put("params", params);

        payload.put("handoff", handoff);
        payload.put("recommendedNextAction", "我会先讲当前报告最关键的事实。 ");
        return payload;
    }

    private Map<String, Object> buildSubjectKnowledgeCase(
            Map<String, Object> payload,
            String userMessage,
            String childName,
            String childGrade,
            List<Map<String, Object>> todayReports) {
        String explanation;
        if (containsAny(userMessage, "牛顿第二定律")) {
            explanation = "牛顿第二定律讲的是力和加速度的关系，受力越大、同样质量下加速度越大。";
        } else if (containsAny(userMessage, "韦达定理")) {
            explanation = "韦达定理是用方程系数直接表示两根的和与积，常用来做根与系数关系题。";
        } else if (containsAny(userMessage, "电磁感应")) {
            explanation = "电磁感应是导体在磁场变化时会产生感应电流。";
        } else {
            explanation = "鸡兔同笼通常用假设法或方程法来解。";
        }

        String relatedSubject = detectRelatedSubject(userMessage);
        List<Map<String, Object>> related = filterBySubject(todayReports, relatedSubject);

        if (!related.isEmpty()) {
            payload.put("status", "needs_input");
            payload.put("found", true);
            payload.put("message", explanation + childName + " 最近也有相关学习记录，您要不要看这份报告？");
            payload.put("facts", Map.of("caseType", "subject_knowledge_with_report", "subject", relatedSubject));
            payload.put("records", buildReportOptions(related));
            payload.put("recommendedNextAction", "选一份报告后，我可以结合数据继续讲。 ");
            return payload;
        }

        if (containsAny(userMessage, "鸡兔同笼") && containsAny(childGrade, "高一", "高二", "高三", "高中")) {
            payload.put("status", "ok");
            payload.put("found", true);
            payload.put("message", explanation + childName + " 现在" + childGrade + "，这类题一般不常见了，有当前学习问题我随时帮您看。");
            payload.put("facts", Map.of("caseType", "subject_knowledge_cross_grade", "subject", relatedSubject));
            payload.put("recommendedNextAction", "您也可以直接问当前报告里的具体问题。 ");
            return payload;
        }

        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("message", explanation + childName + " 最近没有这科的新报告，等下次学了我再帮您对照看掌握情况。");
        payload.put("facts", Map.of("caseType", "subject_knowledge_no_recent_report", "subject", relatedSubject));
        payload.put("recommendedNextAction", "如果要看当前已有报告，我也可以马上帮您切过去。 ");
        return payload;
    }

    private Map<String, Object> buildNeedsInputCase(Map<String, Object> payload, List<Map<String, Object>> todayReports) {
        payload.put("status", "needs_input");
        payload.put("found", true);
        payload.put("message", "您想看哪方面呢？我可以先从学习报告或小程序功能里帮您选。");
        payload.put("facts", Map.of("caseType", "information_insufficient"));

        if (!todayReports.isEmpty()) {
            payload.put("records", buildReportOptions(todayReports));
            payload.put("suggestions", Map.of("pullback", "先从今天的报告里选一份"));
        } else {
            payload.put("records", List.of(
                    Map.of("label", "看最近的学习报告", "target", "report_list"),
                    Map.of("label", "小程序功能怎么用", "target", "hanxue-feature-guide")));
            payload.put("suggestions", Map.of("pullback", "先确认是学情问题还是功能问题"));
        }
        payload.put("recommendedNextAction", "选一项我就可以马上接着处理。 ");
        return payload;
    }

    private boolean isVagueMessage(String userMessage) {
        if (isBlank(userMessage)) {
            return true;
        }
        String trimmed = userMessage.trim();
        return containsAny(trimmed, "看看", "帮帮我", "在吗", "？", "?", "说说") && trimmed.length() <= 6;
    }

    private String detectRelatedSubject(String userMessage) {
        if (containsAny(userMessage, "牛顿", "电磁", "物理")) {
            return "物理";
        }
        if (containsAny(userMessage, "韦达", "判别式", "鸡兔同笼", "数学")) {
            return "数学";
        }
        return "综合";
    }

    private List<Map<String, Object>> filterBySubject(List<Map<String, Object>> reports, String subject) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> report : reports) {
            if (containsAny(asString(report, "subject"), subject) || containsAny(asString(report, "reportType"), subject)) {
                result.add(report);
            }
        }
        return result;
    }

    private List<Map<String, Object>> buildReportOptions(List<Map<String, Object>> reports) {
        List<Map<String, Object>> options = new ArrayList<>();
        int limit = Math.min(5, reports.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> report = reports.get(i);
            options.add(Map.of(
                    "reportId", asString(report, "reportId"),
                    "label", asString(report, "label"),
                    "targetSkill", "hanxue-report-switch"));
        }
        options.add(Map.of("label", "没有想看的", "target", "report_list"));
        return options;
    }
}
