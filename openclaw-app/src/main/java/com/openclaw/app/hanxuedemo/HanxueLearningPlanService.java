package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openclaw.app.hanxuedemo.HanxueServiceSupport.*;

@Service
public class HanxueLearningPlanService {

    private final HanxueDemoDataService demoDataService;

    public HanxueLearningPlanService(HanxueDemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    public Map<String, Object> buildPlan(JsonNode params) {
        Map<String, Object> payload = linkedMap();
        String action = firstNonBlank(readText(params, "action"), "build_targeted_plan");
        payload.put("action", action);

        Map<String, Object> currentContext = readMap(params, "currentReportContext");
        if (currentContext == null || currentContext.isEmpty()) {
            currentContext = demoDataService.getDefaultCurrentReportContext();
        }

        Map<String, Object> recentSummary = readMap(params, "recentLearningSummary");
        if (recentSummary == null || recentSummary.isEmpty()) {
            recentSummary = demoDataService.getRecentLearningSummary();
        }

        String sourceScene = firstNonBlank(readText(params, "sourceScene"), asString(currentContext, "sourceScene"));
        String childGrade = firstNonBlank(readText(params, "childGrade"), asString(currentContext, "childGrade"), "7年级");
        String subject = firstNonBlank(readText(params, "subject"), asString(currentContext, "subject"), "数学");

        List<String> weakPoints = readStringList(params, "weakPoints");
        String knowledgePoint = readText(params, "knowledgePoint");
        if (!isBlank(knowledgePoint) && !weakPoints.contains(knowledgePoint)) {
            weakPoints = new ArrayList<>(weakPoints);
            weakPoints.add(0, knowledgePoint);
        }

        String planMode = resolvePlanMode(action, sourceScene, currentContext);

        return switch (planMode) {
            case "gentle" -> buildGentlePlan(payload, childGrade, subject, weakPoints, sourceScene);
            case "global" -> buildGlobalPlan(payload, childGrade, recentSummary);
            default -> buildTargetedPlan(payload, childGrade, subject, currentContext, weakPoints, recentSummary);
        };
    }

    private String resolvePlanMode(String action, String sourceScene, Map<String, Object> context) {
        if (containsAny(action, "build_gentle_plan") || containsAny(sourceScene, "C")) {
            return "gentle";
        }
        if (containsAny(action, "build_global_plan") || context == null || context.isEmpty()) {
            return "global";
        }
        if (containsAny(action, "build_targeted_plan")) {
            return "targeted";
        }
        return "targeted";
    }

    private Map<String, Object> buildGentlePlan(
            Map<String, Object> payload,
            String childGrade,
            String subject,
            List<String> weakPoints,
            String sourceScene) {
        List<String> apps = chooseGentleApps(childGrade, subject);
        List<String> planPath = List.of(
                "先从轻量练习开始，每次 5 分钟",
                "连续坚持 3-5 天后再慢慢加一点点量");

        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("message", "先别给太大压力，我们可以从轻量、低门槛的练习开始，帮孩子把学习节奏找回来。");
        payload.put("facts", Map.of(
                "planMode", "gentle",
                "sourceScene", firstNonBlank(sourceScene, "C"),
                "targetSubjects", List.of(subject),
                "knowledgePoints", List.of()));
        payload.put("suggestions", Map.of(
                "recommendedApps", apps,
                "planPath", planPath,
                "cautions", List.of("不推荐系统上课", "不推荐具体知识点", "不承诺效果")));
        payload.put("recommendedNextAction", "先按轻量方案试 3 天，再看孩子状态决定是否加量。 ");
        return payload;
    }

    private Map<String, Object> buildTargetedPlan(
            Map<String, Object> payload,
            String childGrade,
            String subject,
            Map<String, Object> currentContext,
            List<String> weakPoints,
            Map<String, Object> recentSummary) {
        Map<String, Object> reportData = castMap((Map<?, ?>) currentContext.getOrDefault("reportData", Map.of()));
        boolean allMastered = weakPoints.isEmpty() && isAllMastered(currentContext, reportData);

        if (allMastered) {
            payload.put("status", "ok");
            payload.put("found", true);
            payload.put("message", "这节内容已经掌握得比较扎实了，可以按计划继续下一节课，保持节奏就很好。");
            payload.put("facts", Map.of(
                    "planMode", "targeted",
                    "targetSubjects", List.of(subject),
                    "knowledgePoints", List.of(),
                    "allMastered", true));
            payload.put("suggestions", Map.of(
                    "recommendedApps", List.of(nextCourseApp(childGrade, subject)),
                    "planPath", List.of("按计划继续下一节", "课后简单回顾 5 分钟"),
                    "cautions", List.of("不需要额外加压")));
            payload.put("recommendedNextAction", "继续按计划学下一节课即可。 ");
            return payload;
        }

        List<String> resolvedWeak = weakPoints.isEmpty() ? inferWeakPoints(currentContext, reportData) : weakPoints;
        int pendingCorrections = readPendingCorrections(recentSummary, subject);

        List<String> apps = new ArrayList<>();
        List<String> path = new ArrayList<>();

        if (pendingCorrections > 0) {
            apps.add("错题本");
            path.add("先把待订正错题处理完");
        }

        if (isLowGrade(childGrade) && containsAny(subject, "数学")) {
            apps.add("计算训练");
            path.add("再做 5 分钟计算训练巩固手感");
        } else if (isHighSchool(childGrade) && containsAny(subject, "物理")) {
            apps.add("物理一对一");
            apps.add("错题本");
            path.add("先在一对一里把知识点重过一遍");
            path.add("再用错题本复盘同类题");
        } else {
            String oneToOneApp = oneToOneApp(childGrade, subject);
            if (!isBlank(oneToOneApp)) {
                apps.add(oneToOneApp);
                path.add("先把关键知识点讲透");
            }
            if (canUseJingzhunxue(childGrade, subject, currentContext)) {
                apps.add("数学精准学");
                path.add("再做同专题练习巩固");
            }
            apps.add("错题本");
            path.add("最后订正并复盘错题");
        }

        List<String> dedupApps = dedupe(apps);
        List<String> dedupPath = dedupe(path);

        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("message", "我给您整理了一条更稳妥的定向路径：先解决当前薄弱点，再做巩固，最后复盘错题。 ");
        payload.put("facts", Map.of(
                "planMode", "targeted",
                "targetSubjects", List.of(subject),
                "knowledgePoints", resolvedWeak,
                "sourceScene", "A"));
        payload.put("suggestions", Map.of(
                "recommendedApps", dedupApps,
                "planPath", dedupPath,
                "cautions", List.of("先按顺序走，不要一次铺太多", "不承诺短期提分")));
        payload.put("recommendedNextAction", "先按这条路径执行 2-3 天，再看掌握变化继续微调。 ");
        return payload;
    }

    private Map<String, Object> buildGlobalPlan(
            Map<String, Object> payload,
            String childGrade,
            Map<String, Object> recentSummary) {
        Map<String, Object> subjects = castMap((Map<?, ?>) recentSummary.getOrDefault("学科概况", Map.of()));
        if (subjects == null || subjects.isEmpty()) {
            payload.put("status", "needs_input");
            payload.put("found", false);
            payload.put("message", "目前缺少近 7 天学情摘要，我还不能给出稳定的全局规划。 ");
            payload.put("recommendedNextAction", "请补充最近的学科学情摘要后，我再给您按优先级排好。 ");
            return payload;
        }

        List<String> orderedSubjects = new ArrayList<>();
        for (String subject : List.of("数学", "语文", "英语")) {
            Map<String, Object> detail = castMap((Map<?, ?>) subjects.getOrDefault(subject, Map.of()));
            int pending = asInt(detail, "未订正错题", 0);
            List<String> weak = safeStringList(detail.get("薄弱知识点"));
            if (pending > 0 || !weak.isEmpty()) {
                orderedSubjects.add(subject);
            }
        }
        if (orderedSubjects.isEmpty()) {
            orderedSubjects.add("数学");
        }

        List<String> selected = orderedSubjects.size() > 2 ? orderedSubjects.subList(0, 2) : orderedSubjects;
        List<String> apps = new ArrayList<>();
        List<String> path = new ArrayList<>();

        for (String subject : selected) {
            if (containsAny(subject, "数学")) {
                apps.add(isLowGrade(childGrade) ? "计算训练" : "数学精准学");
                apps.add("错题本");
                path.add("数学先订正错题，再做同专题巩固");
            } else if (containsAny(subject, "语文")) {
                apps.add("语文一对一");
                path.add("语文聚焦阅读薄弱点，先补短板");
            } else if (containsAny(subject, "英语")) {
                apps.add("英语一对一");
                path.add("英语维持节奏，短时复习高频点");
            }
        }

        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("message", "我先给您排了一个全局优先级：先处理错题多、薄弱点明显的学科，再兼顾第二优先学科。 ");
        payload.put("facts", Map.of(
                "planMode", "global",
                "targetSubjects", selected,
                "knowledgePoints", List.of()));
        payload.put("suggestions", Map.of(
                "recommendedApps", dedupe(apps),
                "planPath", dedupe(path),
                "cautions", List.of("一次聚焦 2 个学科即可", "避免同时铺太多任务")));
        payload.put("recommendedNextAction", "先执行首个学科计划，2 天后再看是否要加第二个学科。 ");
        return payload;
    }

    private List<String> chooseGentleApps(String childGrade, String subject) {
        if (isLowGrade(childGrade)) {
            return List.of("计算训练", "品味古诗");
        }
        if (containsAny(subject, "英语")) {
            return List.of("学练背单词", "单词听写");
        }
        if (containsAny(subject, "语文")) {
            return List.of("品味古诗", "字词听写");
        }
        if (isHighSchool(childGrade)) {
            return List.of("错题本", "5分钟小练");
        }
        return List.of("计算训练", "学练背单词");
    }

    private int readPendingCorrections(Map<String, Object> recentSummary, String subject) {
        Map<String, Object> subjects = castMap((Map<?, ?>) recentSummary.getOrDefault("学科概况", Map.of()));
        if (subjects == null || subjects.isEmpty()) {
            return 0;
        }
        Map<String, Object> detail = castMap((Map<?, ?>) subjects.getOrDefault(subject, Map.of()));
        return asInt(detail, "未订正错题", 0);
    }

    private boolean canUseJingzhunxue(String childGrade, String subject, Map<String, Object> currentContext) {
        if (isLowGrade(childGrade)) {
            return false;
        }
        if (isHighSchool(childGrade) && containsAny(subject, "物理", "化学", "生物", "数学")) {
            return false;
        }
        return containsAny(subject, "数学");
    }

    private String oneToOneApp(String childGrade, String subject) {
        if (isLowGrade(childGrade) && containsAny(subject, "数学")) {
            return null;
        }
        if (containsAny(subject, "数学")) {
            return isHighSchool(childGrade) ? "数学一对一" : "数学一对一(辅学营)";
        }
        if (containsAny(subject, "语文")) {
            return "语文一对一";
        }
        if (containsAny(subject, "英语")) {
            return "英语一对一";
        }
        return "一对一课程";
    }

    private String nextCourseApp(String childGrade, String subject) {
        String app = oneToOneApp(childGrade, subject);
        return isBlank(app) ? "继续当前学习节奏" : app;
    }

    private boolean isAllMastered(Map<String, Object> currentContext, Map<String, Object> reportData) {
        String reportType = firstNonBlank(asString(currentContext, "reportType"), asString(reportData, "报告类型"));
        if (containsAny(reportType, "数学一对一辅学营")) {
            for (Map<String, Object> item : safeMapList(reportData.get("母题列表"))) {
                if (!containsAny(asString(item, "课后掌握状态"), "熟练掌握")) {
                    return false;
                }
            }
            return true;
        }
        if (containsAny(reportType, "语文一对一", "英语一对一")) {
            for (Map<String, Object> pt : safeMapList(reportData.get("PT列表"))) {
                if (!containsAny(asString(pt, "通关状态"), "已通关")) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private List<String> inferWeakPoints(Map<String, Object> currentContext, Map<String, Object> reportData) {
        String reportType = firstNonBlank(asString(currentContext, "reportType"), asString(reportData, "报告类型"));
        List<String> weak = new ArrayList<>();
        if (containsAny(reportType, "数学一对一辅学营")) {
            for (Map<String, Object> item : safeMapList(reportData.get("母题列表"))) {
                if (!containsAny(asString(item, "课后掌握状态"), "熟练掌握")) {
                    weak.add(asString(item, "考点名称"));
                }
            }
        }
        return filterNonBlank(weak);
    }

    private boolean isLowGrade(String grade) {
        return containsAny(grade, "1年级", "2年级");
    }

    private boolean isHighSchool(String grade) {
        return containsAny(grade, "高一", "高二", "高三", "高中");
    }

    private List<String> dedupe(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (isBlank(value) || result.contains(value)) {
                continue;
            }
            result.add(value);
        }
        return result;
    }
}
