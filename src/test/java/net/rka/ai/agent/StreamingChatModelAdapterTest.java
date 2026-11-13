package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingHandle;
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
    void aModelThatWritesTheSameLinesOverAndOverIsStoppedAndTheRepeatsAreCut() {
        int[] sent = {0};
        StreamingChatModel server = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                StreamingHandle handle = new StreamingHandle() {
                    boolean cancelled;

                    @Override public void cancel() { cancelled = true; }

                    @Override public boolean isCancelled() { return cancelled; }
                };
                PartialResponseContext context = new PartialResponseContext(handle);
                handler.onPartialResponse(new PartialResponse("The notes:\n"), context);
                while (!handle.isCancelled() && sent[0] < 1000) {
                    sent[0]++;
                    handler.onPartialResponse(new PartialResponse("- Agent.java: section stream: no cancellation, so nothing can stop it.\n"), context);
                }
                if (!handle.isCancelled()) handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("never stopped")).build());
            }
        };

        ChatResponse response = new StreamingChatModelAdapter(server, new LiveChannel()).chat(REQUEST);

        assertEquals("The notes:\n- Agent.java: section stream: no cancellation, so nothing can stop it.", response.aiMessage().text());
        assertEquals(RepeatGuard.TEXT_REPEATS, sent[0], "stopped as soon as the repeats were clear");
    }

    @Test
    void aStoppedTurnCancelsTheStreamAndEndsWithoutWaitingForTheModel() throws Exception {
        boolean[] cancelled = {false};
        java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
        StreamingChatModel server = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                StreamingHandle handle = new StreamingHandle() {
                    @Override public void cancel() { cancelled[0] = true; }

                    @Override public boolean isCancelled() { return cancelled[0]; }
                };
                handler.onPartialResponse(new PartialResponse("Working on it"), new PartialResponseContext(handle));
                writing.countDown(); // and never finishes
            }
        };
        Throwable[] failure = {null};
        Thread turn = new Thread(() -> {
            try {
                new StreamingChatModelAdapter(server, new LiveChannel()).chat(REQUEST);
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        turn.start();
        writing.await();

        turn.interrupt();
        turn.join(5000);

        assertEquals(false, turn.isAlive());
        assertEquals(java.util.concurrent.CancellationException.class, failure[0].getClass());
        assertEquals(true, cancelled[0]);
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
    void aConnectionThatBreaksBeforeAnyTokenIsAskedOnceMore() {
        ChatResponse finished = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        int[] calls = {0};
        StreamingChatModel server = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                if (++calls[0] == 1) {
                    handler.onError(new dev.langchain4j.exception.LangChain4jException(
                            new java.io.IOException("HTTP/1.1 header parser received no bytes")));
                } else {
                    handler.onCompleteResponse(finished);
                }
            }
        };
        assertSame(finished, new StreamingChatModelAdapter(server, new LiveChannel()).chat(REQUEST));
        assertEquals(2, calls[0]);
    }

    @Test
    void aBreakAfterTheFirstTokenAndAServerAnswerAreNotRetried() {
        int[] calls = {0};
        StreamingChatModel midReply = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                calls[0]++;
                handler.onPartialResponse("par");
                handler.onError(new dev.langchain4j.exception.LangChain4jException(new java.io.IOException("closed")));
            }
        };
        assertThrows(dev.langchain4j.exception.LangChain4jException.class,
                () -> new StreamingChatModelAdapter(midReply, new LiveChannel()).chat(REQUEST));
        assertEquals(1, calls[0]);

        StreamingChatModel refused = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                calls[0]++;
                handler.onError(new InvalidRequestException("bad request"));
            }
        };
        assertThrows(InvalidRequestException.class, () -> new StreamingChatModelAdapter(refused, new LiveChannel()).chat(REQUEST));
        assertEquals(2, calls[0]);
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
