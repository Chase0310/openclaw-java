package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openclaw.app.hanxuedemo.HanxueServiceSupport.*;

/**
 * 寒雪场景演示数据仓。
 *
 * <p>
 * 事实数据来自 `openclaw-app/src/main/resources/hanxue/mock-reports`，
 * 其中基础报告 JSON 由用户提供的 mock 数据目录落仓。
 */
@Service
public class HanxueDemoDataService {

    private static final List<String> RESOURCE_FILES = List.of(
            "hanxue/mock-reports/report-01-math-1v1.json",
            "hanxue/mock-reports/report-02-chinese-1v1.json",
            "hanxue/mock-reports/report-03-english-1v1.json",
            "hanxue/mock-reports/report-04-senior-1v1.json",
            "hanxue/mock-reports/report-05-homework-review.json",
            "hanxue/mock-reports/report-06-essay-review.json",
            "hanxue/mock-reports/report-07-calculation-training.json",
            "hanxue/mock-reports/report-08-picture-writing.json",
            "hanxue/mock-reports/report-09-essay-coaching.json",
            "hanxue/mock-reports/report-10-weekly.json");

    private static final LocalDate BASE_TODAY = LocalDate.of(2026, 3, 24);

    private final Map<String, Map<String, Object>> reportsById;
    private final List<Map<String, Object>> todayReportList;
    private final List<Map<String, Object>> allReportCards;
    private final Map<String, Object> recentLearningSummary;
    private final Map<String, Object> defaultCurrentReportContext;
    private final Map<String, Object> dailyMathReport;

    public HanxueDemoDataService() {
        Map<String, Map<String, Object>> loaded = loadReports();
        this.reportsById = Collections.unmodifiableMap(loaded);
        this.allReportCards = Collections.unmodifiableList(buildReportCards());
        this.todayReportList = Collections.unmodifiableList(buildTodayReportList(allReportCards));
        this.recentLearningSummary = Collections.unmodifiableMap(buildRecentLearningSummary());
        this.defaultCurrentReportContext = Collections.unmodifiableMap(buildDefaultCurrentReportContext());
        this.dailyMathReport = Collections.unmodifiableMap(buildDailyMathReport());
    }

    public Map<String, Object> getDefaultCurrentReportContext() {
        return new LinkedHashMap<>(defaultCurrentReportContext);
    }

    public Map<String, Object> getRecentLearningSummary() {
        return new LinkedHashMap<>(recentLearningSummary);
    }

    public Map<String, Object> getDailyMathReport() {
        return new LinkedHashMap<>(dailyMathReport);
    }

    public List<Map<String, Object>> getTodayReportList() {
        return copyMapList(todayReportList);
    }

    public List<Map<String, Object>> getAllReportCards() {
        return copyMapList(allReportCards);
    }

    public Map<String, Object> getReportById(String reportId) {
        if (isBlank(reportId)) {
            return null;
        }
        Map<String, Object> report = reportsById.get(reportId.trim());
        return report == null ? null : new LinkedHashMap<>(report);
    }

    public Map<String, Object> findCardByReportId(String reportId) {
        if (isBlank(reportId)) {
            return null;
        }
        for (Map<String, Object> card : allReportCards) {
            if (reportId.equals(asString(card, "reportId"))) {
                return new LinkedHashMap<>(card);
            }
        }
        return null;
    }

    public LocalDate getBaseToday() {
        return BASE_TODAY;
    }

    public String resolveReportTypeById(String reportId) {
        Map<String, Object> report = getReportById(reportId);
        if (report == null) {
            return null;
        }
        return firstNonBlank(asString(report, "报告类型"), asString(report, "reportType"));
    }

    private Map<String, Map<String, Object>> loadReports() {
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
        for (String resource : RESOURCE_FILES) {
            Map<String, Object> report = readResource(resource);
            if (report == null || report.isEmpty()) {
                continue;
            }
            String reportId = asString(report, "reportId");
            if (!isBlank(reportId)) {
                loaded.put(reportId, report);
            }
        }

        Map<String, Object> baseMath = loaded.get("rpt_math_1v1_20260319_001");
        if (baseMath != null) {
            loaded.put("rpt_001", cloneMathReport(baseMath, "rpt_001", "2026-03-23", "一元二次方程"));
            loaded.put("rpt_002", cloneMathReport(baseMath, "rpt_002", "2026-03-24", "一元二次方程"));
            loaded.put("rpt_003", cloneMathReport(baseMath, "rpt_003", "2026-03-24", "数列"));
        }

        Map<String, Object> chinese = loaded.get("rpt_chinese_1v1_20260319_001");
        if (chinese != null) {
            loaded.put("rpt_010", cloneChineseReport(chinese, "rpt_010", "2026-03-21"));
        }

        return loaded;
    }

    private Map<String, Object> cloneMathReport(
            Map<String, Object> source,
            String newId,
            String reportDate,
            String courseName) {
        Map<String, Object> cloned = deepCopy(source);
        cloned.put("reportId", newId);
        cloned.put("教学日期", reportDate);
        cloned.put("课次名称", courseName);
        return cloned;
    }

    private Map<String, Object> cloneChineseReport(
            Map<String, Object> source,
            String newId,
            String reportDate) {
        Map<String, Object> cloned = deepCopy(source);
        cloned.put("reportId", newId);
        cloned.put("教学日期", reportDate);
        return cloned;
    }

    private List<Map<String, Object>> buildReportCards() {
        List<Map<String, Object>> cards = new ArrayList<>();
        cards.add(reportCard("rpt_001", "数学一对一辅学营", "数学", "今天的数学一对一", "2026-03-23", "10:00", true));
        cards.add(reportCard("rpt_002", "数学一对一辅学营", "数学", "今天的数学一对一", "2026-03-24", "10:00", true));
        cards.add(reportCard("rpt_003", "数学一对一辅学营", "数学", "今天的数学一对一", "2026-03-24", "16:30", true));
        cards.add(reportCard("rpt_010", "语文一对一", "语文", "今天的语文一对一", "2026-03-21", "09:00", false));
        cards.add(reportCard("rpt_homework_20260319_001", "作业批改", "综合", "今天的作业批改", "2026-03-24", "15:00", false));
        cards.add(reportCard("rpt_essay_review_20260319_001", "作文批改", "语文", "今天的作文批改", "2026-03-24", "20:10", false));
        cards.add(reportCard("rpt_calc_20260319_001", "计算训练", "数学", "今天的计算训练", "2026-03-24", "18:20", false));
        cards.add(reportCard("rpt_weekly_20260316_001", "周报", "综合", "本周学习周报", "2026-03-24", "22:00", false));
        return cards;
    }

    private List<Map<String, Object>> buildTodayReportList(List<Map<String, Object>> cards) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> card : cards) {
            String date = asString(card, "reportDate");
            if (BASE_TODAY.toString().equals(date)) {
                list.add(new LinkedHashMap<>(card));
            }
        }
        list.sort((a, b) -> asString(b, "reportTime").compareTo(asString(a, "reportTime")));
        return list;
    }

    private Map<String, Object> buildRecentLearningSummary() {
        Map<String, Object> weekly = reportsById.get("rpt_weekly_20260316_001");
        Map<String, Object> summary = linkedMap();
        summary.put("学习天数", weekly != null ? asInt(weekly, "学习天数", 5) : 5);
        summary.put("总时长_分钟", weekly != null ? asInt(weekly, "总时长_分钟", 210) : 210);
        summary.put("已坚持天数", weekly != null ? asInt(weekly, "已坚持学习天数", 33) : 33);

        Map<String, Object> subjects = linkedMap();
        subjects.put("数学", Map.of(
                "薄弱知识点", List.of("判别式应用", "韦达定理综合应用"),
                "未订正错题", 3));
        subjects.put("语文", Map.of(
                "薄弱知识点", List.of("文章表达效果判断"),
                "未订正错题", 1));
        subjects.put("英语", Map.of(
                "薄弱知识点", List.of(),
                "未订正错题", 0));
        summary.put("学科概况", subjects);
        return summary;
    }

    private Map<String, Object> buildDefaultCurrentReportContext() {
        Map<String, Object> context = linkedMap();
        context.put("reportId", "rpt_002");
        context.put("reportType", "数学一对一辅学营");
        context.put("subject", "数学");
        context.put("reportDate", "2026-03-24");
        context.put("entryScene", "A");
        context.put("courseName", "一元二次方程");
        context.put("childName", "小鉴");
        context.put("childGrade", "7年级");
        context.put("reportData", reportsById.get("rpt_002"));
        return context;
    }

    private Map<String, Object> buildDailyMathReport() {
        Map<String, Object> report = linkedMap();
        report.put("reportId", "rpt_daily_math_20260324");
        report.put("报告类型", "数学学情日报");
        report.put("学生姓名", "小鉴");
        report.put("学生年级", "7年级");
        report.put("日期", BASE_TODAY.toString());
        report.put("今日做题量", 28);
        report.put("学习时长_分钟", 95);

        List<Map<String, Object>> newlyMastered = List.of(
                Map.of("PT名", "判别式应用", "状态", "已掌握", "今日做题数", 6, "今日正确率", 0.83),
                Map.of("PT名", "配方法", "状态", "已掌握", "今日做题数", 5, "今日正确率", 0.80));
        List<Map<String, Object>> ongoing = List.of(
                Map.of("PT名", "韦达定理", "状态", "突破中", "今日做题数", 4, "今日正确率", 0.5),
                Map.of("PT名", "十字相乘法", "状态", "突破中", "今日做题数", 3, "今日正确率", 0.33),
                Map.of("PT名", "等差数列", "状态", "突破中", "今日做题数", 4, "今日正确率", 0.5));

        report.put("新掌握PT", newlyMastered);
        report.put("突破中PT", ongoing);
        report.put("重点关注知识点", List.of(
                Map.of("PT名", "韦达定理", "类型", "未掌握", "策略", "建议优先学习"),
                Map.of("PT名", "十字相乘法", "类型", "未掌握", "策略", "建议优先学习")));
        report.put("错题概况", Map.of("错题数", 4, "方向", List.of("韦达定理", "十字相乘法")));
        report.put("今日数学课次报告列表", List.of(
                Map.of("reportId", "rpt_003", "label", "16:30 数列（一对一辅学营）"),
                Map.of("reportId", "rpt_002", "label", "10:00 一元二次方程（一对一辅学营）")));
        return report;
    }

    private Map<String, Object> reportCard(
            String reportId,
            String reportType,
            String subject,
            String label,
            String reportDate,
            String reportTime,
            boolean mathSession) {
        Map<String, Object> card = linkedMap();
        card.put("reportId", reportId);
        card.put("reportType", reportType);
        card.put("subject", subject);
        card.put("label", label);
        card.put("reportDate", reportDate);
        card.put("reportTime", reportTime);
        card.put("isMathSession", mathSession);

        Map<String, Object> reportData = reportsById.get(reportId);
        if (reportData != null) {
            card.put("courseName", firstNonBlank(asString(reportData, "课次名称"), asString(reportData, "课程名称"),
                    asString(reportData, "作文标题"), asString(reportData, "练习标题"), "学习报告"));
        }
        return card;
    }

    private Map<String, Object> readResource(String resourcePath) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return null;
            }
            return MAPPER.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> deepCopy(Map<String, Object> source) {
        return MAPPER.convertValue(source, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }
}
