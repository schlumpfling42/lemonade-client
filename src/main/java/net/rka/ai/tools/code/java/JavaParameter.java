package net.rka.ai.tools.code.java;

import java.util.Objects;

/**
 * A parameter of a method or constructor.
 *
 * @param type the type as written in source, e.g. {@code List<String>}
 * @param name the parameter's name
 */
public record JavaParameter(String type, String name) {

    public JavaParameter {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
    }
}
