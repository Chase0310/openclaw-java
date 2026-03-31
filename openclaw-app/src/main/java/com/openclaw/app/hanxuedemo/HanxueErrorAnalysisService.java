package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.openclaw.app.hanxuedemo.HanxueServiceSupport.*;

@Service
public class HanxueErrorAnalysisService {

    private final HanxueDemoDataService demoDataService;

    public HanxueErrorAnalysisService(HanxueDemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    public Map<String, Object> analyze(JsonNode params) {
        Map<String, Object> payload = linkedMap();
        payload.put("action", "analyze_error_reason");

        String userMessage = firstNonBlank(readText(params, "userMessage"), "");
        String reportId = readText(params, "reportId");
        String subject = firstNonBlank(readText(params, "subject"), resolveSubject(reportId), "数学");

        String questionContent = readText(params, "questionContent");
        String studentAnswer = readText(params, "studentAnswer");
        String correctAnswer = readText(params, "correctAnswer");
        String knowledgePoint = firstNonBlank(readText(params, "knowledgePoint"), inferKnowledgePoint(reportId), "当前知识点");
        String negativeAbilityTag = readText(params, "negativeAbilityTag");

        if (shouldHandoffToLearningPlan(userMessage)) {
            return buildLearningPlanHandoff(payload, reportId, subject, knowledgePoint);
        }

        boolean missingQuestion = isBlank(questionContent);
        boolean missingStudent = isBlank(studentAnswer);
        boolean missingCorrect = isBlank(correctAnswer);
        boolean complete = !(missingQuestion || missingStudent || missingCorrect);

        if (!complete) {
            payload.put("status", "partial");
            payload.put("found", true);
            payload.put("message", buildPartialMessage(subject, knowledgePoint, missingQuestion, missingStudent, missingCorrect));
            payload.put("facts", Map.of(
                    "subject", subject,
                    "knowledgePoint", knowledgePoint,
                    "missingQuestionContent", missingQuestion,
                    "missingStudentAnswer", missingStudent,
                    "missingCorrectAnswer", missingCorrect,
                    "hasCompleteQuestionData", false));
            payload.put("suggestions", Map.of(
                    "analysisMode", "fact_fallback",
                    "nextOptions", List.of(knowledgePoint + "该怎么练？", "还有其他错题吗？")));
            payload.put("recommendedNextAction", "如果您方便，把题目原文和孩子作答补上，我可以再精确一些。 ");
            return payload;
        }

        String analysis = buildConservativeAnalysis(
                subject,
                questionContent,
                studentAnswer,
                correctAnswer,
                negativeAbilityTag,
                knowledgePoint);

        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("message", analysis);
        payload.put("record", Map.of(
                "subject", subject,
                "knowledgePoint", knowledgePoint,
                "questionContent", questionContent,
                "studentAnswer", studentAnswer,
                "correctAnswer", correctAnswer));
        payload.put("facts", Map.of(
                "subject", subject,
                "knowledgePoint", knowledgePoint,
                "hasCompleteQuestionData", true,
                "usedAbilityTag", !isBlank(negativeAbilityTag),
                "abilityTag", negativeAbilityTag == null ? "" : negativeAbilityTag));
        payload.put("suggestions", Map.of(
                "analysisMode", "conservative",
                "wordingConstraint", "must_use_maybe",
                "nextOptions", List.of(knowledgePoint + "该怎么练？", "还有其他错题吗？")));
        payload.put("recommendedNextAction", "如果您想继续到‘该怎么练’，我可以直接带着这个知识点进入学习规划。 ");
        return payload;
    }

    private boolean shouldHandoffToLearningPlan(String userMessage) {
        return containsAny(userMessage, "怎么练", "怎么学", "怎么提升", "避免再错", "下一步");
    }

    private Map<String, Object> buildLearningPlanHandoff(
            Map<String, Object> payload,
            String reportId,
            String subject,
            String knowledgePoint) {
        payload.put("status", "handoff");
        payload.put("found", true);
        payload.put("message", "这个问题已经进入‘该怎么练’，我把当前错因上下文承接到学习规划。 ");

        Map<String, Object> params = linkedMap();
        params.put("sourceScene", "B");
        params.put("reportId", reportId);
        params.put("subject", subject);
        params.put("knowledgePoint", knowledgePoint);
        params.put("weakPoints", List.of(knowledgePoint));
        params.put("currentReportContext", demoDataService.getDefaultCurrentReportContext());

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "hanxue-learning-plan");
        handoff.put("reason", "user_asks_how_to_practice");
        handoff.put("params", params);

        payload.put("handoff", handoff);
        payload.put("recommendedNextAction", "进入学习规划后，会给出更具体的练习路径。 ");
        return payload;
    }

    private String buildPartialMessage(
            String subject,
            String knowledgePoint,
            boolean missingQuestion,
            boolean missingStudent,
            boolean missingCorrect) {
        if (missingQuestion && missingStudent && missingCorrect) {
            return subject + "这块目前只有结果信息，我先按已知事实说：" + knowledgePoint + "还在巩固中，具体错因暂时不能下结论。";
        }
        return "这题目前信息还不完整，我先保守判断：可能是 " + knowledgePoint + " 相关环节不够稳定，建议先补齐题目和作答再精确分析。";
    }

    private String buildConservativeAnalysis(
            String subject,
            String questionContent,
            String studentAnswer,
            String correctAnswer,
            String negativeAbilityTag,
            String knowledgePoint) {
        if (containsAny(subject, "数学") && containsAny(questionContent, "两个不相等的实数根", "判别式")) {
            return "从这道题看，孩子可能是把判别式条件或解不等式的方向弄混了，思路接近了但边界判断还不够稳。";
        }
        if (containsAny(subject, "数学") && containsAny(correctAnswer, "≤") && containsAny(studentAnswer, "<")) {
            return "这题更像是边界条件漏了等号，孩子可能知道主思路，但在‘取等是否成立’这一点上处理得不够严谨。";
        }
        if (containsAny(subject, "英语") && containsAny(questionContent, "every day")
                && containsAny(studentAnswer, "went") && containsAny(correctAnswer, "goes")) {
            return "这道题孩子可能忽略了 every day 对应一般现在时，同时主语是第三人称单数，动词需要用 goes。";
        }
        if (containsAny(subject, "语文") && containsAny(questionContent, "中心思想")
                && containsAny(studentAnswer, "A") && containsAny(correctAnswer, "B")) {
            return "这题孩子可能被表层信息吸引了，抓到了景物描写，但对文章真正想表达的中心意思把握还不够稳定。";
        }
        if (!isBlank(negativeAbilityTag)) {
            return "结合这次作答，孩子可能主要卡在“" + negativeAbilityTag + "”这一类能力点，属于能做但稳定性还不够。";
        }
        return "从题目和作答对比看，孩子可能是在 “" + knowledgePoint + "” 这个环节出现了偏差，目前更像是方法已接近但细节不稳定。";
    }

    private String resolveSubject(String reportId) {
        Map<String, Object> report = demoDataService.getReportById(reportId);
        return firstNonBlank(asString(report, "学科"), asString(report, "subject"));
    }

    private String inferKnowledgePoint(String reportId) {
        Map<String, Object> report = demoDataService.getReportById(reportId);
        if (report == null) {
            return null;
        }
        if (containsAny(asString(report, "报告类型"), "数学一对一辅学营")) {
            for (Map<String, Object> item : safeMapList(report.get("母题列表"))) {
                if (!containsAny(asString(item, "课后掌握状态"), "熟练掌握")) {
                    return asString(item, "考点名称");
                }
            }
        }
        return null;
    }
}
