package net.rka.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the analysis reads of the conversation. */
class AnalysisWindowTest {

    @Test
    void aLongConversationIsCutToTheNewestTurnsThatFitAndStartsWithAUserMessage() {
        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(SystemMessage.from("system"));
        for (int i = 0; i < 40; i++) {
            conversation.add(UserMessage.from("question " + i + " " + "x".repeat(1_000)));
            conversation.add(AiMessage.from("answer " + i + " " + "y".repeat(1_000)));
        }
        conversation.add(UserMessage.from("the latest question"));

        List<ChatMessage> window = AgentGraph.analysisWindow(conversation);

        assertTrue(window.size() < 14, "a few turns only: " + window.size());
        assertTrue(window.getFirst() instanceof UserMessage, window.getFirst().toString());
        assertEquals(UserMessage.from("the latest question"), window.getLast());
    }

    @Test
    void aShortConversationIsKeptWhole() {
        List<ChatMessage> conversation = List.of(UserMessage.from("hi"), AiMessage.from("hello"), UserMessage.from("list the files"));

        assertEquals(conversation, AgentGraph.analysisWindow(conversation));
    }

    @Test
    void theLatestMessageIsKeptEvenWhenItIsBiggerThanTheWindow() {
        List<ChatMessage> conversation = List.of(UserMessage.from("old"), AiMessage.from("answer"), UserMessage.from("z".repeat(20_000)));

        List<ChatMessage> window = AgentGraph.analysisWindow(conversation);

        assertEquals(1, window.size());
    }
}
