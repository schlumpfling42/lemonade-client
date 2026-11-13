package net.rka.ai.tools.code.java;

import java.util.ArrayList;
import java.util.List;

/**
 * A statement of a method, constructor or initializer body: a simple one (a declaration, a call, an assignment, a
 * {@code return}) or a compound one that holds the statements it runs ({@link #parts()}).
 *
 * @param kind      what it is
 * @param startLine the first line (comments directly above it included), counted from 1
 * @param endLine   the last line
 * @param summary   its first line of code, shortened: what the outline shows
 * @param parts     the statements inside a compound statement, grouped by what they are for; empty for a simple one
 * @param declares  the variables this statement declares (a {@code for} loop's and a {@code catch} parameter included, and the
 *                  parameters of the lambdas in its expressions)
 * @param reads     the names its own expressions use (the condition of an {@code if}, not the statements inside it): variables,
 *                  and also type names, which only a look at the declared variables tells apart
 * @param writes    the names it assigns, {@code ++} / {@code --} and compound assignments included, and the name a declaration declares
 * @param calls     the calls in its own expressions, in the order of the source
 */
public record Statement(Kind kind, int startLine, int endLine, String summary, List<Part> parts, List<Variable> declares,
                        List<String> reads, List<String> writes, List<Call> calls) implements Block {

    /** What a statement is. */
    public enum Kind {
        DECLARATION, ASSIGNMENT, CALL, EXPRESSION, RETURN, THROW, BREAK, CONTINUE, YIELD, ASSERT,
        IF, FOR, FOREACH, WHILE, DO, SWITCH, TRY, SYNCHRONIZED, LABELED, BLOCK, LOCAL_CLASS
    }

    public Statement {
        parts = List.copyOf(parts);
        declares = List.copyOf(declares);
        reads = List.copyOf(reads);
        writes = List.copyOf(writes);
        calls = List.copyOf(calls);
    }

    /** A statement without facts about its names and calls. */
    public Statement(Kind kind, int startLine, int endLine, String summary, List<Part> parts, List<Variable> declares) {
        this(kind, startLine, endLine, summary, parts, declares, List.of(), List.of(), List.of());
    }

    /** All statements directly inside, in the order of the source. */
    @Override
    public List<Statement> children() {
        List<Statement> all = new ArrayList<>();
        parts.forEach(p -> all.addAll(p.statements()));
        return all;
    }
}
