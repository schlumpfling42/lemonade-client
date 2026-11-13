package net.rka.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Taking the code out of a model's answer. */
class CodeBlocksTest {

    @Test
    void takesTheFencedBlockAndIgnoresTheProseAround() {
        assertEquals(Optional.of("void a() {\n}"),
                CodeBlocks.extract("Here you go:\n\n```java\nvoid a() {\n}\n```\n\nLet me know."));
    }

    @Test
    void anAnswerWithoutAFenceIsNotCode() {
        // Prose must never be written into a file as if it were code
        assertEquals(Optional.empty(), CodeBlocks.extract("I'm not sure what to write here."));
        assertEquals(Optional.empty(), CodeBlocks.extract("int x;"));
    }

    @Test
    void aSectionWithFencedCodeInsideComesOutWhole() {
        String markdown = "## Usage\nRun it:\n```bash\nrun.sh\n```\nDone.";
        assertEquals(Optional.of(markdown), CodeBlocks.extract("```markdown\n" + markdown + "\n```"));
    }

    @Test
    void aMissingClosingFenceTakesTheRest() {
        assertEquals(Optional.of("int x;\nint y;"), CodeBlocks.extract("```\nint x;\nint y;\n"));
    }

    @Test
    void allBlocksOfAnAnswerComeInOrder() {
        assertEquals(java.util.List.of("int x;", "void f() {}"),
                CodeBlocks.all("Change 1:\n```java\nint x;\n```\nChange 2:\n```java\nvoid f() {}\n```\n"));
        assertEquals(java.util.List.of("int x;", "int y;"), CodeBlocks.all("```\nint x;\n```\n```\nint y;\n"), "an open block takes the rest");
        assertEquals(java.util.List.of(), CodeBlocks.all("no code here"));
        assertEquals(java.util.List.of("a"), CodeBlocks.all("```\n\n```\n```\na\n```"), "empty blocks are left out");
        assertEquals(java.util.List.of(), CodeBlocks.all(null));
    }

    @Test
    void windowsLineBreaksAreNormalizedAndEmptyAnswersGiveNothing() {
        assertEquals(Optional.of("a\nb"), CodeBlocks.extract("```\r\na\r\nb\r\n```"));
        assertEquals(Optional.empty(), CodeBlocks.extract("```\n\n```"));
        assertEquals(Optional.empty(), CodeBlocks.extract("   \n"));
        assertEquals(Optional.empty(), CodeBlocks.extract(null));
    }
}
