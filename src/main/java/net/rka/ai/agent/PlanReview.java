package net.rka.ai.agent;

import java.util.Objects;
import java.util.Set;

/**
 * What the user is asked about when a coding job pauses for plan approval ({@link Approver#reviewPlan}):
 * the plan, which of its changes are already made or failed, and, for a revision, why there is a new plan.
 * {@link Agent} reads it from the paused graph's state before anything resumes (the plan approval step clears the
 * revision reason and the comment once it runs).
 *
 * @param plan           the plan to approve
 * @param applied        0-based indexes of the changes already made (from the job's ledger)
 * @param failed         0-based indexes of the changes that could not be made
 * @param revisionReason why the graph asked for a new plan, e.g. a fix in a file the plan doesn't change; "" if none
 * @param userComment    the user's Revise comment that led to this plan; "" if none
 * @param revision       whether this replaces a plan the user saw before (approved, or sent back with Revise)
 * @param planFile       the Markdown file the plan was saved to for review; "" if none
 * @param answer         the answer to the user's question about the plan (the revise step), shown above the plan; "" if none
 */
public record PlanReview(CodingPlan plan, Set<Integer> applied, Set<Integer> failed, String revisionReason,
                         String userComment, boolean revision, String planFile, String answer) {

    /** Without an answer. */
    public PlanReview(CodingPlan plan, Set<Integer> applied, Set<Integer> failed, String revisionReason,
                      String userComment, boolean revision, String planFile) {
        this(plan, applied, failed, revisionReason, userComment, revision, planFile, "");
    }

    /** Without a plan file. */
    public PlanReview(CodingPlan plan, Set<Integer> applied, Set<Integer> failed, String revisionReason,
                      String userComment, boolean revision) {
        this(plan, applied, failed, revisionReason, userComment, revision, "");
    }

    public PlanReview {
        planFile = planFile == null ? "" : planFile;
        answer = answer == null ? "" : answer;
        Objects.requireNonNull(plan, "plan");
        applied = applied == null ? Set.of() : Set.copyOf(applied);
        failed = failed == null ? Set.of() : Set.copyOf(failed);
        revisionReason = revisionReason == null ? "" : revisionReason;
        userComment = userComment == null ? "" : userComment;
    }

    /** A first plan: nothing done yet, no revision. */
    public static PlanReview first(CodingPlan plan) {
        return new PlanReview(plan, Set.of(), Set.of(), "", "", false);
    }

    /**
     * The plan as the dialog and the CLI show it: for a revision a "Revised plan: ..." line first, then
     * the plan with made changes marked ✓ and failed ones ✗ ({@link CodingPlan#toMarkdown(Set, Set)}).
     */
    public String toMarkdown() {
        StringBuilder out = new StringBuilder();
        if (!answer.isBlank()) out.append("**Answer to your comment:** ").append(answer.strip()).append("\n\n");
        if (revision && answer.isBlank()) out.append("Revised plan: ").append(why()).append("\n\n");
        return out.append(plan.toMarkdown(applied, failed)).toString();
    }

    /** One line: the graph's reason (one per refused call, joined), else the user's comment. */
    private String why() {
        if (!revisionReason.isBlank()) return String.join("; ", revisionReason.strip().lines().toList());
        if (!userComment.isBlank()) return "you asked: " + userComment.strip();
        return "a new draft.";
    }
}
