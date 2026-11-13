package net.rka.ai.tools.outline;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sections of Java, Markdown and other files, and finding one by a (slightly wrong) id. */
class OutlineTest {

    // TQ stands for a text block's quotes, which can't be written inside this text block
    private static final String JAVA = """
            package net.rka.demo;

            import java.util.List;
            import java.util.Map;

            /** Doc of Foo. */
            @SuppressWarnings("unused")
            public class Foo<T> implements Runnable {

                public static final int LIMIT = 10; // trailing comment
                private static final String BRACES = "{ not a block; }";
                private final Map<String, List<Integer>> index = new java.util.HashMap<>();
                private int[] numbers = {1, 2, 3};
                private final Runnable task = () -> {
                    System.out.println("x");
                };

                static {
                    init();
                }

                public Foo(String name, int... rest) {
                }

                /** Runs. */
                @Override
                public void run() {
                    String s = TQ
                        } text block {
                        TQ;
                    char c = '}';
                    // } comment
                }

                public <E extends Comparable<E>> E max(List<E> items, @Deprecated final int n) {
                    return null;
                }

                void over(String a) {}
                void over(int a) {}

                public enum Color {
                    RED("r"), GREEN("g") {
                        @Override String code() { return "x"; }
                    };

                    private final String code;
                    Color(String code) { this.code = code; }
                    String code() { return code; }
                }

                public record Point(int x, int y) {
                    public Point {
                        if (x < 0) throw new IllegalArgumentException();
                    }
                    double dist() { return 0; }
                }

                interface Shape {
                    double area();
                    default String name() { return "s"; }
                }
            }
            """.replace("TQ", "\"\"\"");

    private static Outline outline(String name, String text) {
        return Outlines.of(name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static int lineWith(String text, String fragment) {
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(fragment)) return i + 1;
        throw new AssertionError(fragment);
    }

    @Test
    void javaFileIsSplitIntoItsParts() {
        List<String> parts = outline("Foo.java", JAVA).sections().stream()
                .map(s -> s.kind() + " " + s.id()).collect(Collectors.toList());
        assertEquals(List.of(
                "package package",
                "imports imports",
                "class Foo",
                "header Foo.<header>",
                "field Foo.LIMIT",
                "field Foo.BRACES",
                "field Foo.index",
                "field Foo.numbers",
                "field Foo.task",
                "initializer Foo.static{}",
                "constructor Foo.Foo(String,int...)",
                "method Foo.run()",
                "method Foo.max(List,int)",
                "method Foo.over(String)",
                "method Foo.over(int)",
                "enum Foo.Color",
                "header Foo.Color.<header>",
                "constants Foo.Color.constants",
                "field Foo.Color.code",
                "constructor Foo.Color.Color(String)",
                "method Foo.Color.code()",
                "record Foo.Point",
                "header Foo.Point.<header>",
                "constructor Foo.Point.Point",
                "method Foo.Point.dist()",
                "interface Foo.Shape",
                "header Foo.Shape.<header>",
                "method Foo.Shape.area()",
                "method Foo.Shape.name()"), parts);
    }

    @Test
    void sectionsAreWholeLinesFromTheDocCommentToTheClosingBrace() {
        Outline outline = outline("Foo.java", JAVA);
        Section run = outline.byId("Foo.run()");
        assertEquals(lineWith(JAVA, "/** Runs. */"), run.start()); // the doc comment and annotation belong to it
        assertEquals(lineWith(JAVA, "// } comment") + 1, run.end());

        Section limit = outline.byId("Foo.LIMIT");
        assertEquals(lineWith(JAVA, "LIMIT = 10"), limit.start());
        assertEquals(limit.start(), limit.end()); // the trailing comment is on the same line

        Section header = outline.byId("Foo.<header>");
        assertEquals(lineWith(JAVA, "/** Doc of Foo. */"), header.start());
        assertEquals(lineWith(JAVA, "public class Foo"), header.end());

        Section imports = outline.byId("imports");
        assertEquals(lineWith(JAVA, "import java.util.List"), imports.start());
        assertEquals(lineWith(JAVA, "import java.util.Map"), imports.end());

        Section foo = outline.byId("Foo");
        assertEquals(header.start(), foo.start());
        assertEquals(JAVA.lines().count(), foo.end());
        assertTrue(foo.container());

        Section constants = outline.byId("Foo.Color.constants");
        assertEquals(lineWith(JAVA, "RED(\"r\")"), constants.start());
        assertEquals(lineWith(JAVA, "@Override String code()") + 1, constants.end()); // the "};" after GREEN's body
    }

    @Test
    void theOutlineIsTheSameEveryTimeAndTheMd5FollowsTheContent() {
        Outline first = outline("Foo.java", JAVA);
        assertEquals(first, outline("Foo.java", JAVA));
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Outlines.md5("abc".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(first.md5(), outline("Foo.java", JAVA + "\n").md5());
        assertEquals(32, first.md5().length());
    }

    @Test
    void windowsLineBreaksGiveTheSameSections() {
        Outline unix = outline("Foo.java", JAVA);
        Outline windows = outline("Foo.java", JAVA.replace("\n", "\r\n"));
        assertEquals(unix.sections(), windows.sections());
    }

    @Test
    void aBrokenFileStillGetsAnOutline() {
        Outline broken = outline("Broken.java", "public class Broken {\n    void a() {\n        if (x) {\n");
        assertFalse(broken.sections().isEmpty()); // no exception, whatever is found
    }

    @Test
    void anOutlineOfAHugeFileIsFast() {
        StringBuilder big = new StringBuilder("package p;\n\npublic class Big {\n");
        for (int i = 0; i < 5000; i++) {
            big.append("    /** Method ").append(i).append(". */\n    public int m").append(i).append("(int a) {\n")
                    .append("        return a + \"}\".length() + ").append(i).append(";\n    }\n\n");
        }
        big.append("}\n");
        outline("Big.java", big.toString()); // warm up
        long start = System.nanoTime();
        Outline outline = outline("Big.java", big.toString());
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(5000 + 3, outline.sections().size()); // the methods, the class, its header, the package
        assertTrue(millis < 1000, "an outline of 25,000 lines took " + millis + " ms");
    }

    @Test
    void markdownHasOneSectionPerHeadingAndIgnoresCodeBlocks() {
        String md = """
                Intro text.

                # Setup
                Install it.

                ```bash
                # not a heading
                ```

                ## Install
                Run it.

                ## Install
                Again.

                # Usage
                Use it.
                """;
        Outline outline = outline("README.md", md);
        assertEquals(List.of("<top>", "Setup", "Setup > Install", "Setup > Install#2", "Usage"),
                outline.sections().stream().map(Section::id).toList());
        Section setup = outline.byId("Setup");
        assertEquals(lineWith(md, "# Setup"), setup.start());
        assertEquals(lineWith(md, "```") + 2, setup.end()); // up to the next heading, with its code block
        assertTrue(outline.structured());
    }

    @Test
    void otherFilesAreSplitIntoBlocks() {
        StringBuilder text = new StringBuilder();
        for (int block = 1; block <= 3; block++) {
            for (int line = 1; line <= 12; line++) text.append("block").append(block).append(" line ").append(line).append('\n');
            text.append('\n');
        }
        Outline outline = outline("notes.txt", text.toString());
        assertFalse(outline.structured());
        // 12 lines is under the minimum, so two runs make one block; the third is the rest
        assertEquals(List.of("block 1", "block 2"), outline.sections().stream().map(Section::id).toList());
        assertEquals(1, outline.byId("block 1").start());
        assertEquals(25, outline.byId("block 1").end());
        assertEquals("block1 line 1", outline.byId("block 1").label());
        assertTrue(outline.render("notes.txt", null).contains("no code structure is known"));
    }

    @Test
    void anIdMayBeShortenedWhenItIsUnambiguous() {
        Outline outline = outline("Foo.java", JAVA);
        assertEquals("Foo.run()", outline.find("Foo.run").id());
        assertEquals("Foo.run()", outline.find("run()").id());
        assertEquals("Foo.run()", outline.find(" foo.RUN() ").id());
        assertEquals("Foo.max(List,int)", outline.find("max").id());
        assertEquals("Foo.over(int)", outline.find("Foo.over(int)").id());
        assertEquals("Foo.Color.code", outline.find("Color.code").id()); // the field, not the method code()

        IllegalArgumentException ambiguous = assertThrows(IllegalArgumentException.class, () -> outline.find("over"));
        assertTrue(ambiguous.getMessage().contains("Foo.over(String)") && ambiguous.getMessage().contains("Foo.over(int)"),
                ambiguous.getMessage());
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> outline.find("Foo.runn"));
        assertTrue(unknown.getMessage().contains("Foo.run()"), unknown.getMessage()); // "did you mean"
        assertThrows(IllegalArgumentException.class, () -> outline.find("nothing here"));
    }

    @Test
    void textThatIsNotUtf8IsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Outlines.of("a.bin", new byte[]{(byte) 0xC3, (byte) 0x28}));
    }
}
