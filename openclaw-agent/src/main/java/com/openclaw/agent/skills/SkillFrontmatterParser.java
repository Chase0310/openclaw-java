package com.openclaw.agent.skills;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses YAML-like frontmatter from SKILL.md files and extracts metadata.
 * Corresponds to TypeScript's skills/frontmatter.ts.
 */
@Slf4j
public class SkillFrontmatterParser {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper JSON5 = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true);
    private static final Yaml YAML = new Yaml();
    private static final List<String> LEGACY_MANIFEST_KEYS = List.of("pi", "manifest");

    /**
     * Regex to extract frontmatter block between --- delimiters at the start of a
     * file.
     * Matches: ---\nkey: value\nkey: value\n---
     */
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("\\A---\\s*\\n(.*?)\\n---\\s*\\n?",
            Pattern.DOTALL);

    /**
     * Simple key: value pattern for frontmatter lines.
     */
    private static final Pattern KV_PATTERN = Pattern.compile("^([a-zA-Z][a-zA-Z0-9_-]*)\\s*:\\s*(.*)$");

    // =========================================================================
    // Frontmatter parsing
    // =========================================================================

    /**
     * Parse YAML-like frontmatter from SKILL.md content.
     *
     * @param content full SKILL.md file content
     * @return map of key→value pairs from frontmatter; empty if none found
     */
    public static Map<String, String> parseFrontmatter(String content) {
        if (content == null || content.isBlank()) {
            return Map.of();
        }

        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (!m.find()) {
            return Map.of();
        }

        String block = m.group(1);
        Map<String, String> fallback = parseLineFrontmatter(block);

        try {
            Object parsed = YAML.load(block);
            if (!(parsed instanceof Map<?, ?> map)) {
                return fallback;
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey()).trim();
                if (key.isEmpty()) {
                    continue;
                }
                Object value = entry.getValue();
                String inline = fallback.get(key);
                if (inline != null && !inline.isBlank() && (value instanceof Map<?, ?> || value instanceof List<?>)) {
                    result.put(key, inline);
                } else {
                    result.put(key, stringifyFrontmatterValue(value));
                }
            }
            return result;
        } catch (Exception e) {
            log.debug("Failed to parse skill YAML frontmatter: {}", e.getMessage());
        }
        return fallback;
    }

    /**
     * Extract the body content (after frontmatter) from SKILL.md.
     */
    public static String extractBody(String content) {
        if (content == null || content.isBlank())
            return "";
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (m.find()) {
            return content.substring(m.end()).trim();
        }
        return content.trim();
    }

    // =========================================================================
    // Metadata resolution
    // =========================================================================

    /**
     * Resolve OpenClaw-specific metadata from parsed frontmatter.
     * Looks for a "metadata" key containing a JSON object with an "openclaw"
     * sub-key.
     *
     * @param frontmatter parsed frontmatter map
     * @return resolved metadata, or null if not present
     */
    public static SkillTypes.SkillMetadata resolveMetadata(Map<String, String> frontmatter) {
        String raw = frontmatter.get("metadata");
        if (raw == null || raw.isBlank())
            return null;

        try {
            JsonNode root = JSON5.readTree(raw);
            if (root == null || !root.isObject())
                return null;

            JsonNode meta = findMetadataNode(root);
            if (meta == null || !meta.isObject())
                return null;

            return new SkillTypes.SkillMetadata(
                    meta.has("always") ? meta.get("always").asBoolean() : null,
                    textOrNull(meta, "skillKey"),
                    textOrNull(meta, "primaryEnv"),
                    textOrNull(meta, "emoji"),
                    textOrNull(meta, "homepage"),
                    stringList(meta, "os"),
                    resolveRequires(meta),
                    resolveInstall(meta));
        } catch (Exception e) {
            log.debug("Failed to parse skill metadata: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolve invocation policy from frontmatter.
     */
    public static SkillTypes.SkillInvocationPolicy resolveInvocationPolicy(
            Map<String, String> frontmatter) {
        boolean userInvocable = parseBool(frontmatter.get("user-invocable"), true);
        boolean disableModelInvocation = parseBool(
                frontmatter.get("disable-model-invocation"), false);
        return new SkillTypes.SkillInvocationPolicy(userInvocable, disableModelInvocation);
    }

    /**
     * Resolve the skill key (identifier) from skill name and entry.
     */
    public static String resolveSkillKey(SkillTypes.Skill skill, SkillTypes.SkillEntry entry) {
        if (entry != null && entry.metadata() != null
                && entry.metadata().skillKey() != null) {
            return entry.metadata().skillKey();
        }
        return skill.name();
    }

    public static String resolveSkillName(Map<String, String> frontmatter, String legacyDirName) {
        String name = frontmatter.get("name");
        if (name == null || name.isBlank()) {
            return legacyDirName;
        }
        return name.trim();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static JsonNode findMetadataNode(JsonNode root) {
        JsonNode openclaw = root.get("openclaw");
        if (openclaw != null && openclaw.isObject()) {
            return openclaw;
        }
        for (String key : LEGACY_MANIFEST_KEYS) {
            JsonNode node = root.get(key);
            if (node != null && node.isObject())
                return node;
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null)
            return List.of();
        if (child.isArray()) {
            List<String> result = new ArrayList<>();
            child.forEach(el -> {
                if (el.isTextual())
                    result.add(el.asText().trim());
            });
            return result;
        }
        if (child.isTextual()) {
            return Arrays.stream(child.asText().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        return List.of();
    }

    private static SkillTypes.SkillRequires resolveRequires(JsonNode meta) {
        JsonNode req = meta.get("requires");
        if (req == null || !req.isObject())
            return null;
        return new SkillTypes.SkillRequires(
                stringList(req, "bins"),
                stringList(req, "anyBins"),
                stringList(req, "env"),
                stringList(req, "config"));
    }

    private static List<SkillTypes.SkillInstallSpec> resolveInstall(JsonNode meta) {
        JsonNode install = meta.get("install");
        if (install == null) {
            return List.of();
        }

        List<JsonNode> items = new ArrayList<>();
        if (install.isArray()) {
            install.forEach(items::add);
        } else if (install.isObject()) {
            items.add(install);
        } else {
            return List.of();
        }

        List<SkillTypes.SkillInstallSpec> resolved = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject()) {
                continue;
            }
            String kind = textOrNull(item, "kind");
            if (kind == null) {
                kind = textOrNull(item, "type");
            }
            if (kind == null) {
                continue;
            }
            kind = kind.trim();
            if (!Set.of("brew", "node", "go", "uv", "download").contains(kind)) {
                continue;
            }

            String formula = textOrNull(item, "formula");
            String pkg = textOrNull(item, "package");
            if (pkg == null) {
                pkg = textOrNull(item, "pkg");
            }
            String module = textOrNull(item, "module");
            String url = textOrNull(item, "url");
            if (!isValidInstall(kind, formula, pkg, module, url)) {
                continue;
            }

            resolved.add(new SkillTypes.SkillInstallSpec(
                    kind,
                    textOrNull(item, "id"),
                    textOrNull(item, "label"),
                    stringList(item, "bins"),
                    stringList(item, "os"),
                    formula,
                    pkg,
                    module,
                    url,
                    textOrNull(item, "archive"),
                    item.has("extract") ? item.get("extract").asBoolean() : null,
                    item.has("stripComponents") ? item.get("stripComponents").asInt() : null,
                    textOrNull(item, "targetDir")));
        }
        return resolved;
    }

    private static boolean parseBool(String value, boolean fallback) {
        if (value == null || value.isBlank())
            return fallback;
        String v = value.trim().toLowerCase();
        return switch (v) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> fallback;
        };
    }

    private static Map<String, String> parseLineFrontmatter(String block) {
        Map<String, String> result = new LinkedHashMap<>();
        StringBuilder currentKey = null;
        StringBuilder currentValue = null;

        for (String line : block.split("\\n")) {
            Matcher kv = KV_PATTERN.matcher(line);
            if (kv.matches()) {
                if (currentKey != null && currentValue != null) {
                    result.put(currentKey.toString(), currentValue.toString().trim());
                }
                currentKey = new StringBuilder(kv.group(1));
                currentValue = new StringBuilder(kv.group(2));
            } else if (currentKey != null && currentValue != null) {
                currentValue.append("\n").append(line);
            }
        }

        if (currentKey != null && currentValue != null) {
            result.put(currentKey.toString(), currentValue.toString().trim());
        }

        return result;
    }

    private static String stringifyFrontmatterValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s.trim();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static boolean isValidInstall(String kind, String formula, String pkg, String module, String url) {
        return switch (kind) {
            case "brew" -> formula != null && !formula.isBlank();
            case "node", "uv" -> pkg != null && !pkg.isBlank();
            case "go" -> module != null && !module.isBlank();
            case "download" -> url != null && (url.startsWith("http://") || url.startsWith("https://"));
            default -> false;
        };
    }
}
