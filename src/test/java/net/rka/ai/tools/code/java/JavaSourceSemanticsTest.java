package net.rka.ai.tools.code.java;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static net.rka.ai.tools.code.java.JavaTrees.canonical;
import static net.rka.ai.tools.code.java.JavaTrees.firstDifference;
import static net.rka.ai.tools.code.java.JavaTrees.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads every Java file of the main source folder with the util, writes it into an output folder
 * ({@code build/roundtrip-test}, kept after the test so the files can be looked at) and checks that each output means
 * the same as its input.
 * <p>
 * "The same" is decided on the syntax trees that the JDK's own compiler makes of both files ({@link JavaTrees}), not on
 * the text: no whitespace, no comments, a string compared by its value, the members of a type compared as a set. Everything
 * else has to be equal, and so do the comments, in order: package, imports in order, every type with its modifiers, annotations, type parameters, supertypes
 * and permitted types, and every member with its signature and complete body.
 */
class JavaSourceSemanticsTest {

    private static final Path INPUT = Path.of("src/main/java");
    private static final Path OUTPUT = Path.of("build/roundtrip-test");

    @Test
    void everyMainSourceFileWrittenByTheUtilMeansTheSameAsTheOriginal() throws Exception {
        deleteTree(OUTPUT);
        Files.createDirectories(OUTPUT);
        List<Path> inputs = JavaRoundTrip.javaFiles(INPUT);
        List<String> problems = new ArrayList<>();

        for (Path input : inputs) {
            Path folder = OUTPUT.resolve(INPUT.relativize(input).getParent());
            try {
                Path output = JavaRoundTrip.rewrite(input, folder);
                String difference = firstDifference(canonical(parse(input)), canonical(parse(output)));
                if (difference != null) problems.add(INPUT.relativize(input) + ": " + difference);
                String lost = JavaTrees.firstCommentDifference(Files.readString(input), Files.readString(output));
                if (lost != null) problems.add(INPUT.relativize(input) + ": " + lost);
            } catch (IOException | RuntimeException e) {
                problems.add(INPUT.relativize(input) + ": " + e.getMessage());
            }
        }

        assertTrue(inputs.size() > 100, "the main sources were found: " + inputs.size());
        assertEquals(List.of(), problems.stream().limit(10).toList(), problems.size() + " of " + inputs.size() + " files differ");
        // every input has its output, and nothing else was written
        try (Stream<Path> written = Files.walk(OUTPUT)) {
            assertEquals(inputs.size(), written.filter(p -> p.getFileName().toString().endsWith(JavaRoundTrip.SUFFIX + ".java")).count());
        }
    }

    @Test
    void theComparisonSeesRealDifferencesAndIgnoresLayoutCommentsAndMemberOrder() throws Exception {
        String original = "package p;\nimport java.util.List;\n/** doc */\nclass A {\n    int x = 1; // note\n    void f() {\n        g(\"a\");\n    }\n}\n";
        String reordered = "package p;\nimport java.util.List;\nclass A {\n    void f() { g(\"a\"); }\n\n    /* other */ int x=1;\n}\n";
        String other = "package p;\nimport java.util.List;\nclass A {\n    int x = 1;\n    void f() {\n        g(\"b\");\n    }\n}\n";

        assertEquals(null, firstDifference(canonical(parse("A.java", original)), canonical(parse("A.java", reordered))));
        assertNotNull(firstDifference(canonical(parse("A.java", original)), canonical(parse("A.java", other))));
    }

    private static void deleteTree(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path p : paths.sorted(Collections.reverseOrder()).toList()) Files.delete(p);
        }
    }
}
