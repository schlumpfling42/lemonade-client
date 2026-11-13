package net.rka.ai.tools.code.java;

import java.util.ArrayList;
import java.util.List;

import net.rka.ai.tools.code.java.Reference.Kind;

/**
 * The names a file's types use, before they are resolved to types of the project: every dotted name in the head of a type
 * and in each member (its signature and its code), with the type and member it is in. {@link JavaWorkspace} resolves them
 * when it knows which types exist.
 */
final class JavaUses {

    /** A dotted name ({@code Ledger.parse} is [Ledger, parse]) used in a place of the code. */
    record Use(String fromType, String fromMember, Kind kind, List<String> chain) {
    }

    private JavaUses() {
    }

    static List<Use> of(List<JavaClass> types) {
        List<Use> uses = new ArrayList<>();
        for (JavaClass type : types) collect(type, type.qualifiedName(), uses);
        return uses;
    }

    private static void collect(JavaClass type, String qualified, List<Use> uses) {
        Out out = new Out(uses, qualified);
        String head = "";
        type.annotations().forEach(a -> out.add(head, Kind.ANNOTATION, a));
        out.add(head, Kind.DECLARATION, type.typeParameters());
        out.add(head, Kind.SUPERTYPE, type.extendsClass());
        type.implementsInterfaces().forEach(i -> out.add(head, Kind.SUPERTYPE, i));
        type.permits().forEach(p -> out.add(head, Kind.SUPERTYPE, p));
        type.recordComponents().forEach(c -> out.add(head, Kind.DECLARATION, c.type()));
        for (JavaEnumConstant c : type.enumConstants()) {
            String member = qualified + "." + c.name();
            c.annotations().forEach(a -> out.add(member, Kind.ANNOTATION, a));
            out.add(member, Kind.BODY, c.arguments());
            out.add(member, Kind.BODY, c.body());
        }
        for (JavaMember m : type.members()) {
            switch (m) {
                case JavaField f -> {
                    String member = qualified + "." + f.name();
                    f.annotations().forEach(a -> out.add(member, Kind.ANNOTATION, a));
                    out.add(member, Kind.DECLARATION, f.type());
                    out.add(member, Kind.BODY, f.value());
                }
                case JavaInitializer i -> out.add(qualified + (i.isStatic() ? ".<clinit>" : ".<init>"), Kind.BODY, i.code());
                case JavaConstructor c -> {
                    String member = qualified + ".<init>(" + erased(c.parameters()) + ")";
                    c.annotations().forEach(a -> out.add(member, Kind.ANNOTATION, a));
                    out.add(member, Kind.DECLARATION, c.typeParameters());
                    c.parameters().forEach(p -> out.add(member, Kind.DECLARATION, p.type()));
                    c.throwsTypes().forEach(t -> out.add(member, Kind.DECLARATION, t));
                    out.add(member, Kind.BODY, c.code());
                }
                case JavaMethod method -> {
                    String member = qualified + "." + method.name() + "(" + erased(method.parameters()) + ")";
                    method.annotations().forEach(a -> out.add(member, Kind.ANNOTATION, a));
                    out.add(member, Kind.DECLARATION, method.typeParameters());
                    out.add(member, Kind.DECLARATION, method.returnType());
                    method.parameters().forEach(p -> out.add(member, Kind.DECLARATION, p.type()));
                    method.throwsTypes().forEach(t -> out.add(member, Kind.DECLARATION, t));
                    out.add(member, Kind.BODY, method.defaultValue());
                    out.add(member, Kind.BODY, method.code());
                }
                case JavaClass nested -> collect(nested, qualified + "." + nested.name(), uses);
                case JavaComment ignored -> {
                }
            }
        }
    }

    /** The parameter types the way an id shows them: without generics, annotations and {@code final}. */
    static String erased(List<JavaParameter> parameters) {
        List<String> types = new ArrayList<>();
        for (JavaParameter p : parameters) {
            StringBuilder out = new StringBuilder();
            int depth = 0;
            for (char c : p.type().toCharArray()) {
                if (c == '<') depth++;
                else if (c == '>') depth--;
                else if (depth == 0) out.append(c);
            }
            types.add(out.toString().replaceAll("@\\w+(\\.\\w+)*(\\([^)]*\\))?\\s*", "").replaceAll("\\bfinal\\s+", "").replaceAll("\\s+", "")
                    .replace("...", "[]"));
        }
        return String.join(",", types);
    }

    /** Adds the dotted names of a piece of source as uses of one place. */
    private record Out(List<Use> uses, String type) {
        void add(String member, Kind kind, String text) {
            if (text == null || text.isBlank()) return;
            for (List<String> chain : JavaSourceReader.chains(text)) uses.add(new Use(type, member, kind, chain));
        }
    }
}
