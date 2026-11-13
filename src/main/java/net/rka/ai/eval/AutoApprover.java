package net.rka.ai.eval;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import net.rka.ai.agent.Approver;
import net.rka.ai.agent.PlanDecision;
import net.rka.ai.agent.PlanReview;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers the questions an evaluation cannot ask a person: plans are approved, commands that ask for approval run (they start in the
 * case's own copy of the fixture), SSH never does. Everything it was asked is kept, for the report.
 */
final class AutoApprover implements Approver {

    /** Tools that are never approved in an evaluation. */
    private static final Set<String> REFUSED = Set.of("runRemoteCommand");

    private final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile int plansApproved;

    @Override
    public Map<String, Boolean> review(List<ToolExecutionRequest> calls) {
        Map<String, Boolean> decisions = new HashMap<>();
        for (ToolExecutionRequest call : calls) {
            boolean ok = !REFUSED.contains(call.name());
            asked.add((ok ? "approved " : "refused ") + call.name() + " " + call.arguments());
            decisions.put(call.id(), ok);
        }
        return decisions;
    }

    @Override
    public PlanDecision reviewPlan(PlanReview review) {
        plansApproved++;
        // A plan that came back for a second look (a revision) is approved too: an evaluation judges the result, not the dialog
        return new PlanDecision(PlanDecision.Kind.APPROVE, "");
    }

    /** Forgets what the last case was asked. */
    void reset() {
        asked.clear();
        plansApproved = 0;
    }

    List<String> asked() {
        return List.copyOf(asked);
    }

    int plansApproved() {
        return plansApproved;
    }
}
