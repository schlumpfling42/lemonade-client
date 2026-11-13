package net.rka.ai.tracing;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLogTest {

    @TempDir
    Path dir;

    @AfterEach
    void off() {
        AgentLog.configure(AgentLog.OFF);
    }

    /** A model whose replies are scripted; LangChain4j calls its listeners around each request. */
    private static ChatModel model(ChatModelListener listener, List<AiMessage> replies) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(replies.removeFirst())
                        .tokenUsage(new TokenUsage(1234, 56)).build();
            }

            @Override
            public List<ChatModelListener> listeners() {
                return List.of(listener);
            }
        };
    }

    @Test
    void writesTimestampedLinesToTheDailyFile() throws Exception {
        AgentLog log = new AgentLog(AgentLog.Level.SUMMARY, dir, false);
        log.line("hello");
        log.block("hidden at summary level", "text");
        List<String> lines = Files.readAllLines(log.file());
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.getFirst().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}  hello"), lines.getFirst());
        assertTrue(log.file().getFileName().toString().matches("agent-\\d{4}-\\d{2}-\\d{2}\\.log"));
    }

    @Test
    void offWritesNothing() {
        AgentLog log = new AgentLog(AgentLog.Level.OFF, dir, false);
        log.line("x");
        assertFalse(Files.exists(log.file()));
    }

    @Test
    void modelRequestsAndRepliesAreLoggedWithWhatTheServerCanReuse() throws Exception {
        AgentLog log = new AgentLog(AgentLog.Level.FULL, dir, false);
        AgentLog.configure(log);
        ToolExecutionRequest read = ToolExecutionRequest.builder().id("c1").name("readFile").arguments("{\"path\":\"a\"}").build();
        ChatModel model = model(new LoggingChatModelListener(() -> "agent"),
                new ArrayList<>(List.of(AiMessage.from(List.of(read)), AiMessage.from("Done."), AiMessage.from("Hi."))));
        List<ChatMessage> first = List.of(SystemMessage.from("system"), UserMessage.from("read a"));
        model.chat(ChatRequest.builder().messages(first).build());
        List<ChatMessage> second = new ArrayList<>(first);
        second.add(AiMessage.from(List.of(read)));
        model.chat(ChatRequest.builder().messages(second).build());
        model.chat(ChatRequest.builder().messages(UserMessage.from("new start")).build());

        String text = Files.readString(log.file());
        assertTrue(text.contains("→ LLM #1 [agent] 2 messages"), text);
        assertTrue(text.contains("unchanged from the last request: 0 of 2 messages"), text);
        assertTrue(text.contains("← LLM #1 [agent]") && text.contains("tokens in 1,234 / out 56")
                && text.contains("calls readFile {\"path\":\"a\"}"), text);
        assertTrue(text.contains("→ LLM #2 [agent] 3 messages") && text.contains("unchanged from the last request: 2 of 3 messages"), text);
        assertTrue(text.contains("← LLM #2 [agent]") && text.contains("says: Done."), text);
        assertTrue(text.contains("unchanged from the last request: 0 of 1 messages"), text); // the start changed
        assertTrue(text.contains("new message 2 · user") && text.contains("│ read a"), text); // full level
    }

    @Test
    void sameStartCountsUnchangedMessages() {
        List<ChatMessage> a = List.of(SystemMessage.from("s"), UserMessage.from("q"), AiMessage.from("x"));
        assertEquals(3, LoggingChatModelListener.sameStart(a, a));
        assertEquals(1, LoggingChatModelListener.sameStart(a, List.of(SystemMessage.from("s"), UserMessage.from("other"))));
        assertEquals(0, LoggingChatModelListener.sameStart(a, List.of(SystemMessage.from("new summary"))));
    }
}
