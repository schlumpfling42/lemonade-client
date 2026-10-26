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
 * The trusted-command subgraph, run as the "trusted" node of {@link AgentGraph} when every
 * risky call is a trusted command, so nobody is asked:
 *
 *   START -> verify --(anything left to run)--> execute -> END
 *                   --(nothing trusted)-------> END
 *
 * "verify" checks the allow-list again, so a call that slipped through the router unchecked
 * is refused instead of run.
 *
 * <h2>How it works</h2>
 * Same shape as {@link ApprovalGraph}, but without the pause: the parent graph only interrupts
 * before "approval", so this subgraph runs straight through. The double check in {@link #verify}
 * is "defense in depth": the router already decided everything is trusted, but if a future change
 * to the router got that wrong, verify would still refuse instead of running an unapproved command.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Logging trusted runs somewhere visible (an audit list in the UI), since nobody saw them happen.</li>
 *   <li>Rate limits: e.g. at most N trusted commands per turn before asking anyway.</li>
 * </ul>
 */
@Graph(name = "trusted", state = State.class, serializer = Graph.Serializer.LANGCHAIN4J)
@Edge(from = Graph.START, to = "verify")
@Edge(from = "execute", to = Graph.END)
public class TrustedGraph {

    private final ToolCallRunner toolCalls;
    private final NodeTracing tracing;

    TrustedGraph(ToolCallRunner toolCalls, NodeTracing tracing) {
        this.toolCalls = toolCalls;
        this.tracing = tracing;
    }

    /** Refuses every call that would need approval; trusted and safe calls are left to run. */
    @Node("verify")
    Map<String, Object> verify(State state) {
        List<ChatMessage> refused = new ArrayList<>();
        for (ToolExecutionRequest call : state.unansweredToolCalls()) {
            // The same rule the router used (trusted commands + web content in the turn)
            if (state.needsApproval(toolCalls.tools(), call)) {
                refused.add(ToolExecutionResultMessage.builder()
                        .id(call.id())
                        .toolName(call.name())
                        .text("Error: this command is not a trusted command and needs the user's approval.")
                        .isError(true)
                        .build());
            }
        }
        return Map.of(State.MESSAGES, refused);
    }

    @Router(from = "verify", routes = {
            @Route(on = "execute", to = "execute"),
            @Route(on = "done", to = Graph.END)})
    String afterVerify(State state) {
        return state.unansweredToolCalls().isEmpty() ? "done" : "execute";
    }

    /** Runs the trusted calls, and any safe calls from the same message. */
    @Node("execute")
    Map<String, Object> execute(State state) {
        return toolCalls.runUnanswered(state); // results + updated facts, as a state update
    }

    /** LangGraph4j inlines subgraphs into the parent, so name the spans "trusted/verify" etc. */
    @NodeWrapper
    NodeActionWithConfig<State> traced(String name, NodeActionWithConfig<State> action) {
        return tracing.wrap(AgentGraph.TRUSTED + "/" + name, action);
    }
}
