package net.rka.ai.graph.processor;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Compiles Java sources in memory with {@link GraphProcessor}, for tests. */
final class Compilation {

    final boolean success;
    final List<Diagnostic<? extends JavaFileObject>> diagnostics;
    final Path classes;
    final Path generated;

    private Compilation(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics,
                        Path classes, Path generated) {
        this.success = success;
        this.diagnostics = diagnostics;
        this.classes = classes;
        this.generated = generated;
    }

    /** @param sources fully qualified class name -> source code */
    static Compilation compile(Map<String, String> sources) {
        try {
            Path classes = Files.createTempDirectory("graph-test-classes");
            Path generated = Files.createTempDirectory("graph-test-generated");
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            List<JavaFileObject> files = new ArrayList<>();
            sources.forEach((name, code) -> files.add(new Source(name, code)));
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
                JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics,
                        List.of("-classpath", System.getProperty("java.class.path"),
                                "-d", classes.toString(), "-s", generated.toString(), "-proc:full"),
                        null, files);
                task.setProcessors(List.of(new GraphProcessor()));
                boolean success = task.call();
                return new Compilation(success, diagnostics.getDiagnostics(), classes, generated);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<String> errors() {
        return messages(Diagnostic.Kind.ERROR);
    }

    List<String> warnings() {
        return messages(Diagnostic.Kind.WARNING);
    }

    private List<String> messages(Diagnostic.Kind kind) {
        return diagnostics.stream().filter(d -> d.getKind() == kind)
                .map(d -> d.getMessage(null)).collect(Collectors.toList());
    }

    String generatedSource(String qualifiedName) throws IOException {
        return Files.readString(generated.resolve(qualifiedName.replace('.', '/') + ".java"));
    }

    ClassLoader classLoader() throws IOException {
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }

    private static final class Source extends SimpleJavaFileObject {
        private final String code;

        Source(String name, String code) {
            super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
