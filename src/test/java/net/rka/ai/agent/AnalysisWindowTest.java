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
    void thePreviousTurnIsKeptEvenWhenItsAnswerWasLongSoRetryStillMeansSomething() {
        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(UserMessage.from("improve the confirmation dialog"));
        conversation.add(AiMessage.from("The job stopped. " + "x".repeat(9_000))); // a long report
        conversation.add(UserMessage.from("retry"));

        List<ChatMessage> window = AgentGraph.analysisWindow(conversation);

        assertEquals(3, window.size(), window.toString());
        assertEquals(UserMessage.from("improve the confirmation dialog"), window.getFirst());
        assertTrue(((AiMessage) window.get(1)).text().length() < AgentGraph.ANALYSIS_MESSAGE_CHARS + 20, "the long answer is clipped, not cut away");
        assertTrue(((AiMessage) window.get(1)).text().startsWith("The job stopped."));
        assertEquals(UserMessage.from("retry"), window.getLast());
    }

    @Test
    void theJobsOfTheConversationGoInFrontOfTheWindow() {
        var call = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("j1").name(CodingGraph.START_TOOL).arguments("{\"goal\":\"shorten the dialog\"}").build();
        List<ChatMessage> all = List.of(UserMessage.from("shorten the dialog"), AiMessage.from(List.of(call)),
                dev.langchain4j.data.message.ToolExecutionResultMessage.from(call, "Stopped: the step ran out of rounds"), UserMessage.from("retry"));

        List<ChatMessage> window = AgentGraph.withJobs(AgentGraph.analysisWindow(all), all);

        String first = ((UserMessage) window.getFirst()).singleText();
        assertTrue(first.contains("shorten the dialog") && first.contains("Stopped: the step ran out of rounds"), first);
        assertEquals(UserMessage.from("retry"), window.getLast());
        assertEquals(all, AgentGraph.withJobs(all, List.of(UserMessage.from("hi")))); // no job: unchanged
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

        assertEquals(3, window.size(), "the previous turn is kept too, so 'retry' still means something");
        assertEquals(UserMessage.from("z".repeat(20_000)), window.getLast(), "the latest message is kept whole");
    }
}
