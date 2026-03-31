package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.openclaw.app.hanxuedemo.HanxueServiceSupport.*;

@Service
public class HanxueFeatureGuideService {

    private static final Map<String, FeatureDef> FEATURES = buildFeatures();

    public Map<String, Object> lookup(JsonNode params) {
        Map<String, Object> payload = linkedMap();
        payload.put("action", "lookup_feature_guide");

        String userMessage = firstNonBlank(readText(params, "userMessage"), "");
        String featureKey = normalize(firstNonBlank(readText(params, "featureKey"), readText(params, "pageKey"), ""));

        if (shouldTransferToHuman(userMessage)) {
            payload.put("status", "ok");
            payload.put("found", true);
            payload.put("message", "这个问题可能需要顾问老师协助处理，您可以在微信里联系顾问老师。 ");
            payload.put("facts", Map.of("featureName", "人工支持", "supportInMiniApp", false));
            payload.put("suggestions", Map.of("shouldTransferToHuman", true));
            payload.put("recommendedNextAction", "请联系顾问老师进一步处理。 ");
            return payload;
        }

        FeatureDef matched = matchFeature(featureKey, userMessage);
        if (matched == null) {
            payload.put("status", "not_found");
            payload.put("found", false);
            payload.put("message", "这个功能目前在小程序里还没有，您可以在家教机上直接操作。 ");
            payload.put("facts", Map.of("supportInMiniApp", false));
            payload.put("recommendedNextAction", "如果您愿意，我也可以继续帮您找小程序内可替代的功能。 ");
            return payload;
        }

        if (!matched.supportInMiniApp()) {
            payload.put("status", "not_found");
            payload.put("found", false);
            payload.put("message", matched.messageWhenUnsupported());
            payload.put("facts", Map.of(
                    "featureName", matched.featureName(),
                    "supportInMiniApp", false));
            payload.put("recommendedNextAction", "这个场景暂时无法提供小程序跳转入口。 ");
            return payload;
        }

        String fixedCopy = buildFixedCopy(matched, userMessage);

        payload.put("status", "ok");
        payload.put("found", true);
        payload.put("message", fixedCopy);
        payload.put("record", Map.of(
                "featureKey", matched.featureKey(),
                "featureName", matched.featureName(),
                "jumpEntry", matched.jumpEntry()));
        payload.put("facts", Map.of(
                "featureName", matched.featureName(),
                "featurePurpose", matched.featurePurpose(),
                "pageLocation", matched.pageLocation(),
                "supportInMiniApp", true));
        payload.put("suggestions", Map.of(
                "jumpEntry", matched.jumpEntry(),
                "fixedCopy", fixedCopy,
                "shouldTransferToHuman", false));
        payload.put("recommendedNextAction", "按入口跳转后就可以直接设置。 ");
        return payload;
    }

    private FeatureDef matchFeature(String featureKey, String userMessage) {
        if (!isBlank(featureKey) && FEATURES.containsKey(featureKey)) {
            return FEATURES.get(featureKey);
        }

        if (containsAny(userMessage, "忘记密码", "密码忘了", "想不起来")) {
            return FEATURES.get("password_reset");
        }
        if (containsAny(userMessage, "初始密码", "默认密码")) {
            return FEATURES.get("password_setup");
        }
        if (containsAny(userMessage, "密码是干嘛", "为什么改设置要输密码")) {
            return FEATURES.get("password_setup");
        }
        if (containsAny(userMessage, "不让孩子看答案", "隐藏答案", "关答案")) {
            return FEATURES.get("answer_control");
        }
        if (containsAny(userMessage, "偷偷装", "装了别的app", "安装权限")) {
            return FEATURES.get("install_control");
        }
        if (containsAny(userMessage, "夜间", "异常使用", "23点", "夜里")) {
            return FEATURES.get("abnormal_usage_monitor");
        }
        if (containsAny(userMessage, "屏幕截图", "在干嘛", "在做什么")) {
            return FEATURES.get("screen_capture");
        }
        if (containsAny(userMessage, "周末", "平时", "限制时间", "多久")) {
            return FEATURES.get("time_limit");
        }
        if (containsAny(userMessage, "数学报告")) {
            return FEATURES.get("math_report");
        }
        if (containsAny(userMessage, "语文报告")) {
            return FEATURES.get("chinese_report");
        }
        if (containsAny(userMessage, "英语报告")) {
            return FEATURES.get("english_report");
        }
        if (containsAny(userMessage, "周报")) {
            return FEATURES.get("weekly_report");
        }

        if (containsAny(userMessage, "音量", "亮度", "导出错题", "系统设置")) {
            return new FeatureDef(
                    "unsupported",
                    "家教机端设置",
                    "需要在家教机上操作",
                    "家教机端",
                    null,
                    false,
                    "这个功能目前在小程序里还没有，您可以在家教机上直接操作。");
        }

        return null;
    }

    private boolean shouldTransferToHuman(String userMessage) {
        return containsAny(userMessage,
                "登不上", "登录不了", "绑定失败", "账号异常", "权限异常", "找不到功能", "换手机号");
    }

    private String buildFixedCopy(FeatureDef feature, String userMessage) {
        if ("abnormal_usage_monitor".equals(feature.featureKey())) {
            return "系统会自动监控深夜（23点到早上6点）的使用情况，这个不需要您额外设置，默认就是开启的。";
        }
        if ("password_setup".equals(feature.featureKey()) && containsAny(userMessage, "初始密码", "默认密码")) {
            return "管控密码没有初始密码，需要您自己设置一个。";
        }
        if ("password_reset".equals(feature.featureKey())) {
            return "管控密码忘了可以直接在小程序里自助重置，不用联系客服。";
        }
        if ("answer_control".equals(feature.featureKey())) {
            return "在管控页关闭答案管控后，拍照讲题、作业批改和错题本的答案都会隐藏。";
        }
        return feature.featurePurpose();
    }

    private static Map<String, FeatureDef> buildFeatures() {
        Map<String, FeatureDef> map = new LinkedHashMap<>();
        map.put("password_setup", new FeatureDef(
                "password_setup", "管控密码设置", "设置管控密码，防止孩子修改管控设置", "我的-管控密码", "去设置管控密码 >", true, null));
        map.put("password_reset", new FeatureDef(
                "password_reset", "管控密码重置", "忘记密码时可自助重置", "我的-管控密码", "去重置管控密码 >", true, null));
        map.put("answer_control", new FeatureDef(
                "answer_control", "答案管控（总开关）", "关闭后答案会隐藏", "管控页-答案管控", "去设置答案管控 >", true, null));
        map.put("install_control", new FeatureDef(
                "install_control", "安装软件管控", "控制是否允许安装第三方应用", "管控页-安装软件管控", "去设置软件安装权限 >", true, null));
        map.put("time_limit", new FeatureDef(
                "time_limit", "使用时间限制", "支持周中和周末分开设置", "管控页-使用时间", "去设置使用时间 >", true, null));
        map.put("screen_capture", new FeatureDef(
                "screen_capture", "屏幕截图", "每15分钟自动截图，便于远程查看", "管控页-屏幕截图", "去设置屏幕截图 >", true, null));
        map.put("abnormal_usage_monitor", new FeatureDef(
                "abnormal_usage_monitor", "异常使用行为监控", "23:00-6:00自动监控，默认开启", "管控页-异常使用记录", "去查看异常使用记录 >", true, null));
        map.put("math_report", new FeatureDef(
                "math_report", "数学学情报告", "查看数学学习情况", "报告页", "去查看数学学情报告 >", true, null));
        map.put("chinese_report", new FeatureDef(
                "chinese_report", "语文学情报告", "查看语文学习情况", "报告页", "去查看语文学情报告 >", true, null));
        map.put("english_report", new FeatureDef(
                "english_report", "英语学情报告", "查看英语学习情况", "报告页", "去查看英语学情报告 >", true, null));
        map.put("weekly_report", new FeatureDef(
                "weekly_report", "本周学习周报", "查看周度学习汇总", "报告页", "去查看本周学习周报 >", true, null));
        return map;
    }

    private record FeatureDef(
            String featureKey,
            String featureName,
            String featurePurpose,
            String pageLocation,
            String jumpEntry,
            boolean supportInMiniApp,
            String messageWhenUnsupported) {
    }
}
