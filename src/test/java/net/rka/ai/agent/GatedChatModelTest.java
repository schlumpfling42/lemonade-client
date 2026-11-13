package net.rka.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The person sees and may change the request before it is sent and the answer before it is used. */
class GatedChatModelTest {

    private final List<ChatRequest> received = new ArrayList<>();
    private final ChatModel server = new ChatModel() {
        @Override
        public ChatResponse chat(ChatRequest request) {
            received.add(request);
            return ChatResponse.builder().aiMessage(AiMessage.from("server says hi")).build();
        }
    };

    private static ChatRequest request(String text) {
        return ChatRequest.builder().messages(UserMessage.from(text)).build();
    }

    @Test
    void theServerGetsTheRequestAsThePersonLeftItAndTheCallerTheAnswerAsThePersonLeftIt() {
        ModelGate gate = new ModelGate() {
            @Override
            public ChatRequest beforeSend(ChatRequest request) {
                return request("changed question");
            }

            @Override
            public ChatResponse beforeUse(ChatRequest sent, ChatResponse response) {
                return ChatResponse.builder().aiMessage(AiMessage.from("changed answer")).build();
            }
        };

        ChatResponse answer = new GatedChatModel(server, gate).chat(request("original question"));

        assertEquals("changed question", ((UserMessage) received.get(0).messages().get(0)).singleText());
        assertEquals("changed answer", answer.aiMessage().text());
    }

    @Test
    void aCancelledRequestNeverReachesTheServer() {
        ModelGate gate = new ModelGate() {
            @Override
            public ChatRequest beforeSend(ChatRequest request) {
                throw new CancellationException("no");
            }

            @Override
            public ChatResponse beforeUse(ChatRequest sent, ChatResponse response) {
                throw new AssertionError("not reached");
            }
        };

        assertThrows(CancellationException.class, () -> new GatedChatModel(server, gate).chat(request("q")));
        assertTrue(received.isEmpty());
    }

    @Test
    void aFailingServerLetsTheGateGoOfTheCall() {
        List<String> told = new ArrayList<>();
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new IllegalStateException("Connection refused");
            }
        };
        ModelGate gate = new ModelGate() {
            @Override
            public ChatRequest beforeSend(ChatRequest request) {
                return request;
            }

            @Override
            public ChatResponse beforeUse(ChatRequest sent, ChatResponse response) {
                throw new AssertionError("not reached");
            }

            @Override
            public void failed(ChatRequest sent, RuntimeException error) {
                told.add(error.getMessage());
            }
        };

        assertThrows(IllegalStateException.class, () -> new GatedChatModel(failing, gate).chat(request("q")));
        assertEquals(List.of("Connection refused"), told);
    }
}
