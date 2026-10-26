package net.rka.ai.graph.processor;

import net.rka.ai.graph.annotations.Graph;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import java.util.List;

/**
 * A validated {@code @Graph} class, ready for code generation.
 *
 * <p>How it fits in: {@code GraphProcessor} reads the annotations from the compiler's model of the
 * source code ({@link TypeElement} = a class, {@link ExecutableElement} = a method), checks them, and
 * collects the result in this record. {@code GraphSourceWriter} then only has to turn the record into
 * Java text; keeping "read and check" separate from "write" keeps both halves simple.
 * The nested records mirror the annotations one to one.
 */
record GraphModel(
        TypeElement graphClass,
        String name,
        TypeElement stateClass,
        String schemaField,
        Graph.Serializer serializer,
        List<NodeDef> nodes,
        List<SubgraphDef> subgraphs,
        SubgraphsDef dynamicSubgraphs,
        List<EdgeDef> edges,
        List<RouterDef> routers,
        ExecutableElement wrapper) {

    record NodeDef(String name, ExecutableElement method, boolean takesConfig) {
    }

    record SubgraphDef(String name, ExecutableElement method) {
    }

    /**
     * The {@code @Subgraphs} method (the model's {@code dynamicSubgraphs} is null if the graph has none);
     * {@code router} is the {@code @Router} method that gets one extra route per nested graph.
     */
    record SubgraphsDef(String prefix, String returnTo, ExecutableElement router, ExecutableElement method) {
    }

    record EdgeDef(String from, String to) {
    }

    record RouterDef(String from, ExecutableElement method, List<RouteDef> routes) {
    }

    record RouteDef(String on, String to) {
    }
}
