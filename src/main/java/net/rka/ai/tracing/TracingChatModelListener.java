package net.rka.ai.tracing;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.util.List;

/**
 * Emits one "llm" span per LangChain4j chat call, using the GenAI semantic
 * conventions LangSmith understands. Spans nest under whatever span is current
 * (e.g. the LangGraph node that made the call).
 *
 * <h2>How it works</h2>
 * LangChain4j calls a {@link ChatModelListener} around every model request: {@link #onRequest}
 * before sending, then {@link #onResponse} or {@link #onError}. A span is started in onRequest and
 * stored in the context's attribute map (shared between the three callbacks of one request), then
 * finished in onResponse/onError. Attributes follow the OpenTelemetry "GenAI" conventions
 * ({@code gen_ai.prompt.N.role/content}, {@code gen_ai.usage.input_tokens}, …), which LangSmith shows
 * as the model's input, output and token counts.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Recording the tool specifications sent with the request, and the requested tool calls as
 *       structured attributes instead of {@code toString()} text.</li>
 *   <li>Time-to-first-token and tokens-per-second (needs a streaming model).</li>
 * </ul>
 */
public class TracingChatModelListener implements ChatModelListener {

    private static final String SPAN_KEY = "otel.span";

    private final Tracer tracer;
    private final String system;

    public TracingChatModelListener(Tracer tracer, String system) {
        this.tracer = tracer;
        this.system = system;
    }

    /** Before the request goes to Lemonade: start the span and record the prompt. */
    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        ChatRequest request = ctx.chatRequest();
        Span span = tracer.spanBuilder("ChatModel")
                .setParent(Context.current())      // the graph node's span (NodeTracing made it current)
                .setSpanKind(SpanKind.CLIENT)      // an outgoing call to another service
                .setAttribute("langsmith.span.kind", "llm") // LangSmith shows it as an LLM run
                .setAttribute("gen_ai.operation.name", "chat")
                .setAttribute("gen_ai.system", system)
                .setAttribute("gen_ai.request.model", request.modelName())
                .startSpan();
        // One attribute pair per message: LangSmith rebuilds the conversation from gen_ai.prompt.N.*
        List<ChatMessage> messages = request.messages();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            span.setAttribute("gen_ai.prompt." + i + ".role", role(m));
            span.setAttribute("gen_ai.prompt." + i + ".content", m.toString());
        }
        // Hand the span to onResponse/onError, which LangChain4j calls with the same attribute map
        ctx.attributes().put(SPAN_KEY, span);
    }

    /** The answer arrived: record it and the token counts, then finish the span. */
    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        Span span = (Span) ctx.attributes().get(SPAN_KEY); // the span onRequest started
        if (span == null) return;
        ChatResponse response = ctx.chatResponse();
        span.setAttribute("gen_ai.response.model", String.valueOf(response.modelName()));
        span.setAttribute("gen_ai.completion.0.role", "assistant");
        // toString includes requested tool calls, so they're visible in the trace too
        span.setAttribute("gen_ai.completion.0.content", String.valueOf(response.aiMessage()));
        TokenUsage usage = response.tokenUsage(); // reported by the server; may be missing
        if (usage != null) {
            if (usage.inputTokenCount() != null)
                span.setAttribute("gen_ai.usage.input_tokens", usage.inputTokenCount());
            if (usage.outputTokenCount() != null)
                span.setAttribute("gen_ai.usage.output_tokens", usage.outputTokenCount());
        }
        span.end(); // duration = model latency
    }

    /** The request failed (timeout, connection refused, ...): mark the span as an error. */
    @Override
    public void onError(ChatModelErrorContext ctx) {
        Span span = (Span) ctx.attributes().get(SPAN_KEY);
        if (span == null) return;
        span.recordException(ctx.error());                         // adds the stack trace as an event
        span.setStatus(StatusCode.ERROR, ctx.error().getMessage()); // shown red in LangSmith
        span.end();
    }

    /** LangChain4j message type → the role names the GenAI conventions use. */
    private static String role(ChatMessage m) {
        return switch (m.type()) {
            case SYSTEM -> "system";
            case USER -> "user";
            case AI -> "assistant";
            case TOOL_EXECUTION_RESULT -> "tool";
            default -> m.type().name().toLowerCase();
        };
    }
}
