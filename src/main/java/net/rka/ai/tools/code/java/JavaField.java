package net.rka.ai.tools.code.java;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A field of a Java class.
 *
 * @param comment     the comment block right above the declaration as written in source (doc comment or not), or ""
 * @param annotations the annotations as written in source, e.g. {@code @Nullable}, in source order
 * @param visibility  who can see the field
 * @param modifiers   {@code static}, {@code final}, {@code transient}, {@code volatile}
 * @param type        the type as written in source, e.g. {@code List<String>}: it need not be on the classpath
 * @param name        the field's name
 * @param value       the initializer's source text, or "" when the field has none
 * @param startLine   the first line in the source (its comment or annotations included), see {@link JavaMember}
 * @param endLine     the line of the {@code ;} (or of the {@code ,} before the next declarator of the same declaration)
 */
public record JavaField(String comment, List<String> annotations, Visibility visibility, Set<Modifier> modifiers, String type, String name,
                        String value, int startLine, int endLine) implements JavaMember {

    private static final Set<Modifier> ALLOWED = Set.of(Modifier.STATIC, Modifier.FINAL, Modifier.TRANSIENT, Modifier.VOLATILE);

    /** A field that was not read from source: no lines. */
    public JavaField(String comment, List<String> annotations, Visibility visibility, Set<Modifier> modifiers, String type, String name,
                     String value) {
        this(comment, annotations, visibility, modifiers, type, name, value, 0, 0);
    }

    public JavaField {
        comment = comment == null ? "" : comment;
        annotations = List.copyOf(annotations);
        Objects.requireNonNull(visibility, "visibility");
        modifiers = Set.copyOf(modifiers);
        if (!ALLOWED.containsAll(modifiers)) {
            throw new IllegalArgumentException("A field can't be " + modifiers + " (allowed: " + ALLOWED + ")");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        value = value == null ? "" : value;
    }

    @Override
    public JavaField withoutLines() {
        return new JavaField(comment, annotations, visibility, modifiers, type, name, value);
    }

    public boolean isStatic() {
        return modifiers.contains(Modifier.STATIC);
    }
}
