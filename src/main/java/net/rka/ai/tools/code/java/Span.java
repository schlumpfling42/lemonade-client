package net.rka.ai.tools.code.java;

/**
 * The lines of the file an entity covers, counted from 1 and both ends included.
 *
 * @param start the first line; 0 for no lines (the entity is not in the file)
 * @param end   the last line; 0 for no lines
 */
public record Span(int start, int end) {

    /** For an entity that is not in the file, e.g. the package of a file without one. */
    public static final Span NONE = new Span(0, 0);

    public Span {
        if (start < 0 || end < start) throw new IllegalArgumentException("Lines " + start + " to " + end);
    }

    public boolean isNone() {
        return start == 0;
    }
}
