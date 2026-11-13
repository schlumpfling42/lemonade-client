package net.rka.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Runs shell commands on the computer the app runs on. Every call needs the user's approval.
 *
 * <h2>How it works</h2>
 * {@code runCommand} wraps the command in the platform shell ({@link CommandRunner#shell}: PowerShell
 * on Windows, bash elsewhere) and runs it in the conversation's trusted folder, or in
 * {@code workingDirectory} resolved against it. Unlike the file tools, the directory is <em>not</em>
 * confined to the folder: a shell command can reach any path anyway ({@code cd ..}, absolute paths),
 * so confinement would only be cosmetic. The protection is that every call is approved by the user,
 * except trusted commands ({@link TrustedCommands}).
 * <p>
 * {@code systemInfo} is safe (no {@code @RequiresApproval}) and lets the model pick the right
 * syntax (PowerShell vs bash) before it writes a command.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>A persistent shell session (keep {@code cd} and variables between calls).</li>
 *   <li>Choosing the shell (cmd, pwsh 7, WSL bash) per call or in settings.</li>
 *   <li>Running long commands in the background and polling their output.</li>
 *   <li>Showing the effective working directory in the approval dialog.</li>
 * </ul>
 */
public class TerminalTools {

    private final net.rka.ai.tools.code.java.JavaWorkspaces workspaces;

    public TerminalTools() {
        this(net.rka.ai.tools.code.java.JavaWorkspaces.shared());
    }

    public TerminalTools(net.rka.ai.tools.code.java.JavaWorkspaces workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * Invocation parameter (Integer seconds) that overrides the global command timeout for one run;
     * a coding job sets it so long builds and test runs aren't killed after the default minute.
     */
    public static final String TIMEOUT_PARAMETER = "commandTimeoutSeconds";

    /**
     * Invocation parameter (a {@code Consumer<String>}) that receives a command's output while it runs,
     * so the window can show it live; the tool result still carries the full (tail of the) output.
     */
    public static final String OUTPUT_PARAMETER = "commandOutput";

    @RequiresApproval
    @Tool("Runs a shell command on this computer and returns the exit code, stdout and stderr. "
            + "The shell is bash on Linux and macOS, PowerShell on Windows. Commands can't read input. "
            + "The user must approve each command, so run one focused command at a time.")
    public String runCommand(
            @P("the command line to run") String command,
            @P(value = "working directory, relative to the conversation's working folder or absolute; "
                    + "leave empty for the working folder", required = false)
            String workingDirectory,
            InvocationParameters parameters) throws Exception { // filled in by LangChain4j, hidden from the model
        Path folder = WorkFolder.of(parameters);
        Path dir = workingDirectory == null || workingDirectory.isBlank()
                ? folder
                : folder.resolve(workingDirectory.strip()).normalize(); // not confined: every command is approved
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Working directory does not exist: " + dir);
        }
        // Per-run timeout if the graph set one, else CommandRunner's global default
        // A build or test reads the files: write the Java changes held in memory first
        net.rka.ai.tools.code.java.JavaWorkspace.FlushResult flushed = workspaces.flush(folder);
        if (!flushed.ok()) {
            throw new IllegalStateException("Not run: these Java files changed on disk while they had changes in memory, so they were not"
                    + " written: " + String.join(", ", flushed.conflicts()) + ". Call javaFlush with force true to write the changes in"
                    + " memory over the files, or javaDiscard to take the files as they are.");
        }
        Object seconds = parameters.asMap().get(TIMEOUT_PARAMETER);
        Duration timeout = seconds instanceof Integer s ? Duration.ofSeconds(s) : CommandRunner.defaultTimeout();
        return CommandRunner.run(CommandRunner.shell(command), dir, timeout, CommandRunner.output(parameters));
    }

    @Tool("Returns the operating system, shell and home directory of this computer")
    public String systemInfo() {
        return "os: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + "\nshell: " + (CommandRunner.isWindows() ? "PowerShell" : "bash")
                + "\nhome: " + System.getProperty("user.home")
                + "\nuser: " + System.getProperty("user.name");
    }
}
