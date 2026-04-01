package com.openclaw.agent.skills;

import com.openclaw.agent.skills.SkillTypes.*;
import com.openclaw.common.config.OpenClawConfig;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Skill configuration resolution — config path traversal, eligibility checks,
 * bundled allowlist, binary detection.
 * Corresponds to TypeScript skills/config.ts.
 */
public final class SkillConfigResolver {

    private SkillConfigResolver() {
    }

    private static final Map<String, Boolean> DEFAULT_CONFIG_VALUES = Map.of(
            "browser.enabled", true,
            "browser.evaluateEnabled", true);

    private static final Set<String> BUNDLED_SOURCES = Set.of("openclaw-bundled");

    // ── Config path ─────────────────────────────────────────────────

    /**
     * Resolve a nested config value by dot-separated path.
     */
    public static Object resolveConfigPath(OpenClawConfig config, String pathStr) {
        if (config == null || pathStr == null || pathStr.isBlank())
            return null;
        String[] parts = pathStr.split("\\.");
        Object current = config;
        for (String part : parts) {
            if (current == null)
                return null;
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                current = getProperty(current, part);
            }
        }
        return current;
    }

    /**
     * Check if a config path resolves to a truthy value.
     */
    public static boolean isConfigPathTruthy(OpenClawConfig config, String pathStr) {
        Object value = resolveConfigPath(config, pathStr);
        if (value == null && pathStr != null && DEFAULT_CONFIG_VALUES.containsKey(pathStr)) {
            return DEFAULT_CONFIG_VALUES.get(pathStr);
        }
        return isTruthy(value);
    }

    // ── Skill config ────────────────────────────────────────────────

    /**
     * Resolve per-skill configuration from OpenClaw config.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolveSkillConfig(OpenClawConfig config, String skillKey) {
        if (config == null || config.getSkills() == null)
            return null;
        Object entries = config.getSkills().getEntries();
        if (entries instanceof Map<?, ?> map) {
            Object entry = map.get(skillKey);
            if (entry instanceof Map<?, ?>)
                return (Map<String, Object>) entry;
        }
        return null;
    }

    public static Map<String, Object> resolveSkillConfig(OpenClawConfig config, SkillEntry entry) {
        if (entry == null) {
            return null;
        }
        String primaryKey = SkillFrontmatterParser.resolveSkillKey(entry.skill(), entry);
        Map<String, Object> resolved = resolveSkillConfig(config, primaryKey);
        if (resolved != null) {
            return resolved;
        }
        resolved = resolveSkillConfig(config, entry.skill().name());
        if (resolved != null) {
            return resolved;
        }
        String legacyDirName = entry.skill().legacyDirName();
        if (legacyDirName != null && !legacyDirName.isBlank()) {
            return resolveSkillConfig(config, legacyDirName);
        }
        return null;
    }

    // ── Bundled allowlist ────────────────────────────────────────────

    /**
     * Resolve the bundled skills allowlist from config.
     */
    public static List<String> resolveBundledAllowlist(OpenClawConfig config) {
        if (config == null || config.getSkills() == null)
            return null;
        Object raw = config.getSkills().getAllowBundled();
        return normalizeAllowlist(raw);
    }

    public static boolean isBundledSkillAllowed(SkillEntry entry, List<String> allowlist) {
        if (allowlist == null || allowlist.isEmpty())
            return true;
        if (!isBundledSkill(entry))
            return true;
        String key = SkillFrontmatterParser.resolveSkillKey(entry.skill(), entry);
        return allowlist.contains(key) || allowlist.contains(entry.skill().name());
    }

    // ── Binary detection ────────────────────────────────────────────

    /**
     * Check if a binary executable exists on PATH.
     */
    public static boolean hasBinary(String bin) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank())
            return false;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            Path candidate = Path.of(dir, bin);
            if (Files.isExecutable(candidate))
                return true;
        }
        return false;
    }

    /**
     * Resolve the runtime platform string (darwin/win32/linux).
     */
    public static String resolveRuntimePlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("darwin"))
            return "darwin";
        if (os.contains("win"))
            return "win32";
        return "linux";
    }

    // ── Eligibility check ───────────────────────────────────────────

    /**
     * Determine if a skill should be included based on config, OS, requirements.
     */
    public static boolean shouldIncludeSkill(SkillEntry entry, OpenClawConfig config) {
        return shouldIncludeSkill(entry, config, null);
    }

    /**
     * Full eligibility check with remote context.
     */
    @SuppressWarnings("unchecked")
    public static boolean shouldIncludeSkill(
            SkillEntry entry, OpenClawConfig config, SkillEligibilityContext eligibility) {
        return evaluateSkill(entry, config, eligibility).eligible();
    }

    @SuppressWarnings("unchecked")
    public static SkillEligibility evaluateSkill(
            SkillEntry entry, OpenClawConfig config, SkillEligibilityContext eligibility) {

        String skillKey = SkillFrontmatterParser.resolveSkillKey(entry.skill(), entry);
        Map<String, Object> missing = new LinkedHashMap<>();
        List<String> configChecks = new ArrayList<>();

        boolean disabled = false;
        boolean blockedByAllowlist = false;
        if (config != null) {
            var skillConfig = resolveSkillConfig(config, entry);
            if (skillConfig != null && Boolean.FALSE.equals(skillConfig.get("enabled"))) {
                disabled = true;
            }
            List<String> allowBundled = resolveBundledAllowlist(config);
            if (!isBundledSkillAllowed(entry, allowBundled)) {
                blockedByAllowlist = true;
            }
        }

        List<String> osList = entry.metadata() != null ? entry.metadata().os() : null;
        List<String> remotePlatforms = eligibility != null && eligibility.remote() != null
                ? eligibility.remote().platforms()
                : List.of();
        boolean osEligible = true;
        if (osList != null && !osList.isEmpty()) {
            boolean localMatch = osList.contains(resolveRuntimePlatform());
            boolean remoteMatch = remotePlatforms.stream().anyMatch(osList::contains);
            osEligible = localMatch || remoteMatch;
            if (!osEligible) {
                missing.put("os", osList);
            }
        }

        boolean binsEligible = true;
        boolean anyBinsEligible = true;
        boolean envEligible = true;
        boolean configEligible = true;
        if (entry.metadata() != null && entry.metadata().requires() != null) {
            SkillRequires req = entry.metadata().requires();

            if (req.bins() != null && !req.bins().isEmpty()) {
                List<String> missingBins = new ArrayList<>();
                for (String bin : req.bins()) {
                    if (hasBinary(bin))
                        continue;
                    if (eligibility != null && eligibility.remote() != null
                            && eligibility.remote().hasBin() != null
                            && eligibility.remote().hasBin().test(bin))
                        continue;
                    missingBins.add(bin);
                }
                binsEligible = missingBins.isEmpty();
                if (!binsEligible) {
                    missing.put("bins", missingBins);
                }
            }

            if (req.anyBins() != null && !req.anyBins().isEmpty()) {
                boolean localAny = req.anyBins().stream().anyMatch(SkillConfigResolver::hasBinary);
                boolean remoteAny = eligibility != null && eligibility.remote() != null
                        && eligibility.remote().hasAnyBin() != null
                        && Boolean.TRUE.equals(eligibility.remote().hasAnyBin().apply(req.anyBins()));
                anyBinsEligible = localAny || remoteAny;
                if (!anyBinsEligible) {
                    missing.put("anyBins", req.anyBins());
                }
            }

            if (req.env() != null && !req.env().isEmpty()) {
                List<String> missingEnv = new ArrayList<>();
                for (String envName : req.env()) {
                    String envValue = SkillEnvOverrides.getEnvWithOverrides(envName);
                    if (envValue != null && !envValue.isBlank())
                        continue;
                    if (config != null) {
                        Map<String, Object> sc = resolveSkillConfig(config, entry);
                        if (sc != null) {
                            Map<String, String> envMap = (Map<String, String>) sc.get("env");
                            if (envMap != null && envMap.containsKey(envName))
                                continue;
                            if (entry.metadata().primaryEnv() != null
                                    && entry.metadata().primaryEnv().equals(envName)
                                    && sc.containsKey("apiKey"))
                                continue;
                        }
                    }
                    missingEnv.add(envName);
                }
                envEligible = missingEnv.isEmpty();
                if (!envEligible) {
                    missing.put("env", missingEnv);
                }
            }

            if (req.config() != null && !req.config().isEmpty()) {
                List<String> missingConfig = new ArrayList<>();
                for (String configPath : req.config()) {
                    configChecks.add(configPath);
                    if (!isConfigPathTruthy(config, configPath)) {
                        missingConfig.add(configPath);
                    }
                }
                configEligible = missingConfig.isEmpty();
                if (!configEligible) {
                    missing.put("config", missingConfig);
                }
            }
        }

        boolean eligibleResult = !disabled
                && !blockedByAllowlist
                && osEligible
                && binsEligible
                && anyBinsEligible
                && envEligible
                && configEligible;

        return new SkillEligibility(
                disabled,
                blockedByAllowlist,
                osEligible,
                binsEligible,
                anyBinsEligible,
                envEligible,
                configEligible,
                eligibleResult,
                Collections.unmodifiableMap(missing),
                List.copyOf(configChecks));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static boolean isBundledSkill(SkillEntry entry) {
        return entry.skill().source() != null
                && BUNDLED_SOURCES.contains(entry.skill().source().label());
    }

    @SuppressWarnings("unchecked")
    private static List<String> normalizeAllowlist(Object input) {
        if (input == null)
            return null;
        if (input instanceof List<?> list) {
            List<String> normalized = list.stream()
                    .map(e -> String.valueOf(e).trim())
                    .filter(s -> !s.isEmpty())
                    .toList();
            return normalized.isEmpty() ? null : normalized;
        }
        return null;
    }

    private static boolean isTruthy(Object value) {
        if (value == null)
            return false;
        if (value instanceof Boolean b)
            return b;
        if (value instanceof Number n)
            return n.doubleValue() != 0;
        if (value instanceof String s)
            return !s.trim().isEmpty();
        return true;
    }

    private static Object getProperty(Object obj, String name) {
        try {
            var method = obj.getClass().getMethod("get" + capitalize(name));
            return method.invoke(obj);
        } catch (Exception e1) {
            try {
                var method = obj.getClass().getMethod("is" + capitalize(name));
                return method.invoke(obj);
            } catch (Exception e2) {
                try {
                    var field = obj.getClass().getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(obj);
                } catch (Exception e3) {
                    return null;
                }
            }
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty())
            return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
