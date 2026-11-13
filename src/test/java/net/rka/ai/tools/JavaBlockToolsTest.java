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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tools that read and change single statements of a method, through the registry as the model calls them. */
class JavaBlockToolsTest {

    private static final String LEDGER = """
            package p;

            class Ledger {
                int total;

                int run(int limit) {
                    int sum = 0;
                    for (int i = 0; i < limit; i++) {
                        if (i % 2 == 0) {
                            sum += i;
                        }
                    }
                    if (sum > 10) return sum;
                    return total;
                }
            }
            """;

    @TempDir
    Path folder;

    private final JavaWorkspaces workspaces = new JavaWorkspaces(false);
    private final ToolRegistry tools = ToolRegistry.of(new JavaTools(workspaces));
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void project() throws Exception {
        Files.createDirectories(folder.resolve("src/p"));
        Files.writeString(folder.resolve("src/p/Ledger.java"), LEDGER);
    }

    @AfterEach
    void close() {
        workspaces.close();
    }

    private ToolExecutionResultMessage call(String tool, Object... keysAndValues) throws JsonProcessingException {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("type", "Ledger");
        arguments.put("member", "run");
        for (int i = 0; i < keysAndValues.length; i += 2) arguments.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return tools.execute(ToolExecutionRequest.builder().id("1").name(tool).arguments(JSON.writeValueAsString(arguments)).build(),
                InvocationParameters.from(WorkFolder.PARAMETER, folder));
    }

    private String method() throws Exception {
        return call("javaRead", "member", "run").text();
    }

    @Test
    void theStatementsOfAMethodAreShownWithAddressesAndReadOneByOne() throws Exception {
        String blocks = call("javaBlocks").text();
        assertEquals("[src/p/Ledger.java · method run(int) · lines 6–15]\n"
                + "parameters: int limit\n"
                + "1  declaration 7  int sum = 0;  declares sum\n"
                + "2  for 8–12  for (int i = 0; i < limit; i++) {  declares i\n"
                + "  2/init/1  declaration 8  int i = 0  declares i\n"
                + "  2/update/1  expression 8  i++\n"
                + "  2/body/1  if 9–11  if (i % 2 == 0) {\n"
                + "    2/body/1/then/1  assignment 10  sum += i;\n"
                + "3  if 13  if (sum > 10) return sum;\n"
                + "  3/then/1  return 13  return sum;\n"
                + "4  return 14  return total;", blocks);
        assertTrue(call("javaBlocks", "depth", 1).text().lines().noneMatch(l -> l.contains("2/body")));

        String read = call("javaReadBlock", "address", "2/body/1/then/1").text();
        assertTrue(read.startsWith("[src/p/Ledger.java · method run(int) · 2/body/1/then/1 · lines 10–10]\n"), read);
        assertTrue(read.contains("sum += i;") && read.contains("sees: int limit, int sum, int i") && read.contains("reads: sum, i") && read.contains("writes: sum"), read);
        assertTrue(call("javaReadBlock", "address", "9").text().contains("There is no statement 9 here, there are 4"));
    }

    @Test
    void aStatementIsReplacedInsertedAndDeletedByItsAddressWithTheIndentationOfItsNeighbours() throws Exception {
        assertFalse(Boolean.TRUE.equals(call("javaReplaceBlock", "address", "2/body/1/then/1", "code", "sum += 2 * i;").isError()));
        assertTrue(method().contains("            sum += 2 * i;\n"), method());

        call("javaInsertBlock", "where", "after", "address", "2/body/1", "code", "log(sum);");
        call("javaInsertBlock", "where", "end", "address", "2/body", "code", "count++;");
        call("javaInsertBlock", "where", "start", "address", "", "code", "// start\nlog(limit);");
        call("javaInsertBlock", "where", "before", "address", "4", "code", "if (sum < 0) {\n    sum = 0;\n}");
        String method = method();
        assertTrue(method.contains("        // start\n        log(limit);\n        int sum = 0;\n"), method);
        assertTrue(method.contains("            }\n            log(sum);\n            count++;\n        }\n"), method);
        assertTrue(method.contains("        if (sum < 0) {\n            sum = 0;\n        }\n        if (sum > 10) return sum;\n"), method);

        call("javaDeleteBlock", "address", "5");
        assertFalse(method().contains("if (sum > 10)"), method());
        // what is in memory is written before the next command
        workspaces.flush(folder);
        assertTrue(Files.readString(folder.resolve("src/p/Ledger.java")).contains("sum += 2 * i;"));
    }

    @Test
    void aPartialChangeWrittenLikeAPatchIsFoundByItsContextWhateverMovedAbove() throws Exception {
        // lines are added above first: the numbers and addresses of everything below have changed
        call("javaInsertBlock", "where", "start", "address", "", "code", "log(limit);\nlog(limit + 1);");

        ToolExecutionResultMessage patched = call("javaPatch", "patch", String.join("\n",
                "@@ the test in the loop",
                "             if (i % 2 == 0) {",
                "-                sum += i;",
                "+                sum += 2 * i;",
                "+                log(i);",
                "             }"));

        assertFalse(Boolean.TRUE.equals(patched.isError()), patched.text());
        assertTrue(patched.text().startsWith("Patched method run(int)") && patched.text().contains("+2 −1 lines"), patched.text());
        String method = method();
        assertTrue(method.contains("            if (i % 2 == 0) {\n                sum += 2 * i;\n                log(i);\n            }\n"), method);
        assertTrue(method.contains("        log(limit);\n        log(limit + 1);\n        int sum = 0;\n"), "the rest is as it was: " + method);
        // two hunks at once, found at their own places, the second with a different indentation than the file
        ToolExecutionResultMessage two = call("javaPatch", "patch", String.join("\n",
                "@@",
                " int sum = 0;",
                "+int skipped = 0;",
                "@@",
                "-return total;",
                "+return total + skipped;"));
        assertFalse(Boolean.TRUE.equals(two.isError()), two.text());
        assertTrue(method().contains("        int sum = 0;\n        int skipped = 0;\n") && method().contains("        return total + skipped;\n"), method());
    }

    @Test
    void aPatchThatFitsNowhereOrInSeveralPlacesOrBreaksTheMethodChangesNothing() throws Exception {
        String before = method();

        ToolExecutionResultMessage nowhere = call("javaPatch", "patch", " if (i % 3 == 0) {\n-sum += i;\n+sum += 3;");
        assertTrue(nowhere.text().contains("does not fit") && nowhere.text().contains("`if (i % 3 == 0) {` is not in the text"), nowhere.text());
        ToolExecutionResultMessage partly = call("javaPatch", "patch", " if (i % 2 == 0) {\n-sum -= i;\n+sum += 3;");
        assertTrue(partly.text().contains("line 2 of it (`sum -= i;`) is not what is at line 10 (`sum += i;`)"), partly.text());
        ToolExecutionResultMessage several = call("javaPatch", "patch", " }\n+log(1);");
        assertTrue(several.text().contains("fits at lines") && several.text().contains("add a line above or below it"), several.text());
        assertTrue(call("javaPatch", "patch", "+log(1);").text().contains("has only added lines"));
        assertTrue(call("javaPatch", "patch", "no sign").text().contains("is not in the text"), "a line without a sign is a context line");
        // a result that is not valid Java, a variable that is lost
        assertTrue(call("javaPatch", "patch", " return total;\n+}").text().contains("no longer be readable as Java"));
        assertTrue(call("javaPatch", "patch", "-int sum = 0;").text().contains("'sum' was declared by the code that is removed"));

        assertEquals(before, method());
    }

    @Test
    void anExpectedFirstLineProtectsAnAddressThatMovedAndJavaToolsRefuseOtherFiles() throws Exception {
        call("javaInsertBlock", "where", "start", "address", "", "code", "log(limit);");

        ToolExecutionResultMessage moved = call("javaDeleteBlock", "address", "1", "expect", "int sum = 0;");
        assertTrue(moved.text().contains("Statement 1 is now `log(limit);`, not `int sum = 0;`: the statements have moved"), moved.text());
        assertFalse(Boolean.TRUE.equals(call("javaDeleteBlock", "address", "1", "expect", "log(limit)").isError()));

        Files.writeString(folder.resolve("README.md"), "# Ledger\n");
        assertTrue(call("javaOutline", "target", "README.md").text().contains("not a Java file"));
        assertTrue(call("javaOutline", "target", "docs/guide.txt").text().contains("not a Java file"));
        assertTrue(call("javaCreate", "path", "notes.md", "code", "class A {}").text().contains(".java"));
        assertTrue(call("javaPatch", "type", "README", "patch", "-x").text().contains("No type README"));
    }

    @Test
    void aChangeThatWouldNotLeaveTheMethodAsItShouldBeIsRefusedAndNothingChanges() throws Exception {
        String before = method();

        // new code after a statement without braces would land outside the if
        ToolExecutionResultMessage outside = call("javaInsertBlock", "where", "after", "address", "3/then/1", "code", "log(sum);");
        assertTrue(outside.text().contains("A statement without braces"), outside.text());
        // the variable that only the removed code declares is still used
        ToolExecutionResultMessage lost = call("javaDeleteBlock", "address", "1");
        assertTrue(lost.text().contains("'sum' was declared by the code that is removed, but line"), lost.text());
        // a variable declared twice where both are visible
        ToolExecutionResultMessage twice = call("javaInsertBlock", "where", "before", "address", "2", "code", "int sum = 1;");
        assertTrue(twice.text().contains("'sum' would be declared twice"), twice.text());
        // not statements, not Java, not an address
        assertTrue(call("javaReplaceBlock", "address", "1", "code", "int sum = ;").text().contains("not statements that read as Java"));
        assertTrue(call("javaReplaceBlock", "address", "2/body", "code", "x();").text().contains("needs the address of a statement"));
        assertTrue(call("javaInsertBlock", "where", "sideways", "address", "1", "code", "x();").text().contains("where is before, after, start or end"));

        assertEquals(before, method());
    }
}
