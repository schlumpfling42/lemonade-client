package net.rka.ai.tools.code.java;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A method of a Java class (a constructor is a {@link JavaConstructor}).
 *
 * @param comment        the comment block right above the declaration as written in source (doc comment or not), or ""
 * @param annotations    the annotations as written in source, e.g. {@code @Override}, in source order
 * @param visibility     who can see the method
 * @param modifiers      {@code static}, {@code final}, {@code abstract}, {@code synchronized}, {@code native}, {@code strictfp}, {@code default}
 * @param typeParameters the declared type parameters as written, e.g. {@code <T extends Comparable<T>>}, or ""
 * @param returnType     the return type as written in source, e.g. {@code void}
 * @param name           the method's name
 * @param parameters     the parameters in order
 * @param throwsTypes    the declared exception types
 * @param defaultValue   the default of an annotation element ({@code default 3}), or ""
 * @param code           the body's source text between the braces, or "" for an empty body (or none: an abstract or native
 *                       method, or an interface method without a body, is written with {@code ;})
 * @param startLine      the first line in the source (its comment or annotations included), see {@link JavaMember}
 * @param endLine        the line of the closing brace or of the {@code ;}
 */
public record JavaMethod(String comment, List<String> annotations, Visibility visibility, Set<Modifier> modifiers, String typeParameters,
                         String returnType, String name, List<JavaParameter> parameters, List<String> throwsTypes,
                         String code, String defaultValue, int startLine, int endLine) implements JavaMember {

    private static final Set<Modifier> ALLOWED = Set.of(Modifier.STATIC, Modifier.FINAL, Modifier.ABSTRACT, Modifier.SYNCHRONIZED,
            Modifier.NATIVE, Modifier.STRICTFP, Modifier.DEFAULT);

    /** A method that was not read from source: no lines. */
    public JavaMethod(String comment, List<String> annotations, Visibility visibility, Set<Modifier> modifiers, String typeParameters,
                      String returnType, String name, List<JavaParameter> parameters, List<String> throwsTypes,
                      String code, String defaultValue) {
        this(comment, annotations, visibility, modifiers, typeParameters, returnType, name, parameters, throwsTypes, code, defaultValue, 0, 0);
    }

    public JavaMethod {
        comment = comment == null ? "" : comment;
        annotations = List.copyOf(annotations);
        Objects.requireNonNull(visibility, "visibility");
        modifiers = Set.copyOf(modifiers);
        if (!ALLOWED.containsAll(modifiers)) {
            throw new IllegalArgumentException("A method can't be " + modifiers + " (allowed: " + ALLOWED + ")");
        }
        typeParameters = typeParameters == null ? "" : typeParameters;
        Objects.requireNonNull(returnType, "returnType");
        Objects.requireNonNull(name, "name");
        parameters = List.copyOf(parameters);
        throwsTypes = List.copyOf(throwsTypes);
        code = code == null ? "" : code;
        defaultValue = defaultValue == null ? "" : defaultValue;
    }

    @Override
    public JavaMethod withoutLines() {
        return new JavaMethod(comment, annotations, visibility, modifiers, typeParameters, returnType, name, parameters, throwsTypes, code,
                defaultValue);
    }

    public boolean isStatic() {
        return modifiers.contains(Modifier.STATIC);
    }

    public boolean isAbstract() {
        return modifiers.contains(Modifier.ABSTRACT);
    }
}
