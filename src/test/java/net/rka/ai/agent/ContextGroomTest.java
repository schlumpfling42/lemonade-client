package net.rka.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextGroomTest {

    private static final String LONG = "The class ChatWindowMenuButton, which the folder cell uses, needs a style. ".repeat(20);

    private static String text(List<ChatMessage> messages, int index) {
        return ((AiMessage) messages.get(index)).text();
    }

    @Test
    void theUsersWordsStayWholeAndOnlyOlderLongAnswersShrink() {
        List<ChatMessage> messages = List.of(
                UserMessage.from("add a menu button to the conversation rows, like the folder rows"),
                AiMessage.from(LONG), AiMessage.from("A short answer."),
                UserMessage.from("fix the buttons"),
                AiMessage.from(LONG), AiMessage.from(LONG + "!"),
                UserMessage.from("implement the changes"));

        List<ChatMessage> groomed = ContextGroom.apply(messages);

        assertEquals(messages.get(0), groomed.get(0));
        assertEquals(messages.get(3), groomed.get(3));
        assertEquals(messages.get(6), groomed.get(6));
        assertTrue(text(groomed, 1).length() < 400 && text(groomed, 1).contains("earlier answer are left out"), text(groomed, 1));
        assertEquals("A short answer.", text(groomed, 2), "a short answer is not worth cutting");
        assertEquals(LONG, text(groomed, 4), "the last two long answers stay");
        assertEquals(LONG + "!", text(groomed, 5));
        assertEquals(messages.size(), groomed.size(), "the messages keep their places");
    }

    @Test
    void whatTheProgramSaidInsteadOfTheModelIsNotLeftForTheModelToImitate() {
        List<ChatMessage> messages = List.of(UserMessage.from("fix it"), AiMessage.from(AgentGraph.NO_CHANGES_AFTER_JOB),
                UserMessage.from("again"), AiMessage.from(ContextFittingChatModel.EMPTY_REPLY), UserMessage.from("now"));

        List<ChatMessage> groomed = ContextGroom.apply(messages);

        assertEquals("(this turn ended without an answer)", text(groomed, 1));
        assertEquals("(this turn ended without an answer)", text(groomed, 3));
    }

    @Test
    void aConversationThatNeedsNoGroomingIsReturnedAsItIs() {
        List<ChatMessage> messages = List.of(UserMessage.from("hi"), AiMessage.from("Hello."), UserMessage.from("bye"));

        assertEquals(messages, ContextGroom.apply(messages));
    }
}
