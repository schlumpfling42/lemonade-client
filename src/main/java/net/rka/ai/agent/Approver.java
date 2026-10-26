package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import net.rka.ai.agent.skills.SkillAnswer;
import net.rka.ai.agent.skills.SkillQuestion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Asks the user whether tool calls marked {@code @RequiresApproval} may run. Called on the
 * agent's worker thread while the graph is paused before the approval subgraph; it may block
 * until the user answers.
 *
 * <h2>How it works</h2>
 * {@link Agent} doesn't know whether it runs in a window or a terminal, so asking the user is
 * delegated to this interface ("dependency inversion"). Two implementations exist:
 * <ul>
 *   <li>{@code ChatWindow.approve}: shows one {@code ApprovalDialog} per call on the JavaFX thread and
 *       blocks the worker thread until all answers are in (via a {@code CompletableFuture});</li>
 *   <li>{@code ConsoleApprover}: prints the call and reads {@code y}/{@code N} from stdin.</li>
 * </ul>
 * It's a functional interface (one abstract method), so a lambda works too, as {@link #DENY_ALL} shows;
 * a lambda can't answer plans, skill questions or skill drafts, so {@link #reviewPlan} denies them,
 * {@link #askUser} dismisses them and {@link #reviewSkill} cancels them unless overridden.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Richer answers than yes/no: "yes, and trust this command from now on", or "edit the command
 *       before running it".</li>
 *   <li>A reason for each call (why it needs approval: untrusted command vs file change after web content).</li>
 *   <li>Timeouts: an unattended dialog blocks the run forever; it could auto-deny after N minutes.</li>
 *   <li>Remote approval (a phone notification) for long-running unattended tasks.</li>
 * </ul>
 */
@FunctionalInterface
public interface Approver {

    /** Returns a decision per call id: true to run the call, false to deny it. */
    Map<String, Boolean> review(List<ToolExecutionRequest> calls);

    /**
     * Asks the user about a coding job's plan: approve it (its commands and file changes then run
     * without asking per call), revise it with a comment, or deny it. Called while the graph is
     * paused before the coding subgraph's {@code planApproval} node; it may block until the user answers.
     * The review carries the plan with the steps done so far and, for a revision, why it changed;
     * {@link PlanReview#toMarkdown()} renders all of it.
     * <p>
     * The default denies, so an approver that doesn't know about plans (a lambda, {@link #DENY_ALL})
     * never lets a job change anything; the CLI and the chat window override it.
     */
    default PlanDecision reviewPlan(PlanReview review) {
        return PlanDecision.DENIED;
    }

    /**
     * Asks the user a skill's question (an {@code askUser} node): one of {@link SkillQuestion#choices}
     * and an optional comment. Called while the graph is paused before that node; it may block until
     * the user answers.
     * <p>
     * The default dismisses the question, which finishes the skill with an error ("cancelled by the
     * user"): an approver that doesn't know about skills never picks a choice for the user, not even
     * the first one. The CLI and the chat window override it.
     */
    default SkillAnswer askUser(SkillQuestion question) {
        return SkillAnswer.dismissed();
    }

    /**
     * Asks the user about a skill the skill builder drafted ({@code buildSkill}): save it, revise it
     * with a comment, or cancel. Called while the graph is paused before the builder's {@code review}
     * node; it may block until the user answers. {@link SkillDraft#toMarkdown()} renders the draft
     * (its steps, prompts, tools and whether saving replaces an existing skill), {@link SkillDraft#json()}
     * is the file Save writes.
     * <p>
     * The default cancels, so an approver that doesn't know about skills never saves one (a skill file
     * changes what the agent can do); the CLI and the chat window override it.
     */
    default SkillDecision reviewSkill(SkillDraft draft) {
        return SkillDecision.CANCELLED;
    }

    /** Denies everything; used when there's no one to ask. */
    Approver DENY_ALL = calls -> {
        Map<String, Boolean> decisions = new HashMap<>();
        calls.forEach(call -> decisions.put(call.id(), false));
        return decisions;
    };
}
