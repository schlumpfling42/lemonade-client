package net.rka.ai.tracing;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Writes every model request and reply to the {@link AgentLog}: which graph step asked, how much was sent,
 * how much of it the model server can reuse from the previous request, how long the reply took and
 * what came back.
 * <p>
 * Why the reuse number matters: a local server (llama-server behind Lemonade) keeps the last prompt in its
 * cache and only reads what is new after the longest unchanged beginning. Reading is slow (a few hundred
 * tokens a second), so a request whose beginning changed (a new summary, a different tool list, a cut
 * old message) can take minutes although only a little is new. The log shows how many of the request's
 * messages are unchanged from the previous request and how many characters are new; a "0 of N" next to
 * a long wait explains it.
 * <p>
 * At {@code AGENT_LOG=full} it also writes the new messages of each request and the whole reply.
 */
public final class LoggingChatModelListener implements ChatModelListener {

    private static final String START = "agentLog.start";
    private static final String NUMBER = "agentLog.number";

    private final Supplier<String> step;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile List<ChatMessage> previous = List.of();

    /** @param step the graph step whose model call this is ("agent", "skill/coding/plan", ...), for the log line */
    public LoggingChatModelListener(Supplier<String> step) {
        this.step = step;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        AgentLog log = AgentLog.current();
        if (!log.on()) return;
        int number = requests.incrementAndGet();
        ctx.attributes().put(START, System.nanoTime());
        ctx.attributes().put(NUMBER, number);
        ChatRequest request = ctx.chatRequest();
        List<ChatMessage> messages = request.messages();
        int reused = sameStart(previous, messages);
        previous = List.copyOf(messages);
        long chars = 0, fresh = 0;
        for (int i = 0; i < messages.size(); i++) {
            long c = text(messages.get(i)).length();
            chars += c;
            if (i >= reused) fresh += c;
        }
        int tools = request.toolSpecifications() == null ? 0 : request.toolSpecifications().size();
        log.line(String.format(Locale.ROOT, "→ LLM #%d [%s] %d messages, %,d chars, %d tools; unchanged from the last request: %d of %d messages, new: %,d chars",
                number, step(), messages.size(), chars, tools, reused, messages.size(), fresh));
        if (log.full()) {
            for (int i = reused; i < messages.size(); i++) {
                log.block("   new message " + (i + 1) + " · " + role(messages.get(i)), text(messages.get(i)));
            }
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        AgentLog log = AgentLog.current();
        if (!log.on() || !(ctx.attributes().get(START) instanceof Long start)) return;
        long took = System.nanoTime() - start;
        ChatResponse response = ctx.chatResponse();
        TokenUsage usage = response.tokenUsage();
        Integer in = usage == null ? null : usage.inputTokenCount();
        Integer out = usage == null ? null : usage.outputTokenCount();
        AiMessage reply = response.aiMessage();
        String what = reply.hasToolExecutionRequests()
                ? "calls " + String.join(", ", reply.toolExecutionRequests().stream()
                        .map(c -> c.name() + " " + AgentLog.oneLine(c.arguments(), 80)).toList())
                : "says: " + AgentLog.oneLine(reply.text(), 120);
        // (With streaming, the adapter logs the first token and the writing speed separately)
        // Tokens the server did not read again (its prompt cache), when it says so: the measure of how much of a prompt was reused
        String cached = usage instanceof dev.langchain4j.model.openai.OpenAiTokenUsage open && open.inputTokensDetails() != null
                && open.inputTokensDetails().cachedTokens() != null ? " (" + count(open.inputTokensDetails().cachedTokens()) + " cached)" : "";
        log.line(String.format(Locale.ROOT, "← LLM #%s [%s] %s in all, tokens in %s%s / out %s → %s",
                ctx.attributes().get(NUMBER), step(), AgentLog.duration(took), count(in), cached, count(out), what));
        if (log.full()) {
            if (reply.thinking() != null && !reply.thinking().isBlank()) log.block("   thinking", reply.thinking());
            if (reply.text() != null && !reply.text().isBlank()) log.block("   reply", reply.text());
            for (ToolExecutionRequest call : reply.toolExecutionRequests()) {
                log.block("   tool call " + call.name() + " (" + call.id() + ")", call.arguments());
            }
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        AgentLog log = AgentLog.current();
        if (!log.on()) return;
        Object start = ctx.attributes().get(START);
        String after = start instanceof Long s ? " after " + AgentLog.duration(System.nanoTime() - s) : "";
        Throwable error = ctx.error();
        log.line("✗ LLM #" + ctx.attributes().get(NUMBER) + " [" + step() + "] failed" + after + ": "
                + error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private String step() {
        String s = step.get();
        return s == null || s.isBlank() ? "?" : s;
    }

    /** How many messages from the start are the same in both requests. */
    static int sameStart(List<ChatMessage> before, List<ChatMessage> now) {
        int n = 0;
        while (n < before.size() && n < now.size() && before.get(n).equals(now.get(n))) n++;
        return n;
    }

    private static String count(Integer n) {
        return n == null ? "?" : String.format(Locale.ROOT, "%,d", n);
    }

    private static String role(ChatMessage m) {
        if (m instanceof SystemMessage) return "system";
        if (m instanceof UserMessage) return "user";
        if (m instanceof ToolExecutionResultMessage r) return "tool result " + r.toolName();
        if (m instanceof AiMessage) return "assistant";
        return m.type().toString();
    }

    private static String text(ChatMessage m) {
        if (m instanceof SystemMessage s) return s.text();
        if (m instanceof UserMessage u) return u.hasSingleText() ? u.singleText() : String.valueOf(u.contents());
        if (m instanceof ToolExecutionResultMessage r) return r.text();
        if (m instanceof AiMessage ai) {
            StringBuilder b = new StringBuilder(ai.text() == null ? "" : ai.text());
            for (ToolExecutionRequest c : ai.toolExecutionRequests()) b.append("\n→ ").append(c.name()).append(' ').append(c.arguments());
            return b.toString();
        }
        return String.valueOf(m);
    }
}
