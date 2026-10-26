package net.rka.ai.graph.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A fixed edge between two nodes, declared on the {@link Graph} class.
 * Use {@link Graph#START} as {@code from} for the entry edge and {@link Graph#END} as
 * {@code to} to finish. For a choice between several next nodes use {@link Router}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@Repeatable(Edges.class)
public @interface Edge {

    String from();

    String to();
}
