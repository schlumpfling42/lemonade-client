package net.rka.ai.tools.code.java;

/**
 * A member of a type body, in the order of the source: a field, initializer, constructor, method, nested type or comment.
 * <p>
 * Every member read from source knows the lines it covers in {@link JavaClass#source()}: from the first line of its
 * comment or annotations to the line of its last token (the closing brace or the semicolon). Both are counted from 1
 * and included; they are 0 for a member that was built by hand and not read from source.
 */
public sealed interface JavaMember extends Block permits JavaField, JavaInitializer, JavaConstructor, JavaMethod, JavaClass, JavaComment {

    /** The blocks inside a member: the members of a type; the statements of a body are built on demand ({@link JavaBlocks#bodyOf}). */
    @Override
    default java.util.List<? extends Block> children() {
        return java.util.List.of();
    }

    /**
     * The same member without lines (and, for a type, without the source text): for comparing what two readings declare when
     * the text they come from is not the same, e.g. a file and what the writer made of it.
     */
    JavaMember withoutLines();
}
