package net.rka.ai.tools.code.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Writes {@link JavaClass} models as Java source, the inverse of {@link JavaSourceReader}: the header comment, package and
 * imports of the first type, then each type with its comment, annotations, header, enum constants and members in the order
 * of the source, indented by four spaces and separated by blank lines (a field
 * without a comment follows the field before it directly; a signature longer than 120 characters has one parameter per line). Bodies are written back as the source text the reader kept.
 * <p>
 * Reading what this writes gives the same model again; the text itself can differ from the original in whitespace
 * (blank lines, line breaks inside a signature) and in what the reader doesn't keep (see {@link JavaSourceReader}).
 */
public final class JavaSourceWriter {

    private static final String INDENT = "    ";

    /** Java's usual order of modifiers after the visibility. */
    private static final List<Modifier> ORDER = List.of(Modifier.ABSTRACT, Modifier.STATIC, Modifier.DEFAULT, Modifier.SEALED,
            Modifier.NON_SEALED, Modifier.FINAL, Modifier.TRANSIENT, Modifier.VOLATILE, Modifier.SYNCHRONIZED, Modifier.NATIVE,
            Modifier.STRICTFP);

    private JavaSourceWriter() {
    }

    /** The source of the types (one file): the first one's package and imports, then the types. */
    public static String write(List<JavaClass> types) {
        StringBuilder out = new StringBuilder();
        if (!types.isEmpty()) {
            JavaClass first = types.getFirst();
            if (!first.headerComment().isEmpty()) out.append(first.headerComment()).append('\n');
            if (!first.packageName().isEmpty()) out.append("package ").append(first.packageName()).append(";\n\n");
            if (!first.imports().isEmpty()) {
                first.imports().forEach(i -> out.append("import ").append(i).append(";\n"));
                out.append('\n');
            }
        }
        for (int i = 0; i < types.size(); i++) {
            if (i > 0) out.append('\n');
            type(out, types.get(i), "");
        }
        return out.toString();
    }

    /** One block of a type's body and whether it is a field without a comment (those are not separated by a blank line). */
    private record Chunk(String text, boolean field, boolean commented, boolean trailing) {
    }

    private static void type(StringBuilder out, JavaClass c, String indent) {
        comment(out, c.comment(), indent);
        annotations(out, c.annotations(), indent);
        StringBuilder header = new StringBuilder(indent);
        words(header, c.visibility().keyword(), modifiers(c.modifiers()), c.kind().keyword());
        header.append(c.name()).append(c.typeParameters());
        if (c.kind() == JavaKind.RECORD) {
            header.append('(').append(parameterList(c.recordComponents(), header.length(), indent)).append(')');
        }
        if (!c.extendsClass().isEmpty()) header.append(" extends ").append(c.extendsClass());
        if (!c.implementsInterfaces().isEmpty()) {
            header.append(c.kind() == JavaKind.INTERFACE ? " extends " : " implements ").append(String.join(", ", c.implementsInterfaces()));
        }
        if (!c.permits().isEmpty()) header.append(" permits ").append(String.join(", ", c.permits()));
        out.append(header).append(" {\n");

        String inner = indent + INDENT;
        List<Chunk> chunks = new ArrayList<>();
        boolean members = c.members().stream().anyMatch(m -> !(m instanceof JavaComment));
        if (c.kind() == JavaKind.ENUM && (!c.enumConstants().isEmpty() || members)) {
            chunks.add(new Chunk(enumConstants(c.enumConstants(), inner, members), false, false, false));
        }
        for (JavaMember member : c.members()) {
            switch (member) {
                case JavaField f -> chunks.add(new Chunk(field(f, inner), true, !f.comment().isEmpty(), false));
                case JavaInitializer i -> chunks.add(new Chunk(commentText(i.comment(), inner)
                        + block(inner + (i.isStatic() ? "static" : ""), i.code(), true, inner), false, false, false));
                case JavaConstructor k -> chunks.add(new Chunk(constructor(c, k, inner), false, false, false));
                case JavaMethod m -> chunks.add(new Chunk(method(c, m, inner), false, false, false));
                case JavaClass n -> {
                    StringBuilder nested = new StringBuilder();
                    type(nested, n, inner);
                    chunks.add(new Chunk(nested.toString().stripTrailing(), false, false, false));
                }
                case JavaComment k -> chunks.add(new Chunk(k.trailing() ? k.text() : indentLines(k.text(), inner), false, false, k.trailing()));
            }
        }
        Chunk previous = null;
        for (Chunk chunk : chunks) {
            if (chunk.trailing()) { // on the line of what came before it
                if (previous == null) out.insert(out.length() - 1, " " + chunk.text());
                else out.append(' ').append(chunk.text());
                continue;
            }
            if (previous != null) out.append(chunk.field() && !chunk.commented() && previous.field() ? "\n" : "\n\n");
            out.append(chunk.text());
            previous = chunk;
        }
        if (previous != null) out.append('\n');
        out.append(indent).append("}\n");
    }

    private static String enumConstants(List<JavaEnumConstant> constants, String indent, boolean members) {
        List<String> parts = new ArrayList<>();
        for (JavaEnumConstant k : constants) {
            StringBuilder s = new StringBuilder(commentText(k.comment(), indent));
            k.annotations().forEach(a -> s.append(indentLines(a, indent)).append('\n'));
            s.append(indent).append(k.name());
            if (!k.arguments().isEmpty()) s.append('(').append(continuation(k.arguments(), indent)).append(')');
            if (k.hasBody()) s.append(' ').append(block("", k.body(), true, indent).stripLeading());
            parts.add(s.toString());
        }
        String joined = String.join(",\n", parts);
        return members ? (joined.isEmpty() ? indent : joined) + ";" : joined;
    }

    private static String field(JavaField f, String indent) {
        StringBuilder s = new StringBuilder(commentText(f.comment(), indent));
        f.annotations().forEach(a -> s.append(indentLines(a, indent)).append('\n'));
        s.append(indent);
        words(s, f.visibility().keyword(), modifiers(f.modifiers()), f.type());
        s.append(f.name());
        if (!f.value().isEmpty()) s.append(" = ").append(continuation(f.value(), indent));
        return s.append(';').toString();
    }

    private static String constructor(JavaClass owner, JavaConstructor k, String indent) {
        StringBuilder s = new StringBuilder(commentText(k.comment(), indent));
        k.annotations().forEach(a -> s.append(indentLines(a, indent)).append('\n'));
        StringBuilder header = new StringBuilder(indent);
        words(header, k.visibility().keyword(), k.typeParameters());
        header.append(owner.name());
        if (!k.compact()) header.append('(').append(parameterList(k.parameters(), header.length(), indent)).append(')');
        if (!k.throwsTypes().isEmpty()) header.append(" throws ").append(String.join(", ", k.throwsTypes()));
        return s.append(block(header.toString(), k.code(), true, indent)).toString();
    }

    private static String method(JavaClass owner, JavaMethod m, String indent) {
        StringBuilder s = new StringBuilder(commentText(m.comment(), indent));
        m.annotations().forEach(a -> s.append(indentLines(a, indent)).append('\n'));
        StringBuilder header = new StringBuilder(indent);
        words(header, m.visibility().keyword(), modifiers(m.modifiers()), m.typeParameters(), m.returnType());
        header.append(m.name()).append('(').append(parameterList(m.parameters(), header.length(), indent)).append(')');
        if (!m.throwsTypes().isEmpty()) header.append(" throws ").append(String.join(", ", m.throwsTypes()));
        boolean abstractMember = m.modifiers().contains(Modifier.ABSTRACT) || m.modifiers().contains(Modifier.NATIVE);
        boolean bodyless = abstractMember || owner.kind() == JavaKind.ANNOTATION || (owner.kind() == JavaKind.INTERFACE && !m.modifiers().contains(Modifier.DEFAULT)
                && !m.modifiers().contains(Modifier.STATIC) && m.visibility() != Visibility.PRIVATE);
        if (!m.defaultValue().isEmpty()) header.append(" default ").append(continuation(m.defaultValue(), indent));
        return s.append(block(header.toString(), m.code(), !bodyless, indent)).toString();
    }

    /** {@code header {}, its code indented one level, and the closing brace}; or {@code header;} when there is no body. */
    private static String block(String header, String code, boolean body, String indent) {
        if (!body) return header + ";";
        String open = header.isBlank() ? header + "{" : header + " {"; // an instance initializer is just the braces
        if (code.isEmpty()) return open + "\n" + indent + "}";
        return open + "\n" + indentLines(code, indent + INDENT) + "\n" + indent + "}";
    }

    private static final int WRAP_AT = 120;

    /** The parameters on the line, or one per line (indented two levels) when the signature would be longer than 120 characters. */
    private static String parameterList(List<JavaParameter> parameters, int headerLength, String indent) {
        String line = parameters(parameters);
        if (headerLength + line.length() + 20 <= WRAP_AT || parameters.size() < 2) return line;
        String next = "\n" + indent + INDENT + INDENT;
        return parameters.stream().map(p -> p.type() + " " + p.name()).collect(Collectors.joining("," + next, next, ""));
    }

    private static String parameters(List<JavaParameter> parameters) {
        return parameters.stream().map(p -> p.type() + " " + p.name()).collect(Collectors.joining(", "));
    }

    private static String modifiers(Set<Modifier> modifiers) {
        return ORDER.stream().filter(modifiers::contains).map(Modifier::keyword).collect(Collectors.joining(" "));
    }

    /** Appends the non-empty words, each followed by a space. */
    private static void words(StringBuilder out, String... words) {
        for (String w : words) if (!w.isEmpty()) out.append(w).append(' ');
    }

    private static void comment(StringBuilder out, String comment, String indent) {
        out.append(commentText(comment, indent));
    }

    private static void annotations(StringBuilder out, List<String> annotations, String indent) {
        annotations.forEach(a -> out.append(indentLines(a, indent)).append('\n'));
    }

    /** The comment, its lines at the indentation and a line break after it; "" for no comment. */
    private static String commentText(String comment, String indent) {
        return comment.isEmpty() ? "" : indentLines(comment, indent) + "\n";
    }

    /** Every non-blank line with the indentation in front. */
    private static String indentLines(String text, String indent) {
        return text.lines().map(line -> line.isBlank() ? "" : indent + line).collect(Collectors.joining("\n"));
    }

    /** A multi-line text that continues after something else on its first line: the next lines get the indentation. */
    private static String continuation(String text, String indent) {
        List<String> lines = text.lines().toList();
        StringBuilder s = new StringBuilder(lines.isEmpty() ? "" : lines.getFirst());
        for (int i = 1; i < lines.size(); i++) s.append('\n').append(lines.get(i).isBlank() ? "" : indent + lines.get(i));
        return s.toString();
    }
}
