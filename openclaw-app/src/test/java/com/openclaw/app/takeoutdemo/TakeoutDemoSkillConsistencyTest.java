package com.openclaw.app.takeoutdemo;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TakeoutDemoSkillConsistencyTest {

    @Test
    void handoffTargets_matchWorkspaceSkillDirectories() {
        Path repoRoot = locateRepoRoot();

        TakeoutDemoService service = new TakeoutDemoService();
        Map<String, Object> refundHandoff = castMap(service.buildIssueIntakePayload("LAST_3_DAYS").get("handoff"));
        Map<String, Object> deliveryHandoff = castMap(service.buildIssueIntakePayload("DINNER_DELAY").get("handoff"));

        assertTrue(skillExists(repoRoot, String.valueOf(refundHandoff.get("targetSkill"))));
        assertTrue(skillExists(repoRoot, String.valueOf(deliveryHandoff.get("targetSkill"))));
    }

    @Test
    void handoffParams_matchSkillContracts() {
        TakeoutDemoService service = new TakeoutDemoService();

        Map<String, Object> refundHandoff = castMap(service.buildIssueIntakePayload("LAST_3_DAYS").get("handoff"));
        Map<String, Object> refundParams = castMap(refundHandoff.get("params"));
        assertEquals(Set.of(
                "customerId",
                "timeRangeKey",
                "windowSummary",
                "flaggedOrders",
                "orderIds",
                "resolutionPolicyContext",
                "customerAfterSalesProfile"), refundParams.keySet());

        Map<String, Object> deliveryHandoff = castMap(service.buildIssueIntakePayload("DINNER_DELAY").get("handoff"));
        Map<String, Object> deliveryParams = castMap(deliveryHandoff.get("params"));
        assertEquals(Set.of("orderId"), deliveryParams.keySet());
    }

    private Path locateRepoRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("skills/takeout-order-issue-intake-demo/SKILL.md"))) {
                return current;
            }
            current = current.getParent();
        }
        fail("无法定位包含 takeout skill 的仓库根目录。");
        return null;
    }

    private boolean skillExists(Path repoRoot, String skillName) {
        return Files.isRegularFile(repoRoot.resolve("skills").resolve(skillName).resolve("SKILL.md"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }
}
