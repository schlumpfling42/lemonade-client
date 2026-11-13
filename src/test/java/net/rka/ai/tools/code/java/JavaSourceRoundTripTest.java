package net.rka.ai.tools.code.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading Java source into the model and writing it back. */
class JavaSourceRoundTripTest {

    private static final String SAMPLE = """
            /* a license header, kept with the first type */
            package net.rka.demo;

            import java.util.List;
            import static java.lang.Math.max;

            /**
             * A thing.
             */
            @SuppressWarnings("unchecked")
            public abstract class Thing<T extends Comparable<T>> extends Base implements Runnable, Comparable<Thing<T>> {

                /** Count. */
                private static final int LIMIT = max(1, 2), OTHER = 3;
                protected final List<String> names = new java.util.ArrayList<String>(), more[] = null;
                private String text = "a { } ; // not a comment"; // trailing note

                static {
                    TABLE.load(); // runs once
                }

                {
                    count++;
                }

                /** Makes one. */
                public Thing(final String text, int... values) throws java.io.IOException {
                    this.text = text;
                }

                @Override
                public synchronized <R> List<R> map(java.util.function.Function<? super T, ? extends R> f,
                                                   Map<String, List<Integer>> index) throws Exception {
                    String block = \"\"\"
                            a { b }
                            \"\"\";
                    return null;
                }

                abstract void stop();

                // nested
                public static class Inner {
                    int x;
                }

                enum Mode implements Runnable {
                    /** First. */
                    A(1, "one"), B {
                        void run() {
                        }
                    },
                    C;

                    private final int n;

                    Mode() {
                        this.n = 0;
                    }

                    public void run() {
                    }
                }

                record Point(int x, int y) implements java.io.Serializable {
                    Point {
                        if (x < 0) throw new IllegalArgumentException();
                    }

                    static Point origin() {
                        return new Point(0, 0);
                    }
                }

                interface Shape extends Comparable<Shape> {
                    double area();

                    default String label() {
                        return "shape";
                    }

                    static Shape unit() {
                        return null;
                    }
                }

                sealed interface Result permits Ok, Err {
                }

                // the last words
            }
            """;

    @Test
    void aFileIsReadIntoTheModel() {
        List<JavaClass> types = JavaSourceReader.read(SAMPLE);

        assertEquals(1, types.size());
        JavaClass thing = types.getFirst();
        assertEquals("net.rka.demo.Thing", thing.qualifiedName());
        assertEquals(List.of("java.util.List", "static java.lang.Math.max"), thing.imports());
        assertEquals("/**\n * A thing.\n */", thing.comment());
        assertEquals(List.of("@SuppressWarnings(\"unchecked\")"), thing.annotations());
        assertEquals(Set.of(Modifier.ABSTRACT), thing.modifiers());
        assertEquals("<T extends Comparable<T>>", thing.typeParameters());
        assertEquals("Base", thing.extendsClass());
        assertEquals(List.of("Runnable", "Comparable<Thing<T>>"), thing.implementsInterfaces());

        // fields, also several declarators of one declaration, array declarators and strings with braces
        List<JavaField> fields = thing.fields();
        assertEquals(List.of("LIMIT", "OTHER", "names", "more", "text"), fields.stream().map(JavaField::name).toList());
        assertEquals("/** Count. */", fields.get(0).comment());
        assertEquals("", fields.get(1).comment());
        assertEquals("max(1, 2)", fields.get(0).value());
        assertEquals("List<String>[]", fields.get(3).type());
        assertEquals("new java.util.ArrayList<String>()", fields.get(2).value());
        assertEquals("\"a { } ; // not a comment\"", fields.get(4).value());

        assertEquals(List.of(true, false), thing.initializers().stream().map(JavaInitializer::isStatic).toList());
        assertEquals("TABLE.load(); // runs once", thing.initializers().getFirst().code());

        JavaConstructor constructor = thing.constructors().getFirst();
        assertEquals("/** Makes one. */", constructor.comment());
        assertEquals(List.of(new JavaParameter("final String", "text"), new JavaParameter("int...", "values")), constructor.parameters());
        assertEquals(List.of("java.io.IOException"), constructor.throwsTypes());

        JavaMethod map = thing.methods().getFirst();
        assertEquals("<R>", map.typeParameters());
        assertEquals("List<R>", map.returnType());
        assertEquals(2, map.parameters().size());
        assertEquals("Map<String, List<Integer>>", map.parameters().get(1).type());
        assertEquals(Set.of(Modifier.SYNCHRONIZED), map.modifiers());
        assertTrue(map.code().contains("a { b }"), map.code());
        assertTrue(thing.methods().get(1).isAbstract());

        assertEquals(List.of("Inner", "Mode", "Point", "Shape", "Result"), thing.nestedClasses().stream().map(JavaClass::name).toList());
        // the members are in the order of the source, with the comments that belong to no declaration
        List<String> order = thing.members().stream().map(m -> switch (m) {
            case JavaField f -> "field " + f.name();
            case JavaInitializer i -> i.isStatic() ? "static init" : "init";
            case JavaConstructor c -> "constructor";
            case JavaMethod m2 -> "method " + m2.name();
            case JavaClass c -> "type " + c.name();
            case JavaComment c -> (c.trailing() ? "trailing " : "comment ") + c.text();
        }).toList();
        assertEquals(List.of("field LIMIT", "field OTHER", "field names", "field more", "field text", "trailing // trailing note",
                "static init", "init", "constructor", "method map", "method stop", "type Inner", "type Mode", "type Point", "type Shape",
                "type Result", "comment // the last words"), order);
        JavaClass mode = thing.nestedClasses().get(1);
        assertEquals(List.of("A", "B", "C"), mode.enumConstants().stream().map(JavaEnumConstant::name).toList());
        assertEquals("1, \"one\"", mode.enumConstants().get(0).arguments());
        assertTrue(mode.enumConstants().get(1).hasBody());
        JavaClass point = thing.nestedClasses().get(2);
        assertEquals(List.of(new JavaParameter("int", "x"), new JavaParameter("int", "y")), point.recordComponents());
        assertTrue(point.constructors().getFirst().compact());
        JavaClass shape = thing.nestedClasses().get(3);
        assertEquals(List.of("Comparable<Shape>"), shape.implementsInterfaces());
        assertTrue(shape.methods().get(1).modifiers().contains(Modifier.DEFAULT));
        assertEquals(List.of("Ok", "Err"), thing.nestedClasses().get(4).permits());
        assertTrue(thing.nestedClasses().get(4).modifiers().contains(Modifier.SEALED));
    }

    @Test
    void whatIsWrittenReadsBackAsTheSameModelAndWritesTheSameText() {
        List<JavaClass> first = JavaSourceReader.read(SAMPLE);
        String text = JavaSourceWriter.write(first);
        List<JavaClass> second = JavaSourceReader.read(text);

        assertEquals(first.stream().map(JavaClass::withoutLines).toList(), second.stream().map(JavaClass::withoutLines).toList());
        assertEquals(text, JavaSourceWriter.write(second));
        assertTrue(text.startsWith("/* a license header, kept with the first type */\npackage net.rka.demo;\n\nimport java.util.List;\nimport static java.lang.Math.max;\n\n/**\n * A thing.\n */\n"), text);
        assertTrue(text.contains("    private static final int LIMIT = max(1, 2);\n    private static final int OTHER = 3;\n"), text);
        assertTrue(text.contains("    private final List<String> names") || text.contains("    protected final List<String> names"), text);
        assertTrue(text.contains("    static {\n        TABLE.load(); // runs once\n    }\n"), text);
        assertTrue(text.contains("    {\n        count++;\n    }\n"), text);
        assertTrue(text.contains("    abstract void stop();\n"), text);
        assertTrue(text.contains("        double area();\n"), text);
        assertTrue(text.contains("        default String label() {\n            return \"shape\";\n        }\n"), text);
        assertTrue(text.contains("        Point {\n            if (x < 0) throw new IllegalArgumentException();\n        }\n"), text);
        assertTrue(text.contains("        /** First. */\n        A(1, \"one\"),\n        B {\n"), text);
        assertTrue(text.contains("sealed interface Result permits Ok, Err {\n"), text);
    }

    @Test
    void textThatIsNotJavaIsRefusedWithTheLine() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> JavaSourceReader.read("class A {\n    int x\n}\n"));
        assertTrue(e.getMessage().startsWith("Line "), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> JavaSourceReader.read("class A { void f() { }\n"));
    }

    @Test
    void annotationTypesKeepTheirElementsAndDefaults() {
        String source = "@Retention(RetentionPolicy.RUNTIME)\npublic @interface Marker {\n    String value() default \"x\";\n    int[] sizes() default {1, 2};\n    Class<?> type();\n}\n";

        List<JavaClass> types = JavaSourceReader.read(source);

        JavaClass marker = types.getFirst();
        assertEquals(JavaKind.ANNOTATION, marker.kind());
        assertEquals(List.of("value", "sizes", "type"), marker.methods().stream().map(JavaMethod::name).toList());
        assertEquals("\"x\"", marker.methods().get(0).defaultValue());
        assertEquals("{1, 2}", marker.methods().get(1).defaultValue());
        assertEquals("", marker.methods().get(2).defaultValue());
        assertEquals("@Retention(RetentionPolicy.RUNTIME)\npublic @interface Marker {\n    String value() default \"x\";\n\n    int[] sizes() default {1, 2};\n\n    Class<?> type();\n}\n",
                JavaSourceWriter.write(types));
    }

    @Test
    void aFileIsWrittenAsNameOutputNextToItOrIntoAnotherFolder(@TempDir Path dir) throws Exception {
        Path input = dir.resolve("Foo.java");
        Files.writeString(input, "package p;\n\npublic class Foo {\n    int x;\n}\n");

        Path beside = JavaRoundTrip.rewrite(input, null);
        Path elsewhere = JavaRoundTrip.rewrite(input, dir.resolve("out"));

        assertEquals(dir.resolve("Foo_output.java"), beside);
        assertEquals(dir.resolve("out").resolve("Foo_output.java"), elsewhere);
        assertEquals("package p;\n\npublic class Foo {\n    int x;\n}\n", Files.readString(beside));
        // an earlier output is not read again
        assertEquals(List.of(input), JavaRoundTrip.javaFiles(dir));
    }

    /** Where two models first differ, for the message of a failed round trip. */
    private static String firstDifference(Object first, Object second) {
        String a = String.valueOf(first);
        String b = String.valueOf(second);
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        return "...[" + a.substring(Math.max(0, i - 60), Math.min(a.length(), i + 120)) + "] vs [" + b.substring(Math.max(0, i - 60), Math.min(b.length(), i + 120)) + "]";
    }

    /** The reader and the writer on all of this project's own code: the model written and read again is the same. */
    @Test
    void theProjectsOwnSourcesSurviveTheRoundTrip() throws Exception {
        List<String> problems = new ArrayList<>();
        int files = 0;
        for (String root : List.of("src/main/java", "src/test/java")) {
            Path folder = Path.of(root);
            if (!Files.isDirectory(folder)) continue;
            List<Path> sources;
            try (Stream<Path> walk = Files.walk(folder)) {
                sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            }
            for (Path file : sources) {
                files++;
                try {
                    List<JavaClass> first = JavaSourceReader.read(Files.readString(file)).stream().map(JavaClass::withoutLines).toList();
                    String text = JavaSourceWriter.write(first);
                    List<JavaClass> second = JavaSourceReader.read(text).stream().map(JavaClass::withoutLines).toList();
                    if (!first.equals(second)) problems.add(file + ": the model changed when written and read again: " + firstDifference(first, second));
                    else if (!text.equals(JavaSourceWriter.write(second))) problems.add(file + ": the second write differs");
                } catch (RuntimeException e) {
                    problems.add(file + ": " + e.getMessage());
                }
            }
        }
        assertTrue(files > 50, "the sources were found: " + files);
        assertEquals(List.of(), problems.stream().limit(15).toList(), problems.size() + " of " + files + " files");
    }
}
