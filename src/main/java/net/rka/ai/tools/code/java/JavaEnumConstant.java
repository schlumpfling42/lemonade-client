package net.rka.ai.tools.code.java;

import java.util.List;
import java.util.Objects;

/**
 * A constant of an enum.
 *
 * @param comment     the comment block right above the constant as written in source (doc comment or not), or ""
 * @param annotations the annotations as written in source, in source order
 * @param name        the constant's name
 * @param arguments   the constructor arguments as written between the parentheses, or "" (also for {@code A()})
 * @param hasBody     whether the constant has a class body ({@code A { ... }})
 * @param body        the class body's source text between the braces, or ""
 * @param startLine   the first line in the source (its comment or annotations included); 0 when not read from source
 * @param endLine     the last line of the constant, its body included (not the {@code ,} after it)
 */
public record JavaEnumConstant(String comment, List<String> annotations, String name, String arguments, boolean hasBody,
                               String body, int startLine, int endLine) {

    /** A constant that was not read from source: no lines. */
    public JavaEnumConstant(String comment, List<String> annotations, String name, String arguments, boolean hasBody, String body) {
        this(comment, annotations, name, arguments, hasBody, body, 0, 0);
    }

    public JavaEnumConstant {
        comment = comment == null ? "" : comment;
        annotations = List.copyOf(annotations);
        Objects.requireNonNull(name, "name");
        arguments = arguments == null ? "" : arguments;
        body = body == null ? "" : body;
    }

    /** The same constant without lines, see {@link JavaMember#withoutLines()}. */
    public JavaEnumConstant withoutLines() {
        return new JavaEnumConstant(comment, annotations, name, arguments, hasBody, body);
    }
}
