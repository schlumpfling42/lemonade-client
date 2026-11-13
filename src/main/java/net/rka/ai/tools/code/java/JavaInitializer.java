package net.rka.ai.tools.code.java;

/**
 * An initializer block of a Java class: {@code { ... }} runs for every new instance, {@code static { ... }} once when
 * the class is initialized.
 *
 * @param comment  the comment block right above the block as written in source (doc comment or not), or ""
 * @param isStatic whether it is a {@code static} block
 * @param code     the source text between the braces, or "" for an empty block
 * @param startLine the first line in the source (its comment included), see {@link JavaMember}
 * @param endLine   the line of the closing brace
 */
public record JavaInitializer(String comment, boolean isStatic, String code, int startLine, int endLine) implements JavaMember {

    /** An initializer that was not read from source: no lines. */
    public JavaInitializer(String comment, boolean isStatic, String code) {
        this(comment, isStatic, code, 0, 0);
    }

    public JavaInitializer {
        comment = comment == null ? "" : comment;
        code = code == null ? "" : code;
    }

    @Override
    public JavaInitializer withoutLines() {
        return new JavaInitializer(comment, isStatic, code);
    }
}
