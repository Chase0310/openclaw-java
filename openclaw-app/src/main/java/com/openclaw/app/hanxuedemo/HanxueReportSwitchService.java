package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.openclaw.app.hanxuedemo.HanxueServiceSupport.*;

@Service
public class HanxueReportSwitchService {

    private static final DateTimeFormatter MM_DD = DateTimeFormatter.ofPattern("M-d");

    private final HanxueDemoDataService demoDataService;

    public HanxueReportSwitchService(HanxueDemoDataService demoDataService) {
        this.demoDataService = demoDataService;
    }

    public Map<String, Object> locate(JsonNode params) {
        Map<String, Object> payload = linkedMap();
        payload.put("action", "locate_report");

        String userMessage = firstNonBlank(readText(params, "userMessage"), readText(params, "query"), "");
        String explicitReportId = readText(params, "reportId");
        String filter = firstNonBlank(readText(params, "filter"), readText(params, "sceneFilter"));

        LocalDate todayDate = resolveTodayDate(readText(params, "todayDate"));

        List<Map<String, Object>> todayReports = readMapList(params, "todayReportList");
        if (todayReports.isEmpty()) {
            todayReports = demoDataService.getTodayReportList();
        }

        List<Map<String, Object>> allReports = mergeReports(todayReports, demoDataService.getAllReportCards());

        if (allReports.isEmpty()) {
            payload.put("status", "not_found");
            payload.put("found", false);
            payload.put("message", "目前还没有孩子的学习报告，让他先上一节课，我再来帮您解读。 ");
            payload.put("facts", Map.of("hasAnyReport", false));
            payload.put("recommendedNextAction", "先在家教机上完成一节学习后再来查看。 ");
            return payload;
        }

        if (!isBlank(explicitReportId)) {
            Map<String, Object> matched = findByReportId(allReports, explicitReportId);
            if (matched != null) {
                return buildSuccessHandoff(payload, matched, "exact_report_id_match");
            }
        }

        if (containsAny(userMessage, "上上周", "上个月", "去年")) {
            return buildBeyondWindow(payload);
        }

        String subject = firstNonBlank(readText(params, "subject"), detectSubject(userMessage));
        String targetDateRaw = readText(params, "targetDate");
        LocalDate targetDate = isBlank(targetDateRaw)
                ? parseDateFromMessage(userMessage, todayDate)
                : parseDateSafe(targetDateRaw, todayDate);

        List<Map<String, Object>> candidates;
        if (containsAny(filter, "today_math_sessions")) {
            candidates = filterTodayMathSessions(allReports, todayDate);
        } else {
            candidates = filterCandidates(allReports, targetDate, subject);
        }

        if (targetDate != null && !containsAny(userMessage, "今天", "昨天", "上周", "03-", "03月")
                && containsAny(userMessage, "报告", "看看")) {
            // keep inferred date candidate flow
        }

        if (candidates.size() == 1) {
            return buildSuccessHandoff(payload, candidates.get(0), "single_match");
        }
        if (candidates.size() > 1) {
            payload.put("status", "needs_input");
            payload.put("found", true);
            payload.put("message", buildMultiMatchMessage(candidates, targetDate, subject));
            payload.put("records", toCandidateRecords(candidates));
            payload.put("facts", Map.of(
                    "targetDate", targetDate == null ? null : targetDate.toString(),
                    "subject", subject,
                    "candidateCount", candidates.size()));
            payload.put("recommendedNextAction", "请在候选里选一份，我再继续。 ");
            return payload;
        }

        if (targetDate != null || !isBlank(subject)) {
            payload.put("status", "not_found");
            payload.put("found", false);
            payload.put("message", buildNoMatchMessage(targetDate, subject));
            payload.put("records", buildTodayListWithFallback(todayReports));
            payload.put("facts", Map.of(
                    "targetDate", targetDate == null ? null : targetDate.toString(),
                    "subject", subject,
                    "candidateCount", 0));
            payload.put("recommendedNextAction", "可以先从今天的报告里选一份继续看。 ");
            return payload;
        }

        payload.put("status", "needs_input");
        payload.put("found", true);
        payload.put("message", "您想看今天哪个报告呢？");
        payload.put("records", buildTodayListWithFallback(todayReports));
        payload.put("facts", Map.of("candidateCount", todayReports.size(), "vagueIntent", true));
        payload.put("recommendedNextAction", "先选一份报告，我马上帮您切过去。 ");
        return payload;
    }

    private List<Map<String, Object>> mergeReports(
            List<Map<String, Object>> primary,
            List<Map<String, Object>> secondary) {
        Map<String, Map<String, Object>> dedup = new LinkedHashMap<>();
        for (Map<String, Object> report : primary) {
            String reportId = asString(report, "reportId");
            if (!isBlank(reportId)) {
                dedup.put(reportId, new LinkedHashMap<>(report));
            }
        }
        for (Map<String, Object> report : secondary) {
            String reportId = asString(report, "reportId");
            if (!isBlank(reportId) && !dedup.containsKey(reportId)) {
                dedup.put(reportId, new LinkedHashMap<>(report));
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private List<Map<String, Object>> filterCandidates(
            List<Map<String, Object>> reports,
            LocalDate targetDate,
            String subject) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> report : reports) {
            if (targetDate != null) {
                LocalDate reportDate = parseDateSafe(asString(report, "reportDate"), demoDataService.getBaseToday());
                if (!Objects.equals(reportDate, targetDate)) {
                    continue;
                }
            }
            if (!isBlank(subject)) {
                String reportSubject = firstNonBlank(asString(report, "subject"), "");
                if (!containsAny(reportSubject, subject) && !containsAny(asString(report, "reportType"), subject)) {
                    continue;
                }
            }
            filtered.add(report);
        }
        return filtered;
    }

    private List<Map<String, Object>> filterTodayMathSessions(List<Map<String, Object>> reports, LocalDate todayDate) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> report : reports) {
            if (!asBoolean(report, "isMathSession", false)) {
                continue;
            }
            LocalDate reportDate = parseDateSafe(asString(report, "reportDate"), todayDate);
            if (Objects.equals(reportDate, todayDate)) {
                result.add(report);
            }
        }
        return result;
    }

    private Map<String, Object> buildSuccessHandoff(
            Map<String, Object> payload,
            Map<String, Object> matched,
            String reason) {
        String reportId = asString(matched, "reportId");
        String reportType = asString(matched, "reportType");

        Map<String, Object> currentReportContext = linkedMap();
        currentReportContext.put("reportId", reportId);
        currentReportContext.put("reportType", reportType);
        currentReportContext.put("reportDate", asString(matched, "reportDate"));
        currentReportContext.put("subject", asString(matched, "subject"));
        currentReportContext.put("entryScene", "D");
        currentReportContext.put("childName", "小鉴");
        currentReportContext.put("childGrade", "7年级");
        currentReportContext.put("reportData", demoDataService.getReportById(reportId));

        Map<String, Object> params = linkedMap();
        params.put("matchedReportId", reportId);
        params.put("reportType", reportType);
        params.put("currentReportContext", currentReportContext);

        Map<String, Object> handoff = linkedMap();
        handoff.put("targetSkill", "hanxue-report-interpretation");
        handoff.put("reason", reason);
        handoff.put("params", params);

        payload.put("status", "handoff");
        payload.put("found", true);
        payload.put("message", "找到了这份报告，我帮您切过去继续解读。 ");
        payload.put("record", Map.of(
                "matchedReportId", reportId,
                "reportType", reportType,
                "reportDate", asString(matched, "reportDate"),
                "subject", asString(matched, "subject")));
        payload.put("handoff", handoff);
        payload.put("recommendedNextAction", "切换成功后会直接进入该报告的学情解读。 ");
        return payload;
    }

    private Map<String, Object> buildBeyondWindow(Map<String, Object> payload) {
        payload.put("status", "not_found");
        payload.put("found", false);
        payload.put("message", "之前的我记得不太清楚了，您可以先去报告中心找，找到后点“跟我聊聊”我就能继续帮您看。 ");
        payload.put("facts", Map.of("reason", "beyond_7_days"));
        payload.put("suggestions", Map.of("jumpToReportList", true));
        payload.put("recommendedNextAction", "去报告中心定位目标报告后再回来继续。 ");
        return payload;
    }

    private String buildMultiMatchMessage(List<Map<String, Object>> candidates, LocalDate targetDate, String subject) {
        if (!isBlank(subject) && targetDate != null) {
            return "这天有多份" + subject + "报告，您想看哪一份？";
        }
        return "我找到了多份候选报告，您想先看哪一份？";
    }

    private List<Map<String, Object>> toCandidateRecords(List<Map<String, Object>> reports) {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> report : reports) {
            Map<String, Object> row = linkedMap();
            row.put("reportId", asString(report, "reportId"));
            row.put("label", buildCandidateLabel(report));
            row.put("reportType", asString(report, "reportType"));
            row.put("subject", asString(report, "subject"));
            row.put("reportDate", asString(report, "reportDate"));
            records.add(row);
        }
        records.add(Map.of("label", "没有想看的", "action", "jump_report_list"));
        return records;
    }

    private List<Map<String, Object>> buildTodayListWithFallback(List<Map<String, Object>> todayReports) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> report : todayReports) {
            rows.add(Map.of(
                    "reportId", asString(report, "reportId"),
                    "label", asString(report, "label"),
                    "reportType", asString(report, "reportType"),
                    "reportTime", asString(report, "reportTime")));
        }
        rows.add(Map.of("label", "没有想看的", "action", "jump_report_list"));
        return rows;
    }

    private String buildNoMatchMessage(LocalDate targetDate, String subject) {
        if (!isBlank(subject) && targetDate != null) {
            return targetDate + " 好像没有" + subject + "的学习记录。";
        }
        if (!isBlank(subject)) {
            return "暂时没有找到这个学科的目标报告。";
        }
        return "没有找到符合条件的报告。";
    }

    private Map<String, Object> findByReportId(List<Map<String, Object>> reports, String reportId) {
        for (Map<String, Object> report : reports) {
            if (reportId.equals(asString(report, "reportId"))) {
                return report;
            }
        }
        return null;
    }

    private String detectSubject(String userMessage) {
        for (String subject : List.of("数学", "语文", "英语", "物理", "化学", "生物", "历史", "政治", "地理")) {
            if (containsAny(userMessage, subject)) {
                return subject;
            }
        }
        return null;
    }

    private String buildCandidateLabel(Map<String, Object> report) {
        String time = asString(report, "reportTime");
        String courseName = firstNonBlank(asString(report, "courseName"), asString(report, "label"), "学习报告");
        return firstNonBlank(time, "--:--") + " " + courseName;
    }

    private LocalDate parseDateFromMessage(String message, LocalDate todayDate) {
        if (containsAny(message, "今天")) {
            return todayDate;
        }
        if (containsAny(message, "昨天")) {
            return todayDate.minusDays(1);
        }
        if (containsAny(message, "上周")) {
            DayOfWeek targetWeekday = resolveWeekday(message);
            if (targetWeekday != null) {
                LocalDate date = todayDate.minusWeeks(1);
                while (date.getDayOfWeek() != targetWeekday) {
                    date = date.minusDays(1);
                }
                return date;
            }
            return todayDate.minusWeeks(1);
        }

        String mmdd = extractMonthDay(message);
        if (!isBlank(mmdd)) {
            return parseDateSafe(mmdd, todayDate);
        }
        return null;
    }

    private String extractMonthDay(String message) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(\\d{1,2})[-月](\\d{1,2})")
                .matcher(firstNonBlank(message, ""));
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1) + "-" + matcher.group(2);
    }

    private DayOfWeek resolveWeekday(String message) {
        if (containsAny(message, "周一", "星期一")) {
            return DayOfWeek.MONDAY;
        }
        if (containsAny(message, "周二", "星期二")) {
            return DayOfWeek.TUESDAY;
        }
        if (containsAny(message, "周三", "星期三")) {
            return DayOfWeek.WEDNESDAY;
        }
        if (containsAny(message, "周四", "星期四")) {
            return DayOfWeek.THURSDAY;
        }
        if (containsAny(message, "周五", "星期五")) {
            return DayOfWeek.FRIDAY;
        }
        if (containsAny(message, "周六", "星期六")) {
            return DayOfWeek.SATURDAY;
        }
        if (containsAny(message, "周日", "周天", "星期日", "星期天")) {
            return DayOfWeek.SUNDAY;
        }
        return null;
    }

    private LocalDate resolveTodayDate(String todayRaw) {
        if (!isBlank(todayRaw)) {
            LocalDate parsed = parseDateSafe(todayRaw, demoDataService.getBaseToday());
            if (parsed != null) {
                return parsed;
            }
        }
        return demoDataService.getBaseToday();
    }

    private LocalDate parseDateSafe(String raw, LocalDate fallbackToday) {
        if (isBlank(raw)) {
            return null;
        }
        String value = raw.trim();
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.of(fallbackToday.getYear(), MM_DD.parse(value).get(java.time.temporal.ChronoField.MONTH_OF_YEAR),
                    MM_DD.parse(value).get(java.time.temporal.ChronoField.DAY_OF_MONTH));
        } catch (Exception ignored) {
            return null;
        }
    }
}
