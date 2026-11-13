package net.rka.ai.tools.code.java;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAnchorsTest {

    private static final String CODE = """
            class A {
                void run() {
                    int n = 1;
                    button.setOnAction(e -> {
                        stop(n);
                    });
                    if (n > 1) {
                        one();
                    } else {
                        two();
                    }
                    String s = "}; // not code";
                    done();
                }
            }
            """;

    @Test
    void statementsGoBeforeTheAnchorOrAfterTheWholeStatementWithItsIndentation() {
        assertEquals(CODE.replace("        int n = 1;\n", "        log();\n        int n = 1;\n"),
                JavaAnchors.insert(CODE, 2, 14, "int n = 1;", false, "log();"));
        // after a statement of several lines: after its last line
        assertEquals(CODE.replace("        });\n", "        });\n        log();\n"),
                JavaAnchors.insert(CODE, 2, 14, "button.setOnAction", true, "log();"));
        // an if with its else is one statement; a string with brackets in it does not end one
        assertEquals(CODE.replace("        String s =", "        log();\n        String s ="),
                JavaAnchors.insert(CODE, 2, 14, "if (n > 1) {", true, "log();"));
        assertEquals(CODE.replace("        done();\n", "        log();\n        done();\n"),
                JavaAnchors.insert(CODE, 2, 14, "String s", true, "log();"));
    }

    @Test
    void severalLinesKeepTheirOwnIndentationAndWindowsLineEndsStay() {
        String windows = CODE.replace("\n", "\r\n");

        String changed = JavaAnchors.insert(windows, 2, 14, "done();", false, "if (n > 0) {\n    log();\n}");

        assertTrue(changed.contains("        if (n > 0) {\r\n            log();\r\n        }\r\n        done();"), changed);
    }

    @Test
    void anAnchorThatIsNotThereIsOnManyLinesOrContinuesAStatementIsTold() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JavaAnchors.find(CODE, 2, 14, "nothing()")).getMessage().contains("No line"));
        String twice = "void f() {\n    a();\n    a();\n}\n";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JavaAnchors.find(twice, 1, 4, "a();")).getMessage().contains("2 lines"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> JavaAnchors.find(CODE, 2, 14, "});")).getMessage().contains("continues a statement"));
        assertEquals(6, JavaAnchors.statementEnd(CODE, 4, 14));
    }
}
