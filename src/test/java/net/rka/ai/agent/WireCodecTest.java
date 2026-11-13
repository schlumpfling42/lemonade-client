package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A request and an answer as text that a person can change, and back. */
class WireCodecTest {

    private static final ToolExecutionRequest CALL = ToolExecutionRequest.builder().id("call_1").name("readFile")
            .arguments("{\"path\":\"A.java\"}").build();

    private static ChatRequest request() {
        List<ChatMessage> messages = List.of(SystemMessage.from("You are an assistant."), UserMessage.from("read A.java"),
                AiMessage.from(List.of(CALL)), ToolExecutionResultMessage.from(CALL, "class A {}"),
                AiMessage.from("It declares class A."), UserMessage.from("thanks"));
        return ChatRequest.builder().messages(messages)
                .toolSpecifications(ToolSpecification.builder().name("readFile").description("Reads a file").build()).build();
    }

    @Test
    void aRequestIsTextAndGivesBackTheSameMessagesWhenNothingWasChanged() {
        ChatRequest original = request();

        String text = WireCodec.requestText(original);
        ChatRequest back = WireCodec.parseRequest(text, original);

        assertTrue(text.contains("=== system ===\nYou are an assistant.\n") && text.contains("=== tool calls ===")
                && text.contains("=== tool result id=call_1 tool=readFile ===\nclass A {}\n"), text);
        assertEquals(original.messages(), back.messages());
        for (int i = 0; i < original.messages().size(); i++) assertSame(original.messages().get(i), back.messages().get(i), "message " + i + " is the original");
        assertEquals(original.parameters().toolSpecifications(), back.parameters().toolSpecifications(), "the tool definitions are kept");
    }

    @Test
    void aChangedMessageIsTheOnlyThingThatChanges() {
        ChatRequest original = request();

        ChatRequest changed = WireCodec.parseRequest(WireCodec.requestText(original).replace("read A.java", "read B.java instead"), original);

        assertEquals("read B.java instead", ((UserMessage) changed.messages().get(1)).singleText());
        assertSame(original.messages().get(0), changed.messages().get(0));
        assertSame(original.messages().get(3), changed.messages().get(3));
        assertEquals(original.messages().size(), changed.messages().size());
    }

    @Test
    void theArgumentsOfAToolCallAreReadableAndEditable() {
        ChatRequest original = request();
        String text = WireCodec.requestText(original);
        assertTrue(text.contains("\"path\" : \"A.java\""), "the arguments are JSON in the text, not a string: " + text);

        ChatRequest changed = WireCodec.parseRequest(text.replace("\"A.java\"", "\"B.java\""), original);

        AiMessage call = (AiMessage) changed.messages().get(2);
        assertEquals("{\"path\":\"B.java\"}", call.toolExecutionRequests().get(0).arguments());
        assertEquals("call_1", call.toolExecutionRequests().get(0).id(), "the id stays: the result belongs to it");
    }

    @Test
    void aLineThatLooksLikeABlockStartInATextIsNotOne() {
        String tricky = "before\n=== user ===\nafter\n\\=== already escaped";
        ChatRequest original = ChatRequest.builder().messages(List.of(UserMessage.from(tricky))).build();

        ChatRequest back = WireCodec.parseRequest(WireCodec.requestText(original), original);

        assertEquals(tricky, ((UserMessage) back.messages().get(0)).singleText());
    }

    @Test
    void textThatIsNotInTheFormatIsToldBackWithTheBlock() {
        ChatRequest original = request();
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WireCodec.parseRequest("hello", original)).getMessage().contains("before the first block"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WireCodec.parseRequest("=== tool calls ===\nnot json", original)).getMessage().contains("not valid JSON"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WireCodec.parseRequest("=== tool result ===\nx", original)).getMessage().contains("id=<id> tool=<name>"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> WireCodec.parseRequest("", original)).getMessage().contains("no message"));
    }

    @Test
    void anAnswerIsTextAndTheSameAnswerComesBackWhenNothingWasChanged() {
        ChatResponse original = ChatResponse.builder()
                .aiMessage(AiMessage.builder().text("I will read it.").thinking("The user wants A.java.").toolExecutionRequests(List.of(CALL)).build())
                .tokenUsage(new TokenUsage(10, 5)).build();

        String text = WireCodec.responseText(original);

        assertTrue(text.contains("=== thinking ===") && text.contains("=== text ===\nI will read it.") && text.contains("=== tool calls ==="), text);
        assertSame(original, WireCodec.parseResponse(text, original));
    }

    @Test
    void aChangedAnswerKeepsTheTokenCountsAndTakesTheNewTextAndCalls() {
        ChatResponse original = ChatResponse.builder().aiMessage(AiMessage.from("Done.")).tokenUsage(new TokenUsage(10, 5)).build();

        ChatResponse changed = WireCodec.parseResponse("=== text ===\nNot done yet.\n\n=== tool calls ===\n[{\"id\":\"c9\",\"name\":\"listFiles\",\"arguments\":{}}]\n", original);

        assertEquals("Not done yet.", changed.aiMessage().text());
        assertEquals("listFiles", changed.aiMessage().toolExecutionRequests().get(0).name());
        assertEquals(new TokenUsage(10, 5), changed.tokenUsage());
    }
}
