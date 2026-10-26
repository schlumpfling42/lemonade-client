package net.rka.ai.graph.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that wraps every {@link Node} of the graph, e.g. for tracing or logging.
 * At most one per graph. Signature:
 * <pre>{@code
 * NodeActionWithConfig<S> name(String nodeName, NodeActionWithConfig<S> action)
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface NodeWrapper {
}
