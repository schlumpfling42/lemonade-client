package net.rka.ai.graph.processor;

import net.rka.ai.graph.annotations.Edge;
import net.rka.ai.graph.annotations.Edges;
import net.rka.ai.graph.annotations.Graph;
import net.rka.ai.graph.annotations.Node;
import net.rka.ai.graph.annotations.NodeWrapper;
import net.rka.ai.graph.annotations.Route;
import net.rka.ai.graph.annotations.Router;
import net.rka.ai.graph.annotations.Subgraph;
import net.rka.ai.graph.annotations.Subgraphs;
import net.rka.ai.graph.processor.GraphModel.EdgeDef;
import net.rka.ai.graph.processor.GraphModel.NodeDef;
import net.rka.ai.graph.processor.GraphModel.RouteDef;
import net.rka.ai.graph.processor.GraphModel.RouterDef;
import net.rka.ai.graph.processor.GraphModel.SubgraphDef;
import net.rka.ai.graph.processor.GraphModel.SubgraphsDef;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates {@link Graph} classes at compile time and generates a
 * {@code <ClassName>Definition} class that builds the LangGraph4j {@code StateGraph}.
 * Every problem is reported as a compile error on the element that causes it.
 *
 * <h2>How an annotation processor works</h2>
 * javac finds this class through {@code META-INF/services/javax.annotation.processing.Processor}
 * (in this module's resources) when it's on the {@code annotationProcessor} path. During
 * compilation javac calls {@link #process} in "rounds": each round hands over the source elements
 * carrying the annotations listed in {@link #getSupportedAnnotationTypes}. Generated sources are
 * compiled in a later round, together with the rest.
 * <p>
 * The processor never loads or runs the user's classes; it inspects their <em>declarations</em>
 * through the {@code javax.lang.model} API: {@code TypeElement} (a class), {@code ExecutableElement}
 * (a method), {@code TypeMirror} (a type such as {@code Map<String, Object>}). That's also why
 * LangGraph4j types are referred to by name strings below: the processor doesn't depend on LangGraph4j.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>{@link #checkMembersOutsideGraphs}: {@code @Node} etc. outside a {@code @Graph} class is an error.</li>
 *   <li>For each {@code @Graph} class, {@link Reader#read} collects nodes, subgraphs, routers, edges and
 *       the wrapper, checking each method's signature and name, then {@link Reader#checkWiring} checks
 *       the graph as a whole (every target exists, START has an edge, every node has a way out, all
 *       nodes are reachable, END is reachable). Any error makes it return null.</li>
 *   <li>{@link #write} asks {@link GraphSourceWriter} for the Java text and saves it with the
 *       {@code Filer}, which puts it under {@code build/generated/sources/annotationProcessor}.</li>
 * </ol>
 * Unknown names get a "did you mean" hint from {@link #suggest}, based on edit distance.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Incremental-processing metadata (Gradle's "isolating" processor category), so changing one
 *       graph doesn't force a full recompile.</li>
 *   <li>Checking that routers handle every value they can return (see {@code Graph}).</li>
 *   <li>Emitting a Mermaid diagram file for documentation.</li>
 * </ul>
 */
public class GraphProcessor extends AbstractProcessor {

    private static final String AGENT_STATE = "org.bsc.langgraph4j.state.AgentState";
    private static final String RUNNABLE_CONFIG = "org.bsc.langgraph4j.RunnableConfig";
    private static final String STATE_GRAPH = "org.bsc.langgraph4j.StateGraph";
    private static final String COMPILED_GRAPH = "org.bsc.langgraph4j.CompiledGraph";
    private static final String NODE_ACTION_WITH_CONFIG = "org.bsc.langgraph4j.action.NodeActionWithConfig";

    private Messager messager;
    private Types types;

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(Graph.class.getCanonicalName(), Edge.class.getCanonicalName(),
                Edges.class.getCanonicalName(), Node.class.getCanonicalName(),
                Router.class.getCanonicalName(), Subgraph.class.getCanonicalName(),
                Subgraphs.class.getCanonicalName(), NodeWrapper.class.getCanonicalName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /** javac calls this once per round with the elements that carry our annotations. */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        messager = processingEnv.getMessager();   // how we report errors/warnings to javac
        types = processingEnv.getTypeUtils();     // type comparisons (subtype, same type, erasure)

        checkMembersOutsideGraphs(round);
        // typesIn keeps only classes (the elements annotated with @Graph)
        for (TypeElement graphClass : ElementFilter.typesIn(round.getElementsAnnotatedWith(Graph.class))) {
            GraphModel model = new Reader(graphClass).read(); // null if any check failed (errors already reported)
            if (model != null) write(model);
        }
        return true; // "claimed": no other processor needs to handle these annotations
    }

    /** {@code @Node} and friends only mean something inside a {@code @Graph} class. */
    private void checkMembersOutsideGraphs(RoundEnvironment round) {
        for (Class<? extends java.lang.annotation.Annotation> type :
                List.of(Node.class, Router.class, Subgraph.class, Subgraphs.class, NodeWrapper.class)) {
            for (Element e : round.getElementsAnnotatedWith(type)) {
                if (e.getEnclosingElement().getAnnotation(Graph.class) == null) {
                    error(e, "@" + type.getSimpleName() + " is only allowed in a class annotated with @Graph");
                }
            }
        }
        for (Element e : round.getElementsAnnotatedWithAny(Set.of(Edge.class, Edges.class))) {
            if (e.getAnnotation(Graph.class) == null) {
                error(e, "@Edge is only allowed on a class annotated with @Graph");
            }
        }
    }

    /** Generates {@code <Graph>Definition.java} in the graph class's package. */
    private void write(GraphModel model) {
        String packageName = processingEnv.getElementUtils().getPackageOf(model.graphClass()).getQualifiedName().toString();
        String simpleName = GraphSourceWriter.definitionName(model.graphClass());
        String qualified = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        // The Filer creates the file in the generated-sources folder; passing the graph class as the
        // "originating element" tells incremental builds which source the output came from
        try (Writer out = processingEnv.getFiler().createSourceFile(qualified, model.graphClass()).openWriter()) {
            out.write(new GraphSourceWriter(model, packageName, simpleName).source());
        } catch (IOException e) {
            error(model.graphClass(), "Could not write " + qualified + ": " + e.getMessage());
        }
    }

    /** A compile error attached to the element, so the IDE underlines exactly that annotation or method. */
    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void warning(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.WARNING, message, element);
    }

    /** Reads and validates one graph class; returns null if anything is wrong. */
    private final class Reader {

        private final TypeElement graphClass;
        private boolean failed;

        Reader(TypeElement graphClass) {
            this.graphClass = graphClass;
        }

        GraphModel read() {
            Graph graph = graphClass.getAnnotation(Graph.class);
            checkGraphClass();
            TypeElement stateClass = readStateClass(graph); // checks the state class, too

            List<NodeDef> nodes = new ArrayList<>();
            List<SubgraphDef> subgraphs = new ArrayList<>();
            List<RouterDef> routers = new ArrayList<>();
            ExecutableElement wrapper = null;
            ExecutableElement subgraphsMethod = null; // the @Subgraphs method, if any (checked after the loop)
            Map<String, Element> nodeNames = new HashMap<>(); // node and subgraph names → where declared

            // Every method declared in the graph class; only annotated ones matter
            for (ExecutableElement method : ElementFilter.methodsIn(graphClass.getEnclosedElements())) {
                Node node = method.getAnnotation(Node.class);
                Subgraph subgraph = method.getAnnotation(Subgraph.class);
                Subgraphs runtimeSubgraphs = method.getAnnotation(Subgraphs.class);
                Router router = method.getAnnotation(Router.class);
                NodeWrapper nodeWrapper = method.getAnnotation(NodeWrapper.class);
                // A method plays at most one role
                int roles = (node != null ? 1 : 0) + (subgraph != null ? 1 : 0) + (runtimeSubgraphs != null ? 1 : 0)
                        + (router != null ? 1 : 0) + (nodeWrapper != null ? 1 : 0);
                if (roles > 1) {
                    fail(method, "A method can have only one of @Node, @Subgraph, @Subgraphs, @Router, @NodeWrapper");
                    continue;
                }
                if (roles == 0) continue;
                checkCallable(method);

                if (node != null && checkNodeName(node.value(), method, nodeNames)) {
                    boolean takesConfig = checkNodeSignature(method, stateClass);
                    nodes.add(new NodeDef(node.value(), method, takesConfig));
                } else if (subgraph != null && checkNodeName(subgraph.value(), method, nodeNames)) {
                    checkSubgraphSignature(method, stateClass);
                    subgraphs.add(new SubgraphDef(subgraph.value(), method));
                } else if (runtimeSubgraphs != null) {
                    if (subgraphsMethod != null) {
                        fail(method, "Only one @Subgraphs is allowed per graph (already: " + subgraphsMethod.getSimpleName() + ")");
                    } else {
                        checkSubgraphsSignature(method, stateClass);
                        subgraphsMethod = method;
                    }
                } else if (router != null) {
                    checkRouterSignature(method, stateClass);
                    List<RouteDef> routes = new ArrayList<>();
                    for (Route route : router.routes()) routes.add(new RouteDef(route.on(), route.to()));
                    routers.add(new RouterDef(router.from(), method, routes));
                } else if (nodeWrapper != null) {
                    if (wrapper != null) {
                        fail(method, "Only one @NodeWrapper is allowed per graph (already: " + wrapper.getSimpleName() + ")");
                    } else {
                        checkWrapperSignature(method, stateClass);
                        wrapper = method;
                    }
                }
            }

            // getAnnotationsByType unpacks repeated @Edge annotations (stored in an @Edges container)
            List<EdgeDef> edges = new ArrayList<>();
            for (Edge edge : graphClass.getAnnotationsByType(Edge.class)) {
                edges.add(new EdgeDef(edge.from(), edge.to()));
            }
            checkWiring(nodeNames, edges, routers); // the graph as a whole
            SubgraphsDef dynamicSubgraphs = subgraphsMethod == null ? null : readSubgraphs(subgraphsMethod, nodeNames, routers);

            if (failed) return null; // errors were reported; don't generate broken code on top
            return new GraphModel(graphClass, graph.name(), stateClass, graph.schema(), graph.serializer(),
                    nodes, subgraphs, dynamicSubgraphs, edges, routers, wrapper);
        }

        /**
         * Resolves the names in {@code @Subgraphs}: {@code returnTo} must be a node (or END) and
         * {@code router} the name of a {@code @Router} method of this class. Null if either is wrong.
         */
        private SubgraphsDef readSubgraphs(ExecutableElement method, Map<String, Element> nodeNames,
                                           List<RouterDef> routers) {
            Subgraphs annotation = method.getAnnotation(Subgraphs.class);
            String label = "@Subgraphs on " + method.getSimpleName() + "()";
            String returnTo = annotation.returnTo();
            boolean returnKnown = returnTo.equals(Graph.END) || nodeNames.containsKey(returnTo);
            if (!returnKnown) {
                fail(method, label + ": returnTo '" + returnTo + "' is not a node of this graph"
                        + suggest(returnTo, nodeNames.keySet()));
            }
            RouterDef router = routers.stream()
                    .filter(r -> r.method().getSimpleName().contentEquals(annotation.router())).findFirst().orElse(null);
            if (router == null) {
                Set<String> routerNames = new LinkedHashSet<>();
                for (RouterDef r : routers) routerNames.add(r.method().getSimpleName().toString());
                fail(method, label + ": router '" + annotation.router() + "' is not a @Router method of this graph"
                        + suggest(annotation.router(), routerNames));
            }
            return returnKnown && router != null
                    ? new SubgraphsDef(annotation.prefix(), returnTo, router.method(), method) : null;
        }

        private void checkGraphClass() {
            if (!graphClass.getTypeParameters().isEmpty()) {
                fail(graphClass, "@Graph classes can't be generic");
            }
            for (Element e = graphClass; e instanceof TypeElement; e = e.getEnclosingElement()) {
                if (e.getModifiers().contains(Modifier.PRIVATE)) {
                    fail(graphClass, "@Graph classes can't be private (or nested in a private class)");
                    break;
                }
            }
            if (graph().name().isBlank()) fail(graphClass, "@Graph name must not be blank");
        }

        private Graph graph() {
            return graphClass.getAnnotation(Graph.class);
        }

        /** {@code state} is a Class value, so it has to be read from the annotation mirror. */
        private TypeElement readStateClass(Graph graph) {
            // Reading graph.state() directly would throw MirroredTypeException (the class isn't loaded at
            // compile time), so walk the raw annotation values instead
            TypeMirror stateType = null;
            for (AnnotationMirror mirror : graphClass.getAnnotationMirrors()) {
                if (!((TypeElement) mirror.getAnnotationType().asElement()).getQualifiedName()
                        .contentEquals(Graph.class.getCanonicalName())) continue;
                for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                        : mirror.getElementValues().entrySet()) {
                    if (entry.getKey().getSimpleName().contentEquals("state")) {
                        stateType = (TypeMirror) entry.getValue().getValue();
                    }
                }
            }
            if (!(stateType instanceof DeclaredType declared)) {
                fail(graphClass, "@Graph state must be a class");
                return null;
            }
            TypeElement state = (TypeElement) declared.asElement();

            // Look LangGraph4j's AgentState up by name (null if it isn't on the classpath, then skip the check);
            // erasure() drops generics so the subtype check compares raw classes
            TypeElement agentState = processingEnv.getElementUtils().getTypeElement(AGENT_STATE);
            if (agentState != null && !types.isSubtype(types.erasure(state.asType()), types.erasure(agentState.asType()))) {
                fail(graphClass, "@Graph state " + state.getSimpleName() + " must extend " + AGENT_STATE);
            }
            if (!state.getTypeParameters().isEmpty()) {
                fail(graphClass, "@Graph state " + state.getSimpleName() + " can't be generic");
            }
            // The generated class lives in the graph's package: package-private members are fine there
            boolean samePackage = packageOf(state).equals(packageOf(graphClass));

            // LangGraph4j creates state objects with new State(Map) — it must exist and be reachable
            boolean hasConstructor = false;
            for (ExecutableElement ctor : ElementFilter.constructorsIn(state.getEnclosedElements())) {
                if (ctor.getParameters().size() == 1 && isType(ctor.getParameters().get(0).asType(), "java.util.Map")
                        && accessible(ctor, samePackage)) {
                    hasConstructor = true;
                }
            }
            if (!hasConstructor) {
                fail(graphClass, "State " + state.getSimpleName() + " needs a "
                        + (samePackage ? "non-private" : "public") + " constructor taking Map<String, Object>");
            }

            // The generated code passes State.SCHEMA (or the configured field) to new StateGraph(...)
            String schema = graph.schema();
            VariableElement field = ElementFilter.fieldsIn(state.getEnclosedElements()).stream()
                    .filter(f -> f.getSimpleName().contentEquals(schema)).findFirst().orElse(null);
            if (field == null || !field.getModifiers().contains(Modifier.STATIC) || !accessible(field, samePackage)) {
                fail(graphClass, "State " + state.getSimpleName() + " needs a "
                        + (samePackage ? "non-private" : "public") + " static field '" + schema
                        + "' of type Map<String, Channel<?>>");
            } else if (!isType(field.asType(), "java.util.Map")) {
                fail(field, "Schema field '" + schema + "' must be a Map<String, Channel<?>>");
            }
            return state;
        }

        private boolean accessible(Element element, boolean samePackage) {
            Set<Modifier> modifiers = element.getModifiers();
            return samePackage ? !modifiers.contains(Modifier.PRIVATE) : modifiers.contains(Modifier.PUBLIC);
        }

        private void checkCallable(ExecutableElement method) {
            if (method.getModifiers().contains(Modifier.PRIVATE)) {
                fail(method, "Graph methods can't be private: the generated Definition class calls them");
            }
            if (method.getModifiers().contains(Modifier.STATIC)) {
                fail(method, "Graph methods must be instance methods, not static");
            }
            if (!method.getTypeParameters().isEmpty()) {
                fail(method, "Graph methods can't be generic");
            }
        }

        private boolean checkNodeName(String name, ExecutableElement method, Map<String, Element> names) {
            if (name.isBlank()) {
                fail(method, "Node name must not be blank");
                return false;
            }
            if (name.equals(Graph.START) || name.equals(Graph.END)) {
                fail(method, "'" + name + "' is reserved and can't be a node name");
                return false;
            }
            Element previous = names.putIfAbsent(name, method); // returns the earlier owner if the name is taken
            if (previous != null) {
                fail(method, "Duplicate node name '" + name + "' (also on " + previous.getSimpleName() + "())");
                return false;
            }
            return true;
        }

        /** {@code Map<String, Object> m(S)} or {@code m(S, RunnableConfig)}; returns whether it takes the config. */
        private boolean checkNodeSignature(ExecutableElement method, TypeElement state) {
            List<? extends VariableElement> params = method.getParameters();
            boolean ok = (params.size() == 1 || params.size() == 2)
                    && isState(params.get(0).asType(), state)
                    && (params.size() == 1 || isType(params.get(1).asType(), RUNNABLE_CONFIG))
                    && isType(method.getReturnType(), "java.util.Map");
            if (!ok) {
                fail(method, "@Node methods must look like 'Map<String, Object> " + method.getSimpleName() + "("
                        + stateName(state) + " state)' or take (" + stateName(state) + " state, RunnableConfig config)");
            }
            return params.size() == 2;
        }

        private void checkRouterSignature(ExecutableElement method, TypeElement state) {
            List<? extends VariableElement> params = method.getParameters();
            if (params.size() != 1 || !isState(params.get(0).asType(), state)
                    || !isType(method.getReturnType(), "java.lang.String")) {
                fail(method, "@Router methods must look like 'String " + method.getSimpleName() + "("
                        + stateName(state) + " state)'");
            }
        }

        private void checkSubgraphSignature(ExecutableElement method, TypeElement state) {
            TypeMirror returned = method.getReturnType();
            boolean graphType = isType(returned, STATE_GRAPH) || isType(returned, COMPILED_GRAPH);
            // A subgraph shares the parent's state, so its type argument must be the same state class
            boolean sameState = graphType && returned instanceof DeclaredType declared
                    && declared.getTypeArguments().size() == 1
                    && isState(declared.getTypeArguments().get(0), state);
            if (!method.getParameters().isEmpty() || !graphType || !sameState) {
                fail(method, "@Subgraph methods must take no parameters and return StateGraph<"
                        + stateName(state) + "> or CompiledGraph<" + stateName(state) + ">");
            }
        }

        private void checkSubgraphsSignature(ExecutableElement method, TypeElement state) {
            // Map<String, StateGraph<S>> or Map<String, CompiledGraph<S>>, with S the parent's state class
            boolean ok = false;
            if (method.getParameters().isEmpty() && isType(method.getReturnType(), "java.util.Map")
                    && method.getReturnType() instanceof DeclaredType map && map.getTypeArguments().size() == 2
                    && isType(map.getTypeArguments().get(0), "java.lang.String")
                    && map.getTypeArguments().get(1) instanceof DeclaredType value
                    && (isType(value, STATE_GRAPH) || isType(value, COMPILED_GRAPH))
                    && value.getTypeArguments().size() == 1) {
                ok = isState(value.getTypeArguments().get(0), state);
            }
            if (!ok) {
                fail(method, "@Subgraphs methods must take no parameters and return Map<String, StateGraph<"
                        + stateName(state) + ">> or Map<String, CompiledGraph<" + stateName(state) + ">>");
            }
        }

        private void checkWrapperSignature(ExecutableElement method, TypeElement state) {
            List<? extends VariableElement> params = method.getParameters();
            if (params.size() != 2 || !isType(params.get(0).asType(), "java.lang.String")
                    || !isType(params.get(1).asType(), NODE_ACTION_WITH_CONFIG)
                    || !isType(method.getReturnType(), NODE_ACTION_WITH_CONFIG)) {
                String action = "NodeActionWithConfig<" + stateName(state) + ">";
                fail(method, "@NodeWrapper methods must look like '" + action + " " + method.getSimpleName()
                        + "(String nodeName, " + action + " action)'");
            }
        }

        /** Edges and routers: every name exists, one way out of each node, everything reachable. */
        private void checkWiring(Map<String, Element> nodes, List<EdgeDef> edges, List<RouterDef> routers) {
            // The graph as an adjacency list: node -> nodes it can go to next (via an edge or any route)
            Map<String, List<String>> next = new HashMap<>();
            Map<String, String> outgoing = new HashMap<>(); // source -> description of its way out

            // Pass 1: fixed edges — names exist, START/END used correctly, one way out per node
            for (EdgeDef edge : edges) {
                String label = "@Edge(from = \"" + edge.from() + "\", to = \"" + edge.to() + "\")";
                boolean fromKnown = edge.from().equals(Graph.START) || nodes.containsKey(edge.from());
                boolean toKnown = edge.to().equals(Graph.END) || nodes.containsKey(edge.to());
                if (!fromKnown) {
                    fail(graphClass, label + ": unknown node '" + edge.from() + "'" + suggest(edge.from(), nodes.keySet()));
                }
                if (!toKnown) {
                    fail(graphClass, label + ": unknown node '" + edge.to() + "'" + suggest(edge.to(), nodes.keySet()));
                }
                if (edge.from().equals(Graph.END)) fail(graphClass, label + ": edges can't start at END");
                if (edge.to().equals(Graph.START)) fail(graphClass, label + ": edges can't lead to START");
                if (!fromKnown) continue;
                // Count the way out even if its target is misspelled, so only the typo is reported
                String previous = outgoing.putIfAbsent(edge.from(), label);
                if (previous != null) {
                    fail(graphClass, label + ": '" + edge.from() + "' already continues with " + previous
                            + ". Use one @Edge or one @Router per node");
                }
                if (toKnown) next.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
            }

            // Pass 2: routers — source exists, routes are unique and lead to known nodes
            for (RouterDef router : routers) {
                String label = "@Router(from = \"" + router.from() + "\") on " + router.method().getSimpleName() + "()";
                if (!router.from().equals(Graph.START) && !nodes.containsKey(router.from())) {
                    fail(router.method(), label + ": unknown node '" + router.from() + "'" + suggest(router.from(), nodes.keySet()));
                    continue;
                }
                if (router.routes().isEmpty()) fail(router.method(), label + ": needs at least one @Route");
                Set<String> values = new HashSet<>();
                for (RouteDef route : router.routes()) {
                    if (!values.add(route.on())) {
                        fail(router.method(), label + ": duplicate route for return value '" + route.on() + "'");
                    }
                    if (!route.to().equals(Graph.END) && !nodes.containsKey(route.to())) {
                        fail(router.method(), label + ": route '" + route.on() + "' goes to unknown node '"
                                + route.to() + "'" + suggest(route.to(), nodes.keySet()));
                    }
                    next.computeIfAbsent(router.from(), k -> new ArrayList<>()).add(route.to());
                }
                String previous = outgoing.putIfAbsent(router.from(), label);
                if (previous != null) {
                    fail(router.method(), label + ": '" + router.from() + "' already continues with " + previous
                            + ". Use one @Edge or one @Router per node");
                }
            }

            // Pass 3: entry point and dead ends
            if (!outgoing.containsKey(Graph.START)) {
                fail(graphClass, "The graph has no entry point. Add @Edge(from = Graph.START, to = \"...\") "
                        + "or a @Router(from = Graph.START, ...)");
            }
            for (Map.Entry<String, Element> node : nodes.entrySet()) {
                if (!outgoing.containsKey(node.getKey())) {
                    fail(node.getValue(), "Node '" + node.getKey() + "' has no way out. Add @Edge(from = \""
                            + node.getKey() + "\", to = ...) or a @Router(from = \"" + node.getKey() + "\", ...)");
                }
            }
            if (failed) return;

            // Reachability from START: a breadth-first search over the "next" map built above.
            // Every node it never visits is dead code in the graph.
            Set<String> reached = new LinkedHashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>(List.of(Graph.START));
            while (!queue.isEmpty()) {
                for (String target : next.getOrDefault(queue.poll(), List.of())) {
                    if (reached.add(target)) queue.add(target);
                }
            }
            for (Map.Entry<String, Element> node : nodes.entrySet()) {
                if (!reached.contains(node.getKey())) {
                    fail(node.getValue(), "Node '" + node.getKey() + "' can't be reached from START");
                }
            }
            if (!reached.contains(Graph.END)) {
                warning(graphClass, "No path leads to END, so this graph never finishes on its own");
            }
        }

        private boolean isState(TypeMirror type, TypeElement state) {
            return state != null && types.isSameType(types.erasure(type), types.erasure(state.asType()));
        }

        private boolean isType(TypeMirror type, String qualifiedName) {
            if (type.getKind() != TypeKind.DECLARED) return false;
            Element element = types.asElement(type);
            return element instanceof TypeElement t && t.getQualifiedName().contentEquals(qualifiedName);
        }

        private String stateName(TypeElement state) {
            return state == null ? "State" : state.getSimpleName().toString();
        }

        private String packageOf(Element element) {
            return processingEnv.getElementUtils().getPackageOf(element).getQualifiedName().toString();
        }

        private void fail(Element element, String message) {
            failed = true;
            error(element, message);
        }
    }

    /** "Did you mean ...?" for a misspelled node name. */
    static String suggest(String name, Set<String> candidates) {
        // Closest known name by edit distance; only suggested if it's reasonably close
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = editDistance(name.toLowerCase(), candidate.toLowerCase());
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best != null && bestDistance <= Math.max(2, name.length() / 3) ? ". Did you mean '" + best + "'?" : "";
    }

    /**
     * Levenshtein distance: the fewest single-character insertions, deletions or substitutions that
     * turn {@code a} into {@code b} ("agnet" → "agent" is 2). Classic dynamic programming, keeping only
     * two rows of the table at a time.
     */
    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
