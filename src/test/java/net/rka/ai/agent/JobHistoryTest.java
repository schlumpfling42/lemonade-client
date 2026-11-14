package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a job's model has seen of the conversation. */
class JobHistoryTest {

    private static ToolExecutionRequest call(String id, String name, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();
    }

    @Test
    void theUsersAndTheAssistantsMessagesAreInTheirRolesAndAToolExchangeIsFoldedIntoTheAssistantsMessage() {
        ToolExecutionRequest earlier = call("c1", "startCodingJob", "{\"goal\":\"set up the project\"}");
        List<ChatMessage> conversation = List.of(SystemMessage.from("system"), UserMessage.from("finish setting up the test java project"),
                AiMessage.from(List.of(earlier)), ToolExecutionResultMessage.from(earlier, "Error: the app was closed before this call finished."),
                UserMessage.from("continue"),
                AiMessage.from(List.of(call("c2", "startCodingJob", "{\"goal\":\"finish\"}")))); // this job: open

        List<ChatMessage> history = JobHistory.of(conversation);

        assertEquals(3, history.size(), history.toString());
        assertEquals(UserMessage.from("finish setting up the test java project"), history.get(0));
        assertTrue(((AiMessage) history.get(1)).text().contains("startCodingJob") && ((AiMessage) history.get(1)).text().contains("the app was closed"));
        assertEquals(UserMessage.from("continue"), history.get(2));
    }

    @Test
    void theNewestMessagesThatFitAreKeptAndTheHistoryStartsWithTheUser() {
        List<ChatMessage> conversation = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            conversation.add(UserMessage.from("question " + i + " " + "x".repeat(900)));
            conversation.add(AiMessage.from("answer " + i + " " + "y".repeat(900)));
        }

        List<ChatMessage> history = JobHistory.of(conversation);

        int chars = history.stream().mapToInt(m -> m instanceof UserMessage u ? u.singleText().length() : ((AiMessage) m).text().length()).sum();
        assertTrue(chars <= JobHistory.MAX_CHARS + 1_000, "about the budget: " + chars);
        assertTrue(history.getFirst() instanceof UserMessage);
        assertTrue(((AiMessage) history.getLast()).text().startsWith("answer 19"));
    }

    @Test
    void aLongResultAndALongMessageAreClipped() {
        ToolExecutionRequest read = call("c1", "readFile", "{\"path\":\"A.java\"}");
        List<ChatMessage> history = JobHistory.of(List.of(UserMessage.from("look"), AiMessage.from(List.of(read)),
                ToolExecutionResultMessage.from(read, "z".repeat(5_000)), UserMessage.from("w".repeat(5_000))));

        assertTrue(((AiMessage) history.get(1)).text().length() < 1_000);
        assertTrue(((UserMessage) history.get(2)).singleText().length() < 1_600);
    }
}
