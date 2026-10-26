package net.rka.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * Arithmetic tools. Small models are unreliable at math, so let them call these.
 *
 * <h2>How it works</h2>
 * Same pattern as {@link DateTimeTools}: each {@code @Tool} method is one tool. {@code double}
 * parameters become JSON {@code number} properties in the schema the model sees. Results are
 * plain {@code double}s, which LangChain4j turns into text (e.g. {@code "51849.0"}).
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>An expression evaluator ({@code evaluate("(3 + 4) * 2^10")}) instead of one call per
 *       operation: fewer round trips to the model. A library like exp4j would do it.</li>
 *   <li>{@code BigDecimal} for exact money math (doubles give 0.1 + 0.2 = 0.30000000000000004).</li>
 *   <li>Unit conversion, percentages, statistics on a list of numbers.</li>
 * </ul>
 */
public class MathTools {

    @Tool("Adds two numbers")
    public double add(@P("first number") double a, @P("second number") double b) {
        return a + b;
    }

    @Tool("Subtracts the second number from the first")
    public double subtract(@P("number to subtract from") double a, @P("number to subtract") double b) {
        return a - b;
    }

    @Tool("Multiplies two numbers")
    public double multiply(@P("first number") double a, @P("second number") double b) {
        return a * b;
    }

    @Tool("Divides the first number by the second")
    public double divide(@P("dividend") double a, @P("divisor, must not be zero") double b) {
        if (b == 0) {
            // Exceptions are returned to the model as an error result, so it can recover
            throw new IllegalArgumentException("Cannot divide by zero");
        }
        return a / b;
    }
}
