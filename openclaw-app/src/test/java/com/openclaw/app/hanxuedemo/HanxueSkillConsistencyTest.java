package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openclaw.agent.skills.SkillFrontmatterParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HanxueSkillConsistencyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, SkillContract> CONTRACTS = Map.of(
            "skills/家长端小程序寒雪-学情解读skill/SKILL.md",
            new SkillContract("hanxue-report-interpretation", "hanxue_report_interpretation_service", List.of("interpret_current_report")),
            "skills/家长端小程序寒雪-错因分析skill/SKILL.md",
            new SkillContract("hanxue-error-analysis", "hanxue_error_analysis_service", List.of("analyze_error_reason")),
            "skills/家长端小程序寒雪-学习规划skill/SKILL.md",
            new SkillContract("hanxue-learning-plan", "hanxue_learning_plan_service", List.of("build_targeted_plan", "build_global_plan", "build_gentle_plan")),
            "skills/家长端小程序寒雪-情绪安抚skill/SKILL.md",
            new SkillContract("hanxue-emotion-support", "hanxue_emotion_support_service", List.of("support_parent_emotion")),
            "skills/家长端小程序寒雪-功能引导skill/SKILL.md",
            new SkillContract("hanxue-feature-guide", "hanxue_feature_guide_service", List.of("lookup_feature_guide")),
            "skills/家长端小程序寒雪-切换报告skill/SKILL.md",
            new SkillContract("hanxue-report-switch", "hanxue_report_switch_service", List.of("locate_report")),
            "skills/家长端小程序寒雪-兜底skill/SKILL.md",
            new SkillContract("hanxue-fallback", "hanxue_fallback_service", List.of("generate_fallback_response")));

    @Test
    void skillDocuments_matchToolAndActionContracts() throws Exception {
        Path repoRoot = locateRepoRoot();

        for (Map.Entry<String, SkillContract> entry : CONTRACTS.entrySet()) {
            Path skillPath = repoRoot.resolve(entry.getKey());
            assertTrue(Files.isRegularFile(skillPath), "missing skill file: " + skillPath);

            String content = Files.readString(skillPath);
            Map<String, String> frontmatter = SkillFrontmatterParser.parseFrontmatter(content);
            SkillContract contract = entry.getValue();

            assertEquals(contract.skillName(), frontmatter.get("name"), "unexpected skill name for " + skillPath);
            assertTrue(content.contains(contract.toolName()), "tool name missing in " + skillPath);
            for (String action : contract.actions()) {
                assertTrue(content.contains(action), "action missing in " + skillPath + " -> " + action);
            }
        }
    }

    @Test
    void handoffTargets_alignWithSkillNames() {
        HanxueDemoDataService data = new HanxueDemoDataService();
        HanxueReportInterpretationService interpretation = new HanxueReportInterpretationService(data);
        HanxueErrorAnalysisService error = new HanxueErrorAnalysisService(data);
        HanxueEmotionSupportService emotion = new HanxueEmotionSupportService(data);
        HanxueReportSwitchService reportSwitch = new HanxueReportSwitchService(data);
        HanxueFallbackService fallback = new HanxueFallbackService(data);

        Set<String> definedSkillNames = new HashSet<>();
        CONTRACTS.values().forEach(contract -> definedSkillNames.add(contract.skillName()));

        List<Map<String, Object>> payloads = new ArrayList<>();
        payloads.add(interpretation.interpret(params("userMessage", "为什么错了")));
        payloads.add(interpretation.interpret(params("userMessage", "接下来怎么学")));
        payloads.add(interpretation.interpret(params("userMessage", "看看昨天的数学报告")));
        payloads.add(error.analyze(params("userMessage", "这题怎么练", "action", "analyze_error_reason")));
        payloads.add(emotion.support(params("userMessage", "可以试试什么帮他找回兴趣")));
        payloads.add(emotion.support(params("userMessage", "最近他学得还好吗")));
        payloads.add(reportSwitch.locate(params("userMessage", "看看昨天的数学报告", "todayDate", "2026-03-24")));
        payloads.add(fallback.generate(params("userMessage", "想看报告")));
        payloads.add(fallback.generate(params("userMessage", "怎么设置答案管控")));
        payloads.add(fallback.generate(params("userMessage", "接下来怎么学")));

        for (Map<String, Object> payload : payloads) {
            Map<String, Object> handoff = asMap(payload.get("handoff"));
            if (handoff == null || handoff.isEmpty()) {
                continue;
            }
            String target = String.valueOf(handoff.get("targetSkill"));
            assertTrue(definedSkillNames.contains(target), "undefined handoff target: " + target);
        }
    }

    @Test
    void handoffParams_coverCoreProtocolFields() {
        HanxueDemoDataService data = new HanxueDemoDataService();
        HanxueReportInterpretationService interpretation = new HanxueReportInterpretationService(data);
        HanxueEmotionSupportService emotion = new HanxueEmotionSupportService(data);
        HanxueReportSwitchService reportSwitch = new HanxueReportSwitchService(data);
        HanxueFallbackService fallback = new HanxueFallbackService(data);

        Map<String, Object> interpretationPayload = interpretation.interpret(params("userMessage", "为什么错了"));
        Map<String, Object> interpretationHandoffParams = handoffParams(interpretationPayload);
        assertTrue(interpretationHandoffParams.keySet().containsAll(Set.of(
                "reportId", "questionContent", "studentAnswer", "correctAnswer", "knowledgePoint")));

        Map<String, Object> switchPayload = reportSwitch.locate(params("userMessage", "看看昨天的数学报告", "todayDate", "2026-03-24"));
        Map<String, Object> switchHandoffParams = handoffParams(switchPayload);
        assertTrue(switchHandoffParams.keySet().containsAll(Set.of("matchedReportId", "reportType", "currentReportContext")));

        Map<String, Object> emotionPayload = emotion.support(params("userMessage", "可以试试什么帮他找回兴趣"));
        Map<String, Object> emotionHandoffParams = handoffParams(emotionPayload);
        assertEquals("C", String.valueOf(emotionHandoffParams.get("sourceScene")));

        Map<String, Object> fallbackSwitchPayload = fallback.generate(params("userMessage", "想看报告"));
        Map<String, Object> fallbackSwitchParams = handoffParams(fallbackSwitchPayload);
        assertTrue(fallbackSwitchParams.keySet().containsAll(Set.of("todayReportList", "currentReportContext", "userMessage")));

        Map<String, Object> fallbackFeaturePayload = fallback.generate(params("userMessage", "怎么设置答案管控"));
        Map<String, Object> fallbackFeatureParams = handoffParams(fallbackFeaturePayload);
        assertTrue(fallbackFeatureParams.containsKey("userMessage"));
    }

    @Test
    void mockDataSnapshot_isLoadedIntoRuntime() {
        HanxueDemoDataService data = new HanxueDemoDataService();
        assertNotNull(data.getReportById("rpt_math_1v1_20260319_001"));
        assertNotNull(data.getReportById("rpt_001"));
        assertFalse(data.getTodayReportList().isEmpty());
        assertNotNull(data.getDailyMathReport());
    }

    private Path locateRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("skills/家长端小程序寒雪-学情解读skill/SKILL.md"))) {
                return current;
            }
            current = current.getParent();
        }
        fail("无法定位仓库根目录");
        return null;
    }

    private ObjectNode params(String key1, String value1) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put(key1, value1);
        return node;
    }

    private ObjectNode params(String key1, String value1, String key2, String value2) {
        ObjectNode node = params(key1, value1);
        node.put(key2, value2);
        return node;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        return (Map<String, Object>) map;
    }

    private Map<String, Object> handoffParams(Map<String, Object> payload) {
        Map<String, Object> handoff = asMap(payload.get("handoff"));
        assertNotNull(handoff, "handoff missing");
        Map<String, Object> params = asMap(handoff.get("params"));
        assertNotNull(params, "handoff.params missing");
        return params;
    }

    private record SkillContract(String skillName, String toolName, List<String> actions) {
    }
}
