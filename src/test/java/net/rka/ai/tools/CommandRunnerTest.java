package net.rka.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRunnerTest {

    @TempDir
    Path dir;

    @Test
    void doubleQuotesAndSpacesInACommandArriveAsTheyWereWritten() throws Exception {
        // Select-String -Pattern "a b" used to reach the shell as the two words a and b ("A positional parameter cannot be found ...")
        String command = CommandRunner.isWindows()
                ? "Write-Output (\"Not implemented yet\" | Select-String -Pattern \"Not implemented yet\").Matches.Count"
                : "echo \"Not implemented yet\" | grep -c \"Not implemented yet\"";
        String result = CommandRunner.run(CommandRunner.shell(command), dir, Duration.ofSeconds(60), chunk -> { });
        assertTrue(result.startsWith("exit code: 0") && result.contains("1"), result);
    }

    private String exitCodeOf(String command) throws Exception {
        String result = CommandRunner.run(CommandRunner.shell(command), dir, Duration.ofSeconds(60), chunk -> { });
        return result.lines().findFirst().orElse("");
    }

    @Test
    void theExitCodeIsTheOneOfTheLastStatementWhateverTheShell() throws Exception {
        String fail3 = CommandRunner.isWindows() ? "cmd /c exit 3" : "exit 3";
        assertEquals("exit code: 3", exitCodeOf(fail3));
        assertEquals("exit code: 0", exitCodeOf("echo ok"));
        // a failure in the middle that the next statement got over is not the command's failure
        assertEquals("exit code: 0", exitCodeOf(fail3 + "; echo after"));
        if (CommandRunner.isWindows()) {
            assertEquals("exit code: 1", exitCodeOf("Get-Item C:\\does\\not\\exist"));
            assertEquals("exit code: 5", exitCodeOf("exit 5"));
        }
    }

    @Test
    void aWindowsCommandIsRunFromAScriptThatIsDeletedWhenItIsDone() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(CommandRunner.isWindows());
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        long before = scripts(tmp);

        List<String> commandLine = CommandRunner.shell("Write-Output 'a \"quoted\" word'");
        assertEquals(before + 1, scripts(tmp), "the command is written to a script file");
        assertTrue(commandLine.contains("-File") && !commandLine.contains("-EncodedCommand"), commandLine.toString());
        String result = CommandRunner.run(commandLine, dir, Duration.ofSeconds(60), chunk -> { });

        assertTrue(result.contains("a \"quoted\" word"), result);
        assertEquals(before, scripts(tmp), "the script is deleted after the run");
    }

    private static long scripts(Path tmp) throws Exception {
        try (var files = java.nio.file.Files.list(tmp)) {
            return files.filter(f -> f.getFileName().toString().startsWith(CommandRunner.SCRIPT_PREFIX)).count();
        }
    }

    @Test
    void aDownloadCommandIsNotBlockedAndKeepsItsQuotes() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(CommandRunner.isWindows());
        // The same text as -EncodedCommand was refused with "Access is denied" on a machine with endpoint protection
        String result = CommandRunner.run(CommandRunner.shell("curl.exe --version; Write-Output \"a b\""), dir, Duration.ofSeconds(60), chunk -> { });

        assertTrue(result.startsWith("exit code: 0") && result.contains("a b"), result);
    }

    @Test
    void outputIsPassedOnWhileTheCommandRuns() throws Exception {
        // Prints "first", waits, prints "second": the first line must arrive before the command ends
        String command = CommandRunner.isWindows()
                ? "Write-Output first; Start-Sleep -Milliseconds 1500; Write-Output second"
                : "echo first; sleep 1.5; echo second";
        List<String> chunks = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        long start = System.nanoTime();

        String result = CommandRunner.run(CommandRunner.shell(command), dir, Duration.ofSeconds(60), chunk -> {
            chunks.add(chunk);
            times.add(System.nanoTime() - start);
        });
        long total = System.nanoTime() - start;

        String live = String.join("", chunks);
        assertTrue(live.contains("first") && live.contains("second"), live);
        int firstChunk = 0;
        while (!chunks.get(firstChunk).contains("first")) firstChunk++;
        assertTrue(times.get(firstChunk) < total - Duration.ofMillis(1000).toNanos(),
                "\"first\" arrived while the command was still sleeping");
        assertTrue(result.startsWith("exit code: 0") && result.contains("first") && result.contains("second"), result);
    }

    @Test
    void anOutputFileAProcessStillHoldsOpenDoesNotFailTheCommand(@TempDir Path dir) throws Exception {
        // A build tool's daemon keeps the redirected output open; on Windows deleting it then throws
        Path held = dir.resolve("tool-out.txt");
        java.nio.file.Files.writeString(held, "result");
        try (java.io.RandomAccessFile open = new java.io.RandomAccessFile(held.toFile(), "r")) {
            CommandRunner.discard(held); // must not throw, whether or not the file could be deleted now
            assertTrue(open.length() > 0);
        }
    }
}
