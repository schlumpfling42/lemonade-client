package net.rka.ai.tools;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedCommandsTest {

    private static ToolExecutionRequest run(String command) {
        return ToolExecutionRequest.builder().id("1").name("runCommand")
                .arguments("{\"command\": \"" + command + "\"}").build();
    }

    @Test
    void onlyCommandsWithoutShellSyntaxCanBeTrusted() {
        assertTrue(TrustedCommands.canBeTrusted("git status"));
        assertTrue(TrustedCommands.canBeTrusted("git status*"));
        assertFalse(TrustedCommands.canBeTrusted("git status; rm -rf ~"));
        assertFalse(TrustedCommands.canBeTrusted("Get-ChildItem | Select-Object Name"));
        assertFalse(TrustedCommands.canBeTrusted("echo $(whoami)"));
        assertFalse(TrustedCommands.canBeTrusted("  "));
        assertFalse(TrustedCommands.canBeTrusted(null));
    }

    @Test
    void wildcardEntriesMatchArgumentsButNotChaining() {
        TrustedCommands trusted = TrustedCommands.of("git status*, Get-Date");
        assertTrue(trusted.isTrusted(run("git status")));
        assertTrue(trusted.isTrusted(run("git status --short")));
        assertTrue(trusted.isTrusted(run("get-date")));
        assertFalse(trusted.isTrusted(run("git status; rm -rf ~")));
        assertFalse(trusted.isTrusted(run("Get-Date -Format o")));
    }
}
