package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The loop breaker of {@link ToolCallRunner#withoutRepeats}: the same call with the same result again. */
class RepeatedToolCallTest {

    private static ToolExecutionRequest read(String id, String arguments) {
        return ToolExecutionRequest.builder().id(id).name("readFile").arguments(arguments).build();
    }

    private static String text(List<ChatMessage> results, int i) {
        return ((ToolExecutionResultMessage) results.get(i)).text();
    }

    @Test
    void anIdenticalCallWithTheSameResultGetsTheNote() {
        ToolExecutionRequest first = read("1", "{\"path\": \"A.java\", \"startLine\": 10}");
        List<ChatMessage> transcript = List.of(UserMessage.from("change A"), AiMessage.from(List.of(first)),
                ToolExecutionResultMessage.from(first, "class A {}"));
        // Same arguments, other spacing and key order
        ToolExecutionRequest again = read("2", "{\"startLine\":10,\"path\":\"A.java\"}");

        List<ChatMessage> results = ToolCallRunner.withoutRepeats(transcript, List.of(again),
                List.of(ToolExecutionResultMessage.from(again, "class A {}")));

        assertEquals(ToolCallRunner.repeatNote("readFile"), text(results, 0));
        assertEquals("2", ((ToolExecutionResultMessage) results.get(0)).id()); // still answers this call
    }

    @Test
    void aChangedResultOrAnotherCallKeepsItsResult() {
        ToolExecutionRequest first = read("1", "{\"path\": \"A.java\"}");
        List<ChatMessage> transcript = List.of(UserMessage.from("change A"), AiMessage.from(List.of(first)),
                ToolExecutionResultMessage.from(first, "class A {}"));
        ToolExecutionRequest again = read("2", "{\"path\": \"A.java\"}");      // the file was edited meanwhile
        ToolExecutionRequest other = read("3", "{\"path\": \"B.java\"}");

        List<ChatMessage> results = ToolCallRunner.withoutRepeats(transcript, List.of(again, other), List.of(
                ToolExecutionResultMessage.from(again, "class A { int x; }"),
                ToolExecutionResultMessage.from(other, "class A {}")));

        assertEquals("class A { int x; }", text(results, 0));
        assertEquals("class A {}", text(results, 1));
    }

    @Test
    void aCallFailingTheSameWayAgainIsToldToStop() {
        ToolExecutionRequest edit = ToolExecutionRequest.builder().id("1").name("editSection")
                .arguments("{\"path\": \"A.java\", \"oldText\": \"x\", \"newText\": \"y\"}").build();
        ToolExecutionResultMessage failed = ToolExecutionResultMessage.builder().id("1").toolName("editSection")
                .text("Error: oldText was not found").isError(true).build();
        List<ChatMessage> transcript = List.of(UserMessage.from("change A"), AiMessage.from(List.of(edit)), failed);
        ToolExecutionRequest again = ToolExecutionRequest.builder().id("2").name("editSection").arguments(edit.arguments()).build();

        List<ChatMessage> results = ToolCallRunner.withoutRepeats(transcript, List.of(again), List.of(
                ToolExecutionResultMessage.builder().id("2").toolName("editSection")
                        .text("Error: oldText was not found").isError(true).build()));

        ToolExecutionResultMessage result = (ToolExecutionResultMessage) results.get(0);
        assertEquals(ToolCallRunner.repeatedErrorNote("editSection", "Error: oldText was not found"), result.text());
        assertEquals(Boolean.TRUE, result.isError()); // still a failed call: the pill shows it failed
    }

    @Test
    void onlyThisTurnCountsButRepeatsInOneBatchDo() {
        ToolExecutionRequest first = read("1", "{\"path\": \"A.java\"}");
        // The earlier read was in the previous turn: reading again after a new question is fine
        List<ChatMessage> transcript = List.of(UserMessage.from("q1"), AiMessage.from(List.of(first)),
                ToolExecutionResultMessage.from(first, "class A {}"), AiMessage.from("done"), UserMessage.from("q2"));
        ToolExecutionRequest a = read("2", "{\"path\": \"A.java\"}");
        ToolExecutionRequest b = read("3", "{\"path\": \"A.java\"}");

        List<ChatMessage> results = ToolCallRunner.withoutRepeats(transcript, List.of(a, b), List.of(
                ToolExecutionResultMessage.from(a, "class A {}"), ToolExecutionResultMessage.from(b, "class A {}")));

        assertEquals("class A {}", text(results, 0));
        assertEquals(ToolCallRunner.repeatNote("readFile"), text(results, 1));
    }
}
