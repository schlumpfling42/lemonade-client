package net.rka.ai.graph.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a graph node. Signature, with {@code S} the graph's state class:
 * <pre>{@code
 * Map<String, Object> name(S state)
 * Map<String, Object> name(S state, RunnableConfig config)
 * }</pre>
 * The returned map holds the state updates. The method must not be private or static.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Node {

    /** Node name, used in edges and routes. */
    String value();
}
