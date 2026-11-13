package net.rka.ai.tools.code.java;

import java.util.List;
import java.util.Objects;

/**
 * A constructor of a Java class. Its name is the class's name, so it is not stored.
 *
 * @param comment     the comment block right above the declaration as written in source (doc comment or not), or ""
 * @param annotations the annotations as written in source, in source order
 * @param visibility  who can see the constructor
 * @param typeParameters the declared type parameters of a generic constructor as written, e.g. {@code <Z extends CharSequence>}, or ""
 * @param parameters  the parameters in order
 * @param throwsTypes the declared exception types
 * @param code        the body's source text between the braces, or "" for an empty body
 * @param compact     whether it is a record's compact constructor ({@code Name { ... }}, no parameter list)
 * @param startLine   the first line in the source (its comment or annotations included), see {@link JavaMember}
 * @param endLine     the line of the closing brace
 */
public record JavaConstructor(String comment, List<String> annotations, Visibility visibility, String typeParameters,
                              List<JavaParameter> parameters,
                              List<String> throwsTypes, String code, boolean compact, int startLine, int endLine) implements JavaMember {

    /** A constructor that was not read from source: no lines. */
    public JavaConstructor(String comment, List<String> annotations, Visibility visibility, String typeParameters,
                           List<JavaParameter> parameters, List<String> throwsTypes, String code, boolean compact) {
        this(comment, annotations, visibility, typeParameters, parameters, throwsTypes, code, compact, 0, 0);
    }

    public JavaConstructor {
        comment = comment == null ? "" : comment;
        annotations = List.copyOf(annotations);
        Objects.requireNonNull(visibility, "visibility");
        typeParameters = typeParameters == null ? "" : typeParameters;
        parameters = List.copyOf(parameters);
        throwsTypes = List.copyOf(throwsTypes);
        code = code == null ? "" : code;
    }

    @Override
    public JavaConstructor withoutLines() {
        return new JavaConstructor(comment, annotations, visibility, typeParameters, parameters, throwsTypes, code, compact);
    }
}
