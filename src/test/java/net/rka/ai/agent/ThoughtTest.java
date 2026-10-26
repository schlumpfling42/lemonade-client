package net.rka.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThoughtTest {

    @Test
    void separateThinkingField() {
        Thought t = Thought.of(AiMessage.builder().text("The answer is 4.").thinking("2 + 2 = 4").build());
        assertEquals(new Thought("2 + 2 = 4", "The answer is 4."), t);
    }

    @Test
    void inlineThinkTags() {
        assertEquals(new Thought("check the files first", "Let me look."),
                Thought.of(AiMessage.from("<think>\ncheck the files first\n</think>\n\nLet me look.")));
        // Cut off while still thinking: everything is thinking
        assertEquals(new Thought("still going", ""), Thought.of(AiMessage.from("<think>still going")));
    }

    @Test
    void plainReplies() {
        assertEquals(new Thought("", "Hello"), Thought.of(AiMessage.from("Hello")));
        assertEquals(new Thought("", "a <think> in the middle"), Thought.of(AiMessage.from("a <think> in the middle")));
    }
}
