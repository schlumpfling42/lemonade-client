package net.rka.ai.ui;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import javafx.application.Platform;
import net.rka.ai.agent.ModelGate;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * The {@link ModelGate} of the manual mode: every call to the model waits here until the person has seen it in the {@link WireWindow}
 * and decided. The calls come from the threads of the graph, the window lives on the FX thread; each wait is a future that the window
 * completes with the request or the answer to go on with, or fails with a cancellation. One call at a time is shown (a second call waits
 * for the first to be settled), so the window never mixes two calls.
 *
 * <p>The gate can be created before the window exists (the agent is built before the window opens); the window attaches itself. A call
 * made without a window passes unchanged.
 */
public final class WireGate implements ModelGate {

    private final ReentrantLock one = new ReentrantLock(true);
    private final AtomicInteger counter = new AtomicInteger();
    private final ThreadLocal<Integer> number = new ThreadLocal<>();
    private volatile WireView window;
    /** The conversation whose turn is running: the calls it makes belong to it. */
    private volatile String conversation = "";
    private volatile boolean holdRequests;
    private volatile boolean holdResponses;

    void attach(WireView window) {
        this.window = window;
    }

    /** Called when a turn starts: the calls made until the next turn belong to this conversation (one turn runs at a time). */
    void conversation(String id) {
        this.conversation = id == null ? "" : id;
    }

    String conversation() {
        return conversation;
    }

    void holdRequests(boolean hold) {
        this.holdRequests = hold;
    }

    void holdResponses(boolean hold) {
        this.holdResponses = hold;
    }

    /** Whether the answers wait for the person: what the model writes then shows in the traffic view only, and reaches the chat when it is let through. */
    boolean holdsResponses() {
        return holdResponses;
    }

    @Override
    public ChatRequest beforeSend(ChatRequest request) {
        WireView shown = window;
        int n = counter.incrementAndGet();
        number.set(n);
        if (shown == null) return request;
        one.lock(); // released by beforeUse, or here when the call is cancelled
        try {
            ChatRequest toSend = request;
            if (holdRequests) {
                toSend = await(answer -> shown.askRequest(n, request, answer));
            } else {
                Platform.runLater(() -> shown.showRequest(n, request));
            }
            Platform.runLater(() -> shown.waitingForModel(n, request));
            return toSend;
        } catch (RuntimeException e) {
            release(shown);
            throw e;
        }
    }

    @Override
    public ChatResponse beforeUse(ChatRequest sent, ChatResponse response) {
        WireView shown = window;
        int n = number.get() == null ? 0 : number.get();
        number.remove();
        if (shown == null) return response;
        try {
            if (!holdResponses) {
                Platform.runLater(() -> shown.showResponse(n, response));
                return response;
            }
            return await(answer -> shown.askResponse(n, response, answer));
        } finally {
            release(shown);
        }
    }

    @Override
    public void failed(ChatRequest sent, RuntimeException error) {
        number.remove();
        WireView shown = window;
        if (shown != null) {
            release(shown);
            Platform.runLater(() -> shown.failed(String.valueOf(error.getMessage())));
        }
    }

    private void release(WireView shown) {
        if (one.isHeldByCurrentThread()) one.unlock();
        Platform.runLater(shown::idle);
    }

    /** Shows something to the person and waits for what they decide; an interrupted wait (the Stop button) cancels the call. */
    private <T> T await(Consumer<CompletableFuture<T>> show) {
        CompletableFuture<T> answer = new CompletableFuture<>();
        Platform.runLater(() -> show.accept(answer));
        try {
            return answer.get();
        } catch (InterruptedException e) {
            answer.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("stopped by the user");
        } catch (ExecutionException e) {
            throw e.getCause() instanceof CancellationException cancelled ? cancelled : new CancellationException(String.valueOf(e.getCause()));
        } catch (CancellationException e) {
            throw new CancellationException("the call was cancelled by the user");
        }
    }

    /** What the model writes while it writes: shown live in the window, not yet in the chat. */
    void live(String kind, String delta) {
        WireView shown = window;
        if (shown != null) Platform.runLater(() -> shown.live(kind, delta));
    }
}
