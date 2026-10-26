package net.rka.ai.agent;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the user is asked about when a coding job pauses for plan approval ({@link Approver#reviewPlan}):
 * the plan, how far the job got, and, for a revision, why there is a new plan. {@link Agent} reads it
 * from the paused graph's state before anything resumes (implement clears the revision reason and the
 * comment once it runs under the approved plan).
 *
 * @param plan           the plan to approve
 * @param stepsDone      how many steps from the start are finished (the job's {@code codingStep})
 * @param skippedSteps   1-based numbers of finished steps that were skipped ({@code codingSkippedSteps})
 * @param revisionReason why the graph asked for a new plan, e.g. a call the plan didn't cover; "" if none
 * @param userComment    the user's Revise comment that led to this plan; "" if none
 * @param revision       whether this replaces a plan the user saw before (approved, or sent back with Revise)
 * @param planFile       the Markdown file the plan was saved to for review; "" if none
 */
public record PlanReview(CodingPlan plan, int stepsDone, Set<Integer> skippedSteps, String revisionReason,
                         String userComment, boolean revision, String planFile) {

    /** Without a plan file. */
    public PlanReview(CodingPlan plan, int stepsDone, Set<Integer> skippedSteps, String revisionReason,
                      String userComment, boolean revision) {
        this(plan, stepsDone, skippedSteps, revisionReason, userComment, revision, "");
    }

    public PlanReview {
        planFile = planFile == null ? "" : planFile;
        Objects.requireNonNull(plan, "plan");
        skippedSteps = skippedSteps == null ? Set.of() : Set.copyOf(skippedSteps);
        revisionReason = revisionReason == null ? "" : revisionReason;
        userComment = userComment == null ? "" : userComment;
    }

    /** A first plan: nothing done yet, no revision. */
    public static PlanReview first(CodingPlan plan) {
        return new PlanReview(plan, 0, Set.of(), "", "", false);
    }

    /**
     * The plan as the dialog and the CLI show it: for a revision a "Revised plan: ..." line first, then
     * the plan with done steps marked ✓ and skipped ones ✗ ({@link CodingPlan#toMarkdown(int, Set)}).
     */
    public String toMarkdown() {
        StringBuilder out = new StringBuilder();
        if (revision) out.append("Revised plan: ").append(why()).append("\n\n");
        return out.append(plan.toMarkdown(stepsDone, new TreeSet<>(skippedSteps))).toString();
    }

    /** One line: the graph's reason (one per refused call, joined), else the user's comment. */
    private String why() {
        if (!revisionReason.isBlank()) return String.join("; ", revisionReason.strip().lines().toList());
        if (!userComment.isBlank()) return "you asked: " + userComment.strip();
        return "a new draft.";
    }
}
