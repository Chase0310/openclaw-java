package com.openclaw.agent.skills;

import com.openclaw.agent.skills.SkillTypes.SkillEntry;
import com.openclaw.agent.skills.SkillTypes.SkillPromptMode;
import com.openclaw.agent.skills.SkillTypes.SkillSnapshot;
import com.openclaw.agent.skills.SkillTypes.SkillSource;
import com.openclaw.common.config.OpenClawConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadSkillEntries_workspaceOverridesLegacyWorkspace() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        String skillName = "shared-" + UUID.randomUUID().toString().substring(0, 8);
        Path legacySkill = workspace.resolve(".openclaw/skills").resolve(skillName);
        Path workspaceSkill = workspace.resolve("skills").resolve(skillName);
        writeSkill(legacySkill, "legacy description", "legacy body", null);
        writeSkill(workspaceSkill, "workspace description", "workspace body", null);

        List<SkillEntry> entries = SkillLoader.loadSkillEntries(
                workspace.toString(),
                null,
                null,
                configWithLimits(10_000));

        SkillEntry resolved = entries.stream()
                .filter(entry -> skillName.equals(entry.skill().name()))
                .findFirst()
                .orElseThrow();

        assertEquals(SkillSource.WORKSPACE, resolved.skill().source());
        assertEquals(workspaceSkill.resolve("SKILL.md").toRealPath().toString(), resolved.skill().filePath());
        assertEquals("workspace description", resolved.skill().description());
    }

    @Test
    void loadSkillsFromDir_rejectsSymlinkedSkillFile() throws Exception {
        Path root = tempDir.resolve("skills-root");
        Path skillDir = root.resolve("symlink-skill");
        Files.createDirectories(skillDir);
        Path target = tempDir.resolve("real-skill.md");
        Files.writeString(target, """
                ---
                description: linked
                ---
                linked body
                """);
        try {
            Files.createSymbolicLink(skillDir.resolve("SKILL.md"), target);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("Symlinks not supported in this environment");
        }

        List<com.openclaw.agent.skills.SkillTypes.Skill> skills = SkillLoader.loadSkillsFromDir(root, SkillSource.WORKSPACE);
        assertTrue(skills.isEmpty());
    }

    @Test
    void resolveSkillSnapshotForRun_usesCatalogPromptAndSkipsDisableModelInvocation() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        String visible = "visible-" + UUID.randomUUID().toString().substring(0, 8);
        String hidden = "hidden-" + UUID.randomUUID().toString().substring(0, 8);
        Path visibleDir = workspace.resolve("skills").resolve(visible);
        Path hiddenDir = workspace.resolve("skills").resolve(hidden);
        writeSkill(visibleDir, "Visible skill description", "VISIBLE BODY TOKEN", null);
        writeSkill(hiddenDir, "Hidden skill description", "HIDDEN BODY TOKEN", """
                disable-model-invocation: true
                """);

        SkillSnapshot snapshot = SkillLoader.resolveSkillSnapshotForRun(
                workspace.toString(),
                null,
                null,
                configWithLimits(10_000));

        assertEquals(SkillPromptMode.FULL, snapshot.promptMode());
        assertTrue(snapshot.prompt().contains("<available_skills>"));
        assertTrue(snapshot.prompt().contains(visible));
        assertFalse(snapshot.prompt().contains(hidden));
        assertFalse(snapshot.prompt().contains("VISIBLE BODY TOKEN"));
        assertFalse(snapshot.prompt().contains("HIDDEN BODY TOKEN"));
        assertTrue(snapshot.injectedSkillLocations().contains(visibleDir.resolve("SKILL.md").toRealPath().toString()));
        assertFalse(snapshot.injectedSkillLocations().contains(hiddenDir.resolve("SKILL.md").toRealPath().toString()));
    }

    @Test
    void resolveSkillSnapshotForRun_switchesToCompactWhenFullExceedsBudget() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        String skillName = "compact-" + UUID.randomUUID().toString().substring(0, 8);
        String longDescription = "x".repeat(600);
        writeSkill(
                workspace.resolve("skills").resolve(skillName),
                longDescription,
                "body",
                null);

        SkillSnapshot fullSnapshot = SkillLoader.resolveSkillSnapshotForRun(
                workspace.toString(),
                null,
                null,
                configWithLimits(10_000));
        String compactPrompt = fullSnapshot.prompt()
                .replace("matches its description", "matches its name")
                .replace("    <description>" + longDescription + "</description>\n", "");

        SkillSnapshot snapshot = SkillLoader.resolveSkillSnapshotForRun(
                workspace.toString(),
                null,
                null,
                configWithLimits(compactPrompt.length() + 160));

        assertEquals(SkillPromptMode.COMPACT, snapshot.promptMode());
        assertTrue(snapshot.prompt().contains("<location>"));
        assertFalse(snapshot.prompt().contains("<description>"));
    }

    @Test
    void resolveSkillSnapshotForRun_truncatesWhenCompactStillExceedsBudget() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        for (int i = 0; i < 3; i++) {
            writeSkill(
                    workspace.resolve("skills").resolve("truncate-" + i),
                    "Description " + i,
                    "body-" + i,
                    null);
        }

        SkillSnapshot snapshot = SkillLoader.resolveSkillSnapshotForRun(
                workspace.toString(),
                null,
                null,
                configWithLimits(420));

        assertEquals(SkillPromptMode.TRUNCATED, snapshot.promptMode());
        assertTrue(snapshot.prompt().contains("Skills truncated"));
        assertTrue(snapshot.injectedCount() < 3);
    }

    @Test
    void normalizeSkillLocation_handlesHomeAndRelativePaths() throws Exception {
        Path homeDir = Path.of(System.getProperty("user.home"));
        Path skillDir = tempDir.resolve("skill-dir");
        Files.createDirectories(skillDir);
        Path relativeTarget = skillDir.resolve("notes.md");
        Files.writeString(relativeTarget, "hello");

        String homeChild = "skill-loader-" + UUID.randomUUID().toString().substring(0, 8) + "/SKILL.md";
        String tilde = SkillLoader.normalizeSkillLocation("~/"+ homeChild, null, null);
        String envStyle = SkillLoader.normalizeSkillLocation("$HOME/" + homeChild, null, null);
        String envBraceStyle = SkillLoader.normalizeSkillLocation("${HOME}/" + homeChild, null, null);
        String relative = SkillLoader.resolveSkillReadPath("notes.md", skillDir.toString(), null);

        assertEquals(homeDir.resolve(homeChild).toAbsolutePath().normalize().toString(), tilde);
        assertEquals(tilde, envStyle);
        assertEquals(tilde, envBraceStyle);
        assertEquals(relativeTarget.toRealPath().toString(), relative);
    }

    private OpenClawConfig configWithLimits(int maxPromptChars) {
        OpenClawConfig config = new OpenClawConfig();
        OpenClawConfig.SkillsConfig skills = new OpenClawConfig.SkillsConfig();
        OpenClawConfig.SkillsLimitsConfig limits = new OpenClawConfig.SkillsLimitsConfig();
        limits.setMaxCandidatesPerRoot(50);
        limits.setMaxSkillsLoadedPerSource(50);
        limits.setMaxSkillsInPrompt(50);
        limits.setMaxSkillsPromptChars(maxPromptChars);
        limits.setMaxSkillFileBytes(256_000);
        limits.setCompactWarningOverhead(150);
        skills.setLimits(limits);
        config.setSkills(skills);
        return config;
    }

    private void writeSkill(Path skillDir, String description, String body, String extraFrontmatter) throws IOException {
        Files.createDirectories(skillDir);
        StringBuilder frontmatter = new StringBuilder();
        frontmatter.append("---\n");
        frontmatter.append("description: ").append(description).append("\n");
        if (extraFrontmatter != null && !extraFrontmatter.isBlank()) {
            frontmatter.append(extraFrontmatter.strip()).append("\n");
        }
        frontmatter.append("---\n");
        frontmatter.append(body).append("\n");
        Files.writeString(skillDir.resolve("SKILL.md"), frontmatter.toString());
    }
}
