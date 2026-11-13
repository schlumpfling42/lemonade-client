package net.rka.ai.tools.code.java;

import java.util.List;

/**
 * A call in the code of a statement.
 *
 * @param name      the method called; {@code <init>} for {@code new X(...)}
 * @param receiver  what the method is called on, as the parts of the expression before the dot: {@code ledger.size()} has
 *                  [ledger], {@code this.ledger.size()} [this, ledger], {@code Agent.fromEnv(x).run()} [Agent, fromEnv()],
 *                  {@code new X().run()} [new X], a cast {@code ((Y) o).run()} [(Y)], and {@code ?} for what can't be told
 *                  (an array element, a literal); empty for a call without a receiver, which is a method of the same type
 *                  (or an outer one), and {@code X} alone for {@code new X(...)}
 * @param line      the line of the call
 * @param arguments how many arguments, -1 for a method reference
 */
public record Call(String name, List<String> receiver, int line, int arguments) {

    public Call {
        receiver = List.copyOf(receiver);
    }

    /** The receiver as written, e.g. {@code this.ledger}; "" without one. */
    public String receiverText() {
        return String.join(".", receiver);
    }
}
