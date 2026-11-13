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
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialResponse;
import net.rka.ai.tracing.AgentLog;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** How long to wait before asking again after the connection to the server broke. */
    static final long RETRY_PAUSE_MS = 2_000;

    /**
     * One reply. When the connection breaks before the server sent anything (it restarted, dropped the request:
     * "header parser received no bytes", "closed") the request is sent once more after a short pause: nothing was
     * shown yet, so the user sees no difference, and a job doesn't end for a hiccup of the server. A failure after
     * the first token is not retried (the window already shows part of the reply), nor is an answer of the server
     * itself (a refused request would be refused again).
     */
    @Override
    public ChatResponse chat(ChatRequest request) {
        AtomicBoolean first = new AtomicBoolean(true);
        try {
            return once(request, first);
        } catch (RuntimeException e) {
            if (!first.get() || !connectionBroke(e)) throw e;
            AgentLog.current().line("   the connection to the model server broke before a reply (" + e.getMessage()
                    + "): asking again in " + RETRY_PAUSE_MS / 1000 + " s");
            try {
                Thread.sleep(RETRY_PAUSE_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return once(request, first);
        }
    }

    /**
     * A model that writes the same lines over and over is stopped: the stream is cancelled and the reply is what came before the
     * repeats (for thinking: no text, so the reply counts as empty and is asked again, see {@link ContextFittingChatModel}).
     */
    private void stopIfLooping(StringBuilder written, String kind, StreamingHandle handle, CompletableFuture<ChatResponse> done, String what) {
        java.util.Optional<String> trimmed = RepeatGuard.loop(written.toString());
        if (trimmed.isEmpty() || done.isDone()) return;
        AgentLog.current().line("   ⛔ [" + live.node() + "] the model repeats itself while " + what + " (" + written.length()
                + " characters so far): stopped, the repeats are cut");
        handle.cancel();
        AiMessage reply = kind.isEmpty() ? AiMessage.from(trimmed.get()) : AiMessage.from("");
        done.complete(ChatResponse.builder().aiMessage(reply).build());
    }

    /** Whether an {@link java.io.IOException} is the cause (no answer of the server, which other exceptions are). */
    private static boolean connectionBroke(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof java.io.IOException) return true;
        }
        return false;
    }

    /** The repeat check runs each time this many characters have come. */
    private static final int CHECK_EVERY = 60;

    private ChatResponse once(ChatRequest request, AtomicBoolean first) {
        first.set(true);
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        long start = System.nanoTime();
        long[] firstAt = {0}; // when the first token came: the writing speed is measured from there
        // The agent log notes when the first token arrived: until then the server reads the prompt, so a long
        // "first token after" with few new characters means it couldn't reuse its cache
        Runnable firstToken = () -> {
            if (first.getAndSet(false)) {
                firstAt[0] = System.nanoTime();
                AgentLog.current().line("   first token after " + AgentLog.duration(System.nanoTime() - start)
                        + " [" + live.node() + "] (reading the prompt), then writing");
            }
        };
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        int[] checked = {0};         // how much of the text and the thinking the repeat check has seen
        int[] checkedThinking = {0};
        java.util.concurrent.atomic.AtomicReference<StreamingHandle> stream = new java.util.concurrent.atomic.AtomicReference<>();
        streaming.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                firstToken.run();
                stream.set(context.streamingHandle());
                if (done.isDone()) return;
                live.text(partialResponse.text());
                text.append(partialResponse.text());
                if (text.length() - checked[0] >= CHECK_EVERY) {
                    checked[0] = text.length();
                    stopIfLooping(text, "", context.streamingHandle(), done, "writing");
                }
            }

            @Override
            public void onPartialResponse(String partialResponse) {
                firstToken.run(); // a server that has no streaming handle to cancel with: the text goes through
                if (done.isDone()) return;
                live.text(partialResponse);
                text.append(partialResponse);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                firstToken.run();
                if (done.isDone()) return;
                live.thinking(partialThinking.text());
                thinking.append(partialThinking.text());
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                firstToken.run();
                stream.set(context.streamingHandle());
                if (done.isDone()) return;
                live.thinking(partialThinking.text());
                thinking.append(partialThinking.text());
                if (thinking.length() - checkedThinking[0] >= CHECK_EVERY) {
                    checkedThinking[0] = thinking.length();
                    stopIfLooping(thinking, "thinking", context.streamingHandle(), done, "thinking");
                }
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall) {
                firstToken.run();
                live.toolCall(partialToolCall.index(), partialToolCall.name(), partialToolCall.partialArguments());
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                Integer out = response.tokenUsage() == null ? null : response.tokenUsage().outputTokenCount();
                long writing = System.nanoTime() - firstAt[0];
                if (firstAt[0] != 0 && out != null && writing > 0) {
                    AgentLog.current().line(String.format(java.util.Locale.ROOT, "   wrote %,d tokens in %s (%.1f tokens/s)",
                            out, AgentLog.duration(writing), out / (writing / 1e9)));
                }
                done.complete(response);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        try {
            return done.get();
        } catch (InterruptedException e) {
            // The turn was stopped (the stop button interrupts its thread): the stream is cancelled and the turn ends here
            StreamingHandle handle = stream.get();
            if (handle != null) handle.cancel();
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Stopped");
        } catch (java.util.concurrent.ExecutionException e) {
            // Rethrow the server's error itself (e.g. context_length_exceeded, which
            // ContextFittingChatModel recognizes by its message), not the future's wrapper
            if (e.getCause() instanceof RuntimeException cause) throw cause;
            throw new CompletionException(e.getCause());
        }
    }

    // Everything else is the streaming model's
    @Override public ChatRequestParameters defaultRequestParameters() { return streaming.defaultRequestParameters(); }
    @Override public List<ChatModelListener> listeners() { return streaming.listeners(); }
    @Override public ModelProvider provider() { return streaming.provider(); }
    @Override public Set<Capability> supportedCapabilities() { return streaming.supportedCapabilities(); }
}
