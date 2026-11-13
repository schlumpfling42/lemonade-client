package net.rka.ai.tools.code.java;

/**
 * One place where a type of the project is used by another (or by itself): found by the names in its declaration and in
 * its code, resolved the way Java resolves a simple name (a type of the same file, a single import, the same package, an
 * import on demand), so {@code Ledger} in code that imports {@code net.rka.ai.agent.Ledger} points at that type.
 *
 * @param fromType   the qualified name of the innermost type that contains the use
 * @param fromMember the member that contains it: {@code Type.name(ParamType,...)} for a method or constructor,
 *                   {@code Type.name} for a field, {@code Type.<init>} / {@code Type.<clinit>} for an initializer block,
 *                   or "" for the head of the type (its supertypes, annotations, type parameters)
 * @param toType     the qualified name of the type that is used
 * @param toMember   the member named right after the type ({@code Ledger.parse(...)}: {@code parse}), or "" when the type is
 *                   only mentioned. Calls through a variable ({@code ledger.parse(...)}) are not resolved: see
 *                   {@link JavaWorkspace#possibleCallers}
 * @param kind       what the use is
 */
public record Reference(String fromType, String fromMember, String toType, String toMember, Kind kind) {

    /** What a use is. */
    public enum Kind {
        /** In {@code extends}, {@code implements} or {@code permits}. */
        SUPERTYPE,
        /** An annotation on the type or a member. */
        ANNOTATION,
        /** In the signature of a member: the type of a field, a result, a parameter, a thrown type, a type parameter. */
        DECLARATION,
        /** In code: a method or constructor body, a field initializer, an initializer block, an enum constant's arguments. */
        BODY
    }
}
