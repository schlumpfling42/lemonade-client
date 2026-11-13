package net.rka.ai.tools.code.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Checks a change to a Java file against the file's structure ({@link JavaClass}) before it is accepted: the result must
 * still be Java, and it must hold exactly the declarations it held before, minus those of the part that was replaced,
 * plus those that the new text declares. Text that lands outside its part (a duplicated block, a method written into
 * another one, a neighbour swallowed) shows up as a declaration too many or too few and is refused with what is wrong,
 * so a change never leaves a file that has to be repaired.
 */
public final class JavaStructure {

    private JavaStructure() {
    }

    /** What a change does to the part it names. */
    public enum Effect {
        /** The part is replaced (or deleted, with empty text) by the declarations of the new text. */
        REPLACES,
        /** The new text is declared next to the part or inside the type: nothing is removed. */
        ADDS,
        /** Statements go into the body of the part: the declarations stay as they are. */
        BODY
    }

    /** The effect of an edit mode on a part: start and end of a method put statements into its body, of a type they add members. */
    public static Effect effectOf(net.rka.ai.tools.outline.SectionEdit.Mode mode, boolean container) {
        return switch (mode) {
            case REPLACE -> Effect.REPLACES;
            case START, END -> container ? Effect.ADDS : Effect.BODY;
            default -> Effect.ADDS;
        };
    }

    private record Declaration(String signature, String owner, int start, int end) {
    }

    /**
     * @param before   the file's text before the change
     * @param after    its text with the change
     * @param fromLine the first line of the part the change names, in {@code before}
     * @param toLine   the last line of the part
     * @param newText  the text the change writes
     * @return what is wrong with the result, or null when it is what the change says
     */
    public static String problem(String before, String after, int fromLine, int toLine, String newText, Effect effect) {
        String unreadable = unreadable(after);
        if (unreadable != null) return unreadable;
        List<JavaClass> now = JavaSourceReader.read(after);
        List<Declaration> old;
        try {
            old = declarations(JavaSourceReader.read(before));
        } catch (IllegalArgumentException e) {
            return null; // the file was not readable before: there is no structure to compare with
        }
        List<String> expected = new ArrayList<>();
        for (Declaration d : old) {
            // in the part: lines shared with it, but not a type that holds the whole part
            boolean inPart = d.start() <= toLine && d.end() >= fromLine && !(d.start() < fromLine && d.end() > toLine);
            if (!(effect == Effect.REPLACES && inPart)) expected.add(d.signature());
        }
        if (effect != Effect.BODY) {
            List<String> written = declared(newText);
            if (written == null) return null; // not whole members (a comment, an import): only the readability counts
            expected.addAll(written);
        }
        List<Declaration> nowDeclared = declarations(now);
        // the same declaration twice in one type is not Java: a part written again next to the one that was already there
        Map<String, Integer> seen = new HashMap<>();
        List<String> twice = new ArrayList<>();
        for (Declaration d : nowDeclared) {
            if (d.signature().endsWith("initializer")) continue;
            if (seen.merge(d.owner() + "/" + d.signature(), 1, Integer::sum) == 2) twice.add(d.signature());
        }
        if (!twice.isEmpty()) {
            return "the change would declare " + shorten(twice) + " twice in the same type. Nothing was changed. Replace the one that is"
                    + " there; write only the member(s) this change is about, not the code around them.";
        }
        List<String> actual = nowDeclared.stream().map(Declaration::signature).toList();
        Map<String, Integer> balance = new HashMap<>();
        expected.forEach(s -> balance.merge(s, 1, Integer::sum));
        actual.forEach(s -> balance.merge(s, -1, Integer::sum));
        List<String> missing = balance.entrySet().stream().filter(e -> e.getValue() > 0).map(Map.Entry::getKey).sorted().toList();
        List<String> extra = balance.entrySet().stream().filter(e -> e.getValue() < 0).map(Map.Entry::getKey).sorted().toList();
        if (missing.isEmpty() && extra.isEmpty()) return null;
        return "the change would not leave the file's structure as it should be (" + (extra.isEmpty() ? "" : "too many: " + shorten(extra))
                + (extra.isEmpty() || missing.isEmpty() ? "" : "; ") + (missing.isEmpty() ? "" : "missing: " + shorten(missing))
                + "). Nothing was changed. Write only the member(s) this change is about, complete, not the code around them.";
    }

    /**
     * The text with the lines {@code from} to {@code to} (counted from 1, both included; {@code to == from - 1} inserts before line
     * {@code from}) replaced by {@code replacement} (without a final line break; empty removes the lines). Lines are separated by
     * a line feed.
     */
    public static String splice(String text, int from, int to, String replacement) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (from < 1 || to < from - 1 || to > lines.size()) {
            throw new IllegalArgumentException("Lines " + from + "-" + to + " are not in the file (" + lines.size() + " lines)");
        }
        List<String> out = new ArrayList<>(lines.subList(0, from - 1));
        if (!replacement.isEmpty()) out.addAll(List.of(replacement.split("\n", -1)));
        out.addAll(lines.subList(to, lines.size()));
        return String.join("\n", out);
    }

    /**
     * Whether the text is a list of statements: it parses as the body of a method (the JDK's parser, strict). A call such as
     * {@code System.out.println("x" + y);} is: the lenient reader above takes it for a method declaration, a real method is not
     * (a method cannot be declared inside a method). Local variables and blocks are statements too.
     */
    public static boolean readsAsStatements(String text) {
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null || text == null || text.isBlank()) return false;
        String source = "class X {\nvoid m() {\n" + text + "\n}\n}\n";
        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics = new javax.tools.DiagnosticCollector<>();
        try (javax.tools.StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            javax.tools.JavaFileObject object = new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///X.java"), javax.tools.JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            ((com.sun.source.util.JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(object))).parse();
            return diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR);
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    /** Why the text is not Java the reader understands, or null when it is. */
    public static String unreadable(String text) {
        try {
            JavaSourceReader.read(text);
            return null;
        } catch (IllegalArgumentException e) {
            return "the file would no longer be readable as Java (" + e.getMessage() + "). Nothing was changed. Write whole members"
                    + " (a method with its signature and braces, a field), and nothing outside the part you change.";
        }
    }

    private static String shorten(List<String> signatures) {
        return signatures.stream().limit(3).map(s -> "`" + s + "`").collect(Collectors.joining(", ")) + (signatures.size() > 3 ? ", …" : "");
    }

    /** The declarations of members written on their own, or null when the text is not members. */
    private static List<String> declared(String text) {
        try {
            List<JavaClass> read = JavaSourceReader.read("class X {\n" + text + "\n}\n");
            return declarations(read).stream().skip(1).map(Declaration::signature).toList(); // the first is X itself
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<Declaration> declarations(List<JavaClass> types) {
        List<Declaration> out = new ArrayList<>();
        types.forEach(t -> add(t, "", out));
        return out;
    }

    private static void add(JavaClass type, String owner, List<Declaration> out) {
        out.add(new Declaration("type " + type.name(), owner, type.startLine(), type.endLine()));
        String in = owner + "." + type.name();
        for (JavaEnumConstant c : type.enumConstants()) out.add(new Declaration("constant " + c.name(), in, c.startLine(), c.endLine()));
        for (JavaMember m : type.members()) {
            if (m instanceof JavaClass nested) add(nested, in, out);
            else if (!(m instanceof JavaComment)) out.add(new Declaration(signature(m), in, m.startLine(), m.endLine()));
        }
    }

    /**
     * How a member is told apart from the others of its type: {@code field name}, {@code method name(int,String)} (the parameter
     * types as written), {@code constructor(int)}, {@code initializer} / {@code static initializer}, {@code type Name}.
     */
    public static String signature(JavaMember member) {
        return switch (member) {
            case JavaClass c -> "type " + c.name();
            case JavaField f -> "field " + f.name();
            case JavaMethod m -> "method " + m.name() + "(" + m.parameters().stream().map(JavaParameter::type).collect(Collectors.joining(",")) + ")";
            case JavaConstructor c -> "constructor(" + c.parameters().stream().map(JavaParameter::type).collect(Collectors.joining(",")) + ")";
            case JavaInitializer i -> i.isStatic() ? "static initializer" : "initializer";
            case JavaComment c -> "comment";
        };
    }
}
