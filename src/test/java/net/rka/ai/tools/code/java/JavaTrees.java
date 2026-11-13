package net.rka.ai.tools.code.java;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The JDK compiler's view of Java source, for tests that compare files by meaning: the syntax trees have no whitespace
 * and no comments, a string (also a text block) is compared by its value, and {@link #canonical} lists the members of a
 * type in a fixed order, because the writer puts them in its own order (fields, initializers, constructors, methods,
 * nested types).
 */
final class JavaTrees {

    private JavaTrees() {
    }

    static CompilationUnitTree parse(Path file) throws IOException {
        return parse(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
    }

    /** The syntax tree of the source; a syntax error is an exception. (Only parsed: names are not looked up.) */
    static CompilationUnitTree parse(String name, String source) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavaFileObject object = new SimpleJavaFileObject(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(object));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    throw new IllegalStateException(name + " is not valid Java at line " + d.getLineNumber() + ": " + d.getMessage(null));
                }
            }
            return units.getFirst();
        }
    }

    /**
     * Compiles the file completely (names and types are checked) into a throw-away folder.
     *
     * @return the compiler's errors, empty when it compiled
     */
    static List<String> compileErrors(Path file, Path classesFolder) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Files.createDirectories(classesFolder);
            compiler.getTask(null, files, diagnostics, List.of("-proc:none", "-d", classesFolder.toString()), null,
                    files.getJavaFileObjects(file)).call();
            return diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> "line " + d.getLineNumber() + ": " + d.getMessage(null)).toList();
        }
    }

    /** The unit as text that is equal exactly when the meaning is: see the class comment. One member per paragraph. */
    static String canonical(CompilationUnitTree unit) {
        StringBuilder out = new StringBuilder("package ").append(unit.getPackageName()).append(";\n");
        for (ImportTree i : unit.getImports()) out.append(i.toString().strip()).append('\n');
        for (Tree type : unit.getTypeDecls()) out.append(canonical((ClassTree) type)).append('\n');
        return out.toString();
    }

    private static String canonical(ClassTree type) {
        List<String> members = new ArrayList<>();
        for (Tree member : type.getMembers()) members.add(member instanceof ClassTree nested ? canonical(nested) : member.toString().strip());
        members.sort(Comparator.naturalOrder());
        return type.getModifiers() + " " + type.getKind() + " " + type.getSimpleName() + " " + type.getTypeParameters()
                + " extends " + type.getExtendsClause() + " implements " + type.getImplementsClause()
                + " permits " + type.getPermitsClause() + " {\n" + String.join("\n\n", members) + "\n}";
    }

    /** Null when both have the same comments in the same order (whitespace aside), else the first comment that differs. */
    static String firstCommentDifference(String expected, String actual) {
        List<String> a = JavaSourceReader.comments(expected);
        List<String> b = JavaSourceReader.comments(actual);
        int i = 0;
        while (i < a.size() && i < b.size() && a.get(i).equals(b.get(i))) i++;
        if (i == a.size() && i == b.size()) return null;
        return "comment " + (i + 1) + " of " + a.size() + ": expected [" + (i < a.size() ? a.get(i) : "(none)") + "] but was ["
                + (i < b.size() ? b.get(i) : "(none)") + "]";
    }

    /** Null when equal, else the first line where the two differ. */
    static String firstDifference(String expected, String actual) {
        if (expected.equals(actual)) return null;
        List<String> a = expected.lines().toList();
        List<String> b = actual.lines().toList();
        int i = 0;
        while (i < a.size() && i < b.size() && a.get(i).equals(b.get(i))) i++;
        return "first difference at line " + (i + 1) + " of the tree: expected [" + (i < a.size() ? a.get(i) : "(end)")
                + "] but was [" + (i < b.size() ? b.get(i) : "(end)") + "]";
    }
}
