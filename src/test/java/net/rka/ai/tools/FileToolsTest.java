package net.rka.ai.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolsTest {

    @TempDir
    Path temp;

    private final ToolRegistry tools = ToolRegistry.of(new FileTools());

    private ToolExecutionResultMessage call(Path folder, String tool, String arguments) {
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name(tool).arguments(arguments).build();
        return tools.execute(request, InvocationParameters.from(WorkFolder.PARAMETER, folder));
    }

    private static boolean failed(ToolExecutionResultMessage result) {
        return Boolean.TRUE.equals(result.isError());
    }

    @Test
    void writesReadsMovesAndDeletesInsideTheFolder() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("work"));

        assertFalse(failed(call(folder, "writeFile", "{\"path\": \"notes/a.txt\", \"content\": \"hello\"}")));
        assertEquals("hello", call(folder, "readFile", "{\"path\": \"notes/a.txt\"}").text());
        assertFalse(failed(call(folder, "movePath", "{\"from\": \"notes/a.txt\", \"to\": \"b.txt\"}")));
        assertEquals("b.txt\nnotes/", call(folder, "listFiles", "{}").text());
        assertFalse(failed(call(folder, "deletePath", "{\"path\": \"notes\"}")));
        assertEquals("b.txt", call(folder, "findFiles", "{\"glob\": \"*.txt\"}").text());
    }

    @Test
    void refusesPathsOutsideTheFolder() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("work"));
        Path outside = Files.writeString(temp.resolve("secret.txt"), "secret");

        assertTrue(failed(call(folder, "readFile", "{\"path\": \"../secret.txt\"}")));
        assertTrue(failed(call(folder, "readFile", "{\"path\": \"" + json(outside) + "\"}")));
        assertTrue(failed(call(folder, "writeFile", "{\"path\": \"../new.txt\", \"content\": \"x\"}")));
        assertTrue(failed(call(folder, "deletePath", "{\"path\": \"..\"}")));
        assertTrue(failed(call(folder, "deletePath", "{\"path\": \"\"}"))); // the folder itself
        assertTrue(failed(call(folder, "movePath", "{\"from\": \"../secret.txt\", \"to\": \"s.txt\"}")));
        assertFalse(Files.exists(temp.resolve("new.txt")));
        assertTrue(Files.exists(outside));
    }

    @Test
    void absolutePathsInsideTheFolderWork() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("work"));
        Files.writeString(folder.resolve("a.txt"), "inside");
        assertEquals("inside", call(folder, "readFile", "{\"path\": \"" + json(folder.resolve("a.txt")) + "\"}").text());
    }

    @Test
    void theModelDoesNotSeeTheFolderParameter() {
        assertEquals(java.util.Set.of(), properties("workFolder"));
        assertEquals(java.util.Set.of("path", "startLine", "endLine"), properties("readFile"));
        assertEquals(java.util.Set.of("path", "content", "append"), properties("writeFile"));
    }

    private java.util.Set<String> properties(String tool) {
        ToolSpecification spec = tools.specifications().stream().filter(s -> s.name().equals(tool)).findFirst().orElseThrow();
        return spec.parameters() == null ? java.util.Set.of() : spec.parameters().properties().keySet();
    }

    @Test
    void defaultsToTheConfiguredFolderWithoutParameters() {
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name("workFolder").arguments("{}").build();
        assertEquals(WorkFolder.defaultFolder().toString(), tools.execute(request).text());
    }

    @Test
    void bigFilesAreReadInParts() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("work"));
        String line = "x".repeat(99); // 100 characters per line with the newline
        Files.writeString(folder.resolve("big.txt"), (line + "\n").repeat(1000)); // 100,000 characters

        String first = call(folder, "readFile", "{\"path\": \"big.txt\"}").text();
        assertTrue(first.length() < 25_000, "one read stays small");
        assertTrue(first.endsWith("… lines 1–240 of 1000; call readFile again with startLine 241 for more"), first);

        String next = call(folder, "readFile", "{\"path\": \"big.txt\", \"startLine\": 241, \"endLine\": 242}").text();
        assertEquals(line + "\n" + line + "\n… lines 241–242 of 1000", next);
        assertTrue(failed(call(folder, "readFile", "{\"path\": \"big.txt\", \"startLine\": 2000}")));
    }

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }
}
