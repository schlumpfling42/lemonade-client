package net.rka.ai.agent.skills;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import net.rka.ai.agent.ToolCallRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A round of tool calls that only repeats earlier ones ends the tool use of the node's visit. */
class RepeatLoopGuardTest {

    private static final ToolExecutionRequest CALL = ToolExecutionRequest.builder().id("1").name("searchFiles").arguments("{}").build();

    @Test
    void aRoundOfRepeatNotesIsALoop() {
        List<ChatMessage> messages = List.of(UserMessage.from("go"), AiMessage.from(List.of(CALL)),
                ToolExecutionResultMessage.from(CALL, "Same result as your identical searchFiles call above in this turn: nothing changed."));
        assertTrue(GenericKinds.repeatedOnly(messages));
    }

    @Test
    void aRoundThatTheUserRefusedIsALoopToo() {
        ToolExecutionRequest command = ToolExecutionRequest.builder().id("2").name("runCommand").arguments("{}").build();
        List<ChatMessage> messages = List.of(UserMessage.from("go"), AiMessage.from(List.of(command)),
                ToolExecutionResultMessage.builder().id("2").toolName("runCommand")
                        .text("Denied: the user did not approve this call. Don't retry it unless the user asks.").isError(true).build());
        assertTrue(GenericKinds.repeatedOnly(messages), "the model answers instead of proposing another command");
    }

    @Test
    void aNewResultOrNoResultIsNot() {
        assertFalse(GenericKinds.repeatedOnly(List.of(UserMessage.from("go"), AiMessage.from(List.of(CALL)),
                ToolExecutionResultMessage.from(CALL, "found 3 files"))));
        assertFalse(GenericKinds.repeatedOnly(List.of(UserMessage.from("go"))));
        assertTrue(ToolCallRunner.isRepeat(ToolExecutionResultMessage.from(CALL, "Same result as your identical x call above")));
    }
}
