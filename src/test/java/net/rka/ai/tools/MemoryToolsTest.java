package net.rka.ai.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationParameters;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryToolsTest {

    private final ToolRegistry tools = ToolRegistry.of(new MemoryTools());

    private String call(FactBook book, String tool, String arguments) {
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name(tool).arguments(arguments).build();
        return tools.execute(request, InvocationParameters.from(FactBook.PARAMETER, book)).text();
    }

    @Test
    void remembersListsAndForgets() {
        FactBook book = new FactBook(List.of());
        assertEquals("Remembered as [f1]: Favorite bike is the Street Triple",
                call(book, "remember", "{\"fact\": \"Favorite bike is the Street Triple\"}"));
        call(book, "remember", "{\"fact\": \"Lives in Seattle\"}");
        call(book, "remember", "{\"fact\": \"lives in seattle\"}"); // duplicate, different case
        assertEquals("[f1] Favorite bike is the Street Triple\n[f2] Lives in Seattle", call(book, "listFacts", "{}"));

        assertEquals("Forgot f1", call(book, "forget", "{\"id\": \"f1\"}"));
        assertTrue(call(book, "forget", "{\"id\": \"f1\"}").startsWith("Error: No fact"));
        assertEquals(List.of(new FactBook.Fact("f2", "Lives in Seattle")), book.list());
    }

    @Test
    void idsAreNeverReused() {
        FactBook book = new FactBook(List.of(new FactBook.Fact("f1", "a"), new FactBook.Fact("f2", "b")));
        book.remove("f2");
        assertEquals("f3", book.add("c").id());
    }

    @Test
    void rememberAndForgetCountAsWrites() {
        assertTrue(tools.writesFiles("remember"));
        assertTrue(tools.writesFiles("forget"));
        assertTrue(!tools.writesFiles("listFacts"));
    }
}
