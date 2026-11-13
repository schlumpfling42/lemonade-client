package net.rka.ai.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The folder's files as a job's starting point. */
class ProjectMapTest {

    @TempDir
    Path folder;

    private void write(String path, String content) throws Exception {
        Path file = folder.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void listsFilesPerFolderWithTheirLineCountsInSortedOrder() throws Exception {
        write("README.md", "one\ntwo");                   // no final newline: still two lines
        write("src/main/B.java", "b\n");
        write("src/main/A.java", "a\n1\n2\n");
        write("src/test/T.java", "t\n");
        write("empty.txt", "");

        assertEquals("""
                ./: README.md 2, empty.txt 0
                src/main/: A.java 3, B.java 1
                src/test/: T.java 1""", ProjectMap.of(folder));
        assertEquals(ProjectMap.of(folder), ProjectMap.of(folder)); // the same every time
    }

    @Test
    void leavesOutBuildOutputDependenciesAndHiddenFolders() throws Exception {
        write("src/A.java", "a\n");
        write("build/classes/A.class", "x\n");
        write("node_modules/lib/index.js", "x\n");
        write("bin/main/A.class", "x\n");
        write("evals/results/run-1/A.java", "x\n");
        write("traces/agent.log", "x\n");
        write("conversations/c1.json", "x\n");
        write(".git/config", "x\n");
        write(".gradle/caches.bin", "x\n");

        assertEquals("src/: A.java 1", ProjectMap.of(folder));
    }

    @Test
    void aBinaryFileHasNoLineCount() throws Exception {
        Files.write(folder.resolve("logo.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 1, 2});
        assertEquals("./: logo.png", ProjectMap.of(folder));
    }

    @Test
    void anEmptyFolderSaysSo() {
        assertEquals("(the folder has no files yet)", ProjectMap.of(folder));
    }

    @Test
    void aHugeFolderIsCutAndSaysHowToFindTheRest() throws Exception {
        for (int i = 0; i < ProjectMap.MAX_FILES + 20; i++) write("pkg/File" + i + ".txt", "x\n");

        String map = ProjectMap.of(folder);
        assertTrue(map.length() <= ProjectMap.MAX_CHARS + 100, "" + map.length());
        assertTrue(map.endsWith("… more files: list them with findFiles or listFiles"), map);
    }
}
