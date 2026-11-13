package net.rka.ai.tools.code.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.rka.ai.tools.code.java.JavaTrees.canonical;
import static net.rka.ai.tools.code.java.JavaTrees.compileErrors;
import static net.rka.ai.tools.code.java.JavaTrees.firstDifference;
import static net.rka.ai.tools.code.java.JavaTrees.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The util on one class that uses every construct Java has: {@code src/test/resources/.../EveryConstruct.java}. The
 * output is written into the same folder ({@code EveryConstruct_output.java}; it is generated again by every run and
 * ignored by git) so it can be compared with the original by eye or with a diff tool.
 * <p>
 * The file is not in a source set, so the two classes with the same name don't meet in the build; the test compiles
 * the original and a copy of the output under the name {@code EveryConstruct.java} instead.
 */
class JavaEveryConstructTest {

    private static final Path INPUT = Path.of("src/test/resources/net/rka/ai/tools/code/java/EveryConstruct.java");

    @Test
    void theFixtureIsValidJavaAndUsesWhatItClaims(@TempDir Path dir) throws Exception {
        assertEquals(List.of(), compileErrors(INPUT, dir.resolve("classes")));

        List<JavaClass> types = JavaSourceReader.read(Files.readString(INPUT));
        List<JavaClass> all = types.stream().flatMap(JavaEveryConstructTest::withNested).toList();

        // every kind of type, visibility and modifier
        assertEquals(EnumSet.allOf(JavaKind.class), all.stream().map(JavaClass::kind).collect(Collectors.toCollection(() -> EnumSet.noneOf(JavaKind.class))));
        assertEquals(EnumSet.allOf(Visibility.class), visibilities(all));
        assertEquals(EnumSet.allOf(Modifier.class), modifiers(all));
        // the file level
        JavaClass main = types.stream().filter(t -> t.name().equals("EveryConstruct")).findFirst().orElseThrow();
        assertTrue(main.imports().contains("module java.base") && main.imports().contains("static java.lang.Math.*"), main.imports().toString());
        assertTrue(types.size() >= 10, "top-level types: " + types.size());
        // the class with everything
        assertEquals(List.of("EveryConstruct.Leaf", "EveryConstruct.Branch"), main.permits());
        assertEquals(2, main.annotations().stream().filter(a -> a.startsWith("@Tag")).count());
        assertTrue(main.initializers().stream().anyMatch(JavaInitializer::isStatic) && main.initializers().stream().anyMatch(i -> !i.isStatic()));
        assertTrue(main.constructors().size() >= 3 && main.methods().size() >= 15 && main.fields().size() >= 20);
        assertTrue(main.nestedClasses().stream().anyMatch(n -> n.kind() == JavaKind.ANNOTATION));
        assertTrue(all.stream().anyMatch(t -> t.constructors().stream().anyMatch(JavaConstructor::compact)));
        assertTrue(all.stream().anyMatch(t -> t.enumConstants().stream().anyMatch(JavaEnumConstant::hasBody)));
        assertTrue(all.stream().flatMap(t -> t.methods().stream()).anyMatch(m -> !m.defaultValue().isEmpty()));
        assertTrue(all.stream().anyMatch(t -> !t.recordComponents().isEmpty()));
    }

    @Test
    void theOutputBesideTheFixtureMeansTheSameAndCompiles(@TempDir Path dir) throws Exception {
        Path output = JavaRoundTrip.rewrite(INPUT, null);

        assertEquals(INPUT.toAbsolutePath().resolveSibling("EveryConstruct_output.java"), output.toAbsolutePath());
        assertTrue(Files.size(output) > 10_000, "the output is a real file: " + Files.size(output));
        // the same meaning: the compiler's syntax trees (members as a set, no comments, strings by value)
        assertEquals(null, firstDifference(canonical(parse(INPUT)), canonical(parse(output))));
        // no comment is lost or moved: the same comments in the same order
        assertEquals(null, JavaTrees.firstCommentDifference(Files.readString(INPUT), Files.readString(output)));
        // and a real program: the output, under the name its public class needs, compiles
        Path copy = Files.createDirectories(dir.resolve("copy")).resolve("EveryConstruct.java");
        Files.copy(output, copy);
        assertEquals(List.of(), compileErrors(copy, dir.resolve("classes")));
        // writing is stable: reading the output and writing it again gives the same text
        String text = Files.readString(output);
        assertEquals(text, JavaSourceWriter.write(JavaSourceReader.read(text)));
    }

    private static Stream<JavaClass> withNested(JavaClass type) {
        return Stream.concat(Stream.of(type), type.nestedClasses().stream().flatMap(JavaEveryConstructTest::withNested));
    }

    private static Set<Visibility> visibilities(List<JavaClass> types) {
        Set<Visibility> found = EnumSet.noneOf(Visibility.class);
        for (JavaClass t : types) {
            found.add(t.visibility());
            t.fields().forEach(f -> found.add(f.visibility()));
            t.constructors().forEach(c -> found.add(c.visibility()));
            t.methods().forEach(m -> found.add(m.visibility()));
        }
        return found;
    }

    private static Set<Modifier> modifiers(List<JavaClass> types) {
        Set<Modifier> found = EnumSet.noneOf(Modifier.class);
        for (JavaClass t : types) {
            found.addAll(t.modifiers());
            t.fields().forEach(f -> found.addAll(f.modifiers()));
            t.methods().forEach(m -> found.addAll(m.modifiers()));
            t.initializers().stream().filter(JavaInitializer::isStatic).findAny().ifPresent(i -> found.add(Modifier.STATIC));
        }
        return found;
    }
}
