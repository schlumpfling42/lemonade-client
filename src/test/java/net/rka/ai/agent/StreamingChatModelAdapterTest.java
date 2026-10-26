package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StreamingChatModelAdapterTest {

    private static final ChatRequest REQUEST = ChatRequest.builder().messages(UserMessage.from("hi")).build();

    /** Records the live pieces in the order they arrive. */
    private static final class Recorder implements Progress.ProgressListener {
        final List<String> events = new ArrayList<>();

        @Override public void onProgress(Progress progress) { }
        @Override public void onPartialThinking(String delta) { events.add("thinking:" + delta); }
        @Override public void onPartialText(String delta) { events.add("text:" + delta); }
        @Override public void onPartialToolCall(int index, String name, String delta) { events.add("call" + index + ":" + name + ":" + delta); }
    }

    @Test
    void piecesGoLiveAndTheCompleteReplyIsReturned() {
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("c1").name("readFile").arguments("{\"path\":\"a\"}").build();
        ChatResponse finished = ChatResponse.builder()
                .aiMessage(AiMessage.builder().thinking("look").text("Reading it.").toolExecutionRequests(List.of(call)).build())
                .build();
        StreamingChatModel server = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialThinking(new PartialThinking("lo"));
                handler.onPartialThinking(new PartialThinking("ok"));
                handler.onPartialResponse("Reading ");
                handler.onPartialResponse("it.");
                handler.onPartialToolCall(PartialToolCall.builder().index(0).id("c1").name("readFile").partialArguments("{\"pa").build());
                handler.onCompleteResponse(finished);
            }
        };
        LiveChannel live = new LiveChannel();
        Recorder recorder = new Recorder();
        live.set(recorder);

        ChatResponse response = new StreamingChatModelAdapter(server, live).chat(REQUEST);

        assertSame(finished, response);
        assertEquals(List.of("thinking:lo", "thinking:ok", "text:Reading ", "text:it.", "call0:readFile:{\"pa"),
                recorder.events);
    }

    @Test
    void serverErrorsAreRethrownAsThemselves() {
        StreamingChatModel server = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onError(new InvalidRequestException("request (20403 tokens) exceeds the available context size (16384 tokens)"));
            }
        };
        InvalidRequestException e = assertThrows(InvalidRequestException.class,
                () -> new StreamingChatModelAdapter(server, new LiveChannel()).chat(REQUEST));
        assertEquals("request (20403 tokens) exceeds the available context size (16384 tokens)", e.getMessage());
    }

    @Test
    void aBrokenListenerDoesNotBreakTheReply() {
        ChatResponse finished = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        StreamingChatModel server = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("o");
                handler.onCompleteResponse(finished);
            }
        };
        LiveChannel live = new LiveChannel();
        live.set(new Progress.ProgressListener() {
            @Override public void onProgress(Progress progress) { }
            @Override public void onPartialText(String delta) { throw new IllegalStateException("broken"); }
        });
        assertSame(finished, new StreamingChatModelAdapter(server, live).chat(REQUEST));
    }
}
