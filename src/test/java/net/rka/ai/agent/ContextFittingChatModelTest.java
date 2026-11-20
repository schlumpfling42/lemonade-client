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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void aToolCallInAnAnswerCutOffAtTheTokenLimitIsDropped() {
        dev.langchain4j.agent.tool.ToolExecutionRequest call = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("1").name("readFile").arguments("{\"path\":\"a").build();
        ChatModel cut = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest r) {
                return ChatResponse.builder().aiMessage(AiMessage.from("Let me look", List.of(call)))
                        .finishReason(dev.langchain4j.model.output.FinishReason.LENGTH).build();
            }
        };
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("a")).build();

        AiMessage reply = new ContextFittingChatModel(cut, 16_384).chat(request).aiMessage();

        assertEquals("Let me look", reply.text());
        assertFalse(reply.hasToolExecutionRequests());
    }

    @Test
    void anEmptyReplyIsAskedAgainAndOneThatStaysEmptyIsSaidSoAndEmptyOnesInTheHistoryAreLeftOut() {
        List<ChatRequest> received = new ArrayList<>();
        int[] empties = {1}; // the first answer is empty, the next ones are not
        ChatModel flaky = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                received.add(request);
                return ChatResponse.builder().aiMessage(empties[0]-- > 0 ? AiMessage.from("") : AiMessage.from("fine")).build();
            }
        };
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("a"), AiMessage.from(""), UserMessage.from("b")).build();

        ChatResponse response = new ContextFittingChatModel(flaky, 16_384).chat(request);

        assertEquals("fine", response.aiMessage().text());
        assertEquals(2, received.size(), "asked again once");
        assertEquals(2, received.get(0).messages().size(), "the empty reply of an earlier turn is not sent");

        ChatModel dead = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest r) {
                return ChatResponse.builder().aiMessage(AiMessage.from("")).build();
            }
        };
        assertEquals(ContextFittingChatModel.EMPTY_REPLY, new ContextFittingChatModel(dead, 16_384).chat(request).aiMessage().text());
    }

    /** A model that thinks first: with a small limit it ends in the middle of its thinking and has said nothing. */
    private static ChatModel thinksFirst(List<Integer> limits, int neededTokens) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                limits.add(request.maxOutputTokens());
                boolean enough = request.maxOutputTokens() >= neededTokens;
                return ChatResponse.builder().aiMessage(AiMessage.from(enough ? "fine" : ""))
                        .finishReason(enough ? dev.langchain4j.model.output.FinishReason.STOP : dev.langchain4j.model.output.FinishReason.LENGTH).build();
            }
        };
    }

    @Test
    void anAnswerCutOffBeforeItSaidAnythingIsAskedOnceWithABiggerLimitNotTheSameRequestAgain() {
        List<Integer> limits = new ArrayList<>();
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("what can you do?")).maxOutputTokens(300).build();

        ChatResponse response = new ContextFittingChatModel(thinksFirst(limits, 1_000), 16_384).chat(request);

        assertEquals("fine", response.aiMessage().text());
        assertEquals(List.of(300, 1_200), limits);
    }

    @Test
    void anAnswerThatIsCutOffAgainIsSaidSoAfterOneBiggerTry() {
        List<Integer> limits = new ArrayList<>();
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("what can you do?")).maxOutputTokens(300).build();

        ChatResponse response = new ContextFittingChatModel(thinksFirst(limits, 1_000_000), 16_384).chat(request);

        assertEquals(List.of(300, 1_200), limits, "two requests, not three of the same");
        assertTrue(response.aiMessage().text().contains("used all 1200 tokens"), response.aiMessage().text());
    }

    @Test
    void theEstimateLearnsFromThePromptSizeTheServerReportsInBothDirections() {
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("x".repeat(3_600))).build();
        // 3,600 characters counted as 900 tokens: 4 characters per token, so with the 10% margin 3.6
        ChatModel prose = reporting(new dev.langchain4j.model.output.TokenUsage(900, 1));
        ContextFittingChatModel model = new ContextFittingChatModel(prose, 16_384);
        model.chat(request);
        assertEquals(3.6, model.charsPerToken(), 0.01);
        // 3,600 characters counted as 3,600 tokens: dense text, the estimate goes down (to the floor of 1.0)
        ChatModel dense = reporting(new dev.langchain4j.model.output.TokenUsage(3_600, 1));
        ContextFittingChatModel other = new ContextFittingChatModel(dense, 16_384);
        other.chat(request);
        assertEquals(1.0, other.charsPerToken(), 0.01);
        // no usage reported: unchanged
        ContextFittingChatModel silent = new ContextFittingChatModel(reporting(null), 16_384);
        silent.chat(request);
        assertEquals(3.0, silent.charsPerToken(), 0.0);
    }

    /** A server that answers "ok" and reports {@code usage} (none for null). */
    private static ChatModel reporting(dev.langchain4j.model.output.TokenUsage usage) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).tokenUsage(usage).build();
            }
        };
    }

    @Test
    void smallRequestsPassUnchanged() {
        FakeServer server = new FakeServer(1_000_000, 3);
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("hi")).build();
        new ContextFittingChatModel(server, 16_384).chat(request);
        assertSame(request, server.received.get(0));
    }

    @Test
    void shorteningIsTheSameOnEveryCallSoTheServerCacheStaysValid() {
        // The same turn, one call later: one more tool round at the end. Every message the first request
        // already had must be sent exactly as before (a changed old message makes the server re-read all)
        FakeServer server = new FakeServer(1_000_000, 3);
        ContextFittingChatModel model = new ContextFittingChatModel(server, 16_384);
        List<ChatMessage> first = turnWithTwoBigReads();
        ToolExecutionRequest read3 = ToolExecutionRequest.builder().id("r3").name("readFile").arguments("{}").build();
        List<ChatMessage> second = new ArrayList<>(first);
        second.add(AiMessage.from(List.of(read3)));
        second.add(ToolExecutionResultMessage.from(read3, "C".repeat(10_000)));

        model.chat(ChatRequest.builder().messages(first).build());
        model.chat(ChatRequest.builder().messages(second).build());

        List<ChatMessage> sentFirst = server.received.get(0).messages();
        List<ChatMessage> sentSecond = server.received.get(1).messages();
        assertEquals(sentFirst, sentSecond.subList(0, sentFirst.size()));
        assertTrue(((ToolExecutionResultMessage) sentFirst.get(3)).text().length() < 2_200, "cut to the fixed size");
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
        // The server counts 2 characters per token and has less room than configured, so the first
        // (3 chars/token) fit is still too big for it: the error's token count drives a second, tighter fit
        FakeServer server = new FakeServer(20_000, 2);
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
