package net.rka.ai.tools.code.java;

import net.rka.ai.tools.code.java.JavaStructure.Effect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaStructureTest {

    private static final String BEFORE = """
            class A {
                int x;

                void a() {
                    one();
                }

                void b() {
                    two();
                }
            }
            """;

    @Test
    void aChangeThatReplacesItsPartAndNothingElseIsAccepted() {
        String after = BEFORE.replace("one();", "three();");

        assertNull(JavaStructure.problem(BEFORE, after, 4, 6, "void a() {\n    three();\n}", Effect.REPLACES));
        // a new member next to it, statements into a body, a deletion
        assertNull(JavaStructure.problem(BEFORE, BEFORE.replace("    void b() {", "    void c() {\n    }\n\n    void b() {"), 8, 10,
                "void c() {\n}", Effect.ADDS));
        assertNull(JavaStructure.problem(BEFORE, BEFORE.replace("one();", "one();\n        more();"), 4, 6, "more();", Effect.BODY));
        assertNull(JavaStructure.problem(BEFORE, BEFORE.replace("    void b() {\n        two();\n    }\n", ""), 8, 10, "", Effect.REPLACES));
    }

    @Test
    void textThatLandsOutsideItsPartIsRefusedWithWhatIsWrong() {
        // the neighbour is written again together with the part
        String twice = BEFORE.replace("void a() {\n        one();\n    }", "void a() {\n        three();\n    }\n\n    void b() {\n        two();\n    }");
        String problem = JavaStructure.problem(BEFORE, twice, 4, 6, "void a() {\n    three();\n}\n\nvoid b() {\n    two();\n}", Effect.REPLACES);
        assertNotNull(problem);
        assertTrue(problem.contains("twice") && problem.contains("method b()"), problem);
        // a copy of the method is added instead of replacing it
        assertTrue(JavaStructure.problem(BEFORE, BEFORE.replace("    int x;", "    int x;\n\n    void a() {\n        three();\n    }"), 4, 6,
                "void a() {\n    three();\n}", Effect.REPLACES).contains("method a()"));
    }

    @Test
    void aResultThatIsNotJavaIsRefused() {
        String broken = BEFORE.replace("    void b() {", "    }\n    void b() {");

        String problem = JavaStructure.problem(BEFORE, broken, 4, 6, "void a() {\n    one();\n}\n}", Effect.REPLACES);

        assertNotNull(problem);
        assertTrue(problem.contains("no longer be readable as Java") && problem.contains("Nothing was changed"), problem);
        assertNull(JavaStructure.unreadable(BEFORE));
    }
}
