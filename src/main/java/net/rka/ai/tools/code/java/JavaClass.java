package net.rka.ai.tools.code.java;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A Java type declaration: a class, interface, enum, record or annotation, with its members in source order.
 * The data is immutable; the lists are copies.
 *
 * @param headerComment        the comment at the top of the file, before {@code package} (only the first type of a file has one), or ""
 * @param comment              the comment block right above the declaration as written in source (doc comment or not), or ""
 * @param packageName          the package, or "" for the default package (nested types have the package of the outer one)
 * @param imports              the import statements' names, e.g. {@code java.util.List}, {@code static java.lang.Math.max}
 * @param annotations          the annotations as written in source, in source order
 * @param visibility           who can see the type
 * @param modifiers            {@code static} (nested only), {@code final}, {@code abstract}, {@code sealed}, {@code non-sealed}, {@code strictfp}
 * @param kind                 what the declaration is
 * @param name                 the type's simple name
 * @param typeParameters       the declared type parameters as written, e.g. {@code <T>}, or ""
 * @param extendsClass         the superclass as written, or "" when there is none
 * @param implementsInterfaces the implemented interfaces ({@code extends} for an interface)
 * @param permits              the permitted subtypes of a sealed type
 * @param recordComponents     the components of a record
 * @param enumConstants        the constants of an enum, in source order
 * @param members              the fields, initializer blocks, constructors, methods, nested types and loose comments in the order of the
 *                             source ({@link #fields()}, {@link #methods()} and the like pick one kind)
 * @param source               the complete text of the file the type was read from, the same for every type of the file, nested
 *                             ones included; "" for a type that was built by hand
 * @param startLine            the first line of the type in {@code source} (its comment or annotations included), see {@link JavaMember}
 * @param endLine              the line of its closing brace
 * @param headerLines          the lines of the {@code headerComment}, or {@link Span#NONE}
 * @param packageLines         the lines of the {@code package} statement, or {@link Span#NONE}
 * @param importLines          the lines of each import statement, in the order of {@code imports}
 */
public record JavaClass(String headerComment, String comment, String packageName, List<String> imports, List<String> annotations, Visibility visibility,
                        Set<Modifier> modifiers, JavaKind kind, String name, String typeParameters, String extendsClass,
                        List<String> implementsInterfaces, List<String> permits, List<JavaParameter> recordComponents,
                        List<JavaEnumConstant> enumConstants, List<JavaMember> members, String source, int startLine, int endLine,
                        Span headerLines, Span packageLines, List<Span> importLines) implements JavaMember {

    private static final Set<Modifier> ALLOWED = Set.of(Modifier.STATIC, Modifier.FINAL, Modifier.ABSTRACT, Modifier.SEALED,
            Modifier.NON_SEALED, Modifier.STRICTFP);

    /** A type that was not read from source: no text and no lines. */
    public JavaClass(String headerComment, String comment, String packageName, List<String> imports, List<String> annotations, Visibility visibility,
                     Set<Modifier> modifiers, JavaKind kind, String name, String typeParameters, String extendsClass,
                     List<String> implementsInterfaces, List<String> permits, List<JavaParameter> recordComponents,
                     List<JavaEnumConstant> enumConstants, List<JavaMember> members) {
        this(headerComment, comment, packageName, imports, annotations, visibility, modifiers, kind, name, typeParameters, extendsClass,
                implementsInterfaces, permits, recordComponents, enumConstants, members, "", 0, 0, Span.NONE, Span.NONE, List.of());
    }

    public JavaClass {
        headerComment = headerComment == null ? "" : headerComment;
        comment = comment == null ? "" : comment;
        packageName = packageName == null ? "" : packageName;
        imports = List.copyOf(imports);
        annotations = List.copyOf(annotations);
        Objects.requireNonNull(visibility, "visibility");
        modifiers = Set.copyOf(modifiers);
        if (!ALLOWED.containsAll(modifiers)) {
            throw new IllegalArgumentException("A type can't be " + modifiers + " (allowed: " + ALLOWED + ")");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("A type needs a name");
        typeParameters = typeParameters == null ? "" : typeParameters;
        extendsClass = extendsClass == null ? "" : extendsClass;
        implementsInterfaces = List.copyOf(implementsInterfaces);
        permits = List.copyOf(permits);
        recordComponents = List.copyOf(recordComponents);
        enumConstants = List.copyOf(enumConstants);
        members = List.copyOf(members);
        source = source == null ? "" : source;
        headerLines = headerLines == null ? Span.NONE : headerLines;
        packageLines = packageLines == null ? Span.NONE : packageLines;
        importLines = List.copyOf(importLines);
    }

    @Override
    public List<JavaMember> children() {
        return members;
    }

    /** The same type, nested ones and enum constants too, without the source text and the lines. */
    @Override
    public JavaClass withoutLines() {
        return new JavaClass(headerComment, comment, packageName, imports, annotations, visibility, modifiers, kind, name, typeParameters,
                extendsClass, implementsInterfaces, permits, recordComponents,
                enumConstants.stream().map(JavaEnumConstant::withoutLines).toList(),
                members.stream().map(JavaMember::withoutLines).toList());
    }

    /** The lines of the source from {@code first} to {@code last}, counted from 1 and both included, as they are in the file. */
    public String lines(int first, int last) {
        if (first < 1 || last < first) return "";
        String[] lines = source.split("\r?\n", -1);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, first - 1, Math.min(last, lines.length)));
    }

    /** The lines of a member (or of this type) as they are in the file; "" for a member that was not read from source. */
    public String lines(JavaMember member) {
        return lines(member.startLine(), member.endLine());
    }

    /** How many lines the source has (a final line break does not start another line). */
    public int lineCount() {
        if (source.isEmpty()) return 0;
        String[] lines = source.split("\r?\n", -1);
        return source.endsWith("\n") ? lines.length - 1 : lines.length;
    }

    public List<JavaField> fields() {
        return members(JavaField.class);
    }

    public List<JavaInitializer> initializers() {
        return members(JavaInitializer.class);
    }

    public List<JavaConstructor> constructors() {
        return members(JavaConstructor.class);
    }

    public List<JavaMethod> methods() {
        return members(JavaMethod.class);
    }

    public List<JavaClass> nestedClasses() {
        return members(JavaClass.class);
    }

    private <M extends JavaMember> List<M> members(Class<M> kind) {
        return members.stream().filter(kind::isInstance).map(kind::cast).toList();
    }

    public boolean isStatic() {
        return modifiers.contains(Modifier.STATIC);
    }

    public boolean isAbstract() {
        return modifiers.contains(Modifier.ABSTRACT);
    }

    /** The name with its package, e.g. {@code net.rka.ai.Foo}; just the name in the default package. */
    public String qualifiedName() {
        return packageName.isEmpty() ? name : packageName + "." + name;
    }
}
