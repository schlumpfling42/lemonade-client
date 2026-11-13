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

class StaleReadsTest {

    private static final String BIG = "x".repeat(StaleReads.MIN_CHARS + 500);

    private static List<ChatMessage> exchange(String id, String tool, String arguments, String result) {
        ToolExecutionRequest call = ToolExecutionRequest.builder().id(id).name(tool).arguments(arguments).build();
        return List.of(AiMessage.from(List.of(call)), ToolExecutionResultMessage.from(call, result));
    }

    private static List<ChatMessage> conversation(List<ChatMessage>... parts) {
        List<ChatMessage> all = new ArrayList<>(List.of(UserMessage.from("change it")));
        for (List<ChatMessage> part : parts) all.addAll(part);
        return all;
    }

    private static String text(List<ChatMessage> messages, int index) {
        return ((ToolExecutionResultMessage) messages.get(index)).text();
    }

    @Test
    void aReadThatIsDoneAgainReplacesTheEarlierOneAndOnlyThatOne() {
        List<ChatMessage> messages = conversation(
                exchange("1", "readFile", "{\"path\":\"A.java\"}", BIG),
                exchange("2", "readFile", "{\"path\":\"B.java\"}", BIG),
                exchange("3", "readFile", "{ \"path\": \"A.java\" }", BIG)); // the same read, written differently

        List<ChatMessage> result = StaleReads.apply(messages);

        assertTrue(text(result, 2).startsWith("[this result was replaced: the same readFile was done again below; "), text(result, 2));
        assertEquals(BIG, text(result, 4), "the other file stays");
        assertEquals(BIG, text(result, 6), "the last read stays");
        assertEquals(messages.size(), result.size(), "the messages keep their places");
    }

    @Test
    void aReadOfAFileThatWasChangedLaterIsOutOfDateAndAReadAfterTheChangeIsNot() {
        List<ChatMessage> messages = conversation(
                exchange("1", "readSection", "{\"path\":\"A.java\",\"section\":\"A.run()\"}", BIG),
                exchange("2", "editSection", "{\"path\":\"A.java\",\"section\":\"A.run()\",\"newText\":\"x\"}", "ok"),
                exchange("3", "outline", "{\"path\":\"A.java\"}", BIG),
                exchange("4", "javaRead", "{\"type\":\"B\",\"member\":\"go\"}", BIG),
                exchange("5", "javaPatch", "{\"type\":\"B\",\"member\":\"go\",\"patch\":\"-x\"}", "ok"));

        List<ChatMessage> result = StaleReads.apply(messages);

        assertTrue(text(result, 2).contains("out of date"), text(result, 2));
        assertEquals(BIG, text(result, 6), "an outline of the file after the change is true");
        assertTrue(text(result, 8).contains("out of date"), "Java tools name the type: " + text(result, 8));
    }

    @Test
    void smallResultsErrorsAndConversationsWithoutRepeatsAreLeftAlone() {
        List<ChatMessage> messages = conversation(
                exchange("1", "readFile", "{\"path\":\"A.java\"}", "short"),
                exchange("2", "readFile", "{\"path\":\"A.java\"}", "short"),
                exchange("3", "searchFiles", "{\"pattern\":\"x\"}", BIG));

        assertSame(messages, StaleReads.apply(messages));
        List<ChatMessage> withError = conversation(
                exchange("1", "readFile", "{\"path\":\"A.java\"}", BIG),
                List.of(AiMessage.from(List.of(ToolExecutionRequest.builder().id("2").name("readFile").arguments("{\"path\":\"A.java\"}").build())),
                        ToolExecutionResultMessage.builder().id("2").toolName("readFile").text("Error: " + BIG).isError(true).build()));
        assertEquals(BIG, text(StaleReads.apply(withError), 2), "an error does not replace the read before it");
    }
}
