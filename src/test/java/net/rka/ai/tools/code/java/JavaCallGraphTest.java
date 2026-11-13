package net.rka.ai.tools.code.java;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The call graph: calls found through declared types, new, static calls, chains and the subtypes of an interface. */
class JavaCallGraphTest {

    @TempDir
    Path dir;

    private JavaWorkspace workspace;

    private void file(String name, String text) throws Exception {
        Files.writeString(dir.resolve(name), text);
    }

    @BeforeEach
    void project() throws Exception {
        file("Main.java", """
                package p;

                public class Main {
                    public static void main(String[] args) {
                        Service service = new Service(new Store());
                        service.run(args.length);
                        Util.make().log("y");
                    }
                }
                """);
        file("Service.java", """
                package p;

                class Service {
                    private final Repo repo;

                    Service(Repo repo) {
                        this.repo = repo;
                    }

                    void run(int n) {
                        repo.save(n);
                        helper();
                        String s = "text".trim();
                    }

                    private void helper() {
                        Util.log("h");
                    }
                }
                """);
        file("Repo.java", "package p;\n\ninterface Repo {\n    void save(int n);\n}\n");
        file("Store.java", "package p;\n\nclass Store implements Repo {\n    public void save(int n) {\n        Util.log(\"s\");\n    }\n}\n");
        file("Util.java", "package p;\n\nclass Util {\n    static void log(String s) {\n    }\n\n    static Util make() {\n        return new Util();\n    }\n}\n");
        workspace = JavaWorkspace.open(dir);
    }

    @AfterEach
    void close() {
        workspace.close();
    }

    @Test
    void theCallsOfTheMainMethodAreFollowedThroughTypesNewChainsAndInterfaces() {
        JavaCallGraph graph = new JavaCallGraph(workspace);

        assertEquals(List.of("p.Main.main(String[])"), graph.entryPoints().stream().map(JavaCallGraph.Member::id).toList());
        assertEquals(List.of("p.Service.<init>(Repo)", "p.Service.run(int)", "p.Util.log(String)", "p.Util.make()"),
                List.copyOf(graph.callsFrom("p.Main.main(String[])")));
        // an interface call also reaches the implementation
        assertEquals(List.of("p.Repo.save(int)", "p.Service.helper()", "p.Store.save(int)"), List.copyOf(graph.callsFrom("p.Service.run(int)")));
        assertEquals(List.of("p.Util.log(String)"), List.copyOf(graph.callsFrom("p.Store.save(int)")));
        // who calls
        assertEquals(List.of("p.Main.main(String[])", "p.Service.helper()", "p.Store.save(int)"), List.copyOf(graph.callersOf("p.Util.log(String)")));
        // a call on the JDK is not followed, and counted
        assertTrue(graph.unresolvedCalls() >= 1);
    }

    @Test
    void theTreeShowsLevelsFilesAndWhatWasShownAlready() {
        JavaCallGraph graph = new JavaCallGraph(workspace);

        String tree = graph.render(graph.entryPoints(), 3, false, 50);

        assertTrue(tree.startsWith("Main.main(String[])  Main.java:4"), tree);
        assertTrue(tree.contains("\n  Service.run(int)  Service.java:10"), tree);
        assertTrue(tree.contains("\n    Service.helper()  Service.java:16"), tree);
        assertTrue(tree.contains("Util.log(String)  Util.java:4") && tree.contains("Util.log(String)  ↺"), tree);
        String up = graph.render(List.of(graph.member("p.Util.log(String)").orElseThrow()), 2, true, 50);
        assertTrue(up.contains("  Service.helper()") && up.contains("    Service.run(int)"), up);
    }
}
