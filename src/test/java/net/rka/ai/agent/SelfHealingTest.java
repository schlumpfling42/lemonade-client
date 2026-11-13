package net.rka.ai.agent;

import net.rka.ai.agent.CodingPlan.Action;
import net.rka.ai.agent.CodingPlan.Change;
import net.rka.ai.tools.code.java.JavaStructure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Found by the evaluations: a call is not a method, and a patch that did not fit is not tried again as it is. */
class SelfHealingTest {

    @Test
    void aStatementThatLooksLikeAMethodToTheLenientReaderIsAStatement() {
        assertFalse(CodingGraph.declaresMethod("System.out.println(\"Customer: \" + customer);"));
        assertFalse(CodingGraph.declaresMethod("list.add(x);\nint y = 5;"));
        assertFalse(CodingGraph.declaresMethod("if (a) {\n    b();\n}"));
        assertTrue(CodingGraph.declaresMethod("public int count() {\n    return 1;\n}"));
        assertTrue(CodingGraph.declaresMethod("void run() {}"));
        assertTrue(JavaStructure.readsAsStatements("System.out.println(x);"));
        assertFalse(JavaStructure.readsAsStatements("void run() {}"));
    }

    private static Ledger ledger(Ledger.Status... statuses) {
        return new Ledger(java.util.Arrays.stream(statuses).map(s -> new Ledger.Entry(s, s == Ledger.Status.FAILED ? "does not fit" : "")).toList());
    }

    @Test
    void aPatchThatFailedBecomesAReplaceThatTheModelWrites() {
        Change patch = new Change("A.java", "A.constants", Action.PATCH, "add OVERNIGHT", "", " X,\n+Y");
        Change other = new Change("B.java", "B.x", Action.REPLACE, "something", "");
        CodingPlan plan = new CodingPlan("s", List.of(), List.of(), List.of(patch, other), List.of());
        Ledger before = ledger(Ledger.Status.FAILED, Ledger.Status.APPLIED);
        Ledger after = CodingGraph.retryFailed(before);

        CodingPlan rewritten = CodingGraph.effectivePlan(plan, after);

        Change now = rewritten.changes().get(0);
        assertEquals(Action.REPLACE, now.action());
        assertEquals("A.constants", now.section());
        assertTrue(now.intent().contains("add OVERNIGHT") && now.intent().contains("does not fit") && now.intent().contains("+Y"), now.intent());
        assertSame(other, rewritten.changes().get(1));
        assertEquals(Action.PATCH, plan.changes().get(0).action(), "the approved plan itself is not changed");
        assertSame(plan, CodingGraph.effectivePlan(plan, before), "a patch that has not failed twice is worked as planned");
    }

    @Test
    void aPlanWithoutFailedPatchesIsLeftAlone() {
        Change replace = new Change("A.java", "A.x", Action.REPLACE, "x", "");
        CodingPlan plan = new CodingPlan("s", List.of(), List.of(), List.of(replace, replace), List.of());
        Ledger before = ledger(Ledger.Status.FAILED, Ledger.Status.APPLIED);

        assertSame(plan, CodingGraph.effectivePlan(plan, CodingGraph.retryFailed(before)));
    }
}
