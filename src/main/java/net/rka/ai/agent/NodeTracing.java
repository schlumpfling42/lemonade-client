package net.rka.ai.agent;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.bsc.langgraph4j.state.AgentState;

/**
 * Turns each graph node execution into a "chain" span. LangGraph4j runs nodes on pool
 * threads, so the parent span comes from the run's metadata ({@link #TRACE_CONTEXT}),
 * not from the thread-local current context.
 *
 * <h2>How it works</h2>
 * OpenTelemetry normally finds the parent span through a thread-local "current context". That
 * breaks when work hops threads, which LangGraph4j does. So {@code Agent.run} puts the root span's
 * {@link Context} into the {@code RunnableConfig} metadata, and {@link #wrap} reads it back here and
 * sets it as the explicit parent. The result in LangSmith is one tree per question:
 * <pre>
 *   LangGraph Agent
 *   ├─ agent          (this wrapper)
 *   │  └─ ChatModel   (TracingChatModelListener)
 *   ├─ tools
 *   │  └─ listFiles   (ToolCallRunner)
 *   └─ agent ...
 * </pre>
 * The wrapper is applied to every node by the generated {@code *GraphDefinition} classes, because the
 * graph classes declare a {@code @NodeWrapper} method that calls {@link #wrap}.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Recording the node's state changes (input/output messages) as span attributes, so each step's
 *       effect is visible in LangSmith, not just its timing.</li>
 *   <li>Metrics (counts and durations per node) via the OpenTelemetry metrics API.</li>
 * </ul>
 */
public final class NodeTracing {

    /** RunnableConfig metadata key holding the OpenTelemetry {@link Context} of the run's root span. */
    static final String TRACE_CONTEXT = "otel.context";

    private final Tracer tracer;
    private final LiveChannel live;

    public NodeTracing(Tracer tracer) {
        this(tracer, new LiveChannel());
    }

    /** @param live told which node starts, so it can decide what of a streamed reply to show */
    NodeTracing(Tracer tracer, LiveChannel live) {
        this.tracer = tracer;
        this.live = live;
    }

    /** Returns a node action that does the same as {@code action}, inside a span called {@code name}. */
    public <S extends AgentState> NodeActionWithConfig<S> wrap(String name, NodeActionWithConfig<S> action) {
        return (state, config) -> {
            live.enter(name); // e.g. the plan step's JSON is shown as progress, not as text
            // The run's root span, passed through the config; fall back to the thread's context (e.g. in tests)
            Context parent = config.metadata(TRACE_CONTEXT)
                    .map(Context.class::cast)
                    .orElseGet(Context::current);
            Span span = tracer.spanBuilder(name)
                    .setParent(parent)
                    .setAttribute("langsmith.span.kind", "chain")
                    .startSpan();
            // While the node runs, its span is current, so the model call / tool spans nest under it
            try (Scope ignored = span.makeCurrent()) {
                return action.apply(state, config);
            } catch (Exception e) {
                span.recordException(e); // the error shows up on the step in LangSmith
                throw e;
            } finally {
                span.end();
            }
        };
    }
}
