package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import net.rka.ai.tools.code.java.JavaWorkspaces;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeTest {

    @TempDir
    Path folder;

    private final JavaWorkspaces workspaces = new JavaWorkspaces(false);

    @AfterEach
    void close() {
        workspaces.close();
    }

    private static ToolExecutionRequest call(String tool, String arguments) {
        return ToolExecutionRequest.builder().id("1").name(tool).arguments(arguments).build();
    }

    @Test
    void aFileTheCallsTouchedIsTrackedAndAChangeOutsideTheConversationIsNamed() throws Exception {
        Files.writeString(folder.resolve("notes.md"), "# One\ntext\n");
        Files.createDirectories(folder.resolve("src"));
        Files.writeString(folder.resolve("src/Ledger.java"), "class Ledger {\n    void run() {\n        one();\n    }\n\n    void stop() {\n    }\n}\n");
        String ledger = Knowledge.after("", List.of(call("readFile", "{\"path\":\"notes.md\"}"), call("javaRead", "{\"type\":\"Ledger\",\"member\":\"run\"}")),
                folder, workspaces);

        assertEquals(List.of(), Knowledge.changes(ledger, folder, workspaces), "nothing changed: nothing to say");

        // the user edits both files in the editor
        Files.writeString(folder.resolve("notes.md"), "# One\ntext\nmore\nlines\n");
        Files.setLastModifiedTime(folder.resolve("notes.md"), FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Files.writeString(folder.resolve("src/Ledger.java"), "class Ledger {\n    void run() {\n        two();\n    }\n\n    void stop() {\n    }\n\n    void pause() {\n    }\n}\n");

        List<String> changes = Knowledge.changes(ledger, folder, workspaces);
        assertEquals(2, changes.size(), changes.toString());
        assertTrue(changes.stream().anyMatch(c -> c.equals("notes.md: changed, 4 lines now (was 2)")), changes.toString());
        assertTrue(changes.stream().anyMatch(c -> c.startsWith("src/Ledger.java: changed Ledger.run(); added Ledger.pause()")), changes.toString());
        // said once: after the refresh the same state is not a change
        assertEquals(List.of(), Knowledge.changes(Knowledge.refreshed(ledger, folder, workspaces), folder, workspaces));
    }

    @Test
    void whatACallChangedItselfIsNotAChangeAndADeletedFileIsReported() throws Exception {
        Files.writeString(folder.resolve("a.txt"), "one\n");
        String ledger = Knowledge.after("", List.of(call("readFile", "{\"path\":\"a.txt\"}")), folder, workspaces);
        Files.writeString(folder.resolve("a.txt"), "one\ntwo\n"); // the call's own edit
        Files.setLastModifiedTime(folder.resolve("a.txt"), FileTime.fromMillis(System.currentTimeMillis() + 5000));
        ledger = Knowledge.after(ledger, List.of(call("patchFile", "{\"path\":\"a.txt\",\"patch\":\"x\"}")), folder, workspaces);

        assertEquals(List.of(), Knowledge.changes(ledger, folder, workspaces));
        Files.delete(folder.resolve("a.txt"));
        assertEquals(List.of("a.txt: deleted"), Knowledge.changes(ledger, folder, workspaces));
    }

    @Test
    void theLedgerKeepsTheLatestFilesAndOnlyThose() throws Exception {
        String ledger = "";
        for (int i = 0; i < Knowledge.MAX_FILES + 5; i++) {
            Files.writeString(folder.resolve("f" + i + ".txt"), "x\n");
            ledger = Knowledge.after(ledger, List.of(call("readFile", "{\"path\":\"f" + i + ".txt\"}")), folder, workspaces);
        }

        assertEquals(Knowledge.MAX_FILES, Knowledge.decode(ledger).size());
        assertTrue(Knowledge.decode(ledger).containsKey("f" + (Knowledge.MAX_FILES + 4) + ".txt"));
        assertEquals(false, Knowledge.decode(ledger).containsKey("f0.txt"));
    }
}
