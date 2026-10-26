package net.rka.ai.graph.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that supplies a nested graph to run as a single node. Signature, with
 * {@code S} the same state class as the parent graph:
 * <pre>{@code
 * StateGraph<S> name()
 * CompiledGraph<S> name()
 * }</pre>
 * A subgraph declared with {@link Graph} can be returned with
 * {@code OtherGraphDefinition.stateGraph(other)}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Subgraph {

    /** Node name of the subgraph in this graph. */
    String value();
}
