package net.rka.ai.tools.outline;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Statements put at the start or the end of a method's or constructor's body. */
class SectionEditBodyTest {

    private static final String SOURCE = """
            class App {
                private int x;

                App(int x) {
                    this(x, 0);
                }

                /** Runs. */
                @Override
                public void run(
                        String name) {
                    one();
                    two();
                }

                int value() {
                    int y = x;
                    return y;
                }

                void empty() {
                }

                void tiny() { one(); }
            }
            """;

    private static String apply(String id, SectionEdit.Mode mode, String newText) {
        Outline outline = Outlines.of("App.java", SOURCE.getBytes(StandardCharsets.UTF_8));
        return SectionEdit.apply(SOURCE, outline, outline.find(id), mode, newText);
    }

    @Test
    void statementsGoAfterTheOpeningBraceAtTheBodysIndentation() {
        String changed = apply("App.run(String)", SectionEdit.Mode.START, "if (stopped) return;");
        assertTrue(changed.contains("            String name) {\n        if (stopped) return;\n        one();\n"), changed);
    }

    @Test
    void statementsGoBeforeTheClosingBraceButBeforeAFinalReturn() {
        String run = apply("App.run(String)", SectionEdit.Mode.END, "done();");
        assertEquals("        two();\n        done();\n    }", run.substring(run.indexOf("        two();"), run.indexOf("    }", run.indexOf("done();")) + 5));
        String value = apply("App.value()", SectionEdit.Mode.END, "log(y);");
        assertEquals("        int y = x;\n        log(y);\n        return y;\n    }", value.substring(value.indexOf("        int y = x;"), value.indexOf("    }", value.indexOf("log(y)")) + 5));
    }

    @Test
    void aConstructorsExplicitCallStaysFirst() {
        String changed = apply("App.App(int)", SectionEdit.Mode.START, "check(x);");
        assertEquals("    App(int x) {\n        this(x, 0);\n        check(x);\n    }", changed.substring(changed.indexOf("    App(int x)"), changed.indexOf("    }", changed.indexOf("check")) + 5));
    }

    @Test
    void anEmptyBodyGetsOneLevelOfIndentation() {
        String changed = apply("App.empty()", SectionEdit.Mode.START, "one();");
        assertTrue(changed.contains("    void empty() {\n        one();\n    }\n"), changed);
    }

    @Test
    void aBodyOnOneLineAndEmptyTextAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> apply("App.tiny()", SectionEdit.Mode.END, "two();"));
        assertThrows(IllegalArgumentException.class, () -> apply("App.run(String)", SectionEdit.Mode.START, "  "));
    }
}
