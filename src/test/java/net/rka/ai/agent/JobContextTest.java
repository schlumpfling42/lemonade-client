package net.rka.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every phase of a coding job gets the parts of the opening that it uses, and the log says how big they were. */
class JobContextTest {

    private static Map<JobPhase.Part, String> parts() {
        Map<JobPhase.Part, String> parts = new EnumMap<>(JobPhase.Part.class);
        parts.put(JobPhase.Part.GOAL, "Goal: make it stop");
        parts.put(JobPhase.Part.FOLDER, "\nTrusted folder: /work");
        parts.put(JobPhase.Part.ENVIRONMENT, "\n\nEnvironment: os windows");
        parts.put(JobPhase.Part.MAP, "\n\nFiles: a.java b.java");
        parts.put(JobPhase.Part.GUIDE, "\n\nDesign guide: small methods");
        parts.put(JobPhase.Part.NOTES, "\n\nResearch notes: " + "n".repeat(5000));
        return parts;
    }

    @Test
    void anEditGetsTheGoalTheGuideAndCutNotesButNoFileListOrEnvironment() {
        String opening = JobContext.compose(JobPhase.EDIT, parts());

        assertTrue(opening.contains("Goal: make it stop"));
        assertTrue(opening.contains("Design guide"));
        assertFalse(opening.contains("Files: a.java"), "the file list is for choosing files");
        assertFalse(opening.contains("Environment:"), "an edit does not run commands");
        assertTrue(opening.contains("[... cut ...]"), "the notes are cut to the edit's limit");
        assertTrue(opening.length() < JobContext.INTRO.length() + 3_000 + 300);
    }

    @Test
    void thePlanGetsTheFileListTheEnvironmentAndTheNotesButNotTheGuide() {
        String opening = JobContext.compose(JobPhase.PLAN, parts());

        assertTrue(opening.contains("Files: a.java"));
        assertTrue(opening.contains("Environment:"));
        assertTrue(opening.contains("n".repeat(5000)), "the plan gets the notes whole");
        assertFalse(opening.contains("Design guide"));
    }

    @Test
    void theReportGetsOnlyTheGoalAndTheFolder() {
        String opening = JobContext.compose(JobPhase.EXPLAIN, parts());

        assertTrue(opening.endsWith("Trusted folder: /work"), opening);
        assertTrue(opening.length() < 400);
    }

    @Test
    void thePartsAreInTheSameOrderInEveryPhasesoTheyShareTheirStart() {
        String edit = JobContext.compose(JobPhase.EDIT, parts());
        String plan = JobContext.compose(JobPhase.PLAN, parts());
        int common = 0;
        while (common < Math.min(edit.length(), plan.length()) && edit.charAt(common) == plan.charAt(common)) common++;

        assertTrue(common > (JobContext.INTRO + "Goal: make it stop\nTrusted folder: /work").length() - 1, "goal and folder come first in both");
    }

    @Test
    void theLogLineNamesTheSizeOfEachPartSent() {
        String line = JobContext.describe(JobPhase.EDIT, parts());

        assertTrue(line.startsWith("   ctx edit: goal 0.0k"), line);
        assertTrue(line.contains("notes 3.0k"), line);
        assertFalse(line.contains("map"), line);
        assertTrue(line.endsWith("chars (opening)"), line);
    }
}
