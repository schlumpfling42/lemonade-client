package net.rka.ai.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.invocation.InvocationParameters;
import net.rka.ai.tools.code.java.JavaWorkspaces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Java tools and the file tools in a Java project, through the registry as the model calls them. */
class JavaToolsTest {

    @TempDir
    Path folder;

    private final JavaWorkspaces workspaces = new JavaWorkspaces(false);
    private final ToolRegistry tools = ToolRegistry.of(new FileTools(workspaces), new OutlineTools(workspaces), new JavaTools(workspaces),
            new TerminalTools(workspaces));
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void project() throws Exception {
        Files.createDirectories(folder.resolve("src/p"));
        Files.writeString(folder.resolve("src/p/Ledger.java"), "package p;\n\npublic class Ledger {\n    public int size() {\n        return 1;\n    }\n}\n");
        Files.writeString(folder.resolve("src/p/Account.java"), "package p;\n\nclass Account {\n    Ledger ledger;\n\n    int n() {\n        return ledger.size();\n    }\n}\n");
    }

    @AfterEach
    void close() {
        workspaces.close();
    }

    private ToolExecutionResultMessage call(String tool, Object... keysAndValues) throws JsonProcessingException {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) arguments.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return tools.execute(ToolExecutionRequest.builder().id("1").name(tool).arguments(JSON.writeValueAsString(arguments)).build(),
                InvocationParameters.from(WorkFolder.PARAMETER, folder));
    }

    @Test
    void theProjectATypeAndItsUsagesAreAnsweredFromTheIndexes() throws Exception {
        String project = call("javaProject").text();
        assertTrue(project.contains("p: Account, Ledger") && project.contains("2 Java files") && project.contains("no changes in memory"), project);

        String find = call("javaFind", "name", "Ledger").text();
        assertTrue(find.startsWith("p.Ledger · class · src/p/Ledger.java") && find.contains("public int size()"), find);
        assertTrue(call("javaFind", "name", "Lodger").text().contains("No type Lodger"));
        assertTrue(call("javaFind", "name", "Ledg").text().contains("Similar: p.Ledger"));

        String usages = call("javaUsages", "type", "Ledger").text();
        assertTrue(usages.contains("used by 1 type") && usages.contains("declaration · p.Account.ledger"), usages);
        String callers = call("javaUsages", "type", "Ledger", "member", "size").text();
        assertTrue(callers.contains("p.Account.n() (by name)"), callers);
        assertTrue(call("javaUsages", "type", "Account").text().contains("not used by any other type"));
    }

    @Test
    void membersImportsAndFilesAreCreatedReadChangedAndDeletedThroughTheStructure() throws Exception {
        // read
        assertEquals("[src/p/Ledger.java · method size() · lines 4–6]\n    public int size() {\n        return 1;\n    }", call("javaRead", "type", "Ledger", "member", "size").text());
        assertTrue(call("javaRead", "type", "Ledger").text().contains("public class Ledger"));
        assertTrue(call("javaRead", "type", "Ledger", "member", "nope").text().contains("has no member nope: size()"));

        // create a member, indented like the others and after a blank line
        ToolExecutionResultMessage add = call("javaAdd", "type", "Ledger", "code", "/** Adds. */\npublic void add(int n) {\n    total += n;\n}");
        assertFalse(Boolean.TRUE.equals(add.isError()), add.text());
        assertTrue(call("javaRead", "type", "Ledger").text().endsWith("    }\n\n    /** Adds. */\n    public void add(int n) {\n        total += n;\n    }\n}"));
        call("javaAdd", "type", "Ledger", "code", "int total;");
        assertTrue(call("javaRead", "type", "Ledger", "member", "total").text().endsWith("]\n    int total;"));

        // update one member by name or overload; the rest is untouched
        call("javaReplace", "type", "Ledger", "member", "add(int)", "code", "public void add(int n) {\n    total += 2 * n;\n}");
        assertTrue(call("javaRead", "type", "Ledger", "member", "add").text().contains("total += 2 * n;"));
        assertTrue(call("javaRead", "type", "Ledger", "member", "size").text().contains("return 1;"));

        // refused, and nothing changes: not Java, a member twice
        ToolExecutionResultMessage broken = call("javaReplace", "type", "Ledger", "member", "size", "code", "public int size() {\n    return 1;\n}\n}");
        assertTrue(broken.text().contains("no longer be readable as Java"), broken.text());
        ToolExecutionResultMessage twice = call("javaAdd", "type", "Ledger", "code", "public int size() {\n    return 2;\n}");
        assertTrue(twice.text().contains("twice"), twice.text());
        assertTrue(call("javaRead", "type", "Ledger", "member", "size").text().contains("return 1;"), "nothing changed");

        // imports
        call("javaImport", "type", "Ledger", "name", "java.util.List");
        call("javaImport", "type", "Ledger", "name", "java.util.Map");
        assertEquals("The file imports java.util.List already.", call("javaImport", "type", "Ledger", "name", "java.util.List").text());
        call("javaImport", "type", "Ledger", "name", "java.util.List", "remove", true);
        workspaces.flush(folder);
        assertEquals("package p;\n\nimport java.util.Map;\n\npublic class Ledger {",
                Files.readString(folder.resolve("src/p/Ledger.java")).lines().limit(5).collect(java.util.stream.Collectors.joining("\n")));

        // delete a member, create a file, delete the type that is alone in it
        call("javaDelete", "type", "Ledger", "member", "total");
        assertTrue(call("javaRead", "type", "Ledger", "member", "total").text().contains("has no member total"));
        ToolExecutionResultMessage created = call("javaCreate", "path", "src/p/Extra.java", "code", "package p;\n\nclass Extra {\n}\n");
        assertTrue(created.text().startsWith("Created src/p/Extra.java with Extra"), created.text());
        assertTrue(call("javaCreate", "path", "src/p/Extra.java", "code", "package p;\nclass Extra {}\n").text().contains("exists already"));
        assertTrue(call("javaCreate", "path", "src/p/Bad.java", "code", "package p;\nclass Bad {").text().contains("no longer be readable"));
        workspaces.flush(folder);
        call("javaDelete", "type", "Extra");
        workspaces.flush(folder);
        assertFalse(Files.exists(folder.resolve("src/p/Extra.java")));
    }

    @Test
    void theOutlineNamesEveryMemberAsTheOtherToolsTakeItAndTheGraphFollowsCalls() throws Exception {
        Files.writeString(folder.resolve("src/p/Main.java"), "package p;\n\npublic class Main {\n    public static void main(String[] args) {\n        Account a = new Account();\n        a.n();\n    }\n}\n");
        call("javaAdd", "type", "Ledger", "code", "public int size(int extra) {\n    return size() + extra;\n}");

        String outline = call("javaOutline", "target", "Ledger").text();
        assertTrue(outline.startsWith("src/p/Ledger.java · "), outline);
        assertTrue(outline.contains("class Ledger · 3–") && outline.contains("method size() · 4–6") && outline.contains("method size(int) · "), outline);
        assertTrue(call("javaOutline", "target", "src/p/Account.java").text().contains("field ledger · 4") , call("javaOutline", "target", "src/p/Account.java").text());

        String graph = call("javaGraph").text();
        assertTrue(graph.contains("Main.main(String[])  src/p/Main.java:4\n  Account.<init>") || graph.contains("Main.main(String[])  src/p/Main.java:4\n  Account.n()"), graph);
        assertTrue(graph.contains("    Ledger.size()  src/p/Ledger.java:4"), graph);
        assertTrue(call("javaGraph", "from", "Account.n", "depth", 1).text().contains("Ledger.size()"));
        String callers = call("javaGraph", "from", "Ledger.size()", "direction", "callers").text();
        assertTrue(callers.contains("Account.n()") && callers.contains("Main.main(String[])"), callers);
        assertTrue(call("javaGraph", "from", "Nope").text().contains("No type Nope"));
    }

    @Test
    void javaEditsStayInMemoryUntilACommandOrFlushAndOtherToolsSeeThem() throws Exception {
        String md5 = md5(call("outline", "path", "src/p/Ledger.java").text());

        ToolExecutionResultMessage edit = call("editSection", "path", "src/p/Ledger.java", "section", "Ledger.size()",
                "newText", "public int size() {\n    return new Account().n();\n}", "md5", md5);

        assertFalse(Boolean.TRUE.equals(edit.isError()), edit.text());
        assertTrue(Files.readString(folder.resolve("src/p/Ledger.java")).contains("return 1;"), "not written yet");
        assertTrue(call("javaProject").text().contains("changes in memory, not written yet: src/p/Ledger.java"));
        assertTrue(call("readFile", "path", "src/p/Ledger.java").text().contains("new Account().n()"));
        assertTrue(call("javaUsages", "type", "Account").text().contains("p.Ledger.size()"), "the index follows the edit");

        String flushed = call("javaFlush").text();

        assertTrue(flushed.startsWith("Wrote 1 file(s): src/p/Ledger.java"), flushed);
        assertTrue(Files.readString(folder.resolve("src/p/Ledger.java")).contains("new Account().n()"));
    }

    @Test
    void discardingBringsTheFileBack() throws Exception {
        call("writeFile", "path", "src/p/Extra.java", "content", "package p;\nclass Extra {}\n");
        assertFalse(Files.exists(folder.resolve("src/p/Extra.java")), "a new Java file is held in memory too");
        assertTrue(call("javaFind", "name", "Extra").text().startsWith("p.Extra"));

        String discarded = call("javaDiscard").text();

        assertTrue(discarded.contains("src/p/Extra.java"), discarded);
        assertTrue(call("javaFind", "name", "Extra").text().startsWith("No type Extra"));
    }

    @Test
    void aCommandRunsOnWhatIsInMemoryAndAFileChangedMeanwhileStopsIt() throws Exception {
        String md5 = md5(call("outline", "path", "src/p/Ledger.java").text());
        call("editSection", "path", "src/p/Ledger.java", "section", "Ledger.size()", "newText", "public int size() {\n    return 2;\n}", "md5", md5);
        String command = CommandRunner.isWindows() ? "Get-Content src/p/Ledger.java" : "cat src/p/Ledger.java";

        assertTrue(call("runCommand", "command", command).text().contains("return 2;"), "the command saw the edit");

        md5 = md5(call("outline", "path", "src/p/Ledger.java").text());
        call("editSection", "path", "src/p/Ledger.java", "section", "Ledger.size()", "newText", "public int size() {\n    return 3;\n}", "md5", md5);
        Files.writeString(folder.resolve("src/p/Ledger.java"), "package p;\npublic class Ledger {}\n// the user's edit\n");

        ToolExecutionResultMessage refused = call("runCommand", "command", command);

        assertTrue(Boolean.TRUE.equals(refused.isError()) && refused.text().contains("changed on disk") && refused.text().contains("javaFlush"),
                refused.text());
        assertTrue(Files.readString(folder.resolve("src/p/Ledger.java")).contains("the user's edit"));
        assertTrue(call("javaFlush", "force", true).text().contains("Wrote 1 file(s)"));
    }

    @Test
    void aFolderWithoutJavaFilesHasNoJavaTools() throws Exception {
        Path other = Files.createDirectories(folder.resolve("docs-only"));
        Files.writeString(other.resolve("a.md"), "# A\n");

        ToolExecutionResultMessage result = tools.execute(ToolExecutionRequest.builder().id("1").name("javaProject").arguments("{}").build(),
                InvocationParameters.from(WorkFolder.PARAMETER, other));

        assertTrue(Boolean.TRUE.equals(result.isError()) && result.text().contains("no Java files"), result.text());
        assertEquals(true, tools.writesFiles("javaFlush") && tools.writesFiles("javaDiscard") && !tools.writesFiles("javaFind"));
    }

    private static String md5(String text) {
        Matcher m = Pattern.compile("md5 ([0-9a-f]{32})").matcher(text);
        assertTrue(m.find(), text);
        return m.group(1);
    }
}
