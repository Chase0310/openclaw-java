package com.openclaw.agent.skills;

import com.openclaw.agent.skills.SkillTypes.*;
import com.openclaw.common.config.OpenClawConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Discovers and loads skills from workspace, bundled, and managed directories.
 * Corresponds to TypeScript's skills/workspace.ts.
 */
@Slf4j
public class SkillLoader {

    /** The skill definition file that must exist in each skill directory. */
    private static final String SKILL_FILE = "SKILL.md";

    /** Preferred workspace skills sub-directory. */
    private static final String WORKSPACE_SKILLS_DIR = "skills";

    /** Legacy workspace skills sub-directory retained for compatibility. */
    private static final String LEGACY_WORKSPACE_SKILLS_DIR = ".openclaw/skills";

    // =========================================================================
    // Single-directory loading
    // =========================================================================

    /**
     * Load all skills from a single directory.
     * Each subdirectory containing a SKILL.md file is treated as a skill.
     *
     * @param dir    directory to scan
     * @param source where these skills come from
     * @return list of loaded skills (never null)
     */
    public static List<Skill> loadSkillsFromDir(Path dir, SkillSource source) {
        if (dir == null) {
            return List.of();
        }

        if (!Files.isDirectory(dir)) {
            logTrace("Skills trace scanDir={} source={} exists=false", dir, source.label());
            return List.of();
        }

        List<Skill> skills = new ArrayList<>();
        List<String> visitedDirs = new ArrayList<>();
        List<String> matchedSkillFiles = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                if (!Files.isDirectory(child))
                    continue;

                visitedDirs.add(child.getFileName().toString());

                Path skillFile = child.resolve(SKILL_FILE);
                if (!Files.isRegularFile(skillFile))
                    continue;

                try {
                    String content = Files.readString(skillFile, StandardCharsets.UTF_8);
                    String name = child.getFileName().toString();
                    Map<String, String> frontmatter = SkillFrontmatterParser.parseFrontmatter(content);
                    String description = frontmatter.getOrDefault("description", "");
                    String body = SkillFrontmatterParser.extractBody(content);

                    skills.add(new Skill(
                            name,
                            description,
                            source,
                            skillFile.toAbsolutePath().toString(),
                            child.toAbsolutePath().toString(),
                            body));
                    matchedSkillFiles.add(skillFile.toAbsolutePath().toString());
                } catch (IOException e) {
                    log.warn("Failed to read skill file {}: {}", skillFile, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan skills directory {}: {}", dir, e.getMessage());
        }

        logTrace("Skills trace scanDir={} source={} visitedDirs={} matchedSkillFiles={} loadedSkills={}",
                dir.toAbsolutePath(),
                source.label(),
                visitedDirs,
                matchedSkillFiles,
                skills.stream().map(Skill::name).toList());

        // Sort by name for deterministic ordering
        skills.sort(Comparator.comparing(Skill::name));
        return skills;
    }

    // =========================================================================
    // Multi-source loading
    // =========================================================================

    /**
     * Load skill entries from all sources (workspace, bundled, managed).
     *
     * @param workspaceDir     workspace root directory
     * @param bundledSkillsDir optional path to bundled skills
     * @param managedSkillsDir optional path to managed skills
     * @return aggregated list of skill entries
     */
    public static List<SkillEntry> loadSkillEntries(
            String workspaceDir,
            String bundledSkillsDir,
            String managedSkillsDir) {

        List<SkillEntry> entries = new ArrayList<>();

        // 1. Bundled skills
        if (bundledSkillsDir != null) {
            loadAndAppend(entries, Path.of(bundledSkillsDir), SkillSource.BUNDLED);
        }

        // 2. Managed skills
        if (managedSkillsDir != null) {
            loadAndAppend(entries, Path.of(managedSkillsDir), SkillSource.MANAGED);
        }

        // 3. Legacy workspace skills
        if (workspaceDir != null) {
            Path legacyWsSkills = Path.of(workspaceDir, LEGACY_WORKSPACE_SKILLS_DIR);
            loadAndAppend(entries, legacyWsSkills, SkillSource.WORKSPACE);
        }

        // 4. Preferred workspace skills
        if (workspaceDir != null) {
            Path wsSkills = Path.of(workspaceDir, WORKSPACE_SKILLS_DIR);
            loadAndAppend(entries, wsSkills, SkillSource.WORKSPACE);
        }

        return entries;
    }

    /**
     * Convenience overload: load with just workspace dir.
     */
    public static List<SkillEntry> loadSkillEntries(String workspaceDir) {
        return loadSkillEntries(workspaceDir, null, null);
    }

    private static void loadAndAppend(List<SkillEntry> entries, Path dir, SkillSource source) {
        List<Skill> skills = loadSkillsFromDir(dir, source);
        for (Skill skill : skills) {
            String content = readSkillContent(skill.filePath());
            Map<String, String> frontmatter = SkillFrontmatterParser.parseFrontmatter(content);
            SkillMetadata metadata = SkillFrontmatterParser.resolveMetadata(frontmatter);
            SkillInvocationPolicy invocation = SkillFrontmatterParser.resolveInvocationPolicy(frontmatter);
            entries.add(new SkillEntry(skill, frontmatter, metadata, invocation));
        }
    }

    // =========================================================================
    // Filtering
    // =========================================================================

    /**
     * Filter skill entries by eligibility: OS check, requires, config, always flag.
     *
     * @param entries     full list of entries
     * @param config      optional config for skill-specific overrides
     * @param skillFilter optional name filter (only include these names)
     * @return filtered entries
     */
    public static List<SkillEntry> filterSkillEntries(
            List<SkillEntry> entries,
            OpenClawConfig config,
            List<String> skillFilter) {

        if (entries == null || entries.isEmpty())
            return List.of();

        String osName = System.getProperty("os.name", "").toLowerCase();

        return entries.stream()
                .filter(entry -> {
                    // Name filter
                    if (skillFilter != null && !skillFilter.isEmpty()) {
                        if (!skillFilter.contains(entry.skill().name())) {
                            return false;
                        }
                    }

                    // OS filter
                    SkillMetadata meta = entry.metadata();
                    if (meta != null && meta.os() != null && !meta.os().isEmpty()) {
                        boolean osMatch = meta.os().stream().anyMatch(os -> osName.contains(os.toLowerCase()));
                        if (!osMatch) {
                            log.debug("Skill '{}' filtered: OS mismatch (need {}, have {})",
                                    entry.skill().name(), meta.os(), osName);
                            return false;
                        }
                    }

                    return true;
                })
                .toList();
    }

    /**
     * Filter without name filter.
     */
    public static List<SkillEntry> filterSkillEntries(
            List<SkillEntry> entries, OpenClawConfig config) {
        return filterSkillEntries(entries, config, null);
    }

    // =========================================================================
    // Prompt building
    // =========================================================================

    /**
     * Build the skills prompt string for injection into the system prompt.
     *
     * @param entries filtered skill entries
     * @return formatted skills prompt
     */
    public static String buildSkillsPrompt(List<SkillEntry> entries) {
        if (entries == null || entries.isEmpty())
            return "";

        StringBuilder sb = new StringBuilder();
        for (SkillEntry entry : entries) {
            Skill skill = entry.skill();
            String content = skill.content();

            if (content == null || content.isBlank())
                continue;

            sb.append("\n<skill name=\"").append(skill.name()).append("\">\n");
            sb.append(content.trim());
            sb.append("\n</skill>\n");
        }

        return sb.toString().trim();
    }

    /**
     * Build a skill snapshot for caching.
     */
    public static SkillSnapshot buildSkillSnapshot(List<SkillEntry> entries) {
        String prompt = buildSkillsPrompt(entries);
        List<SkillSummary> summaries = entries.stream()
                .map(e -> new SkillSummary(
                        e.skill().name(),
                        e.metadata() != null ? e.metadata().primaryEnv() : null))
                .toList();
        List<Skill> resolvedSkills = entries.stream().map(SkillEntry::skill).toList();
        return new SkillSnapshot(prompt, summaries, resolvedSkills, 1);
    }

    // =========================================================================
    // One-shot pipeline
    // =========================================================================

    /**
     * Full pipeline: load → filter → build prompt.
     *
     * @param workspaceDir workspace root
     * @param config       optional config
     * @return skills prompt string ready for system prompt injection
     */
    public static String resolveSkillsPromptForRun(
            String workspaceDir, OpenClawConfig config) {
        return resolveSkillSnapshotForRun(workspaceDir, config).prompt();
    }

    public static SkillSnapshot resolveSkillSnapshotForRun(
            String workspaceDir, OpenClawConfig config) {
        List<SkillEntry> entries = loadSkillEntries(workspaceDir);
        List<SkillEntry> filtered = filterSkillEntries(entries, config);
        return buildSkillSnapshot(dedupeByName(filtered));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static List<SkillEntry> dedupeByName(List<SkillEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        Map<String, SkillEntry> deduped = new LinkedHashMap<>();
        for (SkillEntry entry : entries) {
            if (entry == null || entry.skill() == null || entry.skill().name() == null) {
                continue;
            }
            // Later sources override earlier ones, matching bundled -> managed ->
            // workspace precedence.
            deduped.remove(entry.skill().name());
            deduped.put(entry.skill().name(), entry);
        }
        return new ArrayList<>(deduped.values());
    }

    private static String readSkillContent(String filePath) {
        try {
            return Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read skill file {}: {}", filePath, e.getMessage());
            return "";
        }
    }

    private static void logTrace(String format, Object... args) {
        if (!isSkillsTraceEnabled()) {
            return;
        }
        log.warn(format, args);
    }

    private static boolean isSkillsTraceEnabled() {
        String env = System.getenv("OPENCLAW_SKILLS_TRACE");
        return env == null || env.isBlank()
                || "true".equalsIgnoreCase(env)
                || "1".equals(env);
    }
}
