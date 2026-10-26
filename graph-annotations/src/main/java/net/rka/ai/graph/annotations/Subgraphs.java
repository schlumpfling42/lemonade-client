package net.rka.ai.graph.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that supplies a <em>set</em> of nested graphs that are only known at run time (for
 * example loaded from files), as opposed to {@link Subgraph}, whose single graph is fixed at compile
 * time. Signature, with {@code S} the same state class as the parent graph:
 * <pre>{@code
 * Map<String, StateGraph<S>> name()
 * Map<String, CompiledGraph<S>> name()
 * }</pre>
 * For every entry the generated {@code stateGraph(graph)} adds a node {@code prefix + key} holding the
 * nested graph, an edge from it back to {@link #returnTo()}, and a route {@code prefix + key} to that
 * node in the conditional edges of the {@link Router} method named {@link #router()}. So the router
 * can return {@code prefix + key} to start the nested graph. At most one {@code @Subgraphs} method is
 * allowed per graph.
 *
 * <pre>{@code
 * @Subgraphs(prefix = "skill-", returnTo = "agent", router = "route")
 * Map<String, StateGraph<State>> skills() { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Subgraphs {

    /** Added in front of each map key to form the node name (and the route value). */
    String prefix() default "";

    /** Node (or {@link Graph#END}) every nested graph continues with when it finishes. */
    String returnTo();

    /** Name of the {@link Router} method whose routes get one entry per nested graph. */
    String router();
}
