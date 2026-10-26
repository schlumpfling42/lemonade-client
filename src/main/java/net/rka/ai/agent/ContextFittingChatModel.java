package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wraps the real {@link ChatModel} so that no request is larger than the model server's context window.
 * <p>
 * Why: a local llama-server has a fixed context ({@code n_ctx}, 16,384 tokens for the default Lemonade
 * setup). Summary memory keeps the <em>conversation</em> small, but nothing limited what a single turn
 * adds: two {@code readFile} results of big source files were enough to reach 20,000 tokens and the
 * server rejected the request ({@code context_length_exceeded}), which failed the whole turn.
 * <p>
 * How it works, for every request (agent, summarizer and every coding job node use this one model):
 * <ol>
 *   <li>Estimate the request's size: the messages plus the tool specifications, at
 *       {@link #charsPerToken} characters per token, plus {@link #RESERVED_OUTPUT_TOKENS} for the answer.</li>
 *   <li>If it doesn't fit {@code contextTokens}, shorten messages <em>in this request only</em> (the graph
 *       state and the saved conversation keep the full text): tool results first, oldest first, then
 *       older user/assistant texts. Each shortened message keeps its beginning and end with a marker in
 *       the middle saying how much was cut, so the model knows to read a smaller part if it needs it.
 *       The system message and the latest user message are never shortened.</li>
 *   <li>If the server still says the request is too big (the estimate was off), take the real token
 *       count from its error message, correct {@link #charsPerToken} and try once more.</li>
 * </ol>
 * This is a model adapter, not graph logic: every node keeps calling {@code model.chat(...)} unchanged.
 * <p>
 * Not implemented yet (could be added):
 * <ul>
 *   <li>Asking the server for its real {@code n_ctx} at startup instead of {@code MODEL_CONTEXT_TOKENS}.</li>
 *   <li>A real tokenizer instead of the characters-per-token estimate.</li>
 *   <li>Summarizing a large tool result with the model instead of cutting its middle.</li>
 * </ul>
 */
final class ContextFittingChatModel implements ChatModel {

    /** Tokens kept free for the model's answer (tool calls and text). */
    static final int RESERVED_OUTPUT_TOKENS = 2048;
    /** A shortened message keeps at least this many characters (head + tail). */
    static final int MIN_KEPT_CHARS = 400;
    private static final int MARKER_CHARS = 120; // about the length of the "characters cut" marker

    // llama-server's error: "request (20403 tokens) exceeds the available context size (16384 tokens)"
    private static final Pattern TOO_BIG = Pattern.compile(
            "request \\((\\d+) tokens\\) exceeds the available context size \\((\\d+) tokens\\)");

    private final ChatModel delegate;
    private final int contextTokens;
    // Starts conservative (source code is about 3 characters per token) and learns from server errors
    private volatile double charsPerToken = 3.0;

    ContextFittingChatModel(ChatModel delegate, int contextTokens) {
        this.delegate = delegate;
        this.contextTokens = contextTokens;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return send(request, r -> delegate.chat(r));
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return send(request, r -> delegate.chat(r, options));
    }

    /** Fits the request and sends it; on a "too big" answer from the server, corrects the estimate once and retries. */
    private ChatResponse send(ChatRequest request, Function<ChatRequest, ChatResponse> call) {
        ChatRequest sent = fit(request);
        try {
            return call.apply(sent);
        } catch (RuntimeException e) {
            Matcher m = TOO_BIG.matcher(String.valueOf(e.getMessage()));
            if (!m.find()) throw e;
            int serverTokens = Integer.parseInt(m.group(1));
            // The characters we sent against the tokens the server counted → the real characters per
            // token, with 10% margin for the chat template the server wraps around the messages
            long chars = characters(sent);
            charsPerToken = Math.max(1.0, Math.min(charsPerToken, (double) chars / serverTokens * 0.9));
            return call.apply(fit(request));
        }
    }

    /** The request with messages shortened so that its estimated size fits the context. */
    ChatRequest fit(ChatRequest request) {
        long budgetChars = (long) ((contextTokens - RESERVED_OUTPUT_TOKENS) * charsPerToken)
                - toolChars(request.toolSpecifications());
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        long excess = total(messages) - budgetChars;
        if (excess <= 0) return request;

        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0 && lastUser < 0; i--) {
            if (messages.get(i) instanceof UserMessage) lastUser = i;
        }
        // Pass 1: tool results, oldest first; pass 2: other texts (never the system or latest user message)
        for (int pass = 1; pass <= 2 && excess > 0; pass++) {
            for (int i = 0; i < messages.size() && excess > 0; i++) {
                ChatMessage message = messages.get(i);
                boolean candidate = pass == 1
                        ? message instanceof ToolExecutionResultMessage
                        : !(message instanceof SystemMessage) && !(message instanceof ToolExecutionResultMessage)
                                && i != lastUser;
                if (!candidate) continue;
                String text = text(message);
                if (text == null || text.length() <= MIN_KEPT_CHARS) continue;
                // Cut the excess plus room for the marker line that shorten() inserts
                int keep = (int) Math.max(MIN_KEPT_CHARS, text.length() - excess - MARKER_CHARS);
                ChatMessage shortened = withText(message, shorten(text, keep));
                if (shortened == null) continue;
                excess -= characters(message) - characters(shortened);
                messages.set(i, shortened);
            }
        }
        return request.toBuilder().messages(messages).build();
    }

    /** Keeps about two thirds from the start and one third from the end, with a marker in between. */
    static String shorten(String text, int keep) {
        if (text.length() <= keep) return text;
        int head = keep * 2 / 3;
        int tail = keep - head;
        return text.substring(0, head)
                + "\n[… " + (text.length() - keep) + " characters cut to fit the model's context;"
                + " read a smaller part if you need them …]\n"
                + text.substring(text.length() - tail);
    }

    private static String text(ChatMessage message) {
        if (message instanceof ToolExecutionResultMessage r) return r.text();
        if (message instanceof UserMessage u && u.hasSingleText()) return u.singleText();
        if (message instanceof AiMessage ai) return ai.text();
        return null;
    }

    /** The same message with new text, or null when the message kind can't be rebuilt. */
    private static ChatMessage withText(ChatMessage message, String text) {
        if (message instanceof ToolExecutionResultMessage r) {
            return ToolExecutionResultMessage.builder().id(r.id()).toolName(r.toolName()).text(text)
                    .isError(r.isError()).build();
        }
        if (message instanceof UserMessage u) return UserMessage.from(text);
        if (message instanceof AiMessage ai) {
            return ai.hasToolExecutionRequests()
                    ? AiMessage.from(text, ai.toolExecutionRequests())
                    : AiMessage.from(text);
        }
        return null;
    }

    private static long total(List<ChatMessage> messages) {
        long n = 0;
        for (ChatMessage m : messages) n += characters(m);
        return n;
    }

    private static long characters(ChatRequest request) {
        return total(request.messages()) + toolChars(request.toolSpecifications());
    }

    private static long characters(ChatMessage message) {
        if (message instanceof SystemMessage s) return s.text().length();
        if (message instanceof UserMessage u) return u.hasSingleText() ? u.singleText().length() : u.contents().toString().length();
        if (message instanceof ToolExecutionResultMessage r) return r.text().length();
        if (message instanceof AiMessage ai) {
            long n = ai.text() == null ? 0 : ai.text().length();
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest call : ai.toolExecutionRequests()) n += call.name().length() + call.arguments().length();
            }
            return n;
        }
        return message.toString().length();
    }

    private static long toolChars(List<ToolSpecification> tools) {
        return tools == null ? 0 : tools.toString().length(); // close to the JSON schema sent to the server
    }

    // Everything else is the real model's
    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
}
