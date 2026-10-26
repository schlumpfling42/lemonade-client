package net.rka.ai.graph.processor;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphProcessorTest {

    /** A state with one append-only list of strings, shared by the test graphs. */
    private static final String STATE = """
            package demo;

            import java.util.ArrayList;
            import java.util.List;
            import java.util.Map;
            import org.bsc.langgraph4j.state.AgentState;
            import org.bsc.langgraph4j.state.Channel;
            import org.bsc.langgraph4j.state.Channels;

            public class Steps extends AgentState {
                public static final Map<String, Channel<?>> SCHEMA = Map.of("steps", Channels.appender(ArrayList::new));

                public Steps(Map<String, Object> init) {
                    super(init);
                }

                public List<String> steps() {
                    return this.<List<String>>value("steps").orElseGet(List::of);
                }
            }
            """;

    /** Counts to three: START -> count -> (more: count, done: END), every node wrapped. */
    private static final String COUNTER = """
            package demo;

            import java.util.Map;
            import net.rka.ai.graph.annotations.*;
            import org.bsc.langgraph4j.RunnableConfig;
            import org.bsc.langgraph4j.action.NodeActionWithConfig;

            @Graph(name = "counter", state = Steps.class)
            @Edge(from = Graph.START, to = "count")
            public class Counter {

                @Node("count")
                Map<String, Object> count(Steps state, RunnableConfig config) {
                    return Map.of("steps", "count-" + (state.steps().size() + 1));
                }

                @Router(from = "count", routes = {
                        @Route(on = "more", to = "count"),
                        @Route(on = "done", to = Graph.END)})
                String next(Steps state) {
                    return state.steps().size() < 3 ? "more" : "done";
                }

                @NodeWrapper
                NodeActionWithConfig<Steps> wrap(String name, NodeActionWithConfig<Steps> action) {
                    return (state, config) -> {
                        Map<String, Object> update = action.apply(state, config);
                        return Map.of("steps", "[" + name + "] " + update.get("steps"));
                    };
                }
            }
            """;

    @Test
    void generatesARunnableGraph() throws Exception {
        Compilation result = Compilation.compile(Map.of("demo.Steps", STATE, "demo.Counter", COUNTER));
        assertTrue(result.success, () -> "compile errors: " + result.errors());

        String source = result.generatedSource("demo.CounterDefinition");
        assertTrue(source.contains("public static final String NAME = \"counter\";"), source);

        List<String> steps = run(result.classLoader(), "demo.Counter", "demo.CounterDefinition");
        assertEquals(List.of("[count] count-1", "[count] count-2", "[count] count-3"), steps);
    }

    @Test
    void runsAnAnnotatedSubgraph() throws Exception {
        String parent = """
                package demo;

                import java.util.Map;
                import net.rka.ai.graph.annotations.*;
                import org.bsc.langgraph4j.GraphStateException;
                import org.bsc.langgraph4j.StateGraph;

                @Graph(name = "parent", state = Steps.class)
                @Edge(from = Graph.START, to = "hello")
                @Edge(from = "hello", to = "counter")
                @Edge(from = "counter", to = Graph.END)
                public class Parent {

                    @Node("hello")
                    Map<String, Object> hello(Steps state) {
                        return Map.of("steps", "hello");
                    }

                    @Subgraph("counter")
                    StateGraph<Steps> counter() throws GraphStateException {
                        return CounterDefinition.stateGraph(new Counter());
                    }
                }
                """;
        Compilation result = Compilation.compile(Map.of("demo.Steps", STATE, "demo.Counter", COUNTER, "demo.Parent", parent));
        assertTrue(result.success, () -> "compile errors: " + result.errors());

        List<String> steps = run(result.classLoader(), "demo.Parent", "demo.ParentDefinition");
        assertEquals("hello", steps.getFirst());
        assertTrue(steps.contains("[count] count-3"), steps::toString);
    }

    /** A graph with runtime subgraphs "a" and "b": agent runs, routes to skill-a, agent, skill-b, agent, END. */
    private static String subgraphsGraph(String annotation) {
        return """
                package demo;

                import java.util.LinkedHashMap;
                import java.util.Map;
                import net.rka.ai.graph.annotations.*;
                import org.bsc.langgraph4j.GraphStateException;
                import org.bsc.langgraph4j.StateGraph;
                import org.bsc.langgraph4j.action.AsyncNodeAction;
                import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;

                @Graph(name = "dynamic", state = Steps.class)
                @Edge(from = Graph.START, to = "agent")
                public class Dynamic {

                    @Node("agent")
                    Map<String, Object> agent(Steps state) {
                        // numbered, because the appender channel drops values it already holds
                        long runs = state.steps().stream().filter(s -> s.startsWith("agent")).count();
                        return Map.of("steps", "agent-" + (runs + 1));
                    }

                    // "agent" runs once before each skill: after 1 run go to skill-a, after 2 to skill-b
                    @Router(from = "agent", routes = @Route(on = "done", to = Graph.END))
                    String route(Steps state) {
                        long runs = state.steps().stream().filter(s -> s.startsWith("agent")).count();
                        return runs == 1 ? "skill-a" : runs == 2 ? "skill-b" : "done";
                    }

                    %s
                    Map<String, StateGraph<Steps>> skills() throws GraphStateException {
                        Map<String, StateGraph<Steps>> skills = new LinkedHashMap<>();
                        for (String key : new String[]{"a", "b"}) {
                            skills.put(key, new StateGraph<Steps>(Steps.SCHEMA, new ObjectStreamStateSerializer<Steps>(Steps::new))
                                    .addNode("work", AsyncNodeAction.node_async(state -> Map.of("steps", "skill " + key)))
                                    .addEdge(StateGraph.START, "work")
                                    .addEdge("work", StateGraph.END));
                        }
                        return skills;
                    }
                }
                """.formatted(annotation);
    }

    @Test
    void subgraphsAddNodesEdgesAndRoutes() throws Exception {
        Compilation result = Compilation.compile(Map.of("demo.Steps", STATE, "demo.Dynamic",
                subgraphsGraph("@Subgraphs(prefix = \"skill-\", returnTo = \"agent\", router = \"route\")")));
        assertTrue(result.success, () -> "compile errors: " + result.errors());

        String source = result.generatedSource("demo.DynamicDefinition");
        assertTrue(source.contains("skill-* = subgraphs skills()"), source);
        assertTrue(source.contains("agent -&gt; route() {done: END, skill-*: skill-*}"), source);

        // agent -> skill-a -> agent (returnTo) -> skill-b -> agent -> done
        List<String> steps = run(result.classLoader(), "demo.Dynamic", "demo.DynamicDefinition");
        assertEquals(List.of("agent-1", "skill a", "agent-2", "skill b", "agent-3"), steps);
    }

    @Test
    void subgraphsNeedAnExistingRouterAndTarget() {
        assertSubgraphsError("@Subgraphs(prefix = \"skill-\", returnTo = \"agent\", router = \"routr\")", "routr");
        assertSubgraphsError("@Subgraphs(prefix = \"skill-\", returnTo = \"agnet\", router = \"route\")", "agnet");
    }

    private static void assertSubgraphsError(String annotation, String name) {
        Compilation result = Compilation.compile(Map.of("demo.Steps", STATE, "demo.Dynamic", subgraphsGraph(annotation)));
        assertTrue(!result.success && result.errors().stream().anyMatch(e -> e.contains("@Subgraphs") && e.contains("'" + name + "'")),
                () -> "expected an error naming '" + name + "' but got: " + result.errors());
    }

    @Test
    void reportsMisspelledNodeWithSuggestion() {
        assertError(graph("""
                @Edge(from = Graph.START, to = "a")
                @Edge(from = "a", to = "tolls")
                @Edge(from = "tools", to = Graph.END)
                """, node("a"), node("tools")),
                "unknown node 'tolls'. Did you mean 'tools'?");
    }

    @Test
    void reportsOnlyTheTypoNotFollowUpErrors() {
        Compilation result = Compilation.compile(Map.of("demo.Steps", STATE, "demo.Loop", graph("""
                @Edge(from = Graph.START, to = "a")
                @Edge(from = "a", to = "bb")
                @Edge(from = "b", to = Graph.END)
                """, node("a"), node("b"))));
        assertEquals(1, result.errors().size(), () -> "errors: " + result.errors());
    }

    @Test
    void reportsMissingEntryPoint() {
        assertError(graph("""
                @Edge(from = "a", to = Graph.END)
                """, node("a")), "no entry point");
    }

    @Test
    void reportsUnreachableNode() {
        assertError(graph("""
                @Edge(from = Graph.START, to = "a")
                @Edge(from = "a", to = Graph.END)
                @Edge(from = "orphan", to = Graph.END)
                """, node("a"), node("orphan")), "Node 'orphan' can't be reached from START");
    }

    @Test
    void reportsNodeWithoutWayOut() {
        assertError(graph("""
                @Edge(from = Graph.START, to = "a")
                @Edge(from = "a", to = "b")
                """, node("a"), node("b")), "Node 'b' has no way out");
    }

    @Test
    void reportsTwoWaysOutOfOneNode() {
        assertError(graph("""
                @Edge(from = Graph.START, to = "a")
                @Edge(from = "a", to = Graph.END)
                """, node("a"), """
                @Router(from = "a", routes = @Route(on = "x", to = Graph.END))
                String route(Steps state) { return "x"; }
                """), "already continues with");
    }

    @Test
    void reportsWrongNodeSignature() {
        assertError(graph("""
                @Edge(from = Graph.START, to = "a")
                @Edge(from = "a", to = Graph.END)
                """, """
                @Node("a")
                String a(Steps state) { return "nope"; }
                """), "@Node methods must look like");
    }

    @Test
    void reportsPrivateNodeMethod() {
        assertError(graph("""
                @Edge(from = Graph.START, to = "a")
                @Edge(from = "a", to = Graph.END)
                """, """
                @Node("a")
                private Map<String, Object> a(Steps state) { return Map.of(); }
                """), "can't be private");
    }

    @Test
    void reportsDuplicateNodeNames() {
        assertError(graph("""
                @Edge(from = Graph.START, to = "a")
                @Edge(from = "a", to = Graph.END)
                """, node("a"), """
                @Node("a")
                Map<String, Object> other(Steps state) { return Map.of(); }
                """), "Duplicate node name 'a'");
    }

    @Test
    void reportsNodeOutsideGraph() {
        Compilation result = Compilation.compile(Map.of("demo.Steps", STATE, "demo.Loose", """
                package demo;

                import java.util.Map;
                import net.rka.ai.graph.annotations.Node;

                class Loose {
                    @Node("a")
                    Map<String, Object> a(Steps state) { return Map.of(); }
                }
                """));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("only allowed in a class annotated with @Graph")),
                () -> "errors: " + result.errors());
    }

    @Test
    void warnsWhenGraphNeverEnds() {
        Compilation result = Compilation.compile(Map.of("demo.Steps", STATE, "demo.Loop", graph("""
                @Edge(from = Graph.START, to = "a")
                @Edge(from = "a", to = "a")
                """, node("a"))));
        assertTrue(result.success, () -> "errors: " + result.errors());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("No path leads to END")),
                () -> "warnings: " + result.warnings());
    }

    // --- helpers ---

    private static void assertError(String graphSource, String expected) {
        Compilation result = Compilation.compile(Map.of("demo.Steps", STATE, "demo.Loop", graphSource));
        assertTrue(!result.success && result.errors().stream().anyMatch(e -> e.contains(expected)),
                () -> "expected an error containing \"" + expected + "\" but got: " + result.errors());
    }

    private static String graph(String edges, String... members) {
        return """
                package demo;

                import java.util.Map;
                import net.rka.ai.graph.annotations.*;

                @Graph(name = "test", state = Steps.class)
                %s
                public class Loop {
                %s
                }
                """.formatted(edges, String.join("\n", members));
    }

    private static String node(String name) {
        return """
                @Node("%1$s")
                Map<String, Object> %1$s(Steps state) { return Map.of("steps", "%1$s"); }
                """.formatted(name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> run(ClassLoader loader, String graphClass, String definitionClass) throws Exception {
        Object graph = loader.loadClass(graphClass).getDeclaredConstructor().newInstance();
        CompiledGraph<AgentState> compiled = (CompiledGraph<AgentState>) loader.loadClass(definitionClass)
                .getMethod("compile", graph.getClass()).invoke(null, graph);
        Optional<AgentState> state = compiled.invoke(GraphInput.args(Map.of()), RunnableConfig.builder().build());
        return (List<String>) state.orElseThrow().value("steps").orElseThrow();
    }
}
