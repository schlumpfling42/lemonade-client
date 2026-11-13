package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.rka.ai.config.Settings;
import net.rka.ai.tools.EnvironmentInfo;
import net.rka.ai.tools.FileTools;
import net.rka.ai.tools.ToolRegistry;
import net.rka.ai.tracing.Tracing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A turn that fails after running tools hands back what it did, so the conversation can keep it. */
class FailedTurnTest {

    @TempDir
    Path dir;

    private static final ToolExecutionRequest WRITE = ToolExecutionRequest.builder()
            .id("w1").name("writeFile").arguments("{\"path\":\"notes.txt\",\"content\":\"hi\"}").build();

    @Test
    void theToolCallsThatRanAreKept() throws Exception {
        Path folder = Files.createDirectory(dir.resolve("work"));
        // First reply: write a file (trusted for this test); second model call: the server fails
        ChatModel model = new ChatModel() {
            int calls;

            @Override
            public ChatResponse chat(ChatRequest request) {
                if (calls++ == 0) return ChatResponse.builder().aiMessage(AiMessage.from(List.of(WRITE))).build();
                throw new IllegalStateException("Connection reset");
            }
        };
        Agent agent = new Agent("test", "fake", model, Tracing.off(), ToolRegistry.of(new FileTools()),
                calls -> Map.of(), new EnvironmentInfo(dir.resolve("env.json"), c -> "", Clock.systemUTC()));
        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from("write notes")));

        Agent.TurnFailedException failed = assertThrows(Agent.TurnFailedException.class, () -> agent.run(history, "t",
                new Settings(folder, List.of("tool:writeFile")), ConversationSummary.EMPTY, List.of()));

        assertEquals("Connection reset", failed.getMessage());
        List<ChatMessage> kept = failed.partial().messages();
        assertEquals(2, kept.size(), kept.toString()); // the call and its result: the file was written
        assertInstanceOf(AiMessage.class, kept.get(0));
        assertEquals("w1", ((ToolExecutionResultMessage) kept.get(1)).id());
        assertTrue(Files.exists(folder.resolve("notes.txt")));
    }

    @Test
    void theAgentLogShowsTheTurnAndItsToolCalls() throws Exception {
        Path folder = Files.createDirectory(dir.resolve("work"));
        net.rka.ai.tracing.AgentLog log = new net.rka.ai.tracing.AgentLog(
                net.rka.ai.tracing.AgentLog.Level.SUMMARY, dir.resolve("logs"), false);
        net.rka.ai.tracing.AgentLog.configure(log);
        try {
            ChatModel model = new ChatModel() {
                int calls;

                @Override
                public ChatResponse chat(ChatRequest request) {
                    return ChatResponse.builder().aiMessage(calls++ == 0 ? AiMessage.from(List.of(WRITE)) : AiMessage.from("Done.")).build();
                }
            };
            Agent agent = new Agent("test", "fake", model, Tracing.off(), ToolRegistry.of(new FileTools()),
                    calls -> Map.of(), new EnvironmentInfo(dir.resolve("env.json"), c -> "", Clock.systemUTC()));
            agent.run(new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from("write notes"))), "t",
                    new Settings(folder, List.of("tool:writeFile")), ConversationSummary.EMPTY, List.of());

            String text = Files.readString(log.file());
            assertTrue(text.contains("■ turn: write notes"), text);
            assertTrue(text.contains("⚙ tool writeFile [trusted/execute] {\"path\":\"notes.txt\",\"content\":\"hi\"} → ok,"), text);
            assertTrue(text.contains("■ turn done in"), text);
        } finally {
            net.rka.ai.tracing.AgentLog.configure(net.rka.ai.tracing.AgentLog.OFF);
        }
    }

    @Test
    void aModelTimeoutIsDescribedPlainly() {
        // As the streaming client reports it: "closed", caused deep down by the HTTP request timing out
        Exception error = new java.util.concurrent.CompletionException(new IllegalStateException("closed",
                new java.io.IOException("closed", new java.net.http.HttpTimeoutException("request timed out"))));
        String text = Agent.TurnFailedException.describe(error);
        assertTrue(text.contains("MODEL_TIMEOUT_MINUTES") && text.contains("minutes"), text);
        assertEquals("IllegalStateException: Connection reset",
                Agent.TurnFailedException.describe(new java.util.concurrent.CompletionException(
                        new IllegalStateException("Connection reset"))));
    }

    @Test
    void callsLeftOpenAreAnsweredWithAnError() {
        AiMessage open = AiMessage.from(List.of(WRITE));
        Agent.MessageFeed feed = new Agent.MessageFeed(2, Progress.NONE);
        feed.update("agent", new AgentGraph.State(Map.of(AgentGraph.State.MESSAGES,
                List.of(SystemMessage.from("s"), UserMessage.from("q"), open))));

        Agent.Turn turn = feed.partial(new IllegalStateException("timeout"));

        assertEquals(2, turn.messages().size());
        ToolExecutionResultMessage answer = (ToolExecutionResultMessage) turn.messages().get(1);
        assertEquals("w1", answer.id());
        assertTrue(answer.isError());
        assertTrue(answer.text().contains("timeout"), answer.text());
    }

    @Test
    void nothingToKeepWhenTheTurnAddedNothing() {
        Agent.MessageFeed feed = new Agent.MessageFeed(2, Progress.NONE);
        assertNull(feed.partial(new IllegalStateException("down")));
        feed.update("memory", new AgentGraph.State(Map.of(AgentGraph.State.MESSAGES,
                List.of(SystemMessage.from("s"), UserMessage.from("q")))));
        assertNull(feed.partial(new IllegalStateException("down")));
    }
}
