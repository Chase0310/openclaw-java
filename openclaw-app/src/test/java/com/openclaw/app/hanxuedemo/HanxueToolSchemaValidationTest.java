package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.openclaw.agent.tools.CustomAgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HanxueToolSchemaValidationTest {

    private HanxueReportInterpretationTool reportInterpretationTool;
    private HanxueErrorAnalysisTool errorAnalysisTool;
    private HanxueLearningPlanTool learningPlanTool;
    private HanxueEmotionSupportTool emotionSupportTool;
    private HanxueFeatureGuideTool featureGuideTool;
    private HanxueReportSwitchTool reportSwitchTool;
    private HanxueFallbackTool fallbackTool;

    @BeforeEach
    void setUp() {
        HanxueDemoDataService dataService = new HanxueDemoDataService();
        reportInterpretationTool = new HanxueReportInterpretationTool(new HanxueReportInterpretationService(dataService));
        errorAnalysisTool = new HanxueErrorAnalysisTool(new HanxueErrorAnalysisService(dataService));
        learningPlanTool = new HanxueLearningPlanTool(new HanxueLearningPlanService(dataService));
        emotionSupportTool = new HanxueEmotionSupportTool(new HanxueEmotionSupportService(dataService));
        featureGuideTool = new HanxueFeatureGuideTool(new HanxueFeatureGuideService());
        reportSwitchTool = new HanxueReportSwitchTool(new HanxueReportSwitchService(dataService));
        fallbackTool = new HanxueFallbackTool(new HanxueFallbackService(dataService));
    }

    @Test
    void allHanxueToolSchemas_haveBaseContract() {
        assertBaseSchema(reportInterpretationTool, "interpret_current_report");
        assertBaseSchema(errorAnalysisTool, "analyze_error_reason");
        assertBaseSchema(learningPlanTool, "build_targeted_plan");
        assertBaseSchema(emotionSupportTool, "support_parent_emotion");
        assertBaseSchema(featureGuideTool, "lookup_feature_guide");
        assertBaseSchema(reportSwitchTool, "locate_report");
        assertBaseSchema(fallbackTool, "generate_fallback_response");
    }

    @Test
    void schemaProperties_alignWithSkillProtocols() {
        assertContainsProperties(reportInterpretationTool,
                "userMessage", "reportId", "reportType", "currentReportContext", "todaySessionSummary", "progressSignals");
        assertContainsProperties(errorAnalysisTool,
                "userMessage", "reportId", "subject", "questionContent", "studentAnswer", "correctAnswer",
                "knowledgePoint", "negativeAbilityTag");
        assertContainsProperties(learningPlanTool,
                "sourceScene", "subject", "childGrade", "knowledgePoint", "weakPoints",
                "currentReportContext", "recentLearningSummary");
        assertContainsProperties(emotionSupportTool,
                "userMessage", "recentLearningSummary", "todaySessionSummary", "currentReportContext");
        assertContainsProperties(featureGuideTool,
                "userMessage", "featureKey", "pageKey");
        assertContainsProperties(reportSwitchTool,
                "userMessage", "reportId", "subject", "targetDate", "todayDate", "todayReportList",
                "filter", "currentReportContext");
        assertContainsProperties(fallbackTool,
                "userMessage", "childName", "childGrade", "currentReportContext", "todayReportList",
                "recentLearningSummary", "todaySessionSummary", "conversationHistory");
    }

    private void assertBaseSchema(CustomAgentTool tool, String expectedActionText) {
        JsonNode schema = tool.getParameterSchema();
        assertEquals("object", schema.path("type").asText(), "schema type should be object: " + tool.getName());

        JsonNode properties = schema.path("properties");
        assertTrue(properties.isObject(), "schema.properties should be object: " + tool.getName());
        assertTrue(properties.has("action"), "action property missing: " + tool.getName());
        assertEquals("string", properties.path("action").path("type").asText(), "action.type should be string: " + tool.getName());

        String actionDesc = properties.path("action").path("description").asText("");
        assertTrue(actionDesc.contains(expectedActionText), "action description should contain expected action: " + tool.getName());

        JsonNode required = schema.path("required");
        assertTrue(required.isArray(), "schema.required should be array: " + tool.getName());
        List<String> requiredFields = new ArrayList<>();
        required.forEach(node -> requiredFields.add(node.asText()));
        assertTrue(requiredFields.contains("action"), "required should contain action: " + tool.getName());
    }

    private void assertContainsProperties(CustomAgentTool tool, String... requiredProperties) {
        JsonNode properties = tool.getParameterSchema().path("properties");
        for (String property : requiredProperties) {
            assertTrue(properties.has(property), "missing schema property '" + property + "' for tool: " + tool.getName());
        }
    }
}
