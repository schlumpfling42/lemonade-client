package net.rka.ai.graph.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** One branch of a {@link Router}: when the router returns {@link #on()}, go to {@link #to()}. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({})
public @interface Route {

    /** Value returned by the router method. */
    String on();

    /** Target node, or {@link Graph#END}. */
    String to();
}
