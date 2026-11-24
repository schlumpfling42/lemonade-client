package net.rka.ai.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
