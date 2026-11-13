package net.rka.ai.tools.code.java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads Java files into the {@link JavaClass} model and writes them again as {@code Name_output.java}: a way to see
 * what the model keeps of a file and to check the reader and the writer on real code.
 * <pre>
 *   ./gradlew roundTripJava --args="src/main/java/net/rka/ai/agent"            (a file or a folder)
 *   ./gradlew roundTripJava --args="src/main/java --out build/roundtrip"       (the output files go to another folder)
 * </pre>
 * By default {@code Foo_output.java} is written next to {@code Foo.java}. It declares the same classes as the input, so
 * inside a source set (like {@code src/main/java}) it makes the build fail until it is deleted: use {@code --out} with a
 * folder outside the source sets for that.
 */
public final class JavaRoundTrip {

    /** The suffix of the written files, before {@code .java}. */
    public static final String SUFFIX = "_output";

    private JavaRoundTrip() {
    }

    /**
     * Reads one file and writes {@code Name_output.java} into {@code outputDirectory} (the file's own folder when null).
     *
     * @return the written file
     * @throws IllegalArgumentException when the reader doesn't understand the file (the message names the line)
     */
    public static Path rewrite(Path input, Path outputDirectory) throws IOException {
        String source = Files.readString(input, StandardCharsets.UTF_8);
        String written = JavaSourceWriter.write(JavaSourceReader.read(source));
        String name = input.getFileName().toString();
        Path directory = outputDirectory == null ? input.toAbsolutePath().getParent() : outputDirectory;
        Files.createDirectories(directory);
        Path output = directory.resolve(name.substring(0, name.length() - ".java".length()) + SUFFIX + ".java");
        Files.writeString(output, written, StandardCharsets.UTF_8);
        return output;
    }

    /** The {@code .java} files of a folder (all levels) or the file itself, without earlier {@code *_output.java} files. */
    public static List<Path> javaFiles(Path pathOrFolder) throws IOException {
        if (Files.isRegularFile(pathOrFolder)) return List.of(pathOrFolder);
        try (Stream<Path> files = Files.walk(pathOrFolder)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java") && !f.getFileName().toString().endsWith(SUFFIX + ".java"))
                    .sorted().toList();
        }
    }

    /** {@code path [--out folder]}: prints each file written and each file that could not be read; exit code 1 if any. */
    public static void main(String[] args) throws IOException {
        Path path = null;
        Path out = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--out") && i + 1 < args.length) out = Path.of(args[++i]);
            else path = Path.of(args[i]);
        }
        if (path == null) {
            System.err.println("Usage: JavaRoundTrip <file or folder> [--out <folder>]");
            System.exit(2);
        }
        List<String> failures = new ArrayList<>();
        int written = 0;
        for (Path file : javaFiles(path)) {
            try {
                Path target = rewrite(file, out == null ? null : out.resolve(relativeParent(path, file)));
                System.out.println(file + " -> " + target.getFileName());
                written++;
            } catch (IllegalArgumentException | IOException e) {
                failures.add(file + ": " + e.getMessage());
            }
        }
        System.out.println(written + " file(s) written" + (failures.isEmpty() ? "." : ", " + failures.size() + " could not be read:"));
        failures.forEach(f -> System.out.println("  " + f));
        String normalized = path.toString().replace('\\', '/');
        if (out == null && (normalized.contains("src/main/java") || normalized.contains("src/test/java"))) {
            System.out.println("Note: the *_output.java files declare the same classes as the inputs; delete them (or use --out with a"
                    + " folder outside src) before the next build.");
        }
        if (!failures.isEmpty()) System.exit(1);
    }

    /** The folder of {@code file} relative to the folder that was given, so {@code --out} keeps the layout. */
    private static Path relativeParent(Path root, Path file) {
        Path base = Files.isRegularFile(root) ? root.toAbsolutePath().getParent() : root.toAbsolutePath();
        Path parent = file.toAbsolutePath().getParent();
        return base.relativize(parent);
    }
}
