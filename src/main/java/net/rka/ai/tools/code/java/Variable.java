package net.rka.ai.tools.code.java;

/**
 * A variable that a block declares.
 *
 * @param name  the variable's name
 * @param type  its type as written ({@code var} stays {@code var})
 * @param kind  what declares it
 * @param line  the line of the declaration
 * @param scope the lines in which the name means this variable: from its declaration to the end of the block that holds it
 *              (a loop's variables: the loop; a catch parameter: the catch)
 */
public record Variable(String name, String type, Kind kind, int line, Span scope) {

    /** What declares a variable. */
    public enum Kind {
        PARAMETER, LOCAL, LOOP, RESOURCE, CATCH
    }
}
