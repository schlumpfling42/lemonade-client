package net.rka.ai.graph.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a conditional edge: after node {@link #from()} runs, the method's
 * return value picks the next node through {@link #routes()}. Signature:
 * {@code String name(S state)}. A node has either one {@link Edge} out or one router.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Router {

    /** The node (or {@link Graph#START}) this router follows. */
    String from();

    /** Return value to next node mapping; every value the method can return needs a route. */
    Route[] routes();
}
