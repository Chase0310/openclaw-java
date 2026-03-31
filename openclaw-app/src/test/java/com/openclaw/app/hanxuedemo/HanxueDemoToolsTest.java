package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openclaw.agent.tools.AgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HanxueDemoToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentTool reportInterpretationTool;
    private AgentTool errorAnalysisTool;
    private AgentTool learningPlanTool;
    private AgentTool emotionSupportTool;
    private AgentTool featureGuideTool;
    private AgentTool reportSwitchTool;
    private AgentTool fallbackTool;

    @BeforeEach
    void setUp() {
        HanxueDemoDataService dataService = new HanxueDemoDataService();

        HanxueReportInterpretationService reportInterpretationService = new HanxueReportInterpretationService(dataService);
        HanxueErrorAnalysisService errorAnalysisService = new HanxueErrorAnalysisService(dataService);
        HanxueLearningPlanService learningPlanService = new HanxueLearningPlanService(dataService);
        HanxueEmotionSupportService emotionSupportService = new HanxueEmotionSupportService(dataService);
        HanxueFeatureGuideService featureGuideService = new HanxueFeatureGuideService();
        HanxueReportSwitchService reportSwitchService = new HanxueReportSwitchService(dataService);
        HanxueFallbackService fallbackService = new HanxueFallbackService(dataService);

        reportInterpretationTool = new HanxueReportInterpretationTool(reportInterpretationService);
        errorAnalysisTool = new HanxueErrorAnalysisTool(errorAnalysisService);
        learningPlanTool = new HanxueLearningPlanTool(learningPlanService);
        emotionSupportTool = new HanxueEmotionSupportTool(emotionSupportService);
        featureGuideTool = new HanxueFeatureGuideTool(featureGuideService);
        reportSwitchTool = new HanxueReportSwitchTool(reportSwitchService);
        fallbackTool = new HanxueFallbackTool(fallbackService);
    }

    @Test
    void reportInterpretation_whyWrong_handoffsToErrorAnalysis() throws Exception {
        JsonNode payload = executeAndRead(reportInterpretationTool, objectParams(
                "action", "interpret_current_report",
                "userMessage", "这道题为什么错了"));

        assertEquals("handoff", payload.path("status").asText());
        assertEquals("hanxue-error-analysis", payload.at("/handoff/targetSkill").asText());
        assertFalse(payload.at("/handoff/params/questionContent").asText().isBlank());
        assertFalse(payload.at("/handoff/params/correctAnswer").asText().isBlank());
    }

    @Test
    void reportInterpretation_normalQuestion_returnsFactsAndNextOptions() throws Exception {
        JsonNode payload = executeAndRead(reportInterpretationTool, objectParams(
                "action", "interpret_current_report",
                "userMessage", "哪些知识点还需要练"));

        assertEquals("ok", payload.path("status").asText());
        assertTrue(payload.path("found").asBoolean());
        assertTrue(payload.at("/facts/weakPoints").isArray());
        assertTrue(payload.at("/suggestions/nextOptions").toString().contains("接下来怎么学比较好"));
    }

    @Test
    void errorAnalysis_missingQuestion_returnsPartial() throws Exception {
        JsonNode payload = executeAndRead(errorAnalysisTool, objectParams(
                "action", "analyze_error_reason",
                "subject", "数学",
                "knowledgePoint", "判别式应用"));

        assertEquals("partial", payload.path("status").asText());
        assertTrue(payload.at("/facts/missingQuestionContent").asBoolean());
    }

    @Test
    void learningPlan_gentleMode_returnsLightweightApps() throws Exception {
        ObjectNode params = objectParams(
                "action", "build_gentle_plan",
                "sourceScene", "C");
        params.put("childGrade", "2年级");
        params.put("subject", "语文");
        JsonNode payload = executeAndRead(learningPlanTool, params);

        assertEquals("ok", payload.path("status").asText());
        assertEquals("gentle", payload.at("/facts/planMode").asText());
        assertFalse(payload.at("/suggestions/recommendedApps").toString().contains("一对一"));
    }

    @Test
    void emotionSupport_refundRequest_requiresHumanTransfer() throws Exception {
        JsonNode payload = executeAndRead(emotionSupportTool, objectParams(
                "action", "support_parent_emotion",
                "userMessage", "这个东西没用，我要退货"));

        assertEquals("ok", payload.path("status").asText());
        assertTrue(payload.at("/suggestions/shouldTransferToHuman").asBoolean());
    }

    @Test
    void featureGuide_answerControl_returnsFixedJumpEntry() throws Exception {
        JsonNode payload = executeAndRead(featureGuideTool, objectParams(
                "action", "lookup_feature_guide",
                "userMessage", "怎么不让孩子看答案"));

        assertEquals("ok", payload.path("status").asText());
        assertEquals("去设置答案管控 >", payload.at("/suggestions/jumpEntry").asText());
    }

    @Test
    void reportSwitch_yesterdayMath_returnsInterpretationHandoff() throws Exception {
        ObjectNode params = objectParams(
                "action", "locate_report",
                "userMessage", "看看昨天的数学报告");
        params.put("todayDate", "2026-03-24");

        JsonNode payload = executeAndRead(reportSwitchTool, params);

        assertEquals("handoff", payload.path("status").asText());
        assertEquals("hanxue-report-interpretation", payload.at("/handoff/targetSkill").asText());
        assertEquals("rpt_001", payload.at("/record/matchedReportId").asText());
    }

    @Test
    void reportSwitch_todayMathMultiple_returnsNeedsInputCandidates() throws Exception {
        ObjectNode params = objectParams(
                "action", "locate_report",
                "userMessage", "看看今天的数学");
        params.put("todayDate", "2026-03-24");

        JsonNode payload = executeAndRead(reportSwitchTool, params);

        assertEquals("needs_input", payload.path("status").asText());
        assertTrue(payload.path("found").asBoolean());
        assertTrue(payload.path("records").size() >= 3);
    }

    @Test
    void fallback_vagueInput_returnsClarificationOptions() throws Exception {
        JsonNode payload = executeAndRead(fallbackTool, objectParams(
                "action", "generate_fallback_response",
                "userMessage", "看看"));

        assertEquals("needs_input", payload.path("status").asText());
        assertTrue(payload.path("records").size() >= 2);
    }

    @Test
    void fallback_featureQuestion_handoffsToFeatureGuide() throws Exception {
        JsonNode payload = executeAndRead(fallbackTool, objectParams(
                "action", "generate_fallback_response",
                "userMessage", "管控密码忘了怎么办"));

        assertEquals("handoff", payload.path("status").asText());
        assertEquals("hanxue-feature-guide", payload.at("/handoff/targetSkill").asText());
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

    private ObjectNode objectParams(String key1, String value1, String key2, String value2, String key3, String value3) {
        ObjectNode params = objectParams(key1, value1, key2, value2);
        params.put(key3, value3);
        return params;
    }
}
