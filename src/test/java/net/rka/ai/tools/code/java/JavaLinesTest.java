package net.rka.ai.tools.code.java;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every type holds the whole text of its file, and every entity knows the lines it covers in that text. */
class JavaLinesTest {

    private static final String SOURCE = """
            /* header */
            package p;

            import java.util.List;
            import java.util.Map;

            /** The A. */
            @Deprecated
            public abstract class A { // open
                /** The names. */
                private int a = 1,
                    b = 2;

                static {
                    init();
                }

                public A(int x) {
                    a = x;
                }

                /** Runs. */
                @Override
                public void run() {
                    a++;
                }

                abstract void gone(); // note

                enum E {
                    ONE,
                    /** two */
                    TWO {
                        void f() {}
                    };
                }
                // the end
            }
            """;

    @Test
    void everyEntityKnowsItsLinesInTheTextTheTypeHolds() {
        JavaClass a = JavaSourceReader.read(SOURCE).getFirst();

        assertEquals(SOURCE, a.source());
        assertEquals(38, a.lineCount());
        assertEquals(new Span(1, 1), a.headerLines());
        assertEquals(new Span(2, 2), a.packageLines());
        assertEquals(List.of(new Span(4, 4), new Span(5, 5)), a.importLines());
        assertEquals("class A 7-38", describe(a));
        assertEquals(List.of("comment 9-9", "field a 10-11", "field b 12-12", "initializer 14-16", "constructor 18-20", "method run 22-26",
                "method gone 28-28", "comment 28-28", "class E 30-36", "comment 37-37"),
                a.members().stream().map(JavaLinesTest::describe).toList());
        JavaClass e = a.nestedClasses().getFirst();
        assertEquals(SOURCE, e.source(), "a nested type holds the same file text");
        assertEquals(List.of("ONE 31-31", "TWO 32-35"), e.enumConstants().stream().map(c -> c.name() + " " + c.startLine() + "-" + c.endLine()).toList());
        // the lines are those of the text
        assertEquals("    /** Runs. */\n    @Override\n    public void run() {\n        a++;\n    }", a.lines(a.methods().getFirst()));
        assertEquals("import java.util.Map;", a.lines(a.importLines().get(1).start(), a.importLines().get(1).end()));
        assertEquals("package p;", a.lines(a.packageLines().start(), a.packageLines().end()));
    }

    @Test
    void windowsLineEndsKeepTheLinesAndTheTextAsGiven() {
        String windows = SOURCE.replace("\n", "\r\n");
        JavaClass a = JavaSourceReader.read(windows).getFirst();

        assertEquals(windows, a.source());
        assertEquals("class A 7-38", describe(a));
        assertEquals("method run 22-26", describe(a.methods().getFirst()));
        assertEquals("    @Override\n    public void run() {", a.lines(23, 24));
    }

    @Test
    void aTypeThatWasBuiltByHandHasNoTextAndNoLines() {
        JavaClass built = new JavaClass(null, "", "p", List.of(), List.of(), Visibility.PUBLIC, java.util.Set.of(), JavaKind.CLASS, "B", "", "",
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertEquals("", built.source());
        assertEquals(0, built.lineCount());
        assertEquals(0, built.startLine());
        assertEquals("", built.lines(built));
    }

    @Test
    void everyFileOfTheProjectAndTheEveryConstructFixtureKeepItsStructureInItsLines() throws Exception {
        List<String> problems = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src"))) {
            walk.filter(p -> p.toString().endsWith(".java") && !p.toString().endsWith("_output.java")).forEach(files::add);
        }
        assertTrue(files.size() > 100, "the project's own sources: " + files.size());
        for (Path file : files) {
            String text = Files.readString(file);
            List<JavaClass> types;
            try {
                types = JavaSourceReader.read(text);
            } catch (IllegalArgumentException e) {
                continue; // the round-trip tests report files the reader does not understand
            }
            for (JavaClass type : types) {
                if (!text.equals(type.source())) problems.add(file + ": the type holds other text");
                check(file.toString(), type, type, problems);
                for (int i = 0; i < type.importLines().size(); i++) {
                    String line = type.lines(type.importLines().get(i).start(), type.importLines().get(i).end());
                    if (!line.startsWith("import ") || !line.endsWith(";")) problems.add(file + ": import " + i + " is '" + line + "'");
                }
                if (!type.packageLines().isNone() && !type.lines(type.packageLines().start(), type.packageLines().end()).startsWith("package ")) {
                    problems.add(file + ": the package lines are wrong");
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    /** The entity and its lines, e.g. {@code method run 22-26}. */
    private static String describe(JavaMember member) {
        String where = member.startLine() + "-" + member.endLine();
        return switch (member) {
            case JavaClass c -> "class " + c.name() + " " + where;
            case JavaField f -> "field " + f.name() + " " + where;
            case JavaMethod m -> "method " + m.name() + " " + where;
            case JavaConstructor c -> "constructor " + where;
            case JavaInitializer i -> "initializer " + where;
            case JavaComment c -> "comment " + where;
        };
    }

    /** The lines of every member lie in the file and in the lines of its type, in order, and hold what they should. */
    private static void check(String file, JavaClass root, JavaClass type, List<String> problems) {
        int last = root.lineCount();
        if (type.startLine() < 1 || type.endLine() < type.startLine() || type.endLine() > last) {
            problems.add(file + ": " + type.name() + " has the lines " + type.startLine() + "-" + type.endLine() + " of " + last);
            return;
        }
        String own = root.lines(type);
        if (!own.stripTrailing().endsWith("}")) problems.add(file + ": " + type.name() + " does not end with a brace: " + tail(own));
        if (!own.contains(type.name())) problems.add(file + ": " + type.name() + " is not in its lines");
        int before = type.startLine();
        for (JavaMember m : type.members()) {
            String where = file + ": " + type.name() + "." + describe(m);
            if (m.startLine() < type.startLine() || m.endLine() > type.endLine() || m.endLine() < m.startLine()) {
                problems.add(where + " is outside " + type.startLine() + "-" + type.endLine());
                continue;
            }
            if (m.startLine() < before) problems.add(where + " starts before the member before it ended");
            before = m.endLine();
            // a comment after the last token, on the same line, belongs to the line (it is the next member, a trailing comment)
            String raw = root.lines(m).stripTrailing();
            String text = raw.replaceAll("\\s*(//[^\\n]*|/\\*[^\\n]*\\*/)\\s*$", "").stripTrailing();
            switch (m) {
                case JavaClass nested -> check(file, root, nested, problems);
                case JavaMethod method -> {
                    if (!text.contains(method.name() + "(")) problems.add(where + " has not its name in its lines");
                    if (!ends(text, raw, "}", ";")) problems.add(where + " does not end with } or ;: " + tail(text));
                }
                case JavaConstructor c -> {
                    if (!ends(text, raw, "}")) problems.add(where + " does not end with }: " + tail(text));
                }
                case JavaInitializer i -> {
                    if (!ends(text, raw, "}")) problems.add(where + " does not end with }: " + tail(text));
                }
                case JavaField f -> {
                    if (!text.contains(f.name())) problems.add(where + " has not its name in its lines");
                    if (!ends(text, raw, ";", ",")) problems.add(where + " does not end with ; or ,: " + tail(text));
                }
                case JavaComment c -> {
                    String first = c.text().lines().findFirst().orElse("").strip();
                    if (!root.lines(m).contains(first)) problems.add(where + " has not its text in its lines");
                }
            }
        }
        for (JavaEnumConstant c : type.enumConstants()) {
            String text = root.lines(c.startLine(), c.endLine());
            if (!text.contains(c.name()) || c.startLine() < type.startLine() || c.endLine() > type.endLine()) {
                problems.add(file + ": " + type.name() + "." + c.name() + " has the lines " + c.startLine() + "-" + c.endLine());
            }
        }
        assertFalse(type.source().isEmpty());
    }

    /** Whether the member's lines end with one of the tokens, with or without a comment after it (a {@code //} can be inside a string). */
    private static boolean ends(String withoutComment, String raw, String... tokens) {
        for (String token : tokens) if (withoutComment.endsWith(token) || raw.endsWith(token)) return true;
        return false;
    }

    private static String tail(String text) {
        return text.substring(Math.max(0, text.length() - 40)).replace("\n", "\\n");
    }
}
