package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextFittingChatModelTest {

    /** Records what reached the "server"; rejects requests above maxChars like llama-server does. */
    private static final class FakeServer implements ChatModel {
        final List<ChatRequest> received = new ArrayList<>();
        final int maxChars;
        final int charsPerToken;

        FakeServer(int maxChars, int charsPerToken) {
            this.maxChars = maxChars;
            this.charsPerToken = charsPerToken;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            received.add(request);
            int chars = request.messages().stream().mapToInt(ContextFittingChatModelTest::length).sum();
            if (chars > maxChars) {
                throw new InvalidRequestException("{\"error\":{\"message\":\"request (" + chars / charsPerToken
                        + " tokens) exceeds the available context size (" + maxChars / charsPerToken + " tokens)\"}}");
            }
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        }
    }

    private static int length(ChatMessage m) {
        if (m instanceof SystemMessage s) return s.text().length();
        if (m instanceof UserMessage u) return u.singleText().length();
        if (m instanceof ToolExecutionResultMessage r) return r.text().length();
        return ((AiMessage) m).text() == null ? 0 : ((AiMessage) m).text().length();
    }

    private static List<ChatMessage> turnWithTwoBigReads() {
        ToolExecutionRequest read1 = ToolExecutionRequest.builder().id("r1").name("readFile").arguments("{}").build();
        ToolExecutionRequest read2 = ToolExecutionRequest.builder().id("r2").name("readFile").arguments("{}").build();
        return List.of(
                SystemMessage.from("You are a helpful agent."),
                UserMessage.from("explain the UI and the agent"),
                AiMessage.from(List.of(read1)),
                ToolExecutionResultMessage.from(read1, "A".repeat(50_000)),
                AiMessage.from(List.of(read2)),
                ToolExecutionResultMessage.from(read2, "B".repeat(25_000)));
    }

    @Test
    void smallRequestsPassUnchanged() {
        FakeServer server = new FakeServer(1_000_000, 3);
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("hi")).build();
        new ContextFittingChatModel(server, 16_384).chat(request);
        assertSame(request, server.received.get(0));
    }

    @Test
    void oldestToolResultsAreShortenedFirst() {
        FakeServer server = new FakeServer(1_000_000, 3);
        new ContextFittingChatModel(server, 16_384).chat(ChatRequest.builder().messages(turnWithTwoBigReads()).build());

        List<ChatMessage> sent = server.received.get(0).messages();
        int total = sent.stream().mapToInt(ContextFittingChatModelTest::length).sum();
        assertTrue(total <= (16_384 - ContextFittingChatModel.RESERVED_OUTPUT_TOKENS) * 3, "fits: " + total);
        String first = ((ToolExecutionResultMessage) sent.get(3)).text();
        String second = ((ToolExecutionResultMessage) sent.get(5)).text();
        assertTrue(first.contains("characters cut to fit the model's context"), "the older read is cut");
        assertEquals("B".repeat(25_000), second, "the newest read is kept while cutting the older one is enough");
        assertEquals("explain the UI and the agent", ((UserMessage) sent.get(1)).singleText());
        assertEquals("r1", ((ToolExecutionResultMessage) sent.get(3)).id());
    }

    @Test
    void retriesOnceWithTheServersTokenCount() {
        // The server counts 2 characters per token, so the first (3 chars/token) estimate is too big
        FakeServer server = new FakeServer(16_384 * 2, 2);
        ChatResponse response = new ContextFittingChatModel(server, 16_384)
                .chat(ChatRequest.builder().messages(turnWithTwoBigReads()).build());

        assertEquals("ok", response.aiMessage().text());
        assertEquals(2, server.received.size());
    }

    @Test
    void otherErrorsAreNotRetried() {
        ChatModel failing = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                throw new InvalidRequestException("model not loaded");
            }
        };
        assertThrows(InvalidRequestException.class, () -> new ContextFittingChatModel(failing, 16_384)
                .chat(ChatRequest.builder().messages(UserMessage.from("hi")).build()));
    }

    @Test
    void shortenKeepsStartAndEnd() {
        String cut = ContextFittingChatModel.shorten("start-" + "x".repeat(1000) + "-end", 300);
        assertTrue(cut.startsWith("start-"));
        assertTrue(cut.endsWith("-end"));
        assertTrue(cut.contains("characters cut"));
    }
}
