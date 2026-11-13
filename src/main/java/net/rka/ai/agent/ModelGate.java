package net.rka.ai.agent;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * Holds every call to the language model for a person: they see the whole request before it is sent and may change it, and they see
 * the whole answer before the application uses it and may change that too. Both methods block until the person has decided, on the
 * thread of the call (a node of the graph), like the approvals do.
 *
 * <p>The gate sits directly in front of the model client ({@link GatedChatModel}), below the fitting of the request to the context
 * window: what the person sees is what the server receives, and what they release is what the graph reads.
 */
public interface ModelGate {

    /**
     * @param request what the application wants to send
     * @return the request that is sent: the same one, or the person's version of it
     * @throws java.util.concurrent.CancellationException when the person cancels the call: the call fails and the node that made it
     *                                                    handles that like any model error
     */
    ChatRequest beforeSend(ChatRequest request);

    /**
     * @param sent     the request that was sent
     * @param response what the server answered
     * @return the response the application uses: the same one, or the person's version of it
     * @throws java.util.concurrent.CancellationException when the person cancels the call
     */
    ChatResponse beforeUse(ChatRequest sent, ChatResponse response);

    /** The call failed after {@link #beforeSend} (the server refused, a timeout, a stop): the gate lets go of it. */
    default void failed(ChatRequest sent, RuntimeException error) {
    }
}
