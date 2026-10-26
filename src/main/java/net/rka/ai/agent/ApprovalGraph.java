package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import net.rka.ai.agent.AgentGraph.State;
import net.rka.ai.graph.annotations.Edge;
import net.rka.ai.graph.annotations.Graph;
import net.rka.ai.graph.annotations.Node;
import net.rka.ai.graph.annotations.NodeWrapper;
import net.rka.ai.graph.annotations.Route;
import net.rka.ai.graph.annotations.Router;
import org.bsc.langgraph4j.action.NodeActionWithConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The approval subgraph, run as the "approval" node of {@link AgentGraph}:
 *
 *   START -> review --(anything left to run)--> execute -> END
 *                   --(everything denied)-----> END
 *
 * By the time it runs, {@link Agent} has stored the user's decisions in the state.
 * Denied calls get a "denied" result so the model knows; approved and safe calls run.
 *
 * <h2>How the pause works</h2>
 * The parent graph is compiled with {@code interruptBefore("approval")}. When the router picks
 * "approval", LangGraph4j saves the state (a "checkpoint") and returns from {@code invoke} without
 * running this subgraph. {@link Agent#run} sees open tool calls, asks the {@link Approver}, writes the
 * answers into the saved state's {@code approvals} map ({@code graph.updateState}) and resumes the
 * graph from the checkpoint. Only then do {@link #review} and {@link #execute} run.
 * <p>
 * Each method below is wired by an annotation; the {@code graph-processor} generates
 * {@code ApprovalGraphDefinition} from them at compile time (see {@code docs/agent-graph.md}).
 * A node returns a map of state <em>updates</em>: {@code MESSAGES} is an "appender" channel, so the
 * returned messages are added to the history rather than replacing it.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Approving with changes: let the user edit the command, then run the edited version.</li>
 *   <li>A denial reason typed by the user and passed to the model ("not that folder, use X").</li>
 * </ul>
 */
@Graph(name = "approval", state = State.class, serializer = Graph.Serializer.LANGCHAIN4J)
@Edge(from = Graph.START, to = ApprovalGraph.REVIEW)
@Edge(from = "execute", to = Graph.END)
public class ApprovalGraph {

    /** The first node: the parent's pause before "approval" is a pause before this (inlined) node. */
    static final String REVIEW = "review";

    private final ToolCallRunner toolCalls;
    private final NodeTracing tracing;

    ApprovalGraph(ToolCallRunner toolCalls, NodeTracing tracing) {
        this.toolCalls = toolCalls;
        this.tracing = tracing;
    }

    /** Answers every call that needs approval but wasn't approved with a "denied" result. */
    @Node(REVIEW)
    Map<String, Object> review(State state) {
        Map<String, Boolean> approvals = state.approvals();
        List<ChatMessage> denied = new ArrayList<>();
        for (ToolExecutionRequest call : state.unansweredToolCalls()) {
            // Missing from the map counts as "no": fail closed if something went wrong while asking
            if (state.needsApproval(toolCalls.tools(), call) && !approvals.getOrDefault(call.id(), false)) {
                denied.add(ToolExecutionResultMessage.builder()
                        .id(call.id())
                        .toolName(call.name())
                        // Telling the model explicitly not to retry stops it from asking again in a loop
                        .text("Denied: the user did not approve this call. Don't retry it unless the user asks.")
                        .isError(true)
                        .build());
            }
        }
        return Map.of(State.MESSAGES, denied);
    }

    /** After review: if anything is still unanswered (approved, trusted or safe calls), run it. */
    @Router(from = "review", routes = {
            @Route(on = "execute", to = "execute"),
            @Route(on = "done", to = Graph.END)})
    String afterReview(State state) {
        // unansweredToolCalls now excludes the denied ones, because review just answered them
        return state.unansweredToolCalls().isEmpty() ? "done" : "execute";
    }

    /** Runs the approved calls, and any trusted or safe calls from the same message. */
    @Node("execute")
    Map<String, Object> execute(State state) {
        return toolCalls.runUnanswered(state); // results + updated facts, as a state update
    }

    /** LangGraph4j inlines subgraphs into the parent, so name the spans "approval/review" etc. */
    @NodeWrapper
    NodeActionWithConfig<State> traced(String name, NodeActionWithConfig<State> action) {
        return tracing.wrap(AgentGraph.APPROVAL + "/" + name, action);
    }
}
