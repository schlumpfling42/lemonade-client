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
import io.opentelemetry.api.OpenTelemetry;
import net.rka.ai.tools.FactBook;
import net.rka.ai.tools.MathTools;
import net.rka.ai.tools.MemoryTools;
import net.rka.ai.tools.ToolRegistry;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryMemoryTest {

    /** A conversation of {@code exchanges} question/answer pairs, each about {@code chars} characters long. */
    private static List<ChatMessage> conversation(int exchanges, int chars) {
        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from("system")));
        for (int i = 0; i < exchanges; i++) {
            messages.add(UserMessage.from("question " + i + " " + "q".repeat(chars)));
            messages.add(AiMessage.from("answer " + i + " " + "a".repeat(chars)));
        }
        return messages;
    }

    @Test
    void smallConversationsAreNotSummarized() {
        List<ChatMessage> messages = conversation(3, 100); // ~150 tokens
        assertEquals(1, SummaryMemory.cutoff(messages, 1, 1000, 200));
    }

    @Test
    void cutsAtAUserMessageKeepingTheRecentBudget() {
        List<ChatMessage> messages = conversation(10, 400); // 20 messages of ~100 tokens
        int cutoff = SummaryMemory.cutoff(messages, 1, 1000, 300);
        assertInstanceOf(UserMessage.class, messages.get(cutoff));
        assertTrue(cutoff > 1 && cutoff < messages.size() - 1, "cutoff " + cutoff);
        int kept = 0;
        for (int i = cutoff; i < messages.size(); i++) kept += SummaryMemory.estimateTokens(messages.get(i));
        assertTrue(kept >= 300, "kept " + kept);
    }

    @Test
    void neverSeparatesAToolCallFromItsResult() {
        List<ChatMessage> messages = conversation(6, 400);
        ToolExecutionRequest call = ToolExecutionRequest.builder().id("t").name("listFiles").arguments("{}").build();
        messages.add(UserMessage.from("list files"));
        messages.add(AiMessage.from(call));
        messages.add(ToolExecutionResultMessage.from(call, "x".repeat(2000)));
        messages.add(AiMessage.from("done"));
        int cutoff = SummaryMemory.cutoff(messages, 1, 500, 100);
        assertInstanceOf(UserMessage.class, messages.get(cutoff)); // cuts only before a question
    }

    @Test
    void viewPutsTheSummaryIntoTheSystemMessage() {
        List<ChatMessage> messages = conversation(4, 10);
        List<ChatMessage> view = SummaryMemory.view(messages, new ConversationSummary("- talked about bikes", 5));
        assertEquals(1 + (messages.size() - 5), view.size());
        assertTrue(((SystemMessage) view.get(0)).text().contains("- talked about bikes"));
        assertEquals(messages.get(5), view.get(1));
        assertEquals(messages, SummaryMemory.view(messages, ConversationSummary.EMPTY)); // nothing summarized
    }

    /** A model that answers summary requests (no tools offered) with "SUMMARY" and records what it was sent. */
    private static final class FakeModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            boolean summaryRequest = request.toolSpecifications() == null || request.toolSpecifications().isEmpty();
            return ChatResponse.builder().aiMessage(AiMessage.from(summaryRequest ? "SUMMARY" : "the answer")).build();
        }
    }

    @Test
    void rememberedFactsEndUpInTheGraphState() throws Exception {
        // The model asks for remember once, then answers; trust tool:remember so no approval pause
        ToolExecutionRequest remember = ToolExecutionRequest.builder().id("r1").name("remember")
                .arguments("{\"fact\": \"likes bikes\"}").build();
        ChatModel model = new ChatModel() {
            int calls;

            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder()
                        .aiMessage(calls++ == 0 ? AiMessage.from(remember) : AiMessage.from("ok")).build();
            }
        };
        var tracer = OpenTelemetry.noop().getTracer("test");
        var graph = new AgentGraph(model, new ToolCallRunner(tracer, ToolRegistry.of(new MemoryTools())),
                new NodeTracing(tracer)).compile();
        AgentGraph.State result = graph.invoke(GraphInput.args(Map.of(
                        AgentGraph.State.MESSAGES, List.of(SystemMessage.from("s"), UserMessage.from("remember I like bikes")),
                        AgentGraph.State.TRUSTED_COMMANDS, "tool:remember",
                        AgentGraph.State.FACTS, AgentGraph.State.encodeFacts(List.of(new FactBook.Fact("f1", "lives in Seattle"))))),
                RunnableConfig.builder().threadId("f").build()).orElseThrow();

        assertEquals(List.of(new FactBook.Fact("f1", "lives in Seattle"), new FactBook.Fact("f2", "likes bikes")),
                result.facts());
    }

    @Test
    void theMemoryNodeSummarizesBeforeTheModelIsAsked() throws Exception {
        FakeModel model = new FakeModel();
        var tracer = OpenTelemetry.noop().getTracer("test");
        var graph = new AgentGraph(model, new ToolCallRunner(tracer, ToolRegistry.of(new MathTools())),
                new NodeTracing(tracer), 1000, 300).compile();

        List<ChatMessage> history = conversation(10, 400);
        history.add(UserMessage.from("new question"));
        AgentGraph.State result = graph.invoke(GraphInput.args(Map.of(AgentGraph.State.MESSAGES, history)),
                RunnableConfig.builder().threadId("t").build()).orElseThrow();

        assertEquals("SUMMARY", result.summary().text());
        assertTrue(result.summary().covered() > 1);
        assertEquals(2, model.requests.size()); // one summary call, then the real question
        List<ChatMessage> sent = model.requests.get(1).messages();
        assertTrue(((SystemMessage) sent.get(0)).text().contains("SUMMARY"));
        assertTrue(sent.size() < history.size()); // older messages replaced by the summary
        assertEquals(history.get(history.size() - 1), sent.get(sent.size() - 1));
    }

    private static final ToolExecutionRequest READ = ToolExecutionRequest.builder().id("r1").name("readFile")
            .arguments("{\"path\":\"big.txt\"}").build();
    private static final ToolExecutionRequest WRITE = ToolExecutionRequest.builder().id("w1").name("writeFile")
            .arguments("{\"path\":\"out.txt\",\"content\":\"" + "x".repeat(5000) + "\"}").build();

    /** An earlier turn that read and wrote big files, then the current turn reading again. */
    private static List<ChatMessage> twoTurns() {
        return List.of(SystemMessage.from("system"),
                UserMessage.from("first question"),
                AiMessage.from(List.of(READ)), ToolExecutionResultMessage.from(READ, "A".repeat(20_000)),
                AiMessage.from(List.of(WRITE)), ToolExecutionResultMessage.from(WRITE, "Wrote 5000 characters"),
                AiMessage.from("Done."),
                UserMessage.from("second question"),
                AiMessage.from(List.of(READ)), ToolExecutionResultMessage.from(READ, "B".repeat(20_000)));
    }

    @Test
    void earlierTurnsAreCompactedAndTheCurrentOneIsNot() throws Exception {
        List<ChatMessage> messages = twoTurns();
        List<ChatMessage> sent = SummaryMemory.compactEarlierTurns(messages);

        assertEquals(messages.size(), sent.size()); // one message per message: the indices stay the same
        String oldRead = ((ToolExecutionResultMessage) sent.get(3)).text();
        assertTrue(oldRead.startsWith("A".repeat(SummaryMemory.EARLIER_TURN_CHARS)) && oldRead.length() < 600, oldRead);
        assertTrue(oldRead.contains("shown in an earlier turn"), oldRead);
        String oldWrite = ((AiMessage) sent.get(4)).toolExecutionRequests().getFirst().arguments();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(oldWrite); // still valid JSON
        assertEquals("out.txt", json.get("path").asText());
        assertEquals("[5000 characters, sent in an earlier turn]", json.get("content").asText());
        assertEquals("w1", ((AiMessage) sent.get(4)).toolExecutionRequests().getFirst().id());
        // The questions, the answer and the whole current turn are unchanged
        for (int i : new int[] {0, 1, 5, 6, 7, 8, 9}) assertEquals(messages.get(i), sent.get(i));
        // The same input gives the same output: every request of a turn sends identical text
        assertEquals(sent, SummaryMemory.compactEarlierTurns(messages));
    }

    @Test
    void aBigEarlierTurnDoesNotForceASummary() {
        // Raw, the earlier turn is ~11,000 estimated tokens; compacted, the conversation is far below the limit
        List<ChatMessage> messages = twoTurns();
        assertTrue(SummaryMemory.cutoff(messages, 1, 8000, 2000) > 1);              // raw: would summarize
        assertEquals(1, SummaryMemory.cutoff(SummaryMemory.compactEarlierTurns(messages), 1, 8000, 2000)); // compacted: not
    }
}
