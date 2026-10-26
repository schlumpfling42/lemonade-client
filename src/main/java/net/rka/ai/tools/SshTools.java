package net.rka.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import net.rka.ai.config.Env;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Runs commands on remote hosts with the system {@code ssh} client. Only hosts listed in
 * {@code SSH_HOSTS} (comma-separated, e.g. {@code rka@192.168.68.59}) are allowed, and login
 * must work without a password prompt (SSH key or agent). Every command needs the user's approval.
 *
 * <h2>How it works</h2>
 * It starts the operating system's {@code ssh} program (OpenSSH, built into Windows 10+) instead of a
 * Java SSH library, so it reuses your keys, {@code ~/.ssh/config} and {@code known_hosts} as they are.
 * The options matter:
 * <ul>
 *   <li>{@code BatchMode=yes}: never prompt (password, unknown host key); fail instead. The tool has
 *       no way to answer prompts, and stdin is closed by {@link CommandRunner}.</li>
 *   <li>{@code ConnectTimeout=10}: give up quickly when the host is down.</li>
 * </ul>
 * The command is passed as one argument; the remote login shell (fish on the AI server!) interprets
 * it. That's why bash-only syntax like {@code for ...; do ...; done} can fail there.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Choosing the remote shell explicitly ({@code bash -lc '...'}) so syntax doesn't depend on the
 *       login shell.</li>
 *   <li>File transfer tools ({@code scp}/{@code sftp}), also confined to folders.</li>
 *   <li>Per-host trusted commands and per-host working directories.</li>
 *   <li>Host aliases from {@code ~/.ssh/config} listed automatically by {@code sshHosts}.</li>
 * </ul>
 */
public class SshTools {

    // Read once when the tool object is created (at app start)
    private final List<String> hosts = Arrays.stream(Env.get("SSH_HOSTS", "").split(","))
            .map(String::strip).filter(h -> !h.isEmpty()).toList();

    @Tool("Lists the SSH hosts that remote commands can run on")
    public String sshHosts() {
        return hosts.isEmpty() ? "No SSH hosts are configured (SSH_HOSTS in .env)." : String.join("\n", hosts);
    }

    @RequiresApproval
    @Tool("Runs a shell command on a remote host over SSH and returns the exit code, stdout and stderr. "
            + "Call sshHosts first to see which hosts are allowed. Commands can't read input. "
            + "The user must approve each command.")
    public String runRemoteCommand(
            @P("the host, exactly as listed by sshHosts, e.g. user@192.168.1.10") String host,
            @P("the command line to run on the remote host") String command,
            InvocationParameters parameters) throws Exception { // hidden from the model; carries the live output sink
        // Exact match against the allow-list; this also stops "host -o ProxyCommand=..." style injection
        if (!hosts.contains(host)) {
            throw new IllegalArgumentException("Host '" + host + "' is not allowed. Allowed hosts: "
                    + (hosts.isEmpty() ? "none (set SSH_HOSTS in .env)" : String.join(", ", hosts)));
        }
        List<String> ssh = List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", host, command);
        return CommandRunner.run(ssh, Path.of(System.getProperty("user.home")), CommandRunner.defaultTimeout(),
                CommandRunner.output(parameters));
    }
}
