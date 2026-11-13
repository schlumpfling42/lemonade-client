package net.rka.ai.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The evaluation framework itself: reading cases, checks, metrics and the report. (Running cases needs the real model: {@code runEval}.) */
class EvalFrameworkTest {

    private static final Set<String> CHECK_TYPES = Set.of("fileExists", "fileContains", "fileNotContains", "fileUnchanged", "command",
            "answerContains", "answerMatches");

    @Test
    void everyCaseOfTheRepositoryIsReadableAndUsesAnExistingFixtureAndKnownChecks() throws Exception {
        Path root = Path.of("evals");
        List<EvalCase> cases = EvalMain.load(root.resolve("cases"), "");

        assertFalse(cases.isEmpty());
        for (EvalCase c : cases) {
            assertTrue(c.fixture().isBlank() || Files.isDirectory(root.resolve("fixtures").resolve(c.fixture())), c.id() + ": fixture " + c.fixture());
            assertFalse(c.checks().isEmpty(), c.id() + " checks nothing");
            for (EvalCase.Check check : c.checks()) {
                assertTrue(CHECK_TYPES.contains(check.type()), c.id() + ": unknown check " + check.type());
            }
        }
        assertEquals(cases.size(), cases.stream().map(EvalCase::id).distinct().count(), "ids are unique");
    }

    @Test
    void aCaseThatExpectsChangesDoesNotPassOnTheUntouchedFixture(@TempDir Path dir) throws Exception {
        Path root = Path.of("evals");
        for (EvalCase c : EvalMain.load(root.resolve("cases"), "")) {
            if (!Boolean.TRUE.equals(c.expectCodingJob())) continue;
            Path project = Files.createDirectories(dir.resolve(c.id()));
            EvalRunner.copy(root.resolve("fixtures").resolve(c.fixture()), project);
            Path fixture = root.resolve("fixtures").resolve(c.fixture());
            // The cheap checks first; the commands (a compile and a run) only when nothing else fails already
            List<EvalCase.Check> files = c.checks().stream().filter(k -> !"command".equals(k.type())).toList();
            boolean failing = EvalChecks.run(files, project, fixture, "").stream().anyMatch(r -> !r.ok());
            if (!failing) {
                List<EvalCase.Check> commands = c.checks().stream().filter(k -> "command".equals(k.type())).toList();
                failing = EvalChecks.run(commands, project, fixture, "").stream().anyMatch(r -> !r.ok());
            }
            assertTrue(failing, c.id() + " passes without the agent doing anything");
        }
    }

    @Test
    void aCaseFileHasDefaults() throws Exception {
        EvalCase c = EvalCase.parse("{\"id\":\"x\",\"prompt\":\"do it\"}");
        assertEquals(EvalCase.DEFAULT_TIMEOUT_MINUTES, c.timeoutMinutes());
        assertTrue(c.checks().isEmpty());
        assertEquals(null, c.expectCodingJob());
    }

    @Test
    void checksJudgeTheFilesTheAnswerAndACommand(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path fixture = Files.createDirectories(dir.resolve("fixture"));
        Files.writeString(project.resolve("A.txt"), "hello world");
        Files.writeString(project.resolve("B.txt"), "same");
        Files.writeString(fixture.resolve("B.txt"), "same");
        List<EvalCase.Check> checks = List.of(
                new EvalCase.Check("fileExists", "A.txt", null, null, null, null, null),
                new EvalCase.Check("fileExists", "Z.txt", null, null, null, null, null),
                new EvalCase.Check("fileContains", "A.txt", "world", null, null, null, null),
                new EvalCase.Check("fileContains", "A.txt", null, "h\\w+o", null, null, null),
                new EvalCase.Check("fileNotContains", "A.txt", "mars", null, null, null, null),
                new EvalCase.Check("fileNotContains", "A.txt", "hello", null, null, null, null),
                new EvalCase.Check("fileUnchanged", "B.txt", null, null, null, null, null),
                new EvalCase.Check("fileUnchanged", "A.txt", null, null, null, null, null),
                new EvalCase.Check("answerContains", null, "SUM", null, null, null, null),
                new EvalCase.Check("answerMatches", null, null, "adds|plus", null, null, null),
                new EvalCase.Check("command", null, null, null, "echo hi", 0, "hi"),
                new EvalCase.Check("command", null, null, null, "echo hi", 0, "nope"),
                new EvalCase.Check("nonsense", null, null, null, null, null, null));

        List<Boolean> ok = EvalChecks.run(checks, project, fixture, "It computes the sum.").stream().map(EvalChecks.Result::ok).toList();

        assertEquals(List.of(true, false, true, true, true, false, true, false, true, false, true, false, false), ok);
    }

    @Test
    void metricsAreReadFromTheLog() {
        List<String> log = List.of(
                "2026-11-10 10:00:00.000  ⇢ triage: coding work of its own, starting the coding skill",
                "2026-11-10 10:00:01.000  ← LLM #1 [skill/coding/plan] 12.5 s in all, tokens in 3,000 / out 400 → says: {",
                "2026-11-10 10:00:02.000  ← LLM #2 [skill/coding/edit] 7.5 s in all, tokens in ? / out ? → says: x",
                "2026-11-10 10:00:03.000     ctx edit: goal 0.1k · notes 2.0k = 2.6k chars (opening)",
                "2026-11-10 10:00:04.000  ⚙ tool readFile [x] {} → ok, 10 chars, 3 ms",
                "2026-11-10 10:00:05.000  ⚙ tool readSection [x] {} → error: no section",
                "2026-11-10 10:00:06.000  ✎ A.java · A.run() (replace): applied, structure checked, written, 3 lines written",
                "2026-11-10 10:00:07.000  ✎✗ A.java · A.x() (replace): refused, nothing written: why",
                "2026-11-10 10:00:08.000     ↺ readFile gave the same result as before",
                "2026-11-10 10:00:09.000  ✗ LLM #3 [agent] failed after 1.0 s: closed");

        EvalMetrics m = EvalMetrics.parse(log);

        assertEquals(2, m.llmCalls());
        assertEquals(20.0, m.llmSeconds(), 0.001);
        assertEquals(3000, m.tokensIn());
        assertEquals(400, m.tokensOut());
        assertEquals(2, m.toolCalls());
        assertEquals(1, m.toolErrors());
        assertEquals(1, m.changesApplied());
        assertEquals(1, m.changesRefused());
        assertEquals(1, m.repeats());
        assertEquals(1, m.llmFailures());
        assertEquals(2.6, m.openingKchars(), 0.001);
        assertTrue(m.triagedCoding());
        assertEquals(Map.of("skill/coding/plan", 1, "skill/coding/edit", 1), m.callsByPhase());
    }

    @Test
    void theReportShowsRegressionsAndTheDifferenceToAnEarlierRun() {
        EvalMetrics fast = EvalMetrics.parse(List.of("x  ← LLM #1 [agent] 10.0 s in all, tokens in 1,000 / out 10"));
        EvalMetrics slow = EvalMetrics.parse(List.of("x  ← LLM #1 [agent] 30.0 s in all, tokens in 3,000 / out 10"));
        CaseResult before = new CaseResult("a", "", true, 20, false, "", "ok", "", List.of(), fast, List.of());
        CaseResult after = new CaseResult("a", "", false, 60, false, "", "", "", List.of(new EvalChecks.Result(
                new EvalCase.Check("fileExists", "A.txt", null, null, null, null, null), false, "no such file")), slow, List.of());

        String report = EvalReport.markdown(new EvalReport.Summary("new", "t", "m", List.of(after)),
                new EvalReport.Summary("old", "t", "m", List.of(before)));

        assertTrue(report.contains("| a | FAIL (REGRESSION) | 60 s (+200%) |"), report);
        assertTrue(report.contains("fileExists A.txt: no such file"), report);
        assertTrue(report.contains("0 of 1 cases passed (old: 1 of 1)"), report);
    }
}
