package net.rka.ai.cli;

import net.rka.ai.agent.CodingPlan;
import net.rka.ai.agent.PlanDecision;
import net.rka.ai.agent.PlanReview;
import net.rka.ai.agent.SkillDecision;
import net.rka.ai.agent.SkillDraft;
import net.rka.ai.agent.skills.SkillAnswer;
import net.rka.ai.agent.skills.SkillFile;
import net.rka.ai.agent.skills.SkillQuestion;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How {@link ConsoleApprover#reviewPlan} shows the plan and reads the user's answer from the terminal. */
class ConsoleApproverTest {

    private static final CodingPlan PLAN = new CodingPlan("Add a README", List.of(), List.of(), List.of(), List.of());

    /** Asks about the plan with {@code input} as the typed text. */
    private static PlanDecision answer(String input) {
        return answer(PlanReview.first(PLAN), input);
    }

    private static PlanDecision answer(PlanReview review, String input) {
        return new ConsoleApprover(new BufferedReader(new StringReader(input))).reviewPlan(review);
    }

    @Test
    void yApproves() {
        assertEquals(new PlanDecision(PlanDecision.Kind.APPROVE, ""), answer("y\n"));
    }

    @Test
    void rWithCommentRevises() {
        assertEquals(new PlanDecision(PlanDecision.Kind.REVISE, "also add tests"), answer("r also add tests\n"));
    }

    @Test
    void anythingElseDenies() {
        assertEquals(PlanDecision.Kind.DENY, answer("\n").kind());
        assertEquals(PlanDecision.Kind.DENY, answer("n\n").kind());
        assertEquals(PlanDecision.Kind.DENY, answer("r\n").kind()); // nothing to revise with
        assertEquals(PlanDecision.Kind.DENY, answer("").kind());    // input ran out
    }

    @Test
    void revisedPlanShowsTheReasonAndTheMadeChanges() {
        CodingPlan plan = CodingPlan.parse("{\"summary\":\"Readme\",\"changes\":["
                + "{\"file\":\"README.md\",\"section\":\"Intro\",\"action\":\"replace\",\"intent\":\"Write it\"},"
                + "{\"file\":\"docs/b.md\",\"action\":\"create\",\"intent\":\"Link it\"}]}");
        PlanReview review = new PlanReview(plan, Set.of(0), Set.of(), "the build reports problems in docs/b.md", "", true);
        PrintStream original = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
        try {
            answer(review, "y\n");
        } finally {
            System.setOut(original);
        }
        String out = printed.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Revised plan: the build reports problems in docs/b.md"), out);
        assertTrue(out.contains("1. ✓ replace `README.md` · `Intro`: Write it") && out.contains("2. create `docs/b.md`: Link it"), out);
    }

    private static final SkillQuestion QUESTION = new SkillQuestion("tidy", "ask", "Delete the old notes?", List.of("Yes", "No", "Skip"));

    /** Asks the skill question with {@code input} as the typed text; prints nothing to the test output. */
    private static SkillAnswer ask(String input) {
        PrintStream original = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            return new ConsoleApprover(new BufferedReader(new StringReader(input))).askUser(QUESTION);
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void askUserReadsAChoiceAndComment() {
        assertEquals(new SkillAnswer("No", ""), ask("2\n"));
        assertEquals(new SkillAnswer("Skip", "next time"), ask("3 next time\n"));
        // "c <comment>" is the first choice with a comment
        assertEquals(new SkillAnswer("Yes", "but keep a.txt"), ask("c but keep a.txt\n"));
        // any whitespace separates the number (or c) from the comment, a TAB too
        assertEquals(new SkillAnswer("Skip", "next time"), ask("3\tnext time\n"));
        assertEquals(new SkillAnswer("Yes", "tabbed"), ask("c\ttabbed\n"));
        // the question and the numbered choices are printed
        PrintStream original = System.out;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        System.setOut(new PrintStream(printed, true, StandardCharsets.UTF_8));
        try {
            new ConsoleApprover(new BufferedReader(new StringReader("1\n"))).askUser(QUESTION);
        } finally {
            System.setOut(original);
        }
        String out = printed.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("Delete the old notes?") && out.contains("1. Yes") && out.contains("3. Skip"), out);
    }

    @Test
    void askUserEofIsDismissed() {
        assertTrue(ask("").isDismissed());      // input ran out
        assertTrue(ask("\n").isDismissed());
        assertTrue(ask("0\n").isDismissed());   // out of range
        assertTrue(ask("4 hi\n").isDismissed());
        assertTrue(ask("c\n").isDismissed());   // no comment to give
        assertTrue(ask("yes\n").isDismissed()); // not a number
    }

    private static final SkillFile SKILL = SkillFile.parse("""
            { "name": "greeter", "description": "Greets the user by name. Use when the user wants a greeting.",
              "start": "hi",
              "nodes": [ { "id": "hi", "kind": "finish", "result": "Hello" } ] }
            """);

    private static SkillDecision review(String input) {
        SkillDraft draft = new SkillDraft(SKILL, "{}", "hi -> end", List.of(), false, false, null);
        PrintStream original = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            return new ConsoleApprover(new BufferedReader(new StringReader(input))).reviewSkill(draft);
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void reviewSkillAnswers() {
        assertEquals(SkillDecision.Kind.SAVE, review("s\n").kind());
        assertEquals(new SkillDecision(SkillDecision.Kind.REVISE, "ask first"), review("r ask first\n"));
        assertEquals(SkillDecision.Kind.CANCEL, review("\n").kind());
        assertEquals(SkillDecision.Kind.CANCEL, review("r\n").kind()); // nothing to revise with
        assertEquals(SkillDecision.Kind.CANCEL, review("x\n").kind());
        assertEquals(SkillDecision.Kind.CANCEL, review("").kind());    // input ran out
    }
}
