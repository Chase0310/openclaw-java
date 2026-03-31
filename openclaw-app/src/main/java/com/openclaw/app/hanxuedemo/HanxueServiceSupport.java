package com.openclaw.app.hanxuedemo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class HanxueServiceSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private HanxueServiceSupport() {
    }

    static Map<String, Object> linkedMap() {
        return new LinkedHashMap<>();
    }

    static String readText(JsonNode params, String key) {
        if (params == null || params.isMissingNode() || params.isNull() || !params.has(key)) {
            return null;
        }
        JsonNode node = params.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            return text.isEmpty() ? null : text;
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return null;
    }

    static Map<String, Object> readMap(JsonNode params, String key) {
        if (params == null || !params.has(key)) {
            return null;
        }
        return toMap(params.get(key));
    }

    static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isObject()) {
            return null;
        }
        return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    static List<Map<String, Object>> readMapList(JsonNode params, String key) {
        if (params == null || !params.has(key)) {
            return List.of();
        }
        JsonNode array = params.get(key);
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode item : array) {
            Map<String, Object> map = toMap(item);
            if (map != null && !map.isEmpty()) {
                result.add(map);
            }
        }
        return result;
    }

    static List<String> readStringList(JsonNode params, String key) {
        if (params == null || !params.has(key)) {
            return List.of();
        }
        JsonNode node = params.get(key);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode child : node) {
                if (child.isTextual()) {
                    String value = child.asText().trim();
                    if (!value.isEmpty()) {
                        values.add(value);
                    }
                }
            }
            return values;
        }
        if (node.isTextual()) {
            String value = node.asText().trim();
            return value.isEmpty() ? List.of() : List.of(value);
        }
        return List.of();
    }

    static boolean containsAny(String source, String... keywords) {
        if (source == null || source.isBlank() || keywords == null || keywords.length == 0) {
            return false;
        }
        String text = source.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    static int asInt(Map<String, Object> map, String key, int fallback) {
        if (map == null || key == null) {
            return fallback;
        }
        Object raw = map.get(key);
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static double asDouble(Map<String, Object> map, String key, double fallback) {
        if (map == null || key == null) {
            return fallback;
        }
        Object raw = map.get(key);
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (Exception ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    static boolean asBoolean(Map<String, Object> map, String key, boolean fallback) {
        if (map == null || key == null) {
            return fallback;
        }
        Object raw = map.get(key);
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(s.trim())) {
                return false;
            }
        }
        return fallback;
    }

    static String asString(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    static List<Map<String, Object>> safeMapList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(castMap(map));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    static List<String> safeStringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String text = String.valueOf(item).trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    static List<Map<String, Object>> copyMapList(List<Map<String, Object>> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> copied = new ArrayList<>(input.size());
        for (Map<String, Object> map : input) {
            if (map == null || map.isEmpty()) {
                continue;
            }
            copied.add(new LinkedHashMap<>(map));
        }
        return copied;
    }

    static List<Map<String, Object>> top(List<Map<String, Object>> list, int maxSize) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        int size = Math.max(0, Math.min(maxSize, list.size()));
        if (size == 0) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(list.subList(0, size)));
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }

    static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static List<String> filterNonBlank(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .toList();
    }
}
