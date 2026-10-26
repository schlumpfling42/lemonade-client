package net.rka.ai.agent;

import java.util.Objects;

/**
 * The user's answer to a coding job's plan, from {@link Approver#reviewPlan}. {@link Agent} writes
 * it into the paused graph's state ({@code codingDecision}, {@code codingComment}) and resumes;
 * the coding subgraph's router after {@code planApproval} then implements, revises or ends the job.
 *
 * @param kind    approve, revise or deny
 * @param comment what should change, with {@link Kind#REVISE}; "" otherwise (null is read as "")
 */
public record PlanDecision(Kind kind, String comment) {

    public enum Kind {
        /** Run the plan: its commands and file changes run without asking per call. */
        APPROVE,
        /** Write a new plan, taking the comment into account; it is asked about again. */
        REVISE,
        /** End the job without changing anything. */
        DENY
    }

    public PlanDecision {
        Objects.requireNonNull(kind, "kind");
        comment = comment == null ? "" : comment;
    }

    /** Denies the plan; used when there's no one to ask. */
    public static final PlanDecision DENIED = new PlanDecision(Kind.DENY, "");

    /** The value of the state key {@code codingDecision} that the coding subgraph's router reads. */
    String stateValue() {
        return switch (kind) {
            case APPROVE -> CodingGraph.APPROVE;
            case REVISE -> CodingGraph.REVISE;
            case DENY -> CodingGraph.DENY;
        };
    }
}
