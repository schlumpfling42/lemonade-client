package net.rka.ai.tools.code.java;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreeScanner;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LabeledStatementTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.SynchronizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The statements inside the bodies of a file's members, as {@link Statement}s with their lines and the variables they
 * declare. The JDK's parser reads the text (only parsed, nothing is looked up, so no classpath is needed); a body is
 * a list of statements, a compound statement holds its statements in named {@link Part}s. Built when asked for, once per
 * text: the result is kept while the text is the same.
 */
public final class JavaBlocks {

    private JavaBlocks() {
    }

    /**
     * A member's body.
     *
     * @param span       the lines of the body's braces (the first line holds the opening brace)
     * @param parameters the parameters, visible in the whole body
     * @param statements the statements, in order
     */
    public record Body(Span span, List<Variable> parameters, List<Statement> statements) {
        static final Body NONE = new Body(Span.NONE, List.of(), List.of());

        public Body {
            parameters = List.copyOf(parameters);
            statements = List.copyOf(statements);
        }
    }

    private record Found(int startLine, int endLine, Body body) {
    }

    private static final Map<String, List<Found>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * The body of a method, constructor or initializer block read from {@code root}'s source; empty for other members and for
     * a method without a body.
     *
     * @throws IllegalArgumentException when the source is not valid Java
     */
    public static Body bodyOf(JavaClass root, JavaMember member) {
        if (!(member instanceof JavaMethod || member instanceof JavaConstructor || member instanceof JavaInitializer)
                || member.startLine() < 1 || root.source().isEmpty()) {
            return Body.NONE;
        }
        for (Found found : CACHE.computeIfAbsent(root.source(), JavaBlocks::parse)) {
            if (found.startLine() >= member.startLine() && found.endLine() <= member.endLine()) return found.body();
        }
        return Body.NONE;
    }

    /**
     * The variables a statement can use at its first line: the parameters and every variable declared before it whose scope
     * covers it (an enclosing loop's, a variable above it in its block or in a block around it). Fields are not included.
     */
    public static List<Variable> visibleBefore(Body body, Statement statement) {
        List<Variable> all = new ArrayList<>(body.parameters());
        collect(body.statements(), all);
        int line = statement.startLine();
        return all.stream().filter(v -> v.scope().start() <= line && line <= v.scope().end() && v.line() < line)
                .collect(java.util.stream.Collectors.toMap(v -> v.name() + "@" + v.line(), v -> v, (first, second) -> first, java.util.LinkedHashMap::new)).values().stream().toList();
    }

    private static void collect(List<Statement> statements, List<Variable> out) {
        for (Statement s : statements) {
            out.addAll(s.declares());
            for (Part part : s.parts()) collect(part.statements(), out);
        }
    }

    /** Every statement of the body, outermost first, in the order of the source. */
    public static List<Statement> flatten(Body body) {
        List<Statement> all = new ArrayList<>();
        flatten(body.statements(), all);
        return all;
    }

    private static void flatten(List<Statement> statements, List<Statement> out) {
        for (Statement s : statements) {
            out.add(s);
            for (Part part : s.parts()) flatten(part.statements(), out);
        }
    }

    // ---- addresses ----

    /**
     * Where an address points in a body: the list of statements it is in, the index in it, and the part and statement around it.
     * An address is the statement's number in the body and then, for each level down, the part's name and the statement's
     * number in it, joined by {@code /}: {@code 2}, {@code 2/body/1}, {@code 5/catch IOException e/1}. An address that ends with
     * a part's name ({@code 2/body}) means the part itself, one that is empty means the body.
     *
     * @param statement the statement the address names, or null for a part or the body
     * @param index     the number of the statement in {@code container} counted from 0 (for a part: the number of statements in it)
     * @param container the statements the address is a number of
     * @param part      the part the container is, or null for the body
     * @param parent    the statement that holds the part, or null for the body
     */
    public record Located(Statement statement, int index, List<Statement> container, Part part, Statement parent) {
    }

    /**
     * @throws IllegalArgumentException when nothing is at the address (the message says what is there)
     */
    public static Located locate(Body body, String address) {
        String[] segments = address == null || address.isBlank() ? new String[0] : address.strip().split("/");
        List<Statement> level = body.statements();
        Part part = null;
        Statement parent = null;
        int i = 0;
        while (i < segments.length) {
            int number;
            try {
                number = Integer.parseInt(segments[i].strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + segments[i] + "' is not a statement number in the address " + address + ": " + what(level));
            }
            if (number < 1 || number > level.size()) {
                throw new IllegalArgumentException("There is no statement " + number + " here, there are " + level.size() + ": " + what(level));
            }
            Statement hit = level.get(number - 1);
            i++;
            if (i == segments.length) return new Located(hit, number - 1, level, part, parent);
            Part next = partNamed(hit, segments[i].strip());
            i++;
            if (i == segments.length) return new Located(null, next.statements().size(), next.statements(), next, hit);
            parent = hit;
            part = next;
            level = next.statements();
        }
        return new Located(null, level.size(), level, part, parent);
    }

    private static Part partNamed(Statement statement, String name) {
        List<Part> fits = statement.parts().stream().filter(p -> p.name().equalsIgnoreCase(name)).toList();
        if (fits.isEmpty()) fits = statement.parts().stream().filter(p -> p.name().toLowerCase().startsWith(name.toLowerCase())).toList();
        if (fits.size() == 1) return fits.getFirst();
        String names = statement.parts().stream().map(Part::name).collect(java.util.stream.Collectors.joining(", "));
        throw new IllegalArgumentException(fits.isEmpty() ? "The " + statement.kind().name().toLowerCase() + " has no part '" + name + "', it has: " + names
                : "'" + name + "' fits more than one part: " + names);
    }

    private static String what(List<Statement> level) {
        return level.isEmpty() ? "nothing" : level.size() + " statement(s), the first is '" + level.getFirst().summary() + "'";
    }

    /**
     * The statements in the text as a method body: for counting what a piece of new code is made of.
     *
     * @throws IllegalArgumentException when the text is not statements
     */
    public static List<Statement> statementsOf(String code) {
        String source = "class X {\nvoid m() {\n" + code + "\n}\n}\n";
        JavaClass x = JavaSourceReader.read(source).getFirst();
        return bodyOf(x, x.methods().getFirst()).statements();
    }

    /** The statement and everything below it that covers the line, outermost first: where a line sits in a body. */
    public static List<Statement> pathTo(Body body, int line) {
        List<Statement> path = new ArrayList<>();
        List<Statement> level = body.statements();
        while (true) {
            Statement hit = level.stream().filter(s -> s.startLine() <= line && line <= s.endLine()).findFirst().orElse(null);
            if (hit == null) return path;
            path.add(hit);
            level = hit.children();
        }
    }

    // ---- parsing ----

    private static List<Found> parse(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("The statements need the JDK's compiler: run on a JDK, not a JRE");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavaFileObject object = new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source;
                }
            };
            JavacTask task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(object));
            CompilationUnitTree unit = task.parse().iterator().next();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    throw new IllegalArgumentException("Line " + d.getLineNumber() + ": " + d.getMessage(null));
                }
            }
            Reader reader = new Reader(source, unit, Trees.instance(task).getSourcePositions());
            List<Found> found = new ArrayList<>();
            unit.getTypeDecls().forEach(t -> {
                if (t instanceof ClassTree c) reader.members(c, found);
            });
            return found;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Reader {
        private final String[] lines;
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final LineMap lineMap;
        private final String source;

        Reader(String source, CompilationUnitTree unit, SourcePositions positions) {
            this.source = source;
            this.lines = source.split("\r?\n", -1);
            this.unit = unit;
            this.positions = positions;
            this.lineMap = unit.getLineMap();
        }

        private int startLine(Tree tree) {
            return (int) lineMap.getLineNumber(positions.getStartPosition(unit, tree));
        }

        private int endLine(Tree tree) {
            long end = positions.getEndPosition(unit, tree);
            return (int) lineMap.getLineNumber(Math.max(0, end - 1));
        }

        /** The bodies of the members of a type and of the types inside it (not of local and anonymous classes). */
        void members(ClassTree type, List<Found> out) {
            for (Tree member : type.getMembers()) {
                if (member instanceof MethodTree method && method.getBody() != null) {
                    List<Variable> parameters = new ArrayList<>();
                    Span bodySpan = new Span(startLine(method.getBody()), endLine(method.getBody()));
                    for (VariableTree p : method.getParameters()) {
                        parameters.add(new Variable(p.getName().toString(), p.getType() == null ? "" : p.getType().toString(),
                                Variable.Kind.PARAMETER, startLine(p), bodySpan));
                    }
                    out.add(new Found(startLine(method), endLine(method), new Body(bodySpan, parameters, block(method.getBody()))));
                } else if (member instanceof BlockTree initializer) {
                    out.add(new Found(startLine(initializer), endLine(initializer),
                            new Body(new Span(startLine(initializer), endLine(initializer)), List.of(), block(initializer))));
                } else if (member instanceof ClassTree nested) {
                    members(nested, out);
                }
            }
        }

        /** The statements of a block, with the variables they declare visible to the end of the block. */
        private List<Statement> block(BlockTree block) {
            return statements(block.getStatements(), endLine(block), startLine(block));
        }

        /** {@code floor}: the line above the first statement, so comments are not taken from above it. */
        private List<Statement> statements(List<? extends StatementTree> trees, int scopeEnd, int floor) {
            List<Statement> out = new ArrayList<>();
            for (StatementTree tree : trees) {
                Statement statement = statement(tree, scopeEnd, floor);
                if (statement == null) continue;
                out.add(statement);
                floor = statement.endLine();
            }
            return out;
        }

        /** The statement with the facts about its own expressions: the names it uses and assigns, its calls and lambdas. */
        private Statement statement(StatementTree tree, int scopeEnd, int floor) {
            Statement statement = build(tree, scopeEnd, floor);
            if (statement == null) return null;
            Scan scan = new Scan();
            if (tree instanceof VariableTree v) {
                scan.writes.add(v.getName().toString());
                if (v.getInitializer() != null) scan.scan(v.getInitializer(), null);
            } else {
                for (Tree own : ownExpressions(tree)) scan.scan(own, null);
            }
            if (scan.reads.isEmpty() && scan.writes.isEmpty() && scan.calls.isEmpty() && scan.lambdas.isEmpty()) return statement;
            List<Part> parts = new ArrayList<>(statement.parts());
            parts.addAll(scan.lambdas);
            List<Variable> declares = new ArrayList<>(statement.declares());
            declares.addAll(scan.lambdaParameters);
            return new Statement(statement.kind(), statement.startLine(), statement.endLine(), statement.summary(), parts, declares,
                    List.copyOf(scan.reads), List.copyOf(scan.writes), scan.calls);
        }

        /** The expressions that belong to the statement itself: not those of the statements inside it. */
        private List<? extends Tree> ownExpressions(StatementTree tree) {
            return switch (tree.getKind()) {
                case EXPRESSION_STATEMENT, RETURN, THROW, YIELD, ASSERT -> List.of(tree);
                case IF -> List.of(((IfTree) tree).getCondition());
                case WHILE_LOOP -> List.of(((WhileLoopTree) tree).getCondition());
                case DO_WHILE_LOOP -> List.of(((DoWhileLoopTree) tree).getCondition());
                case FOR_LOOP -> ((ForLoopTree) tree).getCondition() == null ? List.of() : List.of(((ForLoopTree) tree).getCondition());
                case ENHANCED_FOR_LOOP -> List.of(((EnhancedForLoopTree) tree).getExpression());
                case SWITCH -> List.of(((SwitchTree) tree).getExpression());
                case SYNCHRONIZED -> List.of(((SynchronizedTree) tree).getExpression());
                default -> List.of();
            };
        }

        /** What a statement's expressions use and do. */
        private final class Scan extends TreeScanner<Void, Void> {
            final java.util.Set<String> reads = new java.util.LinkedHashSet<>();
            final java.util.Set<String> writes = new java.util.LinkedHashSet<>();
            final List<Call> calls = new ArrayList<>();
            final List<Part> lambdas = new ArrayList<>();
            final List<Variable> lambdaParameters = new ArrayList<>();

            @Override
            public Void visitIdentifier(IdentifierTree node, Void unused) {
                reads.add(node.getName().toString());
                return null;
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                if (node.getExpression() instanceof IdentifierTree id && id.getName().contentEquals("this")) {
                    reads.add(node.getIdentifier().toString()); // this.x is the field x
                    return null;
                }
                return super.visitMemberSelect(node, unused);
            }

            private void assigned(Tree target, boolean alsoRead) {
                if (target instanceof IdentifierTree id) {
                    writes.add(id.getName().toString());
                    if (alsoRead) reads.add(id.getName().toString());
                } else if (target instanceof MemberSelectTree select && select.getExpression() instanceof IdentifierTree owner
                        && owner.getName().contentEquals("this")) {
                    writes.add(select.getIdentifier().toString());
                    if (alsoRead) reads.add(select.getIdentifier().toString());
                } else {
                    scan(target, null); // an array element or a field of another object: its parts are read
                }
            }

            @Override
            public Void visitAssignment(AssignmentTree node, Void unused) {
                assigned(node.getVariable(), false);
                return scan(node.getExpression(), null);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                assigned(node.getVariable(), true);
                return scan(node.getExpression(), null);
            }

            @Override
            public Void visitUnary(UnaryTree node, Void unused) {
                switch (node.getKind()) {
                    case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> assigned(node.getExpression(), true);
                    default -> scan(node.getExpression(), null);
                }
                return null;
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                ExpressionTree select = node.getMethodSelect();
                if (select instanceof MemberSelectTree member) {
                    calls.add(new Call(member.getIdentifier().toString(), chain(member.getExpression()), startLine(node), node.getArguments().size()));
                    scan(member.getExpression(), null);
                } else if (select instanceof IdentifierTree id) {
                    calls.add(new Call(id.getName().toString(), List.of(), startLine(node), node.getArguments().size()));
                }
                return scan(node.getArguments(), null);
            }

            @Override
            public Void visitNewClass(NewClassTree node, Void unused) {
                calls.add(new Call("<init>", List.of(node.getIdentifier().toString()), startLine(node), node.getArguments().size()));
                scan(node.getArguments(), null);
                return null; // the body of an anonymous class is not a statement of this one
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                calls.add(new Call(node.getName().toString(), chain(node.getQualifierExpression()), startLine(node), -1));
                return scan(node.getQualifierExpression(), null);
            }

            @Override
            public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                Span span = new Span(startLine(node), endLine(node));
                List<String> names = new ArrayList<>();
                for (VariableTree p : node.getParameters()) {
                    names.add(p.getName().toString());
                    lambdaParameters.add(variable(p, Variable.Kind.PARAMETER, span));
                }
                List<Statement> inside = node.getBody() instanceof BlockTree block ? block(block) : List.of(expression(node.getBody()));
                lambdas.add(new Part("lambda (" + String.join(", ", names) + ")", inside));
                return null;
            }
        }

        /** The statement of a lambda that is only an expression. */
        private Statement expression(Tree expression) {
            Scan scan = new Scan();
            scan.scan(expression, null);
            List<Part> parts = scan.lambdas;
            return new Statement(Statement.Kind.EXPRESSION, startLine(expression), endLine(expression), shorten(expression.toString()), parts,
                    scan.lambdaParameters, List.copyOf(scan.reads), List.copyOf(scan.writes), scan.calls);
        }

        /** The parts of the expression before a dot, see {@link Call}. */
        private List<String> chain(Tree expression) {
            List<String> out = new ArrayList<>();
            appendChain(expression, out);
            return out;
        }

        private void appendChain(Tree expression, List<String> out) {
            switch (expression) {
                case IdentifierTree id -> out.add(id.getName().toString());
                case MemberSelectTree select -> {
                    appendChain(select.getExpression(), out);
                    out.add(select.getIdentifier().toString());
                }
                case MethodInvocationTree call -> {
                    if (call.getMethodSelect() instanceof MemberSelectTree select) {
                        appendChain(select.getExpression(), out);
                        out.add(select.getIdentifier() + "()");
                    } else if (call.getMethodSelect() instanceof IdentifierTree id) {
                        out.add(id.getName() + "()");
                    } else {
                        out.add("?");
                    }
                }
                case NewClassTree created -> out.add("new " + created.getIdentifier());
                case ParenthesizedTree inner -> appendChain(inner.getExpression(), out);
                case TypeCastTree cast -> out.add("(" + cast.getType() + ")");
                default -> out.add("?");
            }
        }

        private Statement build(StatementTree tree, int scopeEnd, int floor) {
            int start = withComments(startLine(tree), floor);
            int end = endLine(tree);
            String summary = summary(tree);
            switch (tree.getKind()) {
                case EMPTY_STATEMENT:
                    return null;
                case VARIABLE: {
                    VariableTree v = (VariableTree) tree;
                    return new Statement(Statement.Kind.DECLARATION, start, end, summary, List.of(),
                            List.of(variable(v, Variable.Kind.LOCAL, new Span(startLine(v), Math.max(scopeEnd, end)))));
                }
                case EXPRESSION_STATEMENT: {
                    Tree expression = ((ExpressionStatementTree) tree).getExpression();
                    Statement.Kind kind = switch (expression.getKind()) {
                        case METHOD_INVOCATION -> Statement.Kind.CALL;
                        case ASSIGNMENT, MULTIPLY_ASSIGNMENT, DIVIDE_ASSIGNMENT, REMAINDER_ASSIGNMENT, PLUS_ASSIGNMENT, MINUS_ASSIGNMENT,
                             LEFT_SHIFT_ASSIGNMENT, RIGHT_SHIFT_ASSIGNMENT, UNSIGNED_RIGHT_SHIFT_ASSIGNMENT, AND_ASSIGNMENT,
                             XOR_ASSIGNMENT, OR_ASSIGNMENT -> Statement.Kind.ASSIGNMENT;
                        default -> Statement.Kind.EXPRESSION;
                    };
                    return simple(kind, start, end, summary);
                }
                case RETURN:
                    return simple(Statement.Kind.RETURN, start, end, summary);
                case THROW:
                    return simple(Statement.Kind.THROW, start, end, summary);
                case BREAK:
                    return simple(Statement.Kind.BREAK, start, end, summary);
                case CONTINUE:
                    return simple(Statement.Kind.CONTINUE, start, end, summary);
                case YIELD:
                    return simple(Statement.Kind.YIELD, start, end, summary);
                case ASSERT:
                    return simple(Statement.Kind.ASSERT, start, end, summary);
                case CLASS, INTERFACE, ENUM, RECORD, ANNOTATION_TYPE:
                    return simple(Statement.Kind.LOCAL_CLASS, start, end, summary);
                case BLOCK:
                    return compound(Statement.Kind.BLOCK, start, end, summary, List.of(new Part("body", block((BlockTree) tree))), List.of());
                case IF: {
                    IfTree t = (IfTree) tree;
                    List<Part> parts = new ArrayList<>();
                    parts.add(new Part("then", body(t.getThenStatement(), start)));
                    if (t.getElseStatement() != null) parts.add(new Part("else", body(t.getElseStatement(), endLine(t.getThenStatement()))));
                    return compound(Statement.Kind.IF, start, end, summary, parts, List.of());
                }
                case WHILE_LOOP:
                    return compound(Statement.Kind.WHILE, start, end, summary, List.of(new Part("body", body(((WhileLoopTree) tree).getStatement(), start))), List.of());
                case DO_WHILE_LOOP:
                    return compound(Statement.Kind.DO, start, end, summary, List.of(new Part("body", body(((DoWhileLoopTree) tree).getStatement(), start))), List.of());
                case SYNCHRONIZED:
                    return compound(Statement.Kind.SYNCHRONIZED, start, end, summary, List.of(new Part("body", block(((SynchronizedTree) tree).getBlock()))), List.of());
                case LABELED_STATEMENT:
                    return compound(Statement.Kind.LABELED, start, end, summary,
                            List.of(new Part("body", body(((LabeledStatementTree) tree).getStatement(), start))), List.of());
                case FOR_LOOP: {
                    ForLoopTree t = (ForLoopTree) tree;
                    Span loop = new Span(startLine(t), end);
                    List<Variable> declared = new ArrayList<>();
                    List<Statement> init = new ArrayList<>();
                    for (StatementTree i : t.getInitializer()) {
                        Statement s = statement(i, end, startLine(i));
                        if (s == null) continue;
                        if (i instanceof VariableTree v) declared.add(variable(v, Variable.Kind.LOOP, loop));
                        init.add(s);
                    }
                    List<Part> parts = new ArrayList<>();
                    if (!init.isEmpty()) parts.add(new Part("init", init));
                    List<Statement> update = new ArrayList<>();
                    t.getUpdate().forEach(u -> {
                        Statement s = statement(u, end, startLine(u));
                        if (s != null) update.add(s);
                    });
                    if (!update.isEmpty()) parts.add(new Part("update", update));
                    parts.add(new Part("body", body(t.getStatement(), start)));
                    return compound(Statement.Kind.FOR, start, end, summary, parts, declared);
                }
                case ENHANCED_FOR_LOOP: {
                    EnhancedForLoopTree t = (EnhancedForLoopTree) tree;
                    List<Variable> declared = List.of(variable(t.getVariable(), Variable.Kind.LOOP, new Span(startLine(t), end)));
                    return compound(Statement.Kind.FOREACH, start, end, summary, List.of(new Part("body", body(t.getStatement(), start))), declared);
                }
                case SWITCH: {
                    SwitchTree t = (SwitchTree) tree;
                    List<Part> parts = new ArrayList<>();
                    for (CaseTree c : t.getCases()) {
                        String label = c.getLabels().stream().allMatch(l -> l.getKind() == Tree.Kind.DEFAULT_CASE_LABEL) ? "default" : "case " + c.getLabels().stream().map(Object::toString)
                                .reduce((a, b) -> a + ", " + b).orElse("");
                        List<Statement> inside = new ArrayList<>();
                        if (c.getStatements() != null) {
                            inside.addAll(statements(c.getStatements(), end, startLine(c)));
                        } else if (c.getBody() instanceof StatementTree body) {
                            inside.addAll(body(body, startLine(c)));
                        } else if (c.getBody() != null) { // an arrow case with an expression
                            inside.add(new Statement(Statement.Kind.EXPRESSION, startLine(c.getBody()), endLine(c.getBody()),
                                    shorten(c.getBody().toString()), List.of(), List.of()));
                        }
                        parts.add(new Part(label, inside));
                    }
                    return compound(Statement.Kind.SWITCH, start, end, summary, parts, List.of());
                }
                case TRY: {
                    TryTree t = (TryTree) tree;
                    List<Part> parts = new ArrayList<>();
                    List<Variable> declared = new ArrayList<>();
                    if (!t.getResources().isEmpty()) {
                        List<Statement> resources = new ArrayList<>();
                        for (Tree r : t.getResources()) {
                            if (r instanceof VariableTree v) {
                                Variable variable = variable(v, Variable.Kind.RESOURCE, new Span(startLine(t), endLine(t.getBlock())));
                                declared.add(variable);
                                resources.add(new Statement(Statement.Kind.DECLARATION, startLine(v), endLine(v), shorten(v.toString()), List.of(), List.of(variable)));
                            } else {
                                resources.add(new Statement(Statement.Kind.EXPRESSION, startLine(r), endLine(r), shorten(r.toString()), List.of(), List.of()));
                            }
                        }
                        parts.add(new Part("resources", resources));
                    }
                    parts.add(new Part("body", block(t.getBlock())));
                    for (CatchTree c : t.getCatches()) {
                        VariableTree p = c.getParameter();
                        Variable variable = variable(p, Variable.Kind.CATCH, new Span(startLine(c), endLine(c)));
                        // the parameter is declared by the catch, not by the try: it is listed on the catch's first statement's parent
                        parts.add(new Part("catch " + p.getType() + " " + p.getName(), block(c.getBlock())));
                        declared.add(variable);
                    }
                    if (t.getFinallyBlock() != null) parts.add(new Part("finally", block(t.getFinallyBlock())));
                    return compound(Statement.Kind.TRY, start, end, summary, parts, declared);
                }
                default:
                    return simple(Statement.Kind.EXPRESSION, start, end, summary);
            }
        }

        private static Statement simple(Statement.Kind kind, int start, int end, String summary) {
            return new Statement(kind, start, end, summary, List.of(), List.of());
        }

        private static Statement compound(Statement.Kind kind, int start, int end, String summary, List<Part> parts, List<Variable> declares) {
            // a compound statement without any statement inside keeps one empty part, so it stays compound
            return new Statement(kind, start, end, summary, parts.isEmpty() ? List.of(new Part("body", List.of())) : parts, declares);
        }

        /** The statements of a branch or loop body: those of the braces, or the one statement without braces. */
        private List<Statement> body(StatementTree tree, int floor) {
            if (tree instanceof BlockTree block) return block(block);
            Statement one = statement(tree, endLine(tree), floor);
            return one == null ? List.of() : List.of(one);
        }

        private Variable variable(VariableTree v, Variable.Kind kind, Span scope) {
            return new Variable(v.getName().toString(), v.getType() == null ? "var" : v.getType().toString(), kind, startLine(v), scope);
        }

        /** The line of the first code line of the tree, shortened. */
        private String summary(Tree tree) {
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            if (start < 0 || end <= start || end > source.length()) return "";
            String first = source.substring((int) start, (int) end).lines().findFirst().orElse("").strip();
            return shorten(first);
        }

        private static String shorten(String text) {
            String one = text.replaceAll("\\s+", " ").strip();
            return one.length() > 80 ? one.substring(0, 79) + "…" : one;
        }

        /** The first line of the comment lines directly above {@code line}, not above {@code floor}; else {@code line}. */
        private int withComments(int line, int floor) {
            int first = line;
            while (first - 1 > floor && first - 2 < lines.length) {
                String above = lines[first - 2].strip();
                if (above.startsWith("//")) {
                    first--;
                } else if (above.endsWith("*/")) {
                    int open = first - 2;
                    while (open >= floor && !lines[open].contains("/*")) open--;
                    if (open < floor) break;
                    first = open + 1;
                } else {
                    break;
                }
            }
            return first;
        }
    }
}
