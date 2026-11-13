package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one-word question that decides whether a turn is coding work. */
class CodingTriageTest {

    private static final List<ChatMessage> CONVERSATION = List.of(SystemMessage.from("system"), UserMessage.from("add a stop button"));
    /** A second user message: there is an earlier request for "implement it" to point at. */
    private static final List<ChatMessage> FOLLOW_UP = List.of(SystemMessage.from("system"), UserMessage.from("add a stop button"),
            AiMessage.from("Here is a plan."), UserMessage.from("go ahead"));

    private final List<ChatRequest> requests = new ArrayList<>();

    private CodingTriage answering(AiMessage answer) {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                requests.add(request);
                return ChatResponse.builder().aiMessage(answer).build();
            }
        };
        return new CodingTriage(model);
    }

    @Test
    void aReasoningModelThatWritesItsReasoningBeforeTheWordIsUnderstood() {
        String reasoned = "The user says: \"I want you to fix the buttons.\" That is a request to implement changes. So answer CODING.CODING";

        assertEquals(CodingTriage.Decision.CODING, answering(AiMessage.from(reasoned)).decide(CONVERSATION));
        assertEquals(CodingTriage.Decision.CONTINUE, answering(AiMessage.from("It only makes sense with the earlier messages: CONTINUE")).decide(FOLLOW_UP));
        assertEquals(CodingTriage.Decision.OTHER, answering(AiMessage.from("A question about code, so other things apply. OTHER")).decide(CONVERSATION));
        assertEquals(CodingTriage.Decision.OTHER, answering(AiMessage.from("Not sure about coding or continue at all")).decide(CONVERSATION), "words in small letters are not an answer");
    }

    @Test
    void theAnswerSaysWhetherTheMessageStandsAloneOrContinuesEarlierOnes() {
        assertEquals(CodingTriage.Decision.CODING, answering(AiMessage.from("CODING")).decide(CONVERSATION));
        assertEquals(CodingTriage.Decision.CONTINUE, answering(AiMessage.from("continue.")).decide(FOLLOW_UP));
        assertEquals(CodingTriage.Decision.OTHER, answering(AiMessage.from("continue.")).decide(CONVERSATION), "nothing earlier to continue");
        assertEquals(CodingTriage.Decision.OTHER, answering(AiMessage.from("OTHER")).decide(CONVERSATION));
        assertEquals(CodingTriage.Decision.OTHER, answering(AiMessage.from("maybe")).decide(CONVERSATION));
        assertTrue(answering(AiMessage.from("CONTINUE")).isCoding(FOLLOW_UP));
        assertTrue(CodingTriage.QUESTION.contains("where are we with X?"), "a question about the state of a feature is not coding work");
        assertTrue(CodingTriage.QUESTION.contains("deleting, moving, renaming or copying") && CodingTriage.QUESTION.contains("is OTHER,"),
                "handling files is not coding work; analyzing and changing them is");
    }

    @Test
    void codingIsRecognizedFromTheModelsOneWordAnswer() {
        assertTrue(answering(AiMessage.from("CODING")).isCoding(CONVERSATION));
        assertTrue(answering(AiMessage.from(" coding.\n")).isCoding(CONVERSATION));
        assertFalse(answering(AiMessage.from("OTHER")).isCoding(CONVERSATION));
    }

    @Test
    void anythingUnclearIsNotCoding() {
        assertFalse(answering(AiMessage.from("")).isCoding(CONVERSATION));
        assertFalse(answering(AiMessage.from("It depends: this might be CODING, or maybe OTHER")).isCoding(CONVERSATION)); // the last word counts
        assertFalse(answering(AiMessage.from(List.of(ToolExecutionRequest.builder().id("1").name("readFile")
                .arguments("{\"path\":\"a\"}").build()))).isCoding(CONVERSATION)); // a call instead of an answer
    }

    @Test
    void theRequestIsShortWithoutToolsAndShowsTheLatestMessageAndTheOneBefore() {
        List<ChatMessage> history = List.of(SystemMessage.from("system"), UserMessage.from("add a stop button"),
                AiMessage.from("x".repeat(5000)), UserMessage.from("implement it"));
        answering(AiMessage.from("OTHER")).isCoding(history);

        ChatRequest request = requests.get(0);
        assertEquals(1, request.messages().size());
        assertTrue(request.toolSpecifications() == null || request.toolSpecifications().isEmpty());
        String text = ((UserMessage) request.messages().getFirst()).singleText();
        assertTrue(text.contains("The request before it: add a stop button"));
        assertTrue(text.contains("The latest message: implement it"));
        assertTrue(text.endsWith(CodingTriage.QUESTION));
        assertTrue(text.length() < CodingTriage.CLIP * 2 + CodingTriage.QUESTION.length() + 300, "the long answer is cut");
        assertEquals(CodingTriage.ANSWER_TOKENS, request.maxOutputTokens());
        assertEquals(0.0, request.temperature());
    }
}
