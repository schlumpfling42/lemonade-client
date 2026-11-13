package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A conversation saved in the middle of a turn is made valid when it is opened again. */
class OpenCallsTest {

    private static final ToolExecutionRequest CALL = ToolExecutionRequest.builder().id("c1").name("startCodingJob").arguments("{}").build();

    @Test
    void aCallWithoutAResultGetsAnErrorResultRightAfterIt() {
        List<ChatMessage> saved = List.of(UserMessage.from("create a project"), AiMessage.from(List.of(CALL)));

        List<ChatMessage> opened = OpenCalls.closed(saved, "the app was closed.");

        assertEquals(3, opened.size());
        ToolExecutionResultMessage result = (ToolExecutionResultMessage) opened.get(2);
        assertEquals("c1", result.id());
        assertTrue(result.text().contains("the app was closed.") && Boolean.TRUE.equals(result.isError()), result.text());
    }

    @Test
    void aConversationWhoseCallsAllHaveResultsIsTheSameList() {
        List<ChatMessage> done = List.of(UserMessage.from("hi"), AiMessage.from(List.of(CALL)), ToolExecutionResultMessage.from(CALL, "ok"), AiMessage.from("done"));

        assertSame(done, OpenCalls.closed(done, "x"));
    }
}
