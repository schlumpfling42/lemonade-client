package net.rka.ai.agent.skills;

import net.rka.ai.agent.AgentGraph;
import net.rka.ai.agent.AgentGraph.State;
import net.rka.ai.agent.skills.SkillFile.NodeSpec;
import net.rka.ai.tools.ToolRegistry;
import org.bsc.langgraph4j.action.NodeActionWithConfig;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A kind of node a skill file can use ({@code model}, {@code askUser}, ..., {@code coding.*}).
 * Skill files hold no code: each node names a kind the app provides and fills in its fields.
 *
 * <h2>What a kind tells the validator</h2>
 * <ul>
 *   <li>{@link #outcomes}: the named ways the node can end, which the file wires to the next node
 *       with {@code "on"} (or {@code "next"} for a single outcome). Every kind also has
 *       {@link #STOPPED} (an error or a limit), which is never listed here and never needs wiring:
 *       unwired, it ends the skill with an error result. A kind with no outcomes ends the skill
 *       (it is terminal, like {@code finish}).</li>
 *   <li>{@link #check}: problems with the node's own fields (missing prompt, unknown field, ...).</li>
 *   <li>{@link #reads} / {@link #writes}: the variables the node uses and sets, for the data-flow
 *       check (every variable used is set on every path to the node).</li>
 * </ul>
 * <h2>What a kind tells the graph builder</h2>
 * {@link #action} is what the node does when it runs, {@link #route} which outcome it ended with;
 * {@link SkillGraphBuilder} wires the outcomes to the next nodes.
 */
public interface NodeKind {

    /** The outcome every kind has implicitly: the node failed or hit a limit. */
    String STOPPED = "stopped";

    /** The name skill files use in {@code "kind"}. */
    String name();

    /**
     * The node's outcomes, without {@link #STOPPED}. May depend on the fields (e.g. {@code askUser}'s
     * choices); for a node whose fields are broken, return what can be read ({@link #check} reports the rest).
     */
    Set<String> outcomes(NodeSpec node);

    /** Problems with the node's fields, each a full sentence naming the node id and the field; empty when fine. */
    List<String> check(NodeSpec node, SkillContext context);

    /** Variables the node sets (its {@code output}, {@code set} values), as top-level names. */
    Set<String> writes(NodeSpec node);

    /** Variable paths the node's templates use, as {@link Templates#references} lists them. */
    Set<String> reads(NodeSpec node);

    /**
     * How the node runs: the action of its graph node, built once per node when the skill's subgraph
     * is built ({@link SkillGraphBuilder}). It returns the node's state update, like every node of the
     * agent graph. A failure it can explain (a model error, a dismissed question) is returned as
     * {@link AgentGraph.State#SKILL_STOP_REASON}, so the node's {@link #STOPPED} route runs.
     * <p>
     * The builder adds what every kind shares around it: counting the visit against {@code maxSteps},
     * and the tool step for calls the node's reply leaves open in the skill's transcript.
     */
    NodeActionWithConfig<State> action(NodeSpec node, SkillRuntime runtime);

    /**
     * The outcome after the node ran, one of {@link #outcomes} (or {@link #STOPPED}); the builder maps it
     * to the next node with the file's wiring. Not called for a kind without outcomes (it ends the skill),
     * nor after the node stopped or left tool calls open.
     *
     * @param runtime the skill the node belongs to, e.g. for its limits (a coding job's verify attempts)
     */
    String route(NodeSpec node, SkillRuntime runtime, State state);

    /**
     * The names of the skill's {@code limits} (besides {@code maxSteps} and {@code maxToolRounds}) that
     * nodes of this kind read, e.g. {@code maxVerifyAttempts}; the validator rejects any other name, so a
     * misspelled limit is an error instead of silently not applying.
     */
    default Set<String> limitNames() {
        return Set.of();
    }

    /**
     * Whether the graph pauses before a node of this kind, so {@code Agent} can ask the user and write the
     * answer into the state first ({@link SkillGraphBuilder#pauseNodes}): {@code askUser},
     * {@code coding.planApproval}.
     */
    default boolean pausesBefore() {
        return false;
    }

    /**
     * When a node of this kind is the skill's {@code start}: what the other calls of the reply that
     * started the skill are answered with, instead of the generic "Not run: a skill started; ...".
     * The skill's built-in start node answers them either way; this only lets a kind keep its own words
     * (the coding job's "a coding job started").
     */
    default Optional<String> otherCallsAnswer() {
        return Optional.empty();
    }

    /**
     * What a kind's {@link #check} may look at besides the node.
     *
     * @param skill   the whole skill file
     * @param tools   the app's tools
     * @param builtIn whether the skill ships with the app
     */
    record SkillContext(SkillFile skill, ToolRegistry tools, boolean builtIn) {
    }
}
