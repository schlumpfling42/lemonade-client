package net.rka.ai.tools.code.java;

import java.util.List;

/**
 * A named group of statements inside a compound statement: {@code then} and {@code else} of an {@code if}, {@code body} of a
 * loop, {@code init} and {@code update} of a {@code for}, {@code case X} of a {@code switch}, {@code catch IOException e}
 * and {@code finally} of a {@code try}.
 */
public record Part(String name, List<Statement> statements) {

    public Part {
        statements = List.copyOf(statements);
    }
}
