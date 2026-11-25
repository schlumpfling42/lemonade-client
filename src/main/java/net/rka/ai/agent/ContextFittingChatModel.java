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
    /** What a message is cut to first (head + tail); only if that isn't enough, messages go down to {@link #MIN_KEPT_CHARS}. */
    static final int STABLE_KEPT_CHARS = 2000;
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

    /** How often a request is sent when the model answers with nothing at all. */
    static final int EMPTY_REPLY_TRIES = 3;
    static final String EMPTY_REPLY = "The model returned an empty reply (tried " + EMPTY_REPLY_TRIES + " times). Ask again, or rephrase.";

    /**
     * A reply with no text and no tool call is a failed generation (a local reasoning model sometimes ends after its thinking):
     * the same request is sent again, and when it stays empty the reply says so, so no empty message is ever stored in the
     * conversation, where it makes every later request fail the same way.
     */
    private static ChatResponse answered(ChatRequest request, Function<ChatRequest, ChatResponse> call) {
        ChatResponse response = null;
        boolean raised = false;
        for (int i = 0; i < EMPTY_REPLY_TRIES; i++) {
            ChatResponse raw = call.apply(request);
            boolean cutOff = raw.finishReason() == dev.langchain4j.model.output.FinishReason.LENGTH;
            boolean halfCall = cutOff && raw.aiMessage() != null && raw.aiMessage().hasToolExecutionRequests();
            response = withoutCutOffCalls(raw);
            if (!isEmpty(response.aiMessage()) && !halfCall) return response;
            if (cutOff) {
                // The limit on the answer ran out before the model had said anything usable (a reasoning model thinks first): the same
                // request would end the same way. Once more with a bigger limit; if that is no better, or there is no room, it is said so.
                Integer limit = request.maxOutputTokens();
                int bigger = limit == null ? 0 : Math.min(limit * 4, Math.max(limit, Agent.maxOutputTokens()));
                if (!raised && limit != null && bigger > limit) {
                    request = request.toBuilder().maxOutputTokens(bigger).build();
                    raised = true;
                    continue;
                }
                if (!isEmpty(response.aiMessage())) return response; // the text it had written, without its half call
                return ChatResponse.builder().aiMessage(AiMessage.from("The model used all " + (limit == null ? "its" : request.maxOutputTokens())
                        + " tokens of its answer before it said anything (it was still thinking). Ask again, or rephrase."))
                        .modelName(response.modelName()).build();
            }
        }
        return ChatResponse.builder().aiMessage(AiMessage.from(EMPTY_REPLY)).modelName(response.modelName()).build();
    }

    /**
     * An answer cut off at the token limit that ends in a tool call has a half-written call (broken arguments), which would break
     * every later request of the chat: the call is dropped, and an answer with nothing else counts as empty.
     */
    private static ChatResponse withoutCutOffCalls(ChatResponse response) {
        AiMessage reply = response.aiMessage();
        if (response.finishReason() != dev.langchain4j.model.output.FinishReason.LENGTH || reply == null || !reply.hasToolExecutionRequests()) {
            return response;
        }
        return response.toBuilder().aiMessage(reply.text() == null ? AiMessage.from("") : AiMessage.from(reply.text())).build();
    }

    private static boolean isEmpty(AiMessage reply) {
        return reply != null && (reply.text() == null || reply.text().isBlank()) && !reply.hasToolExecutionRequests();
    }

    /** The request without the empty replies an earlier turn may have stored: they make the server answer nothing again. */
    private static ChatRequest withoutEmptyReplies(ChatRequest request) {
        if (request.messages().stream().noneMatch(m -> m instanceof AiMessage ai && isEmpty(ai))) return request;
        List<ChatMessage> kept = request.messages().stream().filter(m -> !(m instanceof AiMessage ai && isEmpty(ai))).toList();
        return request.toBuilder().messages(kept).build();
    }

    /** Fits the request and sends it; on a "too big" answer from the server, corrects the estimate once and retries. */
    private ChatResponse send(ChatRequest request, Function<ChatRequest, ChatResponse> model) {
        Function<ChatRequest, ChatResponse> call = r -> answered(r, model);
        ChatRequest sent = fit(withoutEmptyReplies(request));
        try {
            ChatResponse response = call.apply(sent);
            calibrate(sent, response);
            return response;
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

    /** The most characters per token the estimate may assume (English prose is about 4, source code about 3). */
    static final double MAX_CHARS_PER_TOKEN = 4.0;

    /**
     * Learns the real characters per token from the prompt size the server reports for a request that worked, in both
     * directions (an error only ever lowered it): the start value of 3.0 is cautious for prose, and the cut in
     * {@link #fit} then happens, and costs the server its cache, earlier than the context requires. The same 10% margin as
     * after an error covers the chat template; if the estimate is still too high, the error path lowers it again.
     */
    private void calibrate(ChatRequest sent, ChatResponse response) {
        var usage = response.tokenUsage();
        if (usage == null || usage.inputTokenCount() == null || usage.inputTokenCount() <= 0) return;
        double observed = (double) characters(sent) / usage.inputTokenCount() * 0.9;
        charsPerToken = Math.max(1.0, Math.min(MAX_CHARS_PER_TOKEN, observed));
    }

    double charsPerToken() {
        return charsPerToken;
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
        // Messages are cut to FIXED sizes, oldest first, never "just enough": a message's shortened text then
        // depends only on the message itself, so the next request of the turn shortens it exactly the same
        // way and the model server can reuse its cache for everything up to the newest cut. (Cutting just the
        // excess would change an old message on every call and make the server re-read the whole prompt.)
        // Stage 1 cuts to STABLE_KEPT_CHARS, stage 2 (only if still too big) to MIN_KEPT_CHARS. In each stage:
        // tool results first, then other texts; never the system message or the latest user message.
        for (int keep : new int[] {STABLE_KEPT_CHARS, MIN_KEPT_CHARS}) {
            for (int pass = 1; pass <= 2 && excess > 0; pass++) {
                for (int i = 0; i < messages.size() && excess > 0; i++) {
                    ChatMessage message = messages.get(i);
                    boolean candidate = pass == 1
                            ? message instanceof ToolExecutionResultMessage
                            : !(message instanceof SystemMessage) && !(message instanceof ToolExecutionResultMessage)
                                    && i != lastUser;
                    if (!candidate) continue;
                    String text = text(message);
                    if (text == null || text.length() <= keep + MARKER_CHARS) continue; // short enough (or cut already)
                    ChatMessage shortened = withText(message, shorten(text, keep));
                    if (shortened == null) continue;
                    excess -= characters(message) - characters(shortened);
                    messages.set(i, shortened);
                }
            }
        }
        return request.toBuilder().messages(messages).build();
    }

    /** Keeps about two thirds from the start and one third from the end, with a marker in between.
     *  If the text is JSON, tries to keep valid JSON by cutting at field boundaries. */
    static String shorten(String text, int keep) {
        if (text.length() <= keep) return text;
        // Try to keep JSON valid: cut at field boundaries instead of mid-character
        String jsonShort = shortenJson(text, keep);
        if (jsonShort != null) return jsonShort;
        // Fallback: blind cut
        int head = keep * 2 / 3;
        int tail = keep - head;
        return text.substring(0, head)
                + "\n[… " + (text.length() - keep) + " characters cut to fit the model's context;"
                + " read a smaller part if you need them …]\n"
                + text.substring(text.length() - tail);
    }

    /** Try to shorten JSON text while keeping it valid. Returns null if not JSON or can't be shortened. */
    private static String shortenJson(String text, int keep) {
        if (text.isBlank() || (text.charAt(0) != '{' && text.charAt(0) != '[')) return null;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(text);
            if (keep < 50) return null; // too small to be useful
            // Walk the tree and shorten long string values
            com.fasterxml.jackson.databind.JsonNode shortened = shortenNode(node, keep, mapper);
            if (shortened == null) return null;
            String result = mapper.writeValueAsString(shortened);
            // If we couldn't shorten enough, return null to fall back to blind cut
            if (result.length() > keep + MARKER_CHARS) return null;
            return result;
        } catch (Exception e) {
            return null; // not valid JSON
        }
    }

    /** Recursively shorten a JSON node, keeping total string length under maxChars. */
    private static com.fasterxml.jackson.databind.JsonNode shortenNode(
            com.fasterxml.jackson.databind.JsonNode node, int maxChars,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (node.isTextual()) {
            String text = node.asText();
            if (text.length() <= maxChars) return node;
            int head = maxChars * 2 / 3;
            int tail = Math.max(maxChars - head, 20);
            return new com.fasterxml.jackson.databind.node.TextNode(text.substring(0, head)
                    + "\n[… " + (text.length() - maxChars) + " chars cut …]\n"
                    + text.substring(Math.max(0, text.length() - tail)));
        }
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode obj = mapper.createObjectNode();
            int remaining = maxChars;
            for (var it = node.fields(); it.hasNext(); ) {
                var entry = it.next();
                com.fasterxml.jackson.databind.JsonNode value = shortenNode(entry.getValue(), remaining, mapper);
                if (value != null) {
                    obj.set(entry.getKey(), value);
                    // Rough estimate: field name + colon + value length
                    remaining -= entry.getKey().length() + 2 + value.toString().length();
                }
            }
            return obj;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
            int remaining = maxChars;
            for (var elem : node) {
                com.fasterxml.jackson.databind.JsonNode shortened = shortenNode(elem, remaining, mapper);
                if (shortened != null) {
                    arr.add(shortened);
                    remaining -= shortened.toString().length();
                }
            }
            return arr;
        }
        return node; // numbers, booleans, null: keep as-is
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
