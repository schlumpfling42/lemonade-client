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

/** outline, readSection and editSection through the tool registry, the way the model calls them. */
class OutlineToolsTest {

    private static final String CLASS_A = """
            class A {
                int x;

                void a() {
                    one();
                }

                void b() {
                }
            }
            """;

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path folder;

    // A registry of its own that doesn't watch: Java files are held in memory and written by flush (see JavaWorkspace)
    private final JavaWorkspaces workspaces = new JavaWorkspaces(false);
    private final ToolRegistry tools = ToolRegistry.of(new FileTools(workspaces), new OutlineTools(workspaces));

    @AfterEach
    void closeWorkspaces() {
        workspaces.close();
    }

    @BeforeEach
    void writeClass() throws Exception {
        Files.writeString(folder.resolve("A.java"), CLASS_A);
    }

    private ToolExecutionResultMessage call(String tool, Object... keysAndValues) throws JsonProcessingException {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) arguments.put((String) keysAndValues[i], keysAndValues[i + 1]);
        ToolExecutionRequest request = ToolExecutionRequest.builder().id("1").name(tool)
                .arguments(JSON.writeValueAsString(arguments)).build();
        return tools.execute(request, InvocationParameters.from(WorkFolder.PARAMETER, folder));
    }

    private static boolean failed(ToolExecutionResultMessage result) {
        return Boolean.TRUE.equals(result.isError());
    }

    private static String md5(String text) {
        Matcher m = Pattern.compile("md5 ([0-9a-f]{32})").matcher(text);
        assertTrue(m.find(), text);
        return m.group(1);
    }

    /** The file on disk after the changes in memory were written. */
    private String file(String name) throws Exception {
        workspaces.flush(folder);
        return Files.readString(folder.resolve(name));
    }

    private String md5Now() throws Exception {
        return md5(call("outline", "path", "A.java").text());
    }

    @Test
    void aWholeBigClassIsNotReadButItsPartsAreListed() throws Exception {
        StringBuilder big = new StringBuilder("class Big {\n");
        for (int i = 0; i < 400; i++) big.append("    int method").append(i).append("() {\n        return ").append(i).append(" + 1000000;\n    }\n");
        Files.writeString(folder.resolve("Big.java"), big.append("}\n").toString());

        String whole = call("readSection", "path", "Big.java", "section", "Big").text();
        String one = call("readSection", "path", "Big.java", "section", "Big.method7()").text();

        assertTrue(whole.contains("NOT read") && whole.contains("Big.method7() (lines"), whole.substring(0, Math.min(300, whole.length())));
        assertTrue(one.contains("complete") && one.contains("return 7 + 1000000;"), one);
    }

    @Test
    void aJavaEditStaysInMemoryUntilItIsFlushed() throws Exception {
        String md5 = md5Now();

        ToolExecutionResultMessage edit = call("editSection", "path", "A.java", "section", "A.b()", "newText", "void b() {\n    two();\n}", "md5", md5);

        assertFalse(failed(edit), edit.text());
        assertEquals(CLASS_A, Files.readString(folder.resolve("A.java")), "the file is not written yet");
        assertTrue(call("readSection", "path", "A.java", "section", "A.b()").text().contains("two();"), "a read sees the edit");
        assertTrue(call("readFile", "path", "A.java").text().contains("two();"));
        workspaces.flush(folder);
        assertTrue(Files.readString(folder.resolve("A.java")).contains("two();"));
        // other files are written at once
        Files.writeString(folder.resolve("B.md"), "# One\ntext\n");
        call("editSection", "path", "B.md", "section", "One", "newText", "# One\nnow", "md5", md5(call("outline", "path", "B.md").text()));
        assertTrue(Files.readString(folder.resolve("B.md")).contains("now"));
    }

    @Test
    void listingAndSearchingSeeJavaFilesAsTheyAreInMemory() throws Exception {
        Files.writeString(folder.resolve("B.md"), "two();\n");
        call("editSection", "path", "A.java", "section", "A.b()", "newText", "void b() {\n    two();\n}", "md5", md5Now());
        call("writeFile", "path", "pkg/New.java", "content", "package pkg;\nclass New {\n    void two() {\n    }\n}\n");

        String search = call("searchFiles", "pattern", "two\\(").text();
        assertTrue(search.contains("A.java · A.b()") && search.contains("two();"), search);
        assertTrue(search.contains("pkg/New.java · New.two()") && search.contains("B.md"), search);
        assertFalse(Files.readString(folder.resolve("A.java")).contains("two();"), "still not written");

        String found = call("findFiles", "glob", "{*.java,**/*.java}").text();
        assertEquals("A.java\n" + Path.of("pkg", "New.java"), found);
        assertEquals("A.java\nB.md\npkg/", call("listFiles").text());
        assertEquals("New.java", call("listFiles", "path", "pkg").text());
    }

    @Test
    void outlineReadAndEditChainThroughTheMd5() throws Exception {
        String outline = call("outline", "path", "A.java").text();
        assertTrue(outline.contains("A.a()") && outline.contains("A.b()") && outline.contains("A.x"), outline);

        String read = call("readSection", "path", "A.java", "section", "A.a()").text();
        assertEquals("[A.java · A.a() · method · lines 4–6 of 10 · md5 " + md5(outline) + " · complete]\n    void a() {\n        one();\n    }", read);

        // Written without the class's indentation: the part is moved to where it belongs
        ToolExecutionResultMessage first = call("editSection", "path", "A.java", "section", "A.a()",
                "newText", "void a() {\n    two();\n}", "md5", md5(read));
        assertFalse(failed(first), first.text());
        assertEquals(CLASS_A.replace("        one();", "        two();"), file("A.java"));
        assertEquals(md5Now(), md5(first.text())); // the result carries the md5 for the next edit

        ToolExecutionResultMessage second = call("editSection", "path", "A.java", "section", "b",
                "newText", "void b() {\n    three();\n}", "md5", md5(first.text()));
        assertFalse(failed(second), second.text());
        assertTrue(file("A.java").contains("    void b() {\n        three();\n    }\n}"), file("A.java"));
        assertFalse(first.text().contains("no longer exist"), first.text()); // same ids: nothing to look up again
    }

    @Test
    void anEditNeedsTheCurrentMd5() throws Exception {
        String old = md5Now();
        Files.writeString(folder.resolve("A.java"), CLASS_A.replace("int x;", "int y;"));

        ToolExecutionResultMessage stale = call("editSection", "path", "A.java", "section", "A.a()", "newText", "void a() {}", "md5", old);
        assertTrue(failed(stale) && stale.text().contains("changed since") && stale.text().contains(md5Now()), stale.text());
        ToolExecutionResultMessage missing = call("editSection", "path", "A.java", "section", "A.a()", "newText", "void a() {}");
        assertTrue(failed(missing) && missing.text().contains("md5 is missing"), missing.text());
        assertEquals(CLASS_A.replace("int x;", "int y;"), file("A.java")); // neither touched the file
    }

    @Test
    void sectionsCanBeAddedBeforeAfterAndToAClass() throws Exception {
        String md5 = md5Now();
        ToolExecutionResultMessage after = call("editSection", "path", "A.java", "section", "A.b()", "mode", "after",
                "newText", "void c() {}", "md5", md5);
        assertFalse(failed(after), after.text());
        assertTrue(after.text().contains("New sections: A.c()"), after.text());
        assertEquals(CLASS_A.replace("    void b() {\n    }\n", "    void b() {\n    }\n\n    void c() {}\n"), file("A.java"));

        ToolExecutionResultMessage before = call("editSection", "path", "A.java", "section", "A.a()", "mode", "before",
                "newText", "/** First. */\nvoid z() {}", "md5", md5(after.text()));
        assertFalse(failed(before), before.text());
        assertTrue(file("A.java").contains("    /** First. */\n    void z() {}\n\n    void a() {"), file("A.java"));

        // add: a new method goes below the last method (c), above the class's closing brace
        ToolExecutionResultMessage method = call("editSection", "path", "A.java", "section", "A", "mode", "add",
                "newText", "void d() {}", "md5", md5(before.text()));
        assertFalse(failed(method), method.text());
        assertTrue(file("A.java").endsWith("    void c() {}\n\n    void d() {}\n}\n"), file("A.java"));

        // a new field goes below the last field (x), not at the end of the class; one field next to another stays together
        ToolExecutionResultMessage field = call("editSection", "path", "A.java", "section", "A", "mode", "add",
                "newText", "int last;", "md5", md5(method.text()));
        assertFalse(failed(field), field.text());
        assertTrue(file("A.java").startsWith("class A {\n    int x;\n    int last;\n\n    /** First. */"), file("A.java"));
        assertTrue(file("A.java").endsWith("    void d() {}\n}\n"), file("A.java"));

        // add only works on a class
        ToolExecutionResultMessage notAClass = call("editSection", "path", "A.java", "section", "A.x", "mode", "add",
                "newText", "int q;", "md5", md5(field.text()));
        assertTrue(failed(notAClass) && notAClass.text().contains("not a class"), notAClass.text());
        assertTrue(failed(call("editSection", "path", "A.java", "section", "A.x", "mode", "sideways",
                "newText", "int q;", "md5", md5(field.text()))));
    }

    @Test
    void aNewPartGoesWhereItsKindBelongsInTheClass() throws Exception {
        Files.writeString(folder.resolve("B.java"), """
                class B {
                    int a;
                    int b;

                    B() {
                    }

                    void m() {
                    }

                    void n() {
                    }

                    static class Inner {
                    }
                }
                """);
        String md5 = md5(call("outline", "path", "B.java").text());

        // a constructor goes below the last constructor, a method below the last method (n), above the nested class
        ToolExecutionResultMessage ctor = call("editSection", "path", "B.java", "section", "B", "mode", "add",
                "newText", "B(int a) {\n    this.a = a;\n}", "md5", md5);
        assertFalse(failed(ctor), ctor.text());
        ToolExecutionResultMessage method = call("editSection", "path", "B.java", "section", "B", "mode", "add",
                "newText", "/** New. */\nvoid o() {}", "md5", md5(ctor.text()));
        assertFalse(failed(method), method.text());
        ToolExecutionResultMessage nested = call("editSection", "path", "B.java", "section", "B", "mode", "add",
                "newText", "static class Other {\n}", "md5", md5(method.text()));
        assertFalse(failed(nested), nested.text());

        assertEquals("""
                class B {
                    int a;
                    int b;

                    B() {
                    }

                    B(int a) {
                        this.a = a;
                    }

                    void m() {
                    }

                    void n() {
                    }

                    /** New. */
                    void o() {}

                    static class Inner {
                    }

                    static class Other {
                    }
                }
                """, file("B.java"));
        assertTrue(nested.text().contains("New sections: B.Other"), nested.text());
    }

    @Test
    void aConstructorWithNoneYetGoesBelowTheFields() throws Exception {
        Files.writeString(folder.resolve("C.java"), "class C {\n    int a;\n\n    void m() {\n    }\n}\n");
        ToolExecutionResultMessage ctor = call("editSection", "path", "C.java", "section", "C", "mode", "add",
                "newText", "C() {}", "md5", md5(call("outline", "path", "C.java").text()));
        assertFalse(failed(ctor), ctor.text());
        assertEquals("class C {\n    int a;\n\n    C() {}\n\n    void m() {\n    }\n}\n", file("C.java"));
    }

    @Test
    void aSectionCanBeDeletedAndItsHeaderChanged() throws Exception {
        ToolExecutionResultMessage deleted = call("editSection", "path", "A.java", "section", "A.a()", "newText", "", "md5", md5Now());
        assertFalse(failed(deleted), deleted.text());
        assertEquals("class A {\n    int x;\n\n    void b() {\n    }\n}\n", file("A.java")); // one blank line, not two
        assertFalse(deleted.text().contains("no longer exist"), deleted.text()); // the deleted id is expected to go

        ToolExecutionResultMessage header = call("editSection", "path", "A.java", "section", "A.<header>",
                "newText", "class A implements Runnable {", "md5", md5(deleted.text()));
        assertFalse(failed(header), header.text());
        assertTrue(file("A.java").startsWith("class A implements Runnable {\n    int x;"), file("A.java"));
    }

    @Test
    void renamingASectionSaysWhichIdsToLookUpAgain() throws Exception {
        ToolExecutionResultMessage renamed = call("editSection", "path", "A.java", "section", "A.a()",
                "newText", "void renamed(int n) {}", "md5", md5Now());
        assertFalse(failed(renamed), renamed.text());
        assertTrue(renamed.text().contains("New sections: A.renamed(int)")
                && renamed.text().contains("no longer exist: A.a()") && renamed.text().contains("Call outline again"), renamed.text());
    }

    @Test
    void windowsLineBreaksStayAndMarkdownWorks() throws Exception {
        Files.writeString(folder.resolve("B.md"), "# One\r\ntext\r\n\r\n# Two\r\nmore\r\n");
        String outline = call("outline", "path", "B.md").text();
        ToolExecutionResultMessage edit = call("editSection", "path", "B.md", "section", "Two", "newText", "# Two\nchanged", "md5", md5(outline));
        assertFalse(failed(edit), edit.text());
        assertEquals("# One\r\ntext\r\n\r\n# Two\r\nchanged\r\n", file("B.md"));
    }

    @Test
    void unknownSectionsAndFilesAreErrorsThatHelp() throws Exception {
        ToolExecutionResultMessage unknown = call("readSection", "path", "A.java", "section", "A.nothing");
        assertTrue(failed(unknown) && unknown.text().contains("No section"), unknown.text());
        assertTrue(failed(call("outline", "path", "Missing.java")));
        assertTrue(failed(call("outline", "path", "../secret.java")));
        Files.write(folder.resolve("bin.dat"), new byte[]{(byte) 0xC3, (byte) 0x28});
        assertTrue(failed(call("outline", "path", "bin.dat")));
    }

    @Test
    void aBigCodeFileIsReadAsItsOutline() throws Exception {
        StringBuilder big = new StringBuilder("class Big {\n");
        for (int i = 0; i < 800; i++) big.append("    int m").append(i).append("() { return ").append(i).append("; } // padding padding\n");
        big.append("}\n");
        Files.writeString(folder.resolve("Big.java"), big.toString());

        String read = call("readFile", "path", "Big.java").text();
        assertTrue(read.contains("800 parts") && read.contains("readSection") && read.length() < 24_000, read.substring(0, 300));
        // the parts of the class are listed on request, as many as fit, and the rest can still be read by id
        String parts = call("outline", "path", "Big.java", "section", "Big").text();
        assertTrue(parts.contains("Big.m0()") && parts.contains("more sections not shown") && parts.length() < 14_000, parts);
        assertTrue(call("readSection", "path", "Big.java", "section", "Big.m799").text().contains("return 799;"));
        // with a range the model gets the lines as before
        assertTrue(call("readFile", "path", "Big.java", "startLine", 2, "endLine", 2).text().startsWith("    int m0()"));
        // and a small file is still read whole
        assertEquals(CLASS_A, call("readFile", "path", "A.java").text());
    }

    @Test
    void theNewToolsAreMarkedForApprovalLikeTheOldOnes() {
        assertTrue(tools.writesFiles("editSection"));
        assertFalse(tools.writesFiles("outline"));
        assertFalse(tools.writesFiles("readSection"));
    }
}
