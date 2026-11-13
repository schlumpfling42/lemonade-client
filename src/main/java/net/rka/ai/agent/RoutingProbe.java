package net.rka.ai.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Asks the model what the old question (triage) and the new analysis decide for one conversation, without running anything: the
 * routing check of the evaluation framework ({@code net.rka.ai.eval.RoutingEval}) uses it.
 */
public final class RoutingProbe {

    /** What was decided: the route ({@code agent} or a {@code job_*} edge; the old triage only knows {@code agent} and {@code job}), the goal, the seconds. */
    public record Result(String route, String goal, double seconds) {
    }

    private RoutingProbe() {
    }

    /** The old question: one word. CODING and CONTINUE are a job, OTHER is the agent. */
    public static Result triage(ChatModel model, List<ChatMessage> conversation) {
        long start = System.nanoTime();
        CodingTriage.Decision decision = new CodingTriage(model).decide(conversation);
        return new Result(decision == CodingTriage.Decision.OTHER ? "agent" : "job", "", (System.nanoTime() - start) / 1e9);
    }

    /** The new analysis: the edge it names, with the goal. "none" when two answers in a row could not be used. */
    public static Result analyze(ChatModel model, List<ChatMessage> conversation) {
        long start = System.nanoTime();
        List<net.rka.ai.graph.annotations.Offers.Edge> edges = AgentGraph.offeredEdges(true);
        Analysis.Offered offered = new Analysis.Offered(edges.stream().map(net.rka.ai.graph.annotations.Offers.Edge::name).collect(Collectors.toSet()), Set.of(), true);
        var analysis = new Analyzer(model).analyze(AnalyzeMessage.compose(edges, List.of(), true, conversation), offered);
        double seconds = (System.nanoTime() - start) / 1e9;
        if (analysis.isEmpty()) return new Result("none", "", seconds);
        if (analysis.get().next() instanceof Analysis.Ask ask) return new Result("ask", ask.question(), seconds);
        return new Result(((Analysis.Edge) analysis.get().next()).name(), analysis.get().goal(), seconds);
    }
}
