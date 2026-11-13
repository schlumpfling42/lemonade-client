package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import net.rka.ai.agent.AgentGraph.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The free route's build check looks at what the job changed, and repairs only what its changes broke. */
class FreeCheckScopeTest {

    private static ToolExecutionRequest call(String id, String tool, String path) {
        return ToolExecutionRequest.builder().id(id).name(tool).arguments("{\"path\":\"" + path + "\"}").build();
    }

    @Test
    void theChangedFilesAreThoseOfWritingCallsThatDidNotFail(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("projects/app"));
        Files.writeString(folder.resolve("projects/app/A.java"), "class A {}");
        ToolExecutionRequest edit = call("1", "patchFile", "projects/app/A.java");
        ToolExecutionRequest failed = call("2", "editSection", "projects/app/B.java");
        ToolExecutionRequest read = call("3", "readFile", "projects/app/C.java");
        ToolExecutionRequest outside = call("4", "writeFile", "../elsewhere.txt");
        List<ChatMessage> transcript = List.of(
                AiMessage.from(List.of(edit, failed, read, outside)),
                ToolExecutionResultMessage.from(edit, "Patched 1 hunk"),
                ToolExecutionResultMessage.from(failed, "Error: No section 'x' in this file"),
                ToolExecutionResultMessage.from(read, "class C {}"),
                ToolExecutionResultMessage.from(outside, "Error: outside the trusted folder"));
        State state = new State(Map.of(State.WORK_FOLDER, folder.toString(), State.SKILL_MESSAGES, transcript));

        assertEquals(List.of("projects/app/A.java"), CodingGraph.changedPaths(state));
    }

    @Test
    void aFailureIsTheJobsOnlyWhenItNamesAChangedFile() {
        List<String> changed = List.of("projects/app/src/A.java");
        assertTrue(CodingGraph.namesChangedFile("src\\A.java:3: error: cannot find symbol", changed));
        assertFalse(CodingGraph.namesChangedFile("error: not a file: C:\\Users\\x\\.gradle\\caches\\Other.java", changed));
    }
}
