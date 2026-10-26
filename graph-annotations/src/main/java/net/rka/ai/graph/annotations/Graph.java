package net.rka.ai.graph.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a LangGraph4j graph. The annotated class holds the node, router and subgraph
 * methods; the graph processor checks the wiring at compile time and generates
 * {@code <ClassName>Definition} in the same package, with {@code stateGraph(graph)} and
 * {@code compile(graph)} methods.
 *
 * <pre>{@code
 * @Graph(name = "agent", state = AgentGraph.State.class)
 * @Edge(from = Graph.START, to = "agent")
 * @Edge(from = "tools", to = "agent")
 * class AgentGraph {
 *     @Node("agent") Map<String, Object> callModel(State state) { ... }
 *     @Node("tools") Map<String, Object> executeTools(State state) { ... }
 *
 *     @Router(from = "agent", routes = {
 *             @Route(on = "tools", to = "tools"),
 *             @Route(on = Graph.END, to = Graph.END)})
 *     String route(State state) { ... }
 * }
 * }</pre>
 *
 * <h2>How it works (annotation processing)</h2>
 * These annotations are read by the <b>compiler</b>, not at run time. {@code build.gradle} registers
 * {@code graph-processor} as an {@code annotationProcessor}; while javac compiles a class carrying
 * {@code @Graph}, it hands the class's structure to {@code GraphProcessor}, which
 * <ol>
 *   <li>checks the wiring (unknown nodes, typos, unreachable nodes, wrong method signatures, …) and
 *       reports mistakes as ordinary compile errors pointing at the annotation, and</li>
 *   <li>writes a new source file {@code <ClassName>Definition.java} that builds the LangGraph4j
 *       {@code StateGraph} with plain method references: no reflection at run time.</li>
 * </ol>
 * That's why the annotations use {@code RetentionPolicy.CLASS}: nothing needs them after compiling.
 * The benefit over hand-written {@code addNode}/{@code addEdge} calls with string names: a misspelled
 * node name is caught by the compiler (with a "did you mean" hint) instead of failing at run time.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Generating a Mermaid diagram next to the definition, so {@code docs/agent-graph.md} could be
 *       produced instead of maintained by hand.</li>
 *   <li>Checking at compile time that a router's possible return values match its routes (would need
 *       the router to return an enum instead of a String).</li>
 *   <li>Declaring {@code interruptBefore}/{@code interruptAfter} with annotations
 *       ({@code @Node(value = "approval", interruptBefore = true)}).</li>
 *   <li>Parallel branches (LangGraph4j supports fan-out to several nodes at once).</li>
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Graph {

    /** The graph's entry point, same value as LangGraph4j's {@code StateGraph.START}. */
    String START = "__START__";

    /** The graph's exit, same value as LangGraph4j's {@code StateGraph.END}. */
    String END = "__END__";

    /** Graph name, exposed as {@code NAME} on the generated class. */
    String name();

    /**
     * The state class: extends {@code AgentState}, has a {@code (Map<String, Object>)}
     * constructor and a static {@code Map<String, Channel<?>>} schema field.
     */
    Class<?> state();

    /** Name of the static schema field on the state class. */
    String schema() default "SCHEMA";

    /** How LangGraph4j copies state between steps. */
    Serializer serializer() default Serializer.STANDARD;

    enum Serializer {
        /** LangGraph4j's default Java serialization; state values must be {@code Serializable}. */
        STANDARD,
        /** {@code LC4jStateSerializer}, for state holding LangChain4j chat messages. */
        LANGCHAIN4J
    }
}
