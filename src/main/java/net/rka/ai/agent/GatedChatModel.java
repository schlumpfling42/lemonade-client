package net.rka.ai.agent;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.List;
import java.util.Set;

/**
 * Every call to the model goes through a {@link ModelGate} first and last: the request as the person leaves it is what the delegate
 * (the real client) gets, and the response as the person leaves it is what the caller gets. A model adapter, not graph logic: the
 * nodes keep calling {@code model.chat(...)} unchanged. Without a gate the application does not wrap the model at all.
 */
final class GatedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ModelGate gate;

    GatedChatModel(ChatModel delegate, ModelGate gate) {
        this.delegate = delegate;
        this.gate = gate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        return through(request, toSend -> delegate.chat(toSend));
    }

    @Override
    public ChatResponse chat(ChatRequest request, ChatRequestOptions options) {
        return through(request, toSend -> delegate.chat(toSend, options));
    }

    private ChatResponse through(ChatRequest request, java.util.function.Function<ChatRequest, ChatResponse> call) {
        ChatRequest toSend = gate.beforeSend(request);
        ChatResponse response;
        try {
            response = call.apply(toSend);
        } catch (RuntimeException e) {
            gate.failed(toSend, e);
            throw e;
        }
        return gate.beforeUse(toSend, response);
    }

    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public List<ChatModelListener> listeners() { return delegate.listeners(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }
}
