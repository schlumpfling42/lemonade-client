package net.rka.ai.agent.skills;

import dev.langchain4j.data.message.ToolExecutionResultMessage;
import net.rka.ai.agent.AgentGraph.State;
import net.rka.ai.agent.skills.SkillFile.NodeSpec;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeActionWithConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns a validated skill file into a LangGraph4j subgraph on the agent graph's state:
 *
 *   START -> start -> (the file's nodes, wired by their outcomes) -> finish -> END
 *   every file node --(reply with tool calls)--> toolStep --(safe or trusted)-----> back to the node
 *                                                         --(needs approval)----> ⏸ toolApproval -> back to the node
 *   every node --(stopped: an error)--> its "on.stopped" target, else the automatic stopped
 *   askUser --(dismissed: "cancelled by the user")--> the automatic stopped -> END
 *   every file node --(next visit would exceed maxSteps)--> stopped -> END
 *
 * The generated graph definitions ({@code *GraphDefinition}) build their graphs from annotations at
 * compile time; a skill only exists at run time, so this adds the same kind of nodes, edges and
 * conditional edges by hand, from the file. Node ids are the file's ids plus the built-in {@link #START},
 * {@link #TOOL_STEP}, {@link #TOOL_APPROVAL} and {@link #STOPPED} (which the validator reserves); the
 * parent graph inlines them as {@code skill-<name>-<id>}.
 *
 * <h2>How a node runs</h2>
 * Each file node runs its kind's {@link NodeKind#action}, inside what every kind shares:
 * <ul>
 *   <li>A new visit (not the same node going on after its tool step, see
 *       {@link GenericKinds#continuesVisit}) sets {@code skillNode}, counts one step in
 *       {@code skillSteps} and clears the stop reason of the node that sent it here.</li>
 *   <li>An exception from the action stops the skill with the error as the reason, so the call is
 *       always answered and the turn doesn't fail.</li>
 *   <li>Its router: stopped → the node's {@code stopped} target (or the automatic {@link #STOPPED});
 *       tool calls left open in the skill's transcript → {@link #TOOL_STEP}; a further step would
 *       exceed {@code maxSteps} → {@link #STOPPED}; else the kind's {@link NodeKind#route outcome},
 *       mapped by the file's {@code next}/{@code on}. A kind without outcomes ({@code finish}) ends
 *       the subgraph once it answered the call; if it failed without answering, {@link #STOPPED}
 *       answers it, so every run answers its call exactly once.</li>
 * </ul>
 * The step limit always goes to the automatic {@link #STOPPED}, never to a file's {@code stopped}
 * target: a target that loops back could otherwise run forever. So does a dismissed {@code askUser}
 * question ({@code skillStopReason} "cancelled by the user"): the user ended the skill.
 *
 * <h2>Pauses</h2>
 * The graph must be compiled (or its parent) with {@code interruptBefore} the {@link #pauseNodes}:
 * {@link #TOOL_APPROVAL}, where {@code Agent} stores the approvals as for the main graph, every
 * {@code askUser} node, where it stores {@code skillAnswer}, and every {@code coding.planApproval}
 * node, where it stores the plan decision ({@code codingDecision}, {@code codingComment}).
 */
public final class SkillGraphBuilder {

    /** Starts every run: finds the call, resets the skill keys, sets the inputs. */
    public static final String START = "start";
    /** Runs (or refuses) the tool calls of a node's reply. */
    public static final String TOOL_STEP = "toolStep";
    /** The pause for tool calls that need the user's approval, then runs the approved ones. */
    public static final String TOOL_APPROVAL = "toolApproval";
    /** The automatic error finish: errors and limits end here unless the node maps {@code stopped}. */
    public static final String STOPPED = NodeKind.STOPPED;
    // The route of a terminal node that answered the call
    private static final String END = "end";

    private SkillGraphBuilder() {
    }

    /**
     * Builds the skill's subgraph (uncompiled, so it can be added to the agent graph).
     *
     * @param runtime the skill, which must be valid ({@link SkillValidator}), and what its nodes run with
     */
    public static StateGraph<State> build(SkillRuntime runtime) throws GraphStateException {
        SkillFile skill = runtime.skill();
        // The same schema and serializer as the generated definitions, so it merges into the agent graph
        StateGraph<State> graph = new StateGraph<>(State.SCHEMA, new LC4jStateSerializer<>(State::new));
        graph.addNode(START, node(runtime, START, GenericKinds.start(runtime)));
        graph.addEdge(StateGraph.START, START);
        graph.addEdge(START, skill.start());

        // Where the built-in tool nodes can go: back to any node that made the calls, or to its stopped target
        Set<String> fileNodes = new LinkedHashSet<>();
        Map<String, Map<String, String>> targetsById = new HashMap<>();
        for (NodeSpec node : skill.nodes()) {
            NodeKind kind = runtime.kinds().get(node.kind());
            if (kind == null) throw new IllegalArgumentException("node `" + node.id() + "`: unknown kind `" + node.kind() + "`");
            fileNodes.add(node.id());
            graph.addNode(node.id(), node(runtime, node.id(), visit(node, kind.action(node, runtime))));
            Set<String> outcomes = kind.outcomes(node);
            if (outcomes.isEmpty()) {
                // A terminal node (finish) answers the call and the skill is over; one that failed without
                // answering goes to the automatic stopped, so every run answers its call exactly once
                graph.addConditionalEdges(node.id(), AsyncEdgeAction.edge_async(state -> answered(state) ? END : STOPPED),
                        Map.of(END, StateGraph.END, STOPPED, STOPPED));
                continue;
            }
            Map<String, String> targets = node.targets(outcomes);
            targetsById.put(node.id(), targets);
            Set<String> to = new LinkedHashSet<>(targets.values());
            to.add(TOOL_STEP);
            to.add(STOPPED);
            graph.addConditionalEdges(node.id(),
                    AsyncEdgeAction.edge_async(state -> next(node, kind, targets, runtime, state)), identity(to));
        }

        Set<String> back = new LinkedHashSet<>(fileNodes);
        back.add(STOPPED);
        graph.addNode(TOOL_STEP, node(runtime, TOOL_STEP, GenericKinds.toolStep(runtime)));
        Set<String> afterToolStep = new LinkedHashSet<>(back);
        afterToolStep.add(TOOL_APPROVAL);
        graph.addConditionalEdges(TOOL_STEP, AsyncEdgeAction.edge_async(state -> {
            String stop = stoppedRoute(targetsById, skill, state);
            if (stop != null) return stop;
            // Calls still open after the tool step are guarded ones it left for the user
            return state.skillUnansweredToolCalls().isEmpty() ? state.skillNode() : TOOL_APPROVAL;
        }), identity(afterToolStep));
        graph.addNode(TOOL_APPROVAL, node(runtime, TOOL_APPROVAL, GenericKinds.toolApproval(runtime)));
        graph.addConditionalEdges(TOOL_APPROVAL, AsyncEdgeAction.edge_async(state -> {
            String stop = stoppedRoute(targetsById, skill, state);
            return stop != null ? stop : state.skillNode();
        }), identity(back));
        graph.addNode(STOPPED, node(runtime, STOPPED, GenericKinds.stopped(runtime)));
        graph.addEdge(STOPPED, StateGraph.END);
        return graph;
    }

    /**
     * The nodes the graph must be interrupted before: {@link #TOOL_APPROVAL} and every node whose kind
     * {@linkplain NodeKind#pausesBefore pauses before} it ({@code askUser}, {@code coding.planApproval}),
     * as ids inside the subgraph (the parent graph prefixes them with {@code skill-<name>-}).
     *
     * @param kinds the node kinds by name, as the skill was validated with
     */
    public static Set<String> pauseNodes(SkillFile skill, Map<String, NodeKind> kinds) {
        Set<String> ids = new LinkedHashSet<>();
        ids.add(TOOL_APPROVAL);
        for (NodeSpec node : skill.nodes()) {
            NodeKind kind = kinds.get(node.kind());
            if (kind != null && kind.pausesBefore()) ids.add(node.id());
        }
        return ids;
    }

    /** The router of a file node (see the class comment for the order of the checks). */
    private static String next(NodeSpec node, NodeKind kind, Map<String, String> targets, SkillRuntime runtime, State state) {
        SkillFile skill = runtime.skill();
        boolean overLimit = state.skillSteps() >= skill.limits().maxSteps();
        // A dismissed question means the user wants the skill to end: always the automatic stopped, never the
        // node's own stopped target (which could ask again, or carry on as if answered)
        if (GenericKinds.CANCELLED.equals(state.skillStopReason())) return STOPPED;
        if (!state.skillStopReason().isBlank()) return overLimit ? STOPPED : targets.getOrDefault(STOPPED, STOPPED);
        if (!state.skillUnansweredToolCalls().isEmpty()) return TOOL_STEP; // the same visit goes on: no new step
        if (overLimit) return STOPPED;
        // A kind may report its own stopped outcome (the coding kinds do, from codingStopReason)
        String target = targets.get(kind.route(node, runtime, state));
        return target == null ? STOPPED : target; // a validated file maps every outcome; fail closed anyway
    }

    /**
     * Whether the conversation has the answer to the skill's call. Checked after a terminal node, not its
     * stop reason: a node that answered and then set a reason must not be answered a second time.
     */
    private static boolean answered(State state) {
        return state.messages().stream().anyMatch(message -> message instanceof ToolExecutionResultMessage result
                && result.id().equals(state.skillCallId()));
    }

    /** After a tool node: where a stopped tool run goes (the calling node's stopped target), or null to go on. */
    private static String stoppedRoute(Map<String, Map<String, String>> targetsById, SkillFile skill, State state) {
        if (state.skillStopReason().isBlank()) return null;
        if (state.skillSteps() >= skill.limits().maxSteps()) return STOPPED;
        return targetsById.getOrDefault(state.skillNode(), Map.of()).getOrDefault(STOPPED, STOPPED);
    }

    /**
     * What every file node runs around its kind's action: a new visit is counted and becomes
     * {@code skillNode}; an exception becomes the stop reason.
     */
    private static NodeActionWithConfig<State> visit(NodeSpec node, NodeActionWithConfig<State> action) {
        return (state, config) -> {
            Map<String, Object> updates = new HashMap<>();
            if (!GenericKinds.continuesVisit(state, node.id())) {
                updates.put(State.SKILL_NODE, node.id());
                updates.put(State.SKILL_STEPS, state.skillSteps() + 1);
                updates.put(State.SKILL_STOP_REASON, ""); // a stopped node sent the skill here to handle it
            }
            try {
                updates.putAll(action.apply(state, config)); // the kind's own updates win
            } catch (RuntimeException e) {
                String message = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
                updates.put(State.SKILL_STOP_REASON, "error in " + node.id() + ": " + message.strip().replaceAll("\\s+", " "));
            }
            return updates;
        };
    }

    /** The node's action in a tracing span named {@code skill/<name>/<id>}, which LiveChannel sees too. */
    private static AsyncNodeActionWithConfig<State> node(SkillRuntime runtime, String id, NodeActionWithConfig<State> action) {
        return AsyncNodeActionWithConfig.node_async(runtime.tracing().wrap(tracingName(runtime.skill().name(), id), action));
    }

    /** The tracing span name of a skill node, {@code skill/<name>/<id>}; LiveChannel sees it as the running node. */
    public static String tracingName(String skill, String id) {
        return "skill/" + skill + "/" + id;
    }

    /** A conditional-edge map where each route is the id of the node it leads to. */
    private static Map<String, String> identity(Set<String> ids) {
        Map<String, String> map = new LinkedHashMap<>();
        ids.forEach(id -> map.put(id, id));
        return map;
    }
}
