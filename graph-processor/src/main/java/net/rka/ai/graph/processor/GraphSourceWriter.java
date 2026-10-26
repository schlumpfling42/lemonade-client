package net.rka.ai.graph.processor;

import net.rka.ai.graph.annotations.Graph;
import net.rka.ai.graph.processor.GraphModel.EdgeDef;
import net.rka.ai.graph.processor.GraphModel.NodeDef;
import net.rka.ai.graph.processor.GraphModel.RouteDef;
import net.rka.ai.graph.processor.GraphModel.RouterDef;
import net.rka.ai.graph.processor.GraphModel.SubgraphDef;
import net.rka.ai.graph.processor.GraphModel.SubgraphsDef;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the Java source of a {@code <ClassName>Definition} class. Uses qualified names, so no imports.
 *
 * <p>How it works: it's plain string building with a small {@code line(...)} helper. The generated
 * class has a Javadoc outline of the graph (handy to compare with {@code docs/agent-graph.md}),
 * {@code NAME} and {@code NODES} constants, {@code stateGraph(graph)} which calls LangGraph4j's
 * {@code addNode} / {@code addEdge} / {@code addConditionalEdges} with lambdas that call the annotated
 * methods directly, and {@code compile(graph[, config])}. Fully qualified names everywhere avoid
 * import clashes with whatever the user's package contains. Open
 * {@code build/generated/sources/annotationProcessor/java/main/net/rka/ai/agent/AgentGraphDefinition.java}
 * to see the output.
 *
 * <p>Not implemented yet: a code-generation library such as JavaPoet would handle formatting and
 * escaping more robustly than string concatenation, at the cost of a dependency.
 */
final class GraphSourceWriter {

    private static final String LG = "org.bsc.langgraph4j.";

    private final GraphModel model;
    private final String packageName;
    private final String className;
    private final String graphType;
    private final String stateType;
    private final StringBuilder out = new StringBuilder();

    GraphSourceWriter(GraphModel model, String packageName, String className) {
        this.model = model;
        this.packageName = packageName;
        this.className = className;
        this.graphType = model.graphClass().getQualifiedName().toString();
        this.stateType = model.stateClass().getQualifiedName().toString();
    }

    /** {@code AgentGraph} -> {@code AgentGraphDefinition}; nested {@code Outer.Inner} -> {@code Outer_InnerDefinition}. */
    static String definitionName(TypeElement graphClass) {
        List<String> names = new ArrayList<>();
        for (Element e = graphClass; e instanceof TypeElement; e = e.getEnclosingElement()) {
            names.addFirst(e.getSimpleName().toString());
        }
        return String.join("_", names) + "Definition";
    }

    /** The complete Java file: header Javadoc with an outline, constants, stateGraph(), compile(), helper. */
    String source() {
        if (!packageName.isEmpty()) line("package " + packageName + ";").line("");
        line("/**")
                .line(" * Generated from {@link " + graphType + "}; edit the annotations there, not this file.")
                .line(" *")
                .line(" * <pre>");
        for (String row : outline()) line(" * " + escapeHtml(row));
        line(" * </pre>")
                .line(" */")
                // @Generated tells tools (and readers) not to edit this file by hand
                .line("@javax.annotation.processing.Generated(\"" + GraphProcessor.class.getName() + "\")")
                .line("public final class " + className + " {")
                .line("")
                .line("    /** The graph's name from {@code @Graph}. */")
                .line("    public static final String NAME = " + quote(model.name()) + ";")
                .line("")
                .line("    /** Node names, in declaration order. */")
                .line("    public static final java.util.List<String> NODES = java.util.List.of(" + nodeNameList() + ");")
                .line("")
                .line("    private " + className + "() {")
                .line("    }")
                .line("");
        writeStateGraph();
        writeCompile();
        writeNodeHelper();
        line("}");
        return out.toString();
    }

    /** stateGraph(graph): one builder chain — new StateGraph(schema, serializer).addNode(...).addEdge(...)... */
    private void writeStateGraph() {
        line("    /** Builds the uncompiled graph, e.g. to use it as a subgraph of another graph. */")
                .line("    public static " + LG + "StateGraph<" + stateType + "> stateGraph(" + graphType + " graph)")
                .line("            throws " + LG + "GraphStateException {")
                // With @Subgraphs the chain is kept in a variable, since more nodes are added in a loop
                .line("        " + (model.dynamicSubgraphs() != null ? LG + "StateGraph<" + stateType + "> result = " : "return ")
                        + "new " + LG + "StateGraph<" + stateType + ">(" + stateType + "." + model.schemaField()
                        + ", " + serializer() + ")");
        // Nodes: LangGraph4j wants (state, config) -> updates; adapt methods that only take the state
        for (NodeDef node : model.nodes()) {
            String action = node.takesConfig()
                    ? "graph::" + node.method().getSimpleName()
                    : "(state, config) -> graph." + node.method().getSimpleName() + "(state)";
            line("                .addNode(" + quote(node.name()) + ", node(graph, " + quote(node.name()) + ", " + action + "))");
        }
        // Subgraphs: the method returns a whole graph, added as a single node
        for (SubgraphDef subgraph : model.subgraphs()) {
            line("                .addNode(" + quote(subgraph.name()) + ", graph." + subgraph.method().getSimpleName() + "())");
        }
        // Fixed edges
        for (EdgeDef edge : model.edges()) {
            line("                .addEdge(" + nodeRef(edge.from()) + ", " + nodeRef(edge.to()) + ")");
        }
        SubgraphsDef dynamic = model.dynamicSubgraphs();
        if (dynamic != null) {
            writeRuntimeSubgraphs(dynamic);
            return;
        }
        // Routers: conditional edges — the router method's return value is looked up in the route map
        for (RouterDef router : model.routers()) {
            line("                .addConditionalEdges(" + nodeRef(router.from()) + ",")
                    .line("                        " + LG + "action.AsyncEdgeAction.<" + stateType + ">edge_async(graph::"
                            + router.method().getSimpleName() + "),")
                    .line("                        java.util.Map.ofEntries(" + routeEntries(router.routes()) + "))");
        }
        out.setLength(out.length() - 1); // end the builder chain on its last line
        line(";")
                .line("    }")
                .line("");
    }

    /**
     * The rest of stateGraph() for a graph with {@code @Subgraphs}: the builder chain so far becomes a
     * local variable, then each runtime entry gets its node and its edge back, and the routers follow as
     * separate statements. The router named in the annotation gets a mutable (insertion-ordered) route
     * map to which one route per entry is added; the other routers keep their fixed maps.
     */
    private void writeRuntimeSubgraphs(SubgraphsDef dynamic) {
        out.setLength(out.length() - 1); // end the builder chain on its last line
        line(";");
        String prefix = quote(dynamic.prefix());
        // The method is called once (it may build graphs from files); node and route names come from its keys
        line("        var runtimeSubgraphs = graph." + dynamic.method().getSimpleName() + "();")
                .line("        for (var entry : runtimeSubgraphs.entrySet()) {")
                .line("            String name = " + prefix + " + entry.getKey();")
                .line("            result.addNode(name, entry.getValue());")
                .line("            result.addEdge(name, " + nodeRef(dynamic.returnTo()) + ");")
                .line("        }");
        for (RouterDef router : model.routers()) {
            String routes = "java.util.Map.ofEntries(" + routeEntries(router.routes()) + ")";
            if (router.method().equals(dynamic.router())) {
                line("        java.util.Map<String, String> routes = new java.util.LinkedHashMap<>(" + routes + ");")
                        .line("        for (String key : runtimeSubgraphs.keySet()) {")
                        .line("            routes.put(" + prefix + " + key, " + prefix + " + key);")
                        .line("        }");
                routes = "routes";
            }
            line("        result.addConditionalEdges(" + nodeRef(router.from()) + ",")
                    .line("                " + LG + "action.AsyncEdgeAction.<" + stateType + ">edge_async(graph::"
                            + router.method().getSimpleName() + "),")
                    .line("                " + routes + ");");
        }
        line("        return result;")
                .line("    }")
                .line("");
    }

    /** compile(graph) and compile(graph, config): stateGraph() plus LangGraph4j's compile step. */
    private void writeCompile() {
        line("    /** Builds and compiles the graph with default settings. */")
                .line("    public static " + LG + "CompiledGraph<" + stateType + "> compile(" + graphType + " graph)")
                .line("            throws " + LG + "GraphStateException {")
                .line("        return stateGraph(graph).compile();")
                .line("    }")
                .line("")
                .line("    /** Builds and compiles the graph, e.g. with a checkpoint saver or interrupts. */")
                .line("    public static " + LG + "CompiledGraph<" + stateType + "> compile(" + graphType + " graph, "
                        + LG + "CompileConfig config)")
                .line("            throws " + LG + "GraphStateException {")
                .line("        return stateGraph(graph).compile(config);")
                .line("    }")
                .line("");
    }

    /**
     * node(graph, name, action): applies the @NodeWrapper (if any) and makes the action async,
     * which is the form LangGraph4j's addNode expects.
     */
    private void writeNodeHelper() {
        String action = LG + "action.NodeActionWithConfig<" + stateType + ">";
        String wrapped = model.wrapper() == null
                ? "action"
                : "graph." + model.wrapper().getSimpleName() + "(name, action)";
        line("    private static " + LG + "action.AsyncNodeActionWithConfig<" + stateType + "> node(")
                .line("            " + graphType + " graph, String name, " + action + " action) {")
                .line("        return " + LG + "action.AsyncNodeActionWithConfig.node_async(" + wrapped + ");")
                .line("    }");
    }

    /** How LangGraph4j copies/checkpoints the state; LangChain4j messages need their own serializer. */
    private String serializer() {
        String factory = stateType + "::new"; // the state's Map constructor
        return model.serializer() == Graph.Serializer.LANGCHAIN4J
                ? "new " + LG + "langchain4j.serializer.std.LC4jStateSerializer<" + stateType + ">(" + factory + ")"
                : "new " + LG + "serializer.std.ObjectStreamStateSerializer<" + stateType + ">(" + factory + ")";
    }

    /** A plain-text picture of the graph for the class Javadoc. */
    private List<String> outline() {
        List<String> rows = new ArrayList<>();
        for (EdgeDef edge : model.edges()) rows.add(display(edge.from()) + " -> " + display(edge.to()));
        for (RouterDef router : model.routers()) {
            List<String> routes = new ArrayList<>();
            for (RouteDef route : router.routes()) routes.add(display(route.on()) + ": " + display(route.to()));
            SubgraphsDef extra = model.dynamicSubgraphs();
            if (extra != null && router.method().equals(extra.router())) {
                routes.add(extra.prefix() + "*: " + extra.prefix() + "*"); // one route per runtime subgraph
            }
            rows.add(display(router.from()) + " -> " + router.method().getSimpleName() + "() {" + String.join(", ", routes) + "}");
        }
        for (SubgraphDef subgraph : model.subgraphs()) rows.add(subgraph.name() + " = subgraph " + subgraph.method().getSimpleName() + "()");
        SubgraphsDef dynamic = model.dynamicSubgraphs();
        if (dynamic != null) {
            rows.add(dynamic.prefix() + "* = subgraphs " + dynamic.method().getSimpleName() + "()");
        }
        return rows;
    }

    private String nodeNameList() {
        List<String> names = new ArrayList<>();
        for (NodeDef node : model.nodes()) names.add(quote(node.name()));
        for (SubgraphDef subgraph : model.subgraphs()) names.add(quote(subgraph.name()));
        return String.join(", ", names);
    }

    private String routeEntries(List<RouteDef> routes) {
        List<String> entries = new ArrayList<>();
        for (RouteDef route : routes) {
            entries.add("java.util.Map.entry(" + nodeRef(route.on()) + ", " + nodeRef(route.to()) + ")");
        }
        return String.join(", ", entries);
    }

    /** A node name as Java source: START/END become LangGraph4j's constants, others a string literal. */
    private static String nodeRef(String name) {
        if (name.equals(Graph.START)) return LG + "StateGraph.START";
        if (name.equals(Graph.END)) return LG + "StateGraph.END";
        return quote(name);
    }

    private static String display(String name) {
        return name.equals(Graph.START) ? "START" : name.equals(Graph.END) ? "END" : name;
    }

    /** A Java string literal for any text: quotes, backslashes and control characters escaped. */
    private static String quote(String s) {
        StringBuilder q = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> q.append("\\\"");
                case '\\' -> q.append("\\\\");
                case '\n' -> q.append("\\n");
                case '\r' -> q.append("\\r");
                case '\t' -> q.append("\\t");
                default -> q.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return q.append('"').toString();
    }

    /** Safe inside the Javadoc: "->" etc. must not become HTML, and a stray "* /" would end the comment. */
    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("*/", "*&#47;");
    }

    private GraphSourceWriter line(String text) {
        out.append(text).append('\n');
        return this;
    }
}
