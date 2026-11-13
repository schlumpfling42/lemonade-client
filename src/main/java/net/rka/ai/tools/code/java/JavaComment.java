package net.rka.ai.tools.code.java;

import java.util.Objects;

/**
 * A comment in a type body that belongs to no declaration: one at the end of the body, or one at the end of a line after a
 * member ({@code int x; // note}). A comment directly above a declaration is that declaration's {@code comment} instead.
 *
 * @param text     the comment as written in source, without the indentation of its first line
 * @param trailing whether it is written on the line of the member before it (or of the opening brace)
 * @param startLine the line it starts on, see {@link JavaMember}
 * @param endLine   the line it ends on
 */
public record JavaComment(String text, boolean trailing, int startLine, int endLine) implements JavaMember {

    /** A comment that was not read from source: no lines. */
    public JavaComment(String text, boolean trailing) {
        this(text, trailing, 0, 0);
    }

    public JavaComment {
        Objects.requireNonNull(text, "text");
    }

    @Override
    public JavaComment withoutLines() {
        return new JavaComment(text, trailing);
    }
}
