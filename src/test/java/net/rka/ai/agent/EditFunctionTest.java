package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The code of an edit is handed in by a function: one text per change. */
class EditFunctionTest {

    private static AiMessage call(String arguments) {
        return AiMessage.from(List.of(ToolExecutionRequest.builder().id("1").name(EditFunction.NAME).arguments(arguments).build()));
    }

    @Test
    void theTextsOfTheCallAreTheCodeInOrder() {
        assertEquals(List.of("void a() {}", "void b() {\n    x();\n}"),
                EditFunction.texts(call("{\"texts\":[\"void a() {}\",\"void b() {\\n    x();\\n}\"]}")));
    }

    @Test
    void aReplyInCodeBlocksIsStillRead() {
        assertEquals(List.of("int x;"), EditFunction.texts(AiMessage.from("```java\nint x;\n```")));
    }

    @Test
    void malformedArgumentsAndAnAnswerWithoutCodeGiveNoTexts() {
        assertTrue(EditFunction.texts(call("{\"texts\": ")).isEmpty());
        assertTrue(EditFunction.texts(AiMessage.from("I'm not sure.")).isEmpty());
    }
}
