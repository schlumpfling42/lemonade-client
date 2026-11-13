package net.rka.ai.tools.code.java;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The statements inside the bodies of members: kinds, lines, the parts of compound statements and the variables they declare. */
class JavaBlocksTest {

    private static final String SOURCE = """
            class A {
                int total;

                int run(int limit, String name) {
                    int sum = 0;
                    // go through the numbers
                    for (int i = 0; i < limit; i++) {
                        if (i % 2 == 0) {
                            sum += i;
                        } else if (i > 5)
                            continue;
                        else {
                            log(name);
                        }
                    }
                    for (String s : names()) {
                        total++;
                    }
                    try (var in = open()) {
                        use(in);
                    } catch (IOException | RuntimeException e) {
                        throw e;
                    } finally {
                        close();
                    }
                    switch (sum) {
                        case 1:
                            return 1;
                        case 2, 3 -> log("x");
                        default -> {
                            total = 0;
                        }
                    }
                    while (sum > 0) sum--;
                    return sum;
                }

                static {
                    init();
                }

                abstract void gone();
            }
            """;

    private static JavaClass a() {
        return JavaSourceReader.read(SOURCE).getFirst();
    }

    /** One line per statement: indentation by depth, kind, lines, and the part it is in. */
    private static void outline(List<Statement> statements, String indent, List<String> out) {
        for (Statement s : statements) {
            out.add(indent + s.kind() + " " + s.startLine() + "-" + s.endLine() + (s.declares().isEmpty() ? "" : " declares "
                    + s.declares().stream().map(v -> v.kind() + " " + v.type() + " " + v.name() + " " + v.scope().start() + "-" + v.scope().end()).toList()));
            for (Part part : s.parts()) {
                out.add(indent + "  " + part.name() + ":");
                outline(part.statements(), indent + "    ", out);
            }
        }
    }

    @Test
    void aMethodBodyIsAListOfStatementsWithLinesPartsAndDeclaredVariables() {
        JavaClass a = a();
        JavaBlocks.Body body = JavaBlocks.bodyOf(a, a.methods().getFirst());

        assertEquals(new Span(4, 36), body.span());
        assertEquals(List.of("PARAMETER int limit 4-36", "PARAMETER String name 4-36"),
                body.parameters().stream().map(v -> v.kind() + " " + v.type() + " " + v.name() + " " + v.scope().start() + "-" + v.scope().end()).toList());
        List<String> out = new ArrayList<>();
        outline(body.statements(), "", out);
        assertEquals(String.join("\n",
                "DECLARATION 5-5 declares [LOCAL int sum 5-36]",
                "FOR 6-15 declares [LOOP int i 7-15]",
                "  init:",
                "    DECLARATION 7-7 declares [LOCAL int i 7-15]",
                "  update:",
                "    EXPRESSION 7-7",
                "  body:",
                "    IF 8-14",
                "      then:",
                "        ASSIGNMENT 9-9",
                "      else:",
                "        IF 10-14",
                "          then:",
                "            CONTINUE 11-11",
                "          else:",
                "            CALL 13-13",
                "FOREACH 16-18 declares [LOOP String s 16-18]",
                "  body:",
                "    EXPRESSION 17-17",
                "TRY 19-25 declares [RESOURCE var in 19-21, CATCH IOException | RuntimeException e 21-23]",
                "  resources:",
                "    DECLARATION 19-19 declares [RESOURCE var in 19-21]",
                "  body:",
                "    CALL 20-20",
                "  catch IOException | RuntimeException e:",
                "    THROW 22-22",
                "  finally:",
                "    CALL 24-24",
                "SWITCH 26-33",
                "  case 1:",
                "    RETURN 28-28",
                "  case 2, 3:",
                "    CALL 29-29",
                "  default:",
                "    ASSIGNMENT 31-31",
                "WHILE 34-34",
                "  body:",
                "    EXPRESSION 34-34",
                "RETURN 35-35"), String.join("\n", out));
    }

    private static final String FACTS = """
            class B {
                Ledger ledger;
                int total;

                int go(Account account, int n) {
                    int local = n + total;
                    this.total = local;
                    ledger.add(local).close();
                    names.forEach(x -> {
                        total += x;
                        log(x);
                    });
                    Runnable r = () -> account.reset();
                    Object o = new Thing(account);
                    for (int i = 0; i < n; i++) {
                        if (account.ok(i)) total++;
                    }
                    return ((Foo) o).size();
                }
            }
            """;

    @Test
    void aStatementKnowsWhatItsOwnExpressionsReadWriteAndCall() {
        JavaClass b = JavaSourceReader.read(FACTS).getFirst();
        JavaBlocks.Body body = JavaBlocks.bodyOf(b, b.methods().getFirst());
        List<Statement> all = JavaBlocks.flatten(body);

        Statement declaration = all.get(0);
        assertEquals(List.of("n", "total"), declaration.reads());
        assertEquals(List.of("local"), declaration.writes());
        Statement assignment = all.get(1);
        assertEquals(List.of("local"), assignment.reads());
        assertEquals(List.of("total"), assignment.writes());
        Statement chain = all.get(2);
        assertEquals(List.of("add", "close"), chain.calls().stream().map(Call::name).sorted().toList());
        Call close = chain.calls().stream().filter(c -> c.name().equals("close")).findFirst().orElseThrow();
        assertEquals(List.of("ledger", "add()"), close.receiver());
        assertEquals(List.of("ledger"), chain.calls().stream().filter(c -> c.name().equals("add")).findFirst().orElseThrow().receiver());

        // a lambda is a part of the statement that holds it, with its parameters declared and its own statements
        Statement forEach = all.get(3);
        assertEquals(List.of("lambda (x)"), forEach.parts().stream().map(Part::name).toList());
        assertEquals(List.of("x"), forEach.declares().stream().map(Variable::name).toList());
        assertEquals(List.of("total"), forEach.parts().getFirst().statements().getFirst().writes());
        assertEquals(List.of("forEach"), forEach.calls().stream().map(Call::name).toList());
        Statement runnable = all.stream().filter(s -> s.summary().startsWith("Runnable")).findFirst().orElseThrow();
        assertEquals(List.of("reset"), runnable.parts().getFirst().statements().getFirst().calls().stream().map(Call::name).toList());
        Statement created = all.stream().filter(s -> s.summary().startsWith("Object o")).findFirst().orElseThrow();
        assertEquals(List.of("<init>"), created.calls().stream().map(Call::name).toList());
        assertEquals(List.of("Thing"), created.calls().getFirst().receiver());

        // the condition belongs to the if, the statement in it to the statement
        Statement test = all.stream().filter(s -> s.kind() == Statement.Kind.IF).findFirst().orElseThrow();
        assertEquals(List.of("ok"), test.calls().stream().map(Call::name).toList());
        assertEquals(List.of(), test.writes());
        Statement ret = all.getLast();
        assertEquals(List.of("(Foo)"), ret.calls().getFirst().receiver());
        assertTrue(ret.reads().contains("o"));
    }

    @Test
    void theVariablesVisibleBeforeAStatementAreTheParametersAndWhatIsDeclaredAbove() {
        JavaClass b = JavaSourceReader.read(FACTS).getFirst();
        JavaBlocks.Body body = JavaBlocks.bodyOf(b, b.methods().getFirst());
        List<Statement> all = JavaBlocks.flatten(body);

        assertEquals(List.of("account", "n"), JavaBlocks.visibleBefore(body, all.get(0)).stream().map(Variable::name).toList());
        assertEquals(List.of("account", "n", "local"), JavaBlocks.visibleBefore(body, all.get(1)).stream().map(Variable::name).toList());
        Statement inLoop = all.stream().filter(s -> s.kind() == Statement.Kind.IF).findFirst().orElseThrow();
        assertEquals(List.of("account", "n", "local", "r", "o", "i"), JavaBlocks.visibleBefore(body, inLoop).stream().map(Variable::name).toList());
        // the lambda's own variable is visible only inside it
        Statement inLambda = all.stream().filter(s -> s.writes().contains("total") && s.reads().contains("x")).findFirst().orElseThrow();
        assertEquals(List.of("account", "n", "local", "x"), JavaBlocks.visibleBefore(body, inLambda).stream().map(Variable::name).toList());
    }

    @Test
    void aStatementTakesTheCommentLinesDirectlyAboveIt() {
        JavaClass a = a();
        List<Statement> statements = JavaBlocks.bodyOf(a, a.methods().getFirst()).statements();

        Statement loop = statements.get(1);
        assertEquals(6, loop.startLine(), "the comment above the loop is part of it");
        assertEquals("    // go through the numbers\n    for (int i = 0; i < limit; i++) {", a.lines(loop.startLine(), loop.startLine() + 1).replace("        ", "    "));
        assertEquals("for (int i = 0; i < limit; i++) {", loop.summary());
    }

    @Test
    void initializersHaveBodiesAndMembersWithoutOneHaveNone() {
        JavaClass a = a();

        JavaBlocks.Body init = JavaBlocks.bodyOf(a, a.initializers().getFirst());
        assertEquals(1, init.statements().size());
        assertEquals(Statement.Kind.CALL, init.statements().getFirst().kind());
        assertTrue(JavaBlocks.bodyOf(a, a.methods().get(1)).statements().isEmpty(), "an abstract method has no body");
        assertTrue(JavaBlocks.bodyOf(a, a.fields().getFirst()).statements().isEmpty());
    }

    @Test
    void theStatementAtALineIsFoundFromTheOutsideIn() {
        JavaClass a = a();
        JavaBlocks.Body body = JavaBlocks.bodyOf(a, a.methods().getFirst());

        assertEquals(List.of(Statement.Kind.FOR, Statement.Kind.IF, Statement.Kind.IF, Statement.Kind.CALL),
                JavaBlocks.pathTo(body, 13).stream().map(Statement::kind).toList());
        assertEquals(List.of(), JavaBlocks.pathTo(body, 2));
    }

    @Test
    void everyBodyOfTheProjectHasStatementsInsideItsLinesInOrderWithoutOverlap() throws Exception {
        List<String> problems = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(Path.of("src"))) {
            walk.filter(p -> p.toString().endsWith(".java") && !p.toString().endsWith("_output.java")).forEach(files::add);
        }
        int bodies = 0;
        int statements = 0;
        for (Path file : files) {
            String text = Files.readString(file);
            List<JavaClass> types;
            try {
                types = JavaSourceReader.read(text);
            } catch (IllegalArgumentException e) {
                continue; // reported by the round-trip tests
            }
            for (JavaClass type : types) {
                for (JavaClass t : withNested(type)) {
                    for (JavaMember m : t.members()) {
                        if (!(m instanceof JavaMethod || m instanceof JavaConstructor || m instanceof JavaInitializer)) continue;
                        JavaBlocks.Body body;
                        try {
                            body = JavaBlocks.bodyOf(type, m);
                        } catch (RuntimeException e) {
                            problems.add(file + ": " + e.getMessage());
                            continue;
                        }
                        if (body.span().isNone()) {
                            if (!(m instanceof JavaMethod method && method.code().isEmpty() && !text.contains(method.name() + "(" ))) {
                                // a method without a body (abstract, native, interface) has none: only those may
                                if (m instanceof JavaMethod method && !method.code().isEmpty()) problems.add(file + ": " + method.name() + " has code but no body");
                            }
                            continue;
                        }
                        bodies++;
                        statements += check(file.toString(), type, body.span().start(), body.span().end(), body.statements(), problems);
                    }
                }
            }
        }
        assertTrue(bodies > 500 && statements > 3000, bodies + " bodies, " + statements + " statements");
        assertEquals(List.of(), problems);
    }

    private static List<JavaClass> withNested(JavaClass type) {
        List<JavaClass> all = new ArrayList<>(List.of(type));
        type.nestedClasses().forEach(n -> all.addAll(withNested(n)));
        return all;
    }

    /** Children lie inside the parent, in order, one after the other; the text of each starts and ends like a statement. */
    private static int check(String file, JavaClass root, int from, int to, List<Statement> statements, List<String> problems) {
        int count = 0;
        int before = from;
        for (Statement s : statements) {
            count++;
            String where = file + ":" + s.startLine() + " " + s.kind();
            if (s.startLine() < from || s.endLine() > to || s.endLine() < s.startLine()) problems.add(where + " is outside " + from + "-" + to);
            if (s.startLine() < before) problems.add(where + " starts before the statement before it ended (" + before + ")");
            before = s.endLine();
            String text = root.lines(s.startLine(), s.endLine()).stripTrailing();
            if (text.isEmpty()) problems.add(where + " has no text");
            for (Part part : s.parts()) count += check(file, root, s.startLine(), s.endLine(), part.statements(), problems);
        }
        return count;
    }
}
