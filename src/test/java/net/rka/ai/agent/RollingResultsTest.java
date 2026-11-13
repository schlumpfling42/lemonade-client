package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old reads of a tool loop shrink to a line; the last rounds, other tools and short results stay. */
class RollingResultsTest {

    private static List<ChatMessage> loop(int rounds, String tool, int resultChars) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("go"));
        for (int i = 0; i < rounds; i++) {
            ToolExecutionRequest call = ToolExecutionRequest.builder().id("c" + i).name(tool).arguments("{\"path\":\"f" + i + "\"}").build();
            messages.add(AiMessage.from(List.of(call)));
            messages.add(ToolExecutionResultMessage.from(call, "x".repeat(resultChars)));
        }
        return messages;
    }

    @Test
    void readsOlderThanTheLastRoundsShrinkToANote() {
        List<ChatMessage> out = RollingResults.apply(loop(7, "readFile", 15000));

        String first = ((ToolExecutionResultMessage) out.get(2)).text();
        assertTrue(first.startsWith("(readFile {\"path\":\"f0\"} returned 15000 characters"), first);
        assertTrue(first.contains("It began:\n") && first.length() < 700, "a shortened result keeps its start: " + first);
        assertEquals(15000, ((ToolExecutionResultMessage) out.get(out.size() - 1)).text().length(), "the last round stays whole");
        int whole = (int) out.stream().filter(m -> m instanceof ToolExecutionResultMessage r && r.text().length() == 15000).count();
        assertEquals(RollingResults.KEEP_ROUNDS, whole);
    }

    @Test
    void whileTheResultsFitTheConversationIsNotRewrittenSoTheServersCacheStaysValid() {
        List<ChatMessage> messages = loop(7, "readFile", 5000); // 35,000 characters in all
        assertSame(messages, RollingResults.apply(messages));
    }

    @Test
    void aShortLoopIsLeftAlone() {
        List<ChatMessage> messages = loop(RollingResults.KEEP_ROUNDS, "readFile", 5000);
        assertSame(messages, RollingResults.apply(messages));
    }

    @Test
    void commandOutputAndShortResultsStay() {
        List<ChatMessage> commands = loop(7, "runCommand", 5000);
        assertEquals(commands, RollingResults.apply(commands));
        List<ChatMessage> shorts = loop(7, "readFile", 100);
        assertEquals(shorts, RollingResults.apply(shorts));
    }
}
