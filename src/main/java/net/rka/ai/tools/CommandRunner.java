package net.rka.ai.tools;

import dev.langchain4j.invocation.InvocationParameters;
import net.rka.ai.config.Env;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs an external process with a timeout and returns its exit code and (truncated) output.
 * Shared by {@link TerminalTools} (local shell) and {@link SshTools} (the {@code ssh} client).
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li>stdout and stderr go to temporary <em>files</em>, not pipes. With pipes, a process that writes
 *       a lot would block once the pipe buffer is full unless another thread keeps reading; files
 *       avoid that without extra threads.</li>
 *   <li>stdin is closed right away, so a command that waits for input (a password prompt, {@code more})
 *       gets end-of-file and fails instead of hanging until the timeout.</li>
 *   <li>On timeout the whole process tree is killed ({@code descendants()}), because the shell's
 *       children would otherwise keep running.</li>
 *   <li>While the process runs, the files are polled every {@value #POLL_MILLIS} ms and anything new is
 *       passed to the caller's output consumer ({@link TerminalTools#OUTPUT_PARAMETER}), so the window
 *       shows the output live; the returned result is still built from the whole files at the end.</li>
 *   <li>Only the <em>end</em> of long output is kept ({@code COMMAND_MAX_OUTPUT_CHARS}), since errors
 *       and summaries usually come last, and the model's context window is limited.</li>
 * </ul>
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>A cancel button wired to {@code destroyForcibly}.</li>
 *   <li>Sandboxing (a container, a restricted user, or Windows job objects limiting CPU/memory/network).</li>
 *   <li>Output encoding: it's read with the platform charset; PowerShell may emit UTF-16 or the
 *       console code page, so non-ASCII text can come out garbled. Forcing
 *       {@code [Console]::OutputEncoding = UTF8} in the PowerShell command line would fix it.</li>
 *   <li>Keeping head <em>and</em> tail of long output instead of only the tail.</li>
 * </ul>
 */
final class CommandRunner {

    // Read once when the class loads; changing them in .env needs a restart
    private static final Duration TIMEOUT = Duration.ofSeconds(
            Long.parseLong(Env.get("COMMAND_TIMEOUT_SECONDS", "60")));
    private static final int MAX_OUTPUT_CHARS = Integer.parseInt(Env.get("COMMAND_MAX_OUTPUT_CHARS", "8000"));

    private CommandRunner() {
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /** The command line wrapped in the platform's shell. */
    static List<String> shell(String command) {
        // The whole command is ONE argument to the shell, so pipes, quotes etc. are interpreted by the shell
        return isWindows()
                ? List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                : List.of("/bin/bash", "-c", command);
    }

    /**
     * Runs the process and returns "exit code: N" plus stdout and stderr. Throws on a timeout,
     * so the tool result is flagged as an error.
     */
    static String run(List<String> commandLine, Path workingDirectory) throws IOException, InterruptedException {
        return run(commandLine, workingDirectory, TIMEOUT);
    }

    /** Like {@link #run(List, Path)} with an explicit timeout (coding jobs allow long builds; probes are short). */
    static String run(List<String> commandLine, Path workingDirectory, Duration timeout)
            throws IOException, InterruptedException {
        return run(commandLine, workingDirectory, timeout, chunk -> { });
    }

    /** The global command timeout ({@code COMMAND_TIMEOUT_SECONDS}). */
    static Duration defaultTimeout() {
        return TIMEOUT;
    }

    /** The live output consumer the graph put into the invocation parameters, or one that ignores everything. */
    @SuppressWarnings("unchecked")
    static Consumer<String> output(InvocationParameters parameters) {
        Object consumer = parameters == null ? null : parameters.asMap().get(TerminalTools.OUTPUT_PARAMETER);
        return consumer instanceof Consumer<?> c ? (Consumer<String>) c : chunk -> { };
    }

    private static final long POLL_MILLIS = 250; // how often running output is passed on

    /**
     * Like {@link #run(List, Path, Duration)}, and passes new output to {@code onOutput} while the
     * process runs (stdout and stderr as they arrive, in pieces of any size).
     */
    static String run(List<String> commandLine, Path workingDirectory, Duration timeout, Consumer<String> onOutput)
            throws IOException, InterruptedException {
        Path stdout = Files.createTempFile("tool-out", ".txt");
        Path stderr = Files.createTempFile("tool-err", ".txt");
        try {
            Process process = new ProcessBuilder(commandLine)
                    .directory(workingDirectory.toFile())
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start();
            process.getOutputStream().close(); // no interactive input: commands that prompt fail fast
            // Wait in short slices; after each, pass on what the process wrote since the last look
            long deadline = System.nanoTime() + timeout.toNanos();
            long[] seen = {0, 0}; // bytes of stdout / stderr already passed on
            boolean finished = false;
            while (!finished && System.nanoTime() < deadline) {
                long slice = Math.min(POLL_MILLIS, Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
                finished = process.waitFor(slice, TimeUnit.MILLISECONDS);
                seen[0] = passOn(stdout, seen[0], onOutput);
                seen[1] = passOn(stderr, seen[1], onOutput);
            }
            if (!finished) {
                // Kill children first (e.g. what PowerShell started), then the shell itself
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IllegalStateException("Command timed out after " + timeout.toSeconds()
                        + "s and was stopped. Partial output:\n" + tail(read(stdout)));
            }
            // A non-zero exit code is NOT an exception: the model gets the code and stderr and can react
            return "exit code: " + process.exitValue()
                    + "\n--- stdout ---\n" + tail(read(stdout))
                    + "\n--- stderr ---\n" + tail(read(stderr));
        } finally {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    /**
     * Passes on the bytes of {@code file} after {@code from} and returns the new position. Decoding
     * pieces separately can split a multi-byte character at the edge; that only affects the live view,
     * not the result, which is read from the whole file.
     */
    private static long passOn(Path file, long from, Consumer<String> onOutput) {
        try (RandomAccessFile in = new RandomAccessFile(file.toFile(), "r")) {
            long length = in.length();
            if (length <= from) return from;
            byte[] bytes = new byte[(int) Math.min(length - from, 1 << 20)]; // at most 1 MB per look
            in.seek(from);
            in.readFully(bytes);
            try {
                onOutput.accept(new String(bytes, Charset.defaultCharset()));
            } catch (RuntimeException ignored) {
                // the live view is only a display; it never fails the command
            }
            return from + bytes.length;
        } catch (IOException e) {
            return from; // try again on the next look
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, Charset.defaultCharset()).strip();
    }

    /** Keeps the end of long output, where errors and results usually are. */
    private static String tail(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return "[... " + (text.length() - MAX_OUTPUT_CHARS) + " earlier characters cut ...]\n"
                + text.substring(text.length() - MAX_OUTPUT_CHARS);
    }
}
