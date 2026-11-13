package net.rka.ai.graph.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * On a {@link Node} method that asks a model what to do next: what the model may name. The message to the model is generated from it,
 * and its answer is checked against it, so the model can only choose what the graph allows from this node.
 * Unlike the other graph annotations it is kept at run time: the program reads it to write the message.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Offers {

    /** The edges the model may take from this node, each with what it does and when to use it. */
    Edge[] edges() default {};

    /** The names of the tools the model may call from this node. */
    String[] tools() default {};

    /** One edge the model may name. */
    @interface Edge {
        /** The name the model writes. */
        String name();

        /** What happens when it is taken. */
        String does();

        /** When the model should take it. */
        String useWhen();
    }
}
