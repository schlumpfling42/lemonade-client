package net.rka.ai.agent;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * A blocking {@link ChatModel} on top of a {@link StreamingChatModel}: every graph node still calls
 * {@code model.chat(request)} and gets the complete reply, but while the server generates it, each
 * piece is passed to the turn's {@link LiveChannel} right away — thinking, answer text and tool call
 * arguments — so the window shows them in real time instead of after the whole reply.
 * <p>
 * How it works: {@code chat} starts a streaming request, the handler forwards partial events to the
 * channel and completes a future with the final {@link ChatResponse} (the same one the blocking model
 * would return: text, thinking, tool calls, token usage), and {@code chat} waits for that future. The
 * tracing listener sits on the streaming model, so spans look the same as before.
 * <p>
 * Not implemented yet (could be added):
 * <ul>
 *   <li>Cancelling a reply mid-stream (a Stop button): the handler would need a way to abort the HTTP call.</li>
 *   <li>Telling the listener which graph node a token belongs to (agent or coding job), so partial
 *       output could be styled per phase before the finished reply replaces it.</li>
 * </ul>
 */
final class StreamingChatModelAdapter implements ChatModel {

    private final StreamingChatModel streaming;
    private final LiveChannel live;

    StreamingChatModelAdapter(StreamingChatModel streaming, LiveChannel live) {
        this.streaming = streaming;
        this.live = live;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        streaming.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                live.text(partialResponse);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                live.thinking(partialThinking.text());
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall) {
                live.toolCall(partialToolCall.index(), partialToolCall.name(), partialToolCall.partialArguments());
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        try {
            return done.join();
        } catch (CompletionException e) {
            // Rethrow the server's error itself (e.g. context_length_exceeded, which
            // ContextFittingChatModel recognizes by its message), not the future's wrapper
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw e;
        }
    }

    // Everything else is the streaming model's
    @Override public ChatRequestParameters defaultRequestParameters() { return streaming.defaultRequestParameters(); }
    @Override public List<ChatModelListener> listeners() { return streaming.listeners(); }
    @Override public ModelProvider provider() { return streaming.provider(); }
    @Override public Set<Capability> supportedCapabilities() { return streaming.supportedCapabilities(); }
}
