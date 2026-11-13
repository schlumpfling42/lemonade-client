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
        assertEquals(java.util.Set.of("path", "content", "append", "overwrite"),properties("writeFile"));
    }

    @Test
    void patchFileFindsItsHunksByContextAndRefusesWhatFitsNowhereOrTwice() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("patch"));
        String build = "plugins {\r\n    id 'java'\r\n}\r\n\r\ndependencies {\r\n    implementation 'a:b:1'\r\n    testImplementation 'c:d:2'\r\n}\r\n";
        Files.writeString(folder.resolve("build.gradle"), build);

        ToolExecutionResultMessage patched = call(folder, "patchFile", args("path", "build.gradle", "patch",
                "@@ dependencies\n dependencies {\n     implementation 'a:b:1'\n+    implementation 'e:f:3'\n     testImplementation 'c:d:2'"));

        assertFalse(failed(patched), patched.text());
        assertEquals("plugins {\r\n    id 'java'\r\n}\r\n\r\ndependencies {\r\n    implementation 'a:b:1'\r\n    implementation 'e:f:3'\r\n    testImplementation 'c:d:2'\r\n}\r\n",
                Files.readString(folder.resolve("build.gradle")), "the line ends of the file are kept");
        String before = Files.readString(folder.resolve("build.gradle"));
        assertTrue(call(folder, "patchFile", args("path", "build.gradle", "patch", " }\n+// end")).text().contains("fits at lines"));
        assertTrue(call(folder, "patchFile", args("path", "build.gradle", "patch", "-    implementation 'zzz'\n+x")).text().contains("does not fit"));
        Files.writeString(folder.resolve("Main.java"), "class Main {\n    int x = 1;\n}\n");
        assertTrue(call(folder, "patchFile", args("path", "Main.java", "patch", "-    int x = 1;\n+    int x = 2;")).text().contains("Patched"));
        assertTrue(Files.readString(folder.resolve("Main.java")).contains("int x = 2;") || Files.readString(folder.resolve("Main.java")).contains("int x = 1;"),
                "a Java file is patched too (in memory when the folder is a Java project, else on disk)");
        assertEquals(before, Files.readString(folder.resolve("build.gradle")));
    }

    /** The arguments of a tool call as JSON, from names and values. */
    private static String args(String... namesAndValues) throws Exception {
        java.util.Map<String, String> arguments = new java.util.LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) arguments.put(namesAndValues[i], namesAndValues[i + 1]);
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(arguments);
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
        assertTrue(first.length() < 13_000, "one read stays small");
        assertTrue(first.endsWith("… lines 1–120 of 1000; call readFile again with startLine 121 for more"), first);

        String next = call(folder, "readFile", "{\"path\": \"big.txt\", \"startLine\": 241, \"endLine\": 242}").text();
        assertEquals(line + "\n" + line + "\n… lines 241–242 of 1000", next);
        assertTrue(failed(call(folder, "readFile", "{\"path\": \"big.txt\", \"startLine\": 2000}")));
    }

    @Test
    void writeFileReplacesOnlyWithOverwriteAndAppendsAtTheEnd()throws Exception {
        Path folder = Files.createDirectory(temp.resolve("work"));
        call(folder, "writeFile", "{\"path\": \"a.txt\", \"content\": \"a long first version\"}");
        ToolExecutionResultMessage refused = call(folder, "writeFile", "{\"path\": \"a.txt\", \"content\": \"short\"}");
        assertTrue(failed(refused) && refused.text().contains("editSection"), refused.text()); // no blind overwrite
        assertEquals("a long first version", Files.readString(folder.resolve("a.txt")));
        call(folder, "writeFile", "{\"path\": \"a.txt\", \"content\": \"short\", \"overwrite\": true}");
        assertEquals("short", Files.readString(folder.resolve("a.txt"))); // no tail of the old text left
        call(folder, "writeFile", "{\"path\": \"a.txt\", \"content\": \" + more\", \"append\": true}");
        assertEquals("short + more", Files.readString(folder.resolve("a.txt")));
        call(folder, "writeFile", "{\"path\": \"u.txt\", \"content\": \"Grüße ✓\"}");
        assertEquals("Grüße ✓", Files.readString(folder.resolve("u.txt"))); // UTF-8, more bytes than characters
    }

    @Test
    void readFileKeepsCharactersWholeAcrossLargeFiles() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("work"));
        String text = "äöü€".repeat(3000); // multi-byte characters over many kilobytes
        Files.writeString(folder.resolve("u.txt"), text);
        assertEquals(text, call(folder, "readFile", "{\"path\": \"u.txt\"}").text());
    }

    @Test
    void findPartsRanksThePartsBySeveralTermsInOneCall() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("work"));
        Files.writeString(folder.resolve("Window.java"), "class Window {\n    Object folderMenu() {\n        Object menu = new Object();\n        return menu;\n    }\n\n"
                + "    Object conversationRow() {\n        Object menu = new Object();\n        menu.button();\n        return menu;\n    }\n\n    void unrelated() {\n        int a = 1;\n    }\n}\n");
        Files.writeString(folder.resolve("style.css"), ".conversation-row .menu-button {\n    -fx-padding: 2;\n}\n\n.other {\n    -fx-opacity: 1;\n}\n");

        String text = call(folder, "findParts", "{\"terms\": \"conversation row menu button\"}").text();

        assertTrue(text.contains("Window.java · Window.conversationRow() · lines 7–11"), text);
        assertTrue(text.contains("style.css · lines 1–3"), text);
        assertFalse(text.contains("unrelated") || text.contains(".other"), text);
    }

    @Test
    void theModelIsOfferedNamedParametersNotArg0AndArg1() {
        // The classes are compiled with -parameters (build.gradle); the compiler of an editor may not do it: the app warns at start
        assertEquals(java.util.List.of(), ToolRegistry.defaultTools().toolsWithUnnamedParameters());
    }

    @Test
    void afterAToolThatWritesFilesTheHookRunsAndItsNoteIsPartOfTheResult() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("work"));
        java.util.List<String> ran = new java.util.ArrayList<>();
        ToolRegistry registry = ToolRegistry.of(new FileTools()).afterWrite(p -> {
            ran.add("written");
            return "Not written to disk: A.java changed meanwhile.";
        });
        java.util.function.BiFunction<String, String, ToolExecutionResultMessage> run = (tool, arguments) -> registry.execute(
                ToolExecutionRequest.builder().id("1").name(tool).arguments(arguments).build(),
                InvocationParameters.from(WorkFolder.PARAMETER, folder));

        ToolExecutionResultMessage written = run.apply("writeFile", "{\"path\": \"A.txt\", \"content\": \"hi\"}");
        run.apply("readFile", "{\"path\": \"A.txt\"}");

        assertEquals(java.util.List.of("written"), ran, "only the tool that writes files runs the hook");
        assertTrue(written.text().contains("Not written to disk: A.java changed meanwhile."), written.text());
    }

    @Test
    void aGlobWithAnyFoldersInTheMiddleAlsoMatchesNoFolderAtAll() throws Exception {
        Path folder = Files.createDirectories(temp.resolve("work/src/ui"));
        Files.writeString(folder.resolve("View.java"), "class View { Object r = new Renderer(); }\n");
        Path root = temp.resolve("work");

        assertTrue(call(root, "searchFiles", "{\"pattern\": \"Renderer\", \"glob\": \"src/ui/**/*.java\"}").text().contains("View.java"));
        assertTrue(call(root, "findFiles", "{\"glob\": \"**/*.java\"}").text().contains("View.java"));
        assertTrue(call(root, "findFiles", "{\"glob\": \"src/**/ui/*.java\"}").text().contains("View.java"));
    }

    @Test
    void whereListsTheDefinitionFirstThenTheUsesAsWholeParts() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("work"));
        Files.writeString(folder.resolve("Main.java"), "class Main {\n    void run() {\n        go();\n    }\n\n    void go() {\n    }\n}\n");

        String text = call(folder, "where", "{\"name\": \"go\"}").text();

        assertEquals("Main.java · Main.go() · lines 6–7 (method) — defined here\n  6: void go() {\nMain.java · Main.run() · lines 2–4 (method)\n  3: go();", text);
        assertEquals("No match for nothing in the project.", call(folder, "where", "{\"name\": \"nothing\"}").text());
    }

    @Test
    void searchFilesFindsLinesAndSkipsBuildOutput() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("work"));
        Files.createDirectories(folder.resolve("src/app"));
        Files.createDirectories(folder.resolve("build/generated"));
        Files.writeString(folder.resolve("src/app/Main.java"), "class Main {\n    static final int MAX_STEPS = 40;\n}\n");
        Files.writeString(folder.resolve("src/app/notes.md"), "MAX_STEPS is 40\n");
        Files.writeString(folder.resolve("build/generated/Gen.java"), "int MAX_STEPS = 1;\n");

        assertEquals("src/app/Main.java · Main.MAX_STEPS · lines 2–2 (field)\n  2: static final int MAX_STEPS = 40;\n"
                        + "src/app/notes.md · <top> · lines 1–1 (preamble)\n  1: MAX_STEPS is 40",
                call(folder, "searchFiles", "{\"pattern\": \"MAX_STEPS\"}").text()); // build/ skipped, grouped by part
        assertEquals("src/app/Main.java · Main.MAX_STEPS · lines 2–2 (field)\n  2: static final int MAX_STEPS = 40;",
                call(folder, "searchFiles", "{\"pattern\": \"MAX_STEPS\", \"glob\": \"**/*.java\"}").text());
        assertTrue(call(folder, "searchFiles", "{\"pattern\": \"MAX_STEPS\", \"glob\": \"build/**\"}").text()
                .contains("build/generated/Gen.java"), "a glob naming build/ looks inside it");
        assertTrue(call(folder, "searchFiles", "{\"pattern\": \"class main\", \"ignoreCase\": true}").text().contains("Main.java ·"));
        assertTrue(call(folder, "searchFiles", "{\"pattern\": \"int MAX_STEPS = (\"}").text()
                .startsWith("No matches"), "an invalid regex is searched as plain text");
    }

    @Test
    void findFilesSkipsBuildOutputUnlessNamed() throws Exception {
        Path folder = Files.createDirectory(temp.resolve("work"));
        Files.createDirectories(folder.resolve("src"));
        Files.createDirectories(folder.resolve("build/classes"));
        Files.writeString(folder.resolve("src/A.java"), "a");
        Files.writeString(folder.resolve("build/classes/B.java"), "b");
        assertEquals("src" + java.io.File.separator + "A.java", call(folder, "findFiles", "{\"glob\": \"**/*.java\"}").text());
        assertTrue(call(folder, "findFiles", "{\"glob\": \"build/**/*.java\"}").text().contains("B.java"));
    }

    private static String json(Path path) {
        return path.toAbsolutePath().toString().replace("\\", "\\\\");
    }
}
