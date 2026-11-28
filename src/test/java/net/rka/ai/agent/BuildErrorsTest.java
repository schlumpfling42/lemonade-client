package net.rka.ai.agent;

import net.rka.ai.agent.BuildErrors.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where a build says something is wrong. */
class BuildErrorsTest {

    @TempDir
    Path folder;

    @BeforeEach
    void files() throws Exception {
        Files.createDirectories(folder.resolve("src"));
        Files.writeString(folder.resolve("src/App.java"), "class App {}\n");
        Files.writeString(folder.resolve("src/app.ts"), "let x;\n");
        Files.writeString(folder.resolve("app.py"), "print(1)\n");
    }

    @Test
    void findsJavacPositionsWithTheirMessageAndSourceLine() {
        String output = "> Task :compileJava FAILED\n"
                + folder.resolve("src").resolve("App.java") + ":340: error: cannot find symbol\n"
                + "        stopAgent();\n"
                + "        ^\n"
                + "  symbol:   method stopAgent()\n"
                + "1 error\n";

        assertEquals(List.of(new Position("src/App.java", 340, "error: cannot find symbol\nstopAgent();\nsymbol: method stopAgent()")),
                BuildErrors.parse(output, folder));
    }

    @Test
    void findsMavenPositionsWithTheLineAndColumnInBrackets() {
        // Maven (with -q) prints the absolute path with a leading slash on Windows, then [line,column]; without this the failure named no file,
        // nothing could be repaired and a job that had made every change rolled them all back after one attempt
        String output = "[ERROR] COMPILATION ERROR :\n"
                + "[ERROR] /" + folder.resolve("src").resolve("App.java").toString().replace('\\', '/') + ":[6,52] package org.springframework.boot.test.autoconfigure.web.servlet does not exist\n"
                + "[ERROR] /" + folder.resolve("src").resolve("App.java").toString().replace('\\', '/') + ":[9,2] cannot find symbol\n"
                + "[INFO] 2 errors\n";

        List<Position> positions = BuildErrors.parse(output, folder);

        assertEquals(2, positions.size(), positions.toString());
        assertEquals(new Position("src/App.java", 6, "package org.springframework.boot.test.autoconfigure.web.servlet does not exist"), positions.get(0));
        assertEquals(9, positions.get(1).line());
    }

    @Test
    void findsRelativeTypeScriptAndPythonPositions() {
        String output = "src/app.ts(12,5): error TS2304: Cannot find name 'y'.\n"
                + "Traceback (most recent call last):\n"
                + "  File \"app.py\", line 7, in <module>\n"
                + "    boom()\n";

        List<Position> positions = BuildErrors.parse(output, folder);

        assertEquals(2, positions.size(), positions.toString());
        assertEquals(new Position("src/app.ts", 12, "error TS2304: Cannot find name 'y'."), positions.get(0));
        assertEquals("app.py", positions.get(1).file());
        assertEquals(7, positions.get(1).line());
    }

    @Test
    void leavesOutFilesOutsideTheFolderUnknownFilesAndRepeats() {
        String output = "C:\\Users\\x\\.gradle\\caches\\lib.jar\\Lib.java:10: error: not ours\n"
                + "src/Missing.java:3: error: no such file here\n"
                + "src/App.java:1: error: first\n"
                + "src/App.java:1: error: again\n"
                + "src/App.java:2: warning: second line\n";

        List<Position> positions = BuildErrors.parse(output, folder);

        assertEquals(List.of(1, 2), positions.stream().map(Position::line).toList());
        assertEquals("error: first", positions.get(0).message());
    }

    @Test
    void textWithoutPositionsGivesNothing() {
        assertEquals(List.of(), BuildErrors.parse("BUILD FAILED in 2s\n3 tests completed, 1 failed\n", folder));
    }
}
