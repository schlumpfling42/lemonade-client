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
import net.rka.ai.tracing.AgentLog;
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
        List<ToolExecutionRequest> calls = state.unansweredToolCalls();
        List<ChatMessage> results = withoutRepeats(state.messages(), calls,
                run(calls, state.workFolder(), facts, null));
        return Map.of(
                AgentGraph.State.MESSAGES, results,                            // appended to the history
                AgentGraph.State.FACTS, AgentGraph.State.encodeFacts(facts.list()), // replaces the facts
                AgentGraph.State.KNOWLEDGE, Knowledge.after(state.knowledge(), calls, state.workFolder())); // the files the calls touched
    }

    /**
     * Runs calls of a skill's tool step and returns the node's state update, like {@link #runUnanswered}
     * but with the results in the skill's transcript ({@code skillMessages}) instead of the conversation.
     * The skill decides which calls may run; this only runs them.
     */
    public Map<String, Object> runInSkill(AgentGraph.State state, List<ToolExecutionRequest> calls) {
        FactBook facts = new FactBook(state.facts()); // a listed remember/forget changes this copy
        List<ChatMessage> results = withoutRepeats(RollingResults.apply(state.skillMessages()), calls, run(calls, state.workFolder(), facts, null));
        return Map.of(
                AgentGraph.State.SKILL_MESSAGES, results,                      // appended to the skill's transcript
                AgentGraph.State.FACTS, AgentGraph.State.encodeFacts(facts.list()), // replaces the facts
                AgentGraph.State.KNOWLEDGE, Knowledge.after(state.knowledge(), calls, state.workFolder()));
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
                long start = System.nanoTime();
                ToolExecutionResultMessage result = tools.execute(call, parameters); // never throws: errors become results
                log(call, result, System.nanoTime() - start);
                span.setAttribute("gen_ai.completion", result.text());
                if (Boolean.TRUE.equals(result.isError())) span.setStatus(StatusCode.ERROR, result.text());
                results.add(result);
            } finally {
                span.end(); // records the duration; the exporter sends it to LangSmith / the JSONL file
            }
        }
        return results;
    }

    /** What the model gets instead of a result it already has (see {@link #withoutRepeats}). */
    static String repeatNote(String toolName) {
        return "Same result as your identical " + toolName + " call above in this turn: nothing changed since. "
                + "Don't call it again; use that result and go on with the task (with a different call, or your answer).";
    }

    /** Whether a result is a note that the call repeated an earlier one ({@link #repeatNote}, {@link #repeatedErrorNote}). */
    public static boolean isRepeat(ToolExecutionResultMessage result) {
        String text = result.text() == null ? "" : result.text();
        return text.startsWith("Same result as your identical ") || text.contains("This is the same ") && text.contains(" call that already failed this way");
    }

    /** What the model gets when it repeats a call that already failed the same way (see {@link #withoutRepeats}). */
    static String repeatedErrorNote(String toolName, String error) {
        return error + "\nThis is the same " + toolName + " call that already failed this way earlier in this turn. "
                + "Don't repeat it: change the call (read what is really there first), or stop and tell the user what blocks you.";
    }

    /**
     * Breaks the loops a local model can fall into: calling the same tool with the same arguments again
     * and again (the log showed {@code readFile} of the same lines three times in a row, and an
     * {@code editSection} that failed, the same read, the same failing edit). A call whose result is exactly
     * the result of an identical call earlier in this turn (since the last user message, or in this
     * batch) gets {@link #repeatNote} instead: the model is told it already has it, and the context doesn't
     * grow by the same text again. A call that fails with exactly the error an identical call already got
     * keeps that error with {@link #repeatedErrorNote} added. The call still ran, so a call whose answer
     * changed (a file edited in between, the time, a command's output) keeps its new result.
     */
    static List<ChatMessage> withoutRepeats(List<ChatMessage> transcript, List<ToolExecutionRequest> calls,
                                            List<ChatMessage> results) {
        Map<String, String> earlier = earlierResults(transcript);
        List<ChatMessage> kept = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            ChatMessage message = results.get(i);
            if (i < calls.size() && message instanceof ToolExecutionResultMessage result) {
                ToolExecutionRequest call = calls.get(i);
                boolean failed = Boolean.TRUE.equals(result.isError());
                String key = (failed ? "error\u0000" : "") + key(call);
                if (!result.text().equals(earlier.get(key))) {
                    earlier.put(key, result.text());
                } else if (failed) {
                    AgentLog.current().line("   ↺ " + call.name() + " failed the same way as before: the model is told to stop repeating it");
                    message = ToolExecutionResultMessage.builder().id(call.id()).toolName(call.name())
                            .text(repeatedErrorNote(call.name(), result.text())).isError(true).build();
                } else {
                    AgentLog.current().line("   ↺ " + call.name() + " gave the same result as before: the model is told to use that one");
                    message = ToolExecutionResultMessage.from(call, repeatNote(call.name()));
                }
            }
            kept.add(message);
        }
        return kept;
    }

    /** Results since the last user message, by {@link #key} of their call ("error" + key for failed calls). */
    private static Map<String, String> earlierResults(List<ChatMessage> transcript) {
        int from = 0;
        for (int i = transcript.size() - 1; i >= 0; i--) {
            if (transcript.get(i) instanceof dev.langchain4j.data.message.UserMessage) {
                from = i + 1;
                break;
            }
        }
        Map<String, ToolExecutionRequest> calls = new HashMap<>();
        Map<String, String> results = new HashMap<>();
        for (ChatMessage message : transcript.subList(from, transcript.size())) {
            if (message instanceof dev.langchain4j.data.message.AiMessage ai && ai.hasToolExecutionRequests()) {
                ai.toolExecutionRequests().forEach(call -> calls.put(call.id(), call));
            } else if (message instanceof ToolExecutionResultMessage result) {
                ToolExecutionRequest call = calls.get(result.id());
                if (call != null) results.put((Boolean.TRUE.equals(result.isError()) ? "error\u0000" : "") + key(call), result.text());
            }
        }
        return results;
    }

    /** The tool and its arguments, with JSON spacing and key order not mattering. */
    private static String key(ToolExecutionRequest call) {
        String arguments = call.arguments() == null ? "" : call.arguments().strip();
        try {
            com.fasterxml.jackson.databind.JsonNode tree = JSON.readTree(arguments.isEmpty() ? "{}" : arguments);
            arguments = JSON.writeValueAsString(JSON.treeToValue(tree, Object.class)); // maps sorted below
        } catch (java.io.IOException | RuntimeException e) {
            // not JSON: compare the text
        }
        return call.name() + "\u0000" + arguments;
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * One line per tool call in the agent log (the step that ran it, the arguments, ok/error, the result's
     * size and how long it took); at {@code AGENT_LOG=full} also the whole result.
     */
    private void log(ToolExecutionRequest call, ToolExecutionResultMessage result, long nanos) {
        AgentLog log = AgentLog.current();
        if (!log.on()) return;
        boolean failed = Boolean.TRUE.equals(result.isError());
        log.line("⚙ tool " + call.name() + " [" + live.node() + "] " + AgentLog.oneLine(call.arguments(), 160)
                + " → " + (failed ? "error: " + AgentLog.oneLine(result.text(), 160)
                        : "ok, " + String.format(java.util.Locale.ROOT, "%,d", result.text().length()) + " chars")
                + ", " + AgentLog.duration(nanos));
        if (log.full()) log.block("   result of " + call.name() + " (" + call.id() + ")", result.text());
    }
}
