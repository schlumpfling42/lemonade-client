package net.rka.ai.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The style facts read from a file's text. */
class StyleNotesTest {

    private static final String SPACED = """
            /** A thing. */
            class Thing {
                /** One. */
                int one() {
                    return 1;
                }

                /** Two. */
                int two() {
                    return 2;
                }

                /** Three. */
                int three() {
                    return 3;
                }
            }
            """;

    private static final String ALLMAN_TABS = "class Thing\n{\n\tint one()\n\t{\n\t\treturn 1;\n\t}\n\n\tint two()\n\t{\n\t\treturn 2;\n\t}\n\n\tint three()\n\t{\n\t\treturn 3;\n\t}\n}\n";

    @Test
    void indentationBracesAndDocCommentsAreCounted() {
        String notes = StyleNotes.of(SPACED);
        assertTrue(notes.contains("Indentation: 4 spaces per level"), notes);
        assertTrue(notes.contains("opening brace is on the line of the declaration"), notes);
        assertTrue(notes.contains("almost every method has a doc comment"), notes);
    }

    @Test
    void tabsAndBracesOnTheirOwnLineAreSeen() {
        String notes = StyleNotes.of(ALLMAN_TABS);
        assertTrue(notes.contains("Indentation: tabs"), notes);
        assertTrue(notes.contains("opening brace is on its own line"), notes);
        assertTrue(notes.contains("methods have no doc comments: don't add any"), notes);
    }

    @Test
    void aTwoSpaceFileAndATinyFile() {
        assertTrue(StyleNotes.of(SPACED.replace("    ", "  ")).contains("2 spaces per level"));
        assertEquals("", StyleNotes.of("class A {}\n"));
    }
}
