package com.openclaw.agent.skills;

import com.openclaw.agent.skills.SkillTypes.Skill;
import com.openclaw.agent.skills.SkillTypes.SkillEligibility;
import com.openclaw.agent.skills.SkillTypes.SkillEntry;
import com.openclaw.agent.skills.SkillTypes.SkillPromptMode;
import com.openclaw.agent.skills.SkillTypes.SkillSnapshot;
import com.openclaw.agent.skills.SkillTypes.SkillSource;
import com.openclaw.agent.skills.SkillTypes.SkillStatus;
import com.openclaw.agent.skills.SkillTypes.SkillSummary;
import com.openclaw.common.config.OpenClawConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Discovers, validates, and formats skills for progressive disclosure.
 */
@Slf4j
public final class SkillLoader {

    private SkillLoader() {
    }

    private static final String SKILL_FILE = "SKILL.md";
    private static final String HOME = System.getProperty("user.home", "");
    private static final String MANAGED_SKILLS_DIR = ".openclaw/skills";
    private static final String PERSONAL_AGENTS_SKILLS_DIR = ".agents/skills";
    private static final String PROJECT_AGENTS_SKILLS_DIR = ".agents/skills";
    private static final String LEGACY_WORKSPACE_SKILLS_DIR = ".openclaw/skills";
    private static final String WORKSPACE_SKILLS_DIR = "skills";

    private static final int DEFAULT_MAX_CANDIDATES_PER_ROOT = 300;
    private static final int DEFAULT_MAX_SKILLS_LOADED_PER_SOURCE = 200;
    private static final int DEFAULT_MAX_SKILLS_IN_PROMPT = 150;
    private static final int DEFAULT_MAX_SKILLS_PROMPT_CHARS = 30_000;
    private static final int DEFAULT_MAX_SKILL_FILE_BYTES = 256_000;
    private static final int DEFAULT_COMPACT_WARNING_OVERHEAD = 150;

    private record SkillRoot(Path path, SkillSource source) {
    }

    private record PromptBuildResult(
            String prompt,
            SkillPromptMode promptMode,
            List<SkillEntry> injectedEntries) {
    }

    public static List<Skill> loadSkillsFromDir(Path dir, SkillSource source) {
        return loadSkillsFromDir(dir, source, defaultLimits());
    }

    public static List<Skill> loadSkillsFromDir(Path dir, SkillSource source, OpenClawConfig.SkillsLimitsConfig limits) {
        return loadEntriesFromRoot(new SkillRoot(dir, source), limits).stream()
                .map(SkillEntry::skill)
                .toList();
    }

    public static String resolveManagedSkillsDir() {
        if (HOME == null || HOME.isBlank()) {
            return null;
        }
        return Path.of(HOME).resolve(MANAGED_SKILLS_DIR).toString();
    }

    public static List<String> resolveDiscoveryRootPaths(String workspaceDir, OpenClawConfig config) {
        return resolveDiscoveryRoots(workspaceDir, config, SkillBundledDir.resolveBundledSkillsDir(), resolveManagedSkillsDir())
                .stream()
                .map(root -> root.path().toString())
                .distinct()
                .toList();
    }

    public static List<SkillEntry> loadSkillEntries(String workspaceDir, OpenClawConfig config) {
        return loadSkillEntries(workspaceDir, SkillBundledDir.resolveBundledSkillsDir(), resolveManagedSkillsDir(), config);
    }

    public static List<SkillEntry> loadSkillEntries(
            String workspaceDir,
            String bundledSkillsDir,
            String managedSkillsDir,
            OpenClawConfig config) {

        OpenClawConfig.SkillsLimitsConfig limits = resolveLimits(config);
        Map<String, SkillEntry> deduped = new LinkedHashMap<>();

        for (SkillRoot root : resolveDiscoveryRoots(workspaceDir, config, bundledSkillsDir, managedSkillsDir)) {
            for (SkillEntry entry : loadEntriesFromRoot(root, limits)) {
                deduped.put(entry.skill().name(), entry);
            }
        }

        return deduped.values().stream()
                .sorted(skillEntryComparator())
                .toList();
    }

    public static List<SkillEntry> filterSkillEntries(
            List<SkillEntry> entries,
            OpenClawConfig config,
            List<String> skillFilter) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }

        Set<String> filterSet = normalizeSkillFilter(skillFilter);
        List<SkillEntry> filtered = new ArrayList<>();
        for (SkillEntry entry : entries) {
            if (!filterSet.isEmpty()) {
                String skillKey = SkillFrontmatterParser.resolveSkillKey(entry.skill(), entry);
                if (!filterSet.contains(entry.skill().name())
                        && !filterSet.contains(skillKey)
                        && !filterSet.contains(entry.skill().legacyDirName())) {
                    continue;
                }
            }
            SkillEligibility eligibility = SkillConfigResolver.evaluateSkill(entry, config, null);
            if (!eligibility.eligible()) {
                continue;
            }
            if (entry.invocation() != null && entry.invocation().disableModelInvocation()) {
                continue;
            }
            filtered.add(entry);
        }
        return filtered;
    }

    public static List<SkillEntry> filterSkillEntries(List<SkillEntry> entries, OpenClawConfig config) {
        return filterSkillEntries(entries, config, null);
    }

    public static SkillSnapshot resolveSkillSnapshotForRun(String workspaceDir, OpenClawConfig config) {
        return resolveSkillSnapshotForRun(
                workspaceDir,
                SkillBundledDir.resolveBundledSkillsDir(),
                resolveManagedSkillsDir(),
                config);
    }

    public static SkillSnapshot resolveSkillSnapshotForRun(
            String workspaceDir,
            String bundledSkillsDir,
            String managedSkillsDir,
            OpenClawConfig config) {
        List<SkillEntry> discovered = loadSkillEntries(workspaceDir, bundledSkillsDir, managedSkillsDir, config);
        List<SkillEntry> eligible = new ArrayList<>();
        List<SkillEntry> promptEligible = new ArrayList<>();
        for (SkillEntry entry : discovered) {
            SkillEligibility evaluation = SkillConfigResolver.evaluateSkill(entry, config, null);
            if (!evaluation.eligible()) {
                continue;
            }
            eligible.add(entry);
            if (entry.invocation() == null || !entry.invocation().disableModelInvocation()) {
                promptEligible.add(entry);
            }
        }

        PromptBuildResult promptBuild = buildSkillsCatalogPrompt(promptEligible, config);
        List<SkillSummary> summaries = promptBuild.injectedEntries().stream()
                .map(entry -> new SkillSummary(
                        entry.skill().name(),
                        SkillFrontmatterParser.resolveSkillKey(entry.skill(), entry),
                        entry.skill().legacyDirName(),
                        entry.skill().filePath(),
                        entry.metadata() != null ? entry.metadata().primaryEnv() : null))
                .toList();
        List<Skill> resolvedSkills = promptBuild.injectedEntries().stream()
                .map(SkillEntry::skill)
                .toList();
        List<String> injectedLocations = resolvedSkills.stream()
                .map(Skill::filePath)
                .toList();

        return new SkillSnapshot(
                promptBuild.prompt(),
                summaries,
                resolvedSkills,
                1,
                promptBuild.promptMode(),
                injectedLocations,
                discovered.size(),
                eligible.size(),
                promptEligible.size(),
                resolvedSkills.size());
    }

    public static String resolveSkillsPromptForRun(String workspaceDir, OpenClawConfig config) {
        return resolveSkillSnapshotForRun(workspaceDir, config).prompt();
    }

    public static List<SkillStatus> buildSkillStatuses(String workspaceDir, OpenClawConfig config) {
        List<SkillEntry> entries = loadSkillEntries(workspaceDir, config);
        List<SkillStatus> statuses = new ArrayList<>();
        for (SkillEntry entry : entries) {
            SkillEligibility evaluation = SkillConfigResolver.evaluateSkill(entry, config, null);
            statuses.add(new SkillStatus(
                    entry.skill().name(),
                    entry.skill().legacyDirName(),
                    SkillFrontmatterParser.resolveSkillKey(entry.skill(), entry),
                    entry.skill().description(),
                    entry.skill().source().label(),
                    entry.skill().source() == SkillSource.BUNDLED,
                    entry.skill().filePath(),
                    entry.skill().baseDir(),
                    entry.metadata() != null && Boolean.TRUE.equals(entry.metadata().always()),
                    evaluation.disabled(),
                    evaluation.blockedByAllowlist(),
                    evaluation.eligible(),
                    entry.invocation() == null || entry.invocation().userInvocable(),
                    entry.invocation() != null && entry.invocation().disableModelInvocation(),
                    entry.metadata() != null ? entry.metadata().requires() : null,
                    evaluation.missing(),
                    evaluation.configChecks(),
                    entry.metadata() != null ? entry.metadata().install() : List.of()));
        }
        return statuses;
    }

    public static String normalizeSkillLocation(String rawPath, String baseDir, String cwd) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String expanded = expandHomePrefix(rawPath.trim());
        Path path = Path.of(expanded);
        if (!path.isAbsolute()) {
            if (baseDir != null && !baseDir.isBlank()) {
                path = Path.of(baseDir).resolve(path);
            } else if (cwd != null && !cwd.isBlank()) {
                path = Path.of(cwd).resolve(path);
            } else {
                path = path.toAbsolutePath();
            }
        }
        path = path.normalize().toAbsolutePath();
        try {
            if (Files.exists(path)) {
                path = path.toRealPath();
            }
        } catch (IOException e) {
            log.debug("Failed to resolve skill location {}: {}", rawPath, e.getMessage());
        }
        return path.toString();
    }

    public static String resolveSkillReadPath(String rawPath, String baseDir, String cwd) {
        return normalizeSkillLocation(rawPath, baseDir, cwd);
    }

    public static OpenClawConfig.SkillsLimitsConfig resolveLimits(OpenClawConfig config) {
        OpenClawConfig.SkillsLimitsConfig resolved = new OpenClawConfig.SkillsLimitsConfig();
        OpenClawConfig.SkillsLimitsConfig configured = config != null && config.getSkills() != null
                ? config.getSkills().getLimits()
                : null;

        resolved.setMaxCandidatesPerRoot(readLimit(configured != null ? configured.getMaxCandidatesPerRoot() : null,
                DEFAULT_MAX_CANDIDATES_PER_ROOT));
        resolved.setMaxSkillsLoadedPerSource(readLimit(
                configured != null ? configured.getMaxSkillsLoadedPerSource() : null,
                DEFAULT_MAX_SKILLS_LOADED_PER_SOURCE));
        resolved.setMaxSkillsInPrompt(readLimit(configured != null ? configured.getMaxSkillsInPrompt() : null,
                DEFAULT_MAX_SKILLS_IN_PROMPT));
        resolved.setMaxSkillsPromptChars(readLimit(configured != null ? configured.getMaxSkillsPromptChars() : null,
                DEFAULT_MAX_SKILLS_PROMPT_CHARS));
        resolved.setMaxSkillFileBytes(readLimit(configured != null ? configured.getMaxSkillFileBytes() : null,
                DEFAULT_MAX_SKILL_FILE_BYTES));
        resolved.setCompactWarningOverhead(readLimit(
                configured != null ? configured.getCompactWarningOverhead() : null,
                DEFAULT_COMPACT_WARNING_OVERHEAD));
        return resolved;
    }

    private static OpenClawConfig.SkillsLimitsConfig defaultLimits() {
        return resolveLimits(null);
    }

    private static int readLimit(Integer configured, int defaultValue) {
        return configured != null && configured > 0 ? configured : defaultValue;
    }

    private static List<SkillRoot> resolveDiscoveryRoots(
            String workspaceDir,
            OpenClawConfig config,
            String bundledSkillsDir,
            String managedSkillsDir) {

        List<SkillRoot> roots = new ArrayList<>();
        Collection<String> extraDirs = config != null
                && config.getSkills() != null
                && config.getSkills().getLoad() != null
                && config.getSkills().getLoad().getExtraDirs() != null
                        ? config.getSkills().getLoad().getExtraDirs()
                        : List.of();
        for (String dir : extraDirs) {
            addRoot(roots, dir, SkillSource.EXTRA);
        }
        for (String dir : SkillPluginResolver.resolvePluginSkillDirs(workspaceDir, config)) {
            addRoot(roots, dir, SkillSource.EXTRA);
        }

        addRoot(roots, bundledSkillsDir, SkillSource.BUNDLED);
        addRoot(roots, managedSkillsDir, SkillSource.MANAGED);
        addRoot(roots, HOME == null || HOME.isBlank() ? null : Path.of(HOME, PERSONAL_AGENTS_SKILLS_DIR).toString(),
                SkillSource.AGENTS_PERSONAL);

        if (workspaceDir != null && !workspaceDir.isBlank()) {
            Path workspace = Path.of(workspaceDir);
            addRoot(roots, workspace.resolve(PROJECT_AGENTS_SKILLS_DIR).toString(), SkillSource.AGENTS_PROJECT);
            addRoot(roots, workspace.resolve(LEGACY_WORKSPACE_SKILLS_DIR).toString(), SkillSource.WORKSPACE_LEGACY);
            addRoot(roots, workspace.resolve(WORKSPACE_SKILLS_DIR).toString(), SkillSource.WORKSPACE);
        }

        LinkedHashMap<String, SkillRoot> deduped = new LinkedHashMap<>();
        for (SkillRoot root : roots) {
            deduped.putIfAbsent(root.path().normalize().toAbsolutePath().toString(), root);
        }
        return new ArrayList<>(deduped.values());
    }

    private static void addRoot(List<SkillRoot> roots, String dir, SkillSource source) {
        if (dir == null || dir.isBlank()) {
            return;
        }
        roots.add(new SkillRoot(Path.of(expandHomePrefix(dir.trim())), source));
    }

    private static List<SkillEntry> loadEntriesFromRoot(SkillRoot root, OpenClawConfig.SkillsLimitsConfig limits) {
        if (root == null || root.path() == null || !Files.isDirectory(root.path())) {
            return List.of();
        }

        Path canonicalRoot;
        try {
            canonicalRoot = root.path().toRealPath();
        } catch (IOException e) {
            log.warn("Failed to resolve skills root {}: {}", root.path(), e.getMessage());
            return List.of();
        }

        List<Path> candidates = discoverSkillDirectories(canonicalRoot, limits.getMaxCandidatesPerRoot());
        List<SkillEntry> entries = new ArrayList<>();
        for (Path candidate : candidates) {
            if (entries.size() >= limits.getMaxSkillsLoadedPerSource()) {
                log.warn("Skills source {} hit load cap {}", canonicalRoot, limits.getMaxSkillsLoadedPerSource());
                break;
            }
            SkillEntry entry = loadEntry(canonicalRoot, candidate, root.source(), limits.getMaxSkillFileBytes());
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    private static List<Path> discoverSkillDirectories(Path root, int maxCandidates) {
        Set<Path> candidates = new LinkedHashSet<>();
        if (isSkillDirectory(root)) {
            candidates.add(root);
        }

        int scanned = scanSkillDirectories(root, candidates, maxCandidates);
        Path nestedSkills = root.resolve("skills");
        if (!nestedSkills.equals(root) && Files.isDirectory(nestedSkills)) {
            scanSkillDirectories(nestedSkills, candidates, Math.max(0, maxCandidates - scanned));
        }

        return candidates.stream()
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
    }

    private static int scanSkillDirectories(Path dir, Set<Path> candidates, int budget) {
        if (budget <= 0 || !Files.isDirectory(dir)) {
            return 0;
        }
        int scanned = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            List<Path> children = new ArrayList<>();
            for (Path child : stream) {
                if (Files.isDirectory(child)) {
                    children.add(child);
                }
            }
            children.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path child : children) {
                scanned++;
                if (scanned > budget) {
                    log.warn("Skills root {} hit candidate scan cap {}", dir, budget);
                    break;
                }
                if (isSkillDirectory(child)) {
                    candidates.add(child);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to scan skills directory {}: {}", dir, e.getMessage());
        }
        return Math.min(scanned, budget);
    }

    private static boolean isSkillDirectory(Path dir) {
        return Files.isDirectory(dir) && Files.isRegularFile(dir.resolve(SKILL_FILE), LinkOption.NOFOLLOW_LINKS);
    }

    private static SkillEntry loadEntry(Path canonicalRoot, Path candidateDir, SkillSource source, int maxSkillFileBytes) {
        try {
            Path canonicalDir = candidateDir.toRealPath();
            if (!canonicalDir.startsWith(canonicalRoot)) {
                log.warn("Rejecting skill outside root: {}", candidateDir);
                return null;
            }

            Path skillFile = canonicalDir.resolve(SKILL_FILE);
            if (Files.isSymbolicLink(skillFile)) {
                log.warn("Rejecting symlinked SKILL.md: {}", skillFile);
                return null;
            }
            if (!Files.isRegularFile(skillFile, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }

            Path canonicalSkillFile = skillFile.toRealPath();
            if (!canonicalSkillFile.startsWith(canonicalRoot)) {
                log.warn("Rejecting out-of-root SKILL.md: {}", skillFile);
                return null;
            }
            long size = Files.size(canonicalSkillFile);
            if (size > maxSkillFileBytes) {
                log.warn("Rejecting oversized SKILL.md ({} bytes): {}", size, canonicalSkillFile);
                return null;
            }

            String fullContent = readUtf8(canonicalSkillFile);
            Map<String, String> frontmatter = SkillFrontmatterParser.parseFrontmatter(fullContent);
            String description = trimToNull(frontmatter.get("description"));
            if (description == null) {
                log.warn("Skipping skill without description: {}", canonicalSkillFile);
                return null;
            }

            String legacyDirName = canonicalDir.getFileName().toString();
            Skill skill = new Skill(
                    SkillFrontmatterParser.resolveSkillName(frontmatter, legacyDirName),
                    legacyDirName,
                    description,
                    source,
                    canonicalSkillFile.toString(),
                    canonicalDir.toString(),
                    SkillFrontmatterParser.extractBody(fullContent));
            return new SkillEntry(
                    skill,
                    frontmatter,
                    SkillFrontmatterParser.resolveMetadata(frontmatter),
                    SkillFrontmatterParser.resolveInvocationPolicy(frontmatter));
        } catch (CharacterCodingException e) {
            log.warn("Skipping non-UTF-8 skill {}: {}", candidateDir, e.getMessage());
            return null;
        } catch (IOException e) {
            log.warn("Failed to load skill {}: {}", candidateDir, e.getMessage());
            return null;
        }
    }

    private static String readUtf8(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static PromptBuildResult buildSkillsCatalogPrompt(List<SkillEntry> entries, OpenClawConfig config) {
        if (entries == null || entries.isEmpty()) {
            return new PromptBuildResult("", SkillPromptMode.EMPTY, List.of());
        }

        OpenClawConfig.SkillsLimitsConfig limits = resolveLimits(config);
        List<SkillEntry> capped = entries.subList(0, Math.min(entries.size(), limits.getMaxSkillsInPrompt()));
        boolean omittedByCount = entries.size() > capped.size();

        String fullPrompt = renderPrompt(capped, true, null);
        if (fullPrompt.length() <= limits.getMaxSkillsPromptChars() && !omittedByCount) {
            return new PromptBuildResult(fullPrompt, SkillPromptMode.FULL, capped);
        }

        String countWarning = omittedByCount
                ? buildTruncatedWarning(capped.size(), entries.size(), "")
                : null;
        if (countWarning != null) {
            String fullWithWarning = renderPrompt(capped, true, countWarning);
            if (fullWithWarning.length() <= limits.getMaxSkillsPromptChars()) {
                return new PromptBuildResult(fullWithWarning, SkillPromptMode.TRUNCATED, capped);
            }
        }

        int compactBudget = limits.getMaxSkillsPromptChars();
        if (!omittedByCount) {
            compactBudget -= limits.getCompactWarningOverhead();
        }
        String compactPrompt = renderPrompt(capped, false, countWarning);
        if (compactPrompt.length() <= compactBudget) {
            return new PromptBuildResult(compactPrompt,
                    omittedByCount ? SkillPromptMode.TRUNCATED : SkillPromptMode.COMPACT,
                    capped);
        }

        int low = 0;
        int high = capped.size();
        List<SkillEntry> best = List.of();
        String bestPrompt = "";
        while (low <= high) {
            int mid = (low + high) >>> 1;
            List<SkillEntry> prefix = capped.subList(0, mid);
            String warning = buildTruncatedWarning(prefix.size(), entries.size(),
                    prefix.size() < entries.size() ? " (compact format)" : "");
            String prompt = renderPrompt(prefix, false, warning);
            if (prompt.length() <= limits.getMaxSkillsPromptChars()) {
                best = List.copyOf(prefix);
                bestPrompt = prompt;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        if (best.isEmpty()) {
            String warning = buildTruncatedWarning(0, entries.size(), " (compact format)");
            return new PromptBuildResult(renderPrompt(List.of(), false, warning), SkillPromptMode.TRUNCATED, List.of());
        }
        return new PromptBuildResult(bestPrompt, SkillPromptMode.TRUNCATED, best);
    }

    private static String renderPrompt(List<SkillEntry> entries, boolean includeDescription, String warning) {
        StringBuilder sb = new StringBuilder();
        sb.append("The following skills provide specialized instructions for specific tasks.\n");
        sb.append("Use the read_file tool to load a skill's file when the task matches its ");
        sb.append(includeDescription ? "description" : "name");
        sb.append(".\n");
        sb.append("When a skill file references a relative path, resolve it against the skill directory ");
        sb.append("(parent of SKILL.md / dirname of the path) and use that absolute path in tool commands.\n\n");
        if (warning != null && !warning.isBlank()) {
            sb.append(warning).append("\n\n");
        }
        sb.append("<available_skills>\n");
        for (SkillEntry entry : entries) {
            sb.append("  <skill>\n");
            sb.append("    <name>").append(xmlEscape(entry.skill().name())).append("</name>\n");
            if (includeDescription) {
                sb.append("    <description>").append(xmlEscape(entry.skill().description())).append("</description>\n");
            }
            sb.append("    <location>").append(xmlEscape(entry.skill().filePath())).append("</location>\n");
            sb.append("  </skill>\n");
        }
        sb.append("</available_skills>");
        return sb.toString();
    }

    private static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String buildTruncatedWarning(int included, int total, String compactSuffix) {
        if (included == total && (compactSuffix == null || compactSuffix.isBlank())) {
            return "⚠️ Skills catalog using compact format (descriptions omitted). Check skills.status for full inventory.";
        }
        return "⚠️ Skills truncated: included " + included + " of " + total
                + (compactSuffix == null ? "" : compactSuffix)
                + ". Check skills.status for full inventory.";
    }

    private static Set<String> normalizeSkillFilter(List<String> skillFilter) {
        if (skillFilter == null || skillFilter.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String name : skillFilter) {
            if (name != null && !name.isBlank()) {
                normalized.add(name.trim());
            }
        }
        return normalized;
    }

    private static Comparator<SkillEntry> skillEntryComparator() {
        return Comparator
                .comparingInt((SkillEntry entry) -> sourceOrder(entry.skill().source()))
                .thenComparing(entry -> entry.skill().legacyDirName(), Comparator.nullsLast(String::compareTo))
                .thenComparing(entry -> entry.skill().filePath(), Comparator.nullsLast(String::compareTo));
    }

    private static int sourceOrder(SkillSource source) {
        return switch (source) {
            case EXTRA, PLUGIN -> 0;
            case BUNDLED -> 1;
            case MANAGED -> 2;
            case AGENTS_PERSONAL -> 3;
            case AGENTS_PROJECT -> 4;
            case WORKSPACE_LEGACY -> 5;
            case WORKSPACE -> 6;
        };
    }

    private static String expandHomePrefix(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        if (raw.startsWith("~/")) {
            return Path.of(HOME, raw.substring(2)).toString();
        }
        if (raw.startsWith("$HOME/")) {
            return Path.of(HOME, raw.substring("$HOME/".length())).toString();
        }
        if (raw.startsWith("${HOME}/")) {
            return Path.of(HOME, raw.substring("${HOME}/".length())).toString();
        }
        return raw;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
