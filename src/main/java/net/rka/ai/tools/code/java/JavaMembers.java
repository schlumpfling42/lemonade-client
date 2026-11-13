package net.rka.ai.tools.code.java;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Finds a member of a type by how a person (or a model) names it, and writes member text at the indentation of a type's body.
 * <p>
 * A member is named {@code name} (a field, a method or a nested type), {@code name(int,String)} to tell overloads apart (the
 * parameter types as written, generics and packages left out), {@code <init>} or {@code <init>(int)} (or the type's own name
 * with parentheses) for a constructor, {@code {}} or {@code static{}} for an initializer block, and {@code Inner.name} for a
 * member of a nested type.
 */
public final class JavaMembers {

    private JavaMembers() {
    }

    /** The member and the type that holds it. */
    public record Found(JavaClass owner, JavaMember member) {
    }

    /**
     * @throws IllegalArgumentException when nothing or more than one member fits (the message lists the members to pick from)
     */
    public static Found find(JavaClass type, String spec) {
        String name = spec == null ? "" : spec.strip();
        if (name.isEmpty()) throw new IllegalArgumentException("Name a member of " + type.name() + ": " + listing(type));
        int paren = name.indexOf('(');
        String head = paren < 0 ? name : name.substring(0, paren);
        int dot = head.indexOf('.');
        if (dot > 0) { // Inner.member: down into the nested type
            String inner = head.substring(0, dot);
            JavaClass nested = type.nestedClasses().stream().filter(n -> n.name().equals(inner)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(type.name() + " has no nested type " + inner + ": " + listing(type)));
            return find(nested, name.substring(dot + 1));
        }
        List<JavaMember> fits = new ArrayList<>();
        List<String> types = paren < 0 ? null : parameterTypes(name.substring(paren));
        for (JavaMember member : type.members()) {
            if (matches(type, member, head, types)) fits.add(member);
        }
        if (fits.size() == 1) return new Found(type, fits.getFirst());
        if (fits.isEmpty()) throw new IllegalArgumentException(type.name() + " has no member " + spec.strip() + ": " + listing(type));
        throw new IllegalArgumentException(spec.strip() + " fits more than one member of " + type.name() + ", name one with its parameter types: "
                + fits.stream().map(m -> JavaStructure.signature(m)).collect(Collectors.joining(", ")));
    }

    private static boolean matches(JavaClass type, JavaMember member, String head, List<String> types) {
        switch (member) {
            case JavaField f -> {
                return types == null && f.name().equals(head);
            }
            case JavaClass n -> {
                return types == null && n.name().equals(head);
            }
            case JavaMethod m -> {
                return m.name().equals(head) && (types == null || sameTypes(types, m.parameters()));
            }
            case JavaConstructor c -> {
                return (head.equals("<init>") || head.equals(type.name())) && (types == null || sameTypes(types, c.parameters()));
            }
            case JavaInitializer i -> {
                return types == null && head.equals(i.isStatic() ? "static{}" : "{}");
            }
            case JavaComment c -> {
                return false;
            }
        }
    }

    private static boolean sameTypes(List<String> wanted, List<JavaParameter> parameters) {
        if (wanted.size() != parameters.size()) return false;
        for (int i = 0; i < wanted.size(); i++) if (!simple(wanted.get(i)).equals(simple(parameters.get(i).type()))) return false;
        return true;
    }

    /** {@code (int, List<String>)} → [int, List<String>]. */
    private static List<String> parameterTypes(String parenthesized) {
        String inner = parenthesized.strip();
        if (inner.startsWith("(")) inner = inner.substring(1);
        if (inner.endsWith(")")) inner = inner.substring(0, inner.length() - 1);
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : inner.toCharArray()) {
            if (c == '<') depth++;
            if (c == '>') depth--;
            if (c == ',' && depth == 0) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) out.add(current.toString());
        return out;
    }

    /** A type as it is compared: no blanks, no generics, no package ({@code java.util.List<String>} is {@code List}). */
    static String simple(String type) {
        String noGenerics = type.replaceAll("<.*>", "").replaceAll("\\s+", "");
        String last = noGenerics.substring(noGenerics.lastIndexOf('.') + 1);
        return last.endsWith("...") ? last.substring(0, last.length() - 3) + "[]" : last;
    }

    /** How the tools name this member of {@code owner}: its name, with the parameter types only where several members share it. */
    public static String spec(JavaClass owner, JavaMember member) {
        return switch (member) {
            case JavaField f -> f.name();
            case JavaClass n -> n.name();
            case JavaInitializer i -> i.isStatic() ? "static{}" : "{}";
            case JavaMethod m -> owner.methods().stream().filter(o -> o.name().equals(m.name())).count() > 1
                    ? m.name() + "(" + simpleTypes(m.parameters()) + ")" : m.name();
            case JavaConstructor c -> owner.constructors().size() > 1 ? "<init>(" + simpleTypes(c.parameters()) + ")" : "<init>";
            case JavaComment c -> "";
        };
    }

    private static String simpleTypes(List<JavaParameter> parameters) {
        return parameters.stream().map(p -> simple(p.type())).collect(Collectors.joining(","));
    }

    /** The members of a type as the tools name them, for a message. */
    public static String listing(JavaClass type) {
        List<String> names = type.members().stream().filter(m -> !(m instanceof JavaComment))
                .map(m -> JavaStructure.signature(m).replaceFirst("^(field|method|type) ", "")).toList();
        return names.isEmpty() ? "(no members)" : String.join(", ", names);
    }

    // ---- text ----

    /** The blanks at the start of the line. */
    public static String indentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    /** The code without its common indentation, then indented by {@code indent}; blank lines stay empty. */
    public static String indented(String code, String indent) {
        List<String> lines = code.strip().lines().toList();
        int common = Integer.MAX_VALUE;
        for (String line : lines.stream().skip(1).toList()) {
            if (!line.isBlank()) common = Math.min(common, indentOf(line).length());
        }
        // the first line starts where the text starts, so only the following lines say how far the text is indented
        int strip = common == Integer.MAX_VALUE ? 0 : common;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String body = i == 0 ? line.strip() : line.isBlank() ? "" : line.substring(Math.min(strip, indentOf(line).length()));
            if (i > 0) out.append('\n');
            out.append(body.isBlank() ? "" : indent + body);
        }
        return out.toString();
    }
}
