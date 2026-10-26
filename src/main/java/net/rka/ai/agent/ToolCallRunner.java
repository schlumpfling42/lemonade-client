package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import dev.langchain4j.invocation.InvocationParameters;
import net.rka.ai.tools.FactBook;
import net.rka.ai.tools.TerminalTools;
import net.rka.ai.tools.ToolRegistry;
import net.rka.ai.tools.WorkFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs tool calls through the registry, one "tool" span per call.
 *
 * <h2>How it works</h2>
 * The three graph nodes that execute tools ({@code tools}, {@code trusted/execute},
 * {@code approval/execute}), and the skills' tool steps, all call {@link #run}, so tracing and the work folder are handled in
 * one place. For each call it opens an OpenTelemetry span named after the tool (LangSmith shows it
 * as a "tool" step with the arguments as input and the result as output), runs the call via
 * {@link ToolRegistry#execute}, and returns the results in the same order as the calls. The results
 * become new messages in the graph state.
 * <p>
 * Every result carries the call's id; the model needs that to match results to its requests, and
 * {@code AgentGraph.State.unansweredToolCalls} uses it to know what's still open.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Running independent calls in parallel (e.g. several {@code fetchPage}s) on a thread pool.</li>
 *   <li>Retrying transient failures (network timeouts) before reporting an error to the model.</li>
 *   <li>Progress for tools other than commands (e.g. a long fetchPage); commands already stream their output.</li>
 * </ul>
 */
public final class ToolCallRunner {

    private final Tracer tracer;
    private final ToolRegistry tools;
    private final LiveChannel live;
    // Folders file tools refuse besides the default skill folder (see WorkFolder#DENIED_PARAMETER)
    private final List<Path> deniedFolders;

    public ToolCallRunner(Tracer tracer, ToolRegistry tools) {
        this(tracer, tools, new LiveChannel());
    }

    /** @param live where running commands send their output while they run (see {@link LiveChannel}) */
    ToolCallRunner(Tracer tracer, ToolRegistry tools, LiveChannel live) {
        this(tracer, tools, live, List.of());
    }

    /** @param deniedFolders folders file tools refuse even inside the trusted folder: the skill folder {@code Agent} loads from */
    ToolCallRunner(Tracer tracer, ToolRegistry tools, LiveChannel live, List<Path> deniedFolders) {
        this.tracer = tracer;
        this.tools = tools;
        this.live = live;
        this.deniedFolders = List.copyOf(deniedFolders);
    }

    public ToolRegistry tools() {
        return tools;
    }

    /**
     * Runs the state's unanswered tool calls and returns the node's state update: the result
     * messages, and the conversation's facts as the memory tools left them. Every node that runs
     * tools ("tools", "trusted/execute", "approval/execute") returns this directly.
     */
    Map<String, Object> runUnanswered(AgentGraph.State state) {
        FactBook facts = new FactBook(state.facts()); // remember/forget change this copy during the calls
        List<ChatMessage> results = run(state.unansweredToolCalls(), state.workFolder(), facts, null);
        return Map.of(
                AgentGraph.State.MESSAGES, results,                            // appended to the history
                AgentGraph.State.FACTS, AgentGraph.State.encodeFacts(facts.list())); // replaces the facts
    }

    /**
     * Runs calls of a skill's tool step and returns the node's state update, like {@link #runUnanswered}
     * but with the results in the skill's transcript ({@code skillMessages}) instead of the conversation.
     * The skill decides which calls may run; this only runs them.
     */
    public Map<String, Object> runInSkill(AgentGraph.State state, List<ToolExecutionRequest> calls) {
        FactBook facts = new FactBook(state.facts()); // a listed remember/forget changes this copy
        List<ChatMessage> results = run(calls, state.workFolder(), facts, null);
        return Map.of(
                AgentGraph.State.SKILL_MESSAGES, results,                      // appended to the skill's transcript
                AgentGraph.State.FACTS, AgentGraph.State.encodeFacts(facts.list())); // replaces the facts
    }

    /**
     * @param workFolder the conversation's trusted folder, passed to tools as {@link WorkFolder#PARAMETER}
     * @param facts      the conversation's long-term facts, passed to tools as {@link FactBook#PARAMETER}
     * @param timeoutSeconds command timeout for this run, passed as {@link TerminalTools#TIMEOUT_PARAMETER};
     *                   null keeps the global default
     */
    List<ChatMessage> run(List<ToolExecutionRequest> calls, Path workFolder, FactBook facts, Integer timeoutSeconds) {
        // Same parameters for every call in this batch: the conversation's folder and facts (and timeout)
        Map<String, Object> values = new HashMap<>();
        values.put(WorkFolder.PARAMETER, workFolder);
        values.put(FactBook.PARAMETER, facts);
        values.put(WorkFolder.DENIED_PARAMETER, deniedFolders);
        if (timeoutSeconds != null) values.put(TerminalTools.TIMEOUT_PARAMETER, timeoutSeconds);
        List<ChatMessage> results = new ArrayList<>();
        for (ToolExecutionRequest call : calls) {
            // Per call: a running command sends its output, tagged with this call's id, to the live channel
            Map<String, Object> callValues = new HashMap<>(values);
            callValues.put(TerminalTools.OUTPUT_PARAMETER, (Consumer<String>) chunk -> live.toolOutput(call.id(), chunk));
            InvocationParameters parameters = InvocationParameters.from(callValues);
            Span span = tracer.spanBuilder(call.name())
                    .setAttribute("langsmith.span.kind", "tool") // LangSmith's run type for this span
                    .setAttribute("gen_ai.prompt", call.arguments())
                    .startSpan();
            // makeCurrent: spans created inside the tool (none today) would become children of this one
            try (Scope ignored = span.makeCurrent()) {
                ToolExecutionResultMessage result = tools.execute(call, parameters); // never throws: errors become results
                span.setAttribute("gen_ai.completion", result.text());
                if (Boolean.TRUE.equals(result.isError())) span.setStatus(StatusCode.ERROR, result.text());
                results.add(result);
            } finally {
                span.end(); // records the duration; the exporter sends it to LangSmith / the JSONL file
            }
        }
        return results;
    }
}
