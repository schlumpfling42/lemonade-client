package net.rka.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.rka.ai.config.Settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A short description of this computer (OS, shell, installed tool versions) for a coding job's prompts,
 * so the model doesn't have to discover it with commands on every job.
 *
 * <h2>How it works</h2>
 * {@link #summary()} returns the cached text from {@code environment.json} (next to the global settings
 * file) while it is younger than {@link #MAX_AGE}. Otherwise it runs every command of {@link #PROBES}
 * through the probe runner, builds the text (the {@code systemInfo} lines, then one line per tool),
 * stores {@code {"checked": ISO-instant, "summary": text}} and returns it. A tool whose probe fails or
 * says "not recognized" / "not found" is listed as {@code <tool>: not installed}.
 * <p>
 * {@link #markStale()} deletes the cache, so a job whose verify step showed a missing tool
 * ({@link #looksLikeMissingTool}) re-probes next time. {@link #isProbe} lets the graph run exactly
 * these commands without asking the user.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Probing only the tool that changed after {@code markStale}, instead of all of them.</li>
 *   <li>Project-specific probes (e.g. the Gradle wrapper version in the work folder).</li>
 * </ul>
 */
public final class EnvironmentInfo {

    /** The only commands that run without approval during investigation; matched exactly by {@link #isProbe}. */
    public static final List<String> PROBES = List.of(
            "java -version", "javac -version", "gradle --version", "mvn -v", "node --version",
            "npm --version", "git --version", "python --version", "docker --version");

    /** How long a probe may run before it counts as failed. */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    /** The cache is re-probed after this long. */
    private static final Duration MAX_AGE = Duration.ofDays(7);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path cacheFile;
    private final Function<String, String> probeRunner;
    private final Clock clock;

    /**
     * @param cacheFile   where the summary is stored
     * @param probeRunner runs one probe command and returns its output (tests pass a fake)
     * @param clock       the time source for the cache age (tests pass a fixed clock)
     */
    public EnvironmentInfo(Path cacheFile, Function<String, String> probeRunner, Clock clock) {
        this.cacheFile = cacheFile;
        this.probeRunner = probeRunner;
        this.clock = clock;
    }

    /** The production instance: cache next to the global settings, probes run in the home folder. */
    public static EnvironmentInfo fromSettings() {
        Path home = Path.of(System.getProperty("user.home"));
        return new EnvironmentInfo(Settings.globalFile().resolveSibling("environment.json"), command -> {
            try {
                return CommandRunner.run(CommandRunner.shell(command), home, PROBE_TIMEOUT);
            } catch (IOException | RuntimeException e) { // includes the timeout, which CommandRunner throws
                return "exit code: -1\n" + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // keep the interrupt for the caller
                return "exit code: -1\n" + e.getMessage();
            }
        }, Clock.systemUTC());
    }

    /** The cached summary if fresh, else a newly probed one (which is then cached). */
    public String summary() {
        String cached = readFreshCache();
        if (cached != null) return cached;

        List<String> lines = new ArrayList<>();
        lines.add(new TerminalTools().systemInfo());
        for (String probe : PROBES) {
            String output = probeRunner.apply(probe);
            lines.add(isFailure(output)
                    ? tool(probe) + ": not installed"
                    : probe + ": " + firstOutputLine(output));
        }
        String summary = String.join("\n", lines);
        writeCache(summary);
        return summary;
    }

    /** Forgets the cached summary, so the next {@link #summary()} probes again. */
    public void markStale() {
        try {
            Files.deleteIfExists(cacheFile);
        } catch (IOException e) {
            // Not fatal: the worst case is a few more jobs using the old summary until it ages out
        }
    }

    /** True if the command is exactly one of {@link #PROBES} (whitespace-normalized). */
    public static boolean isProbe(String command) {
        return command != null && PROBES.contains(TrustedCommands.normalize(command));
    }

    /** True if the output looks like a command that isn't installed (Windows or POSIX wording). */
    public static boolean looksLikeMissingTool(String output) {
        if (output == null) return false;
        String text = output.toLowerCase();
        return text.contains("is not recognized") || text.contains("command not found") || text.contains("not found");
    }

    private String readFreshCache() {
        try {
            if (!Files.isRegularFile(cacheFile)) return null;
            JsonNode node = JSON.readTree(cacheFile.toFile());
            Instant checked = Instant.parse(node.get("checked").asText());
            return Duration.between(checked, clock.instant()).compareTo(MAX_AGE) < 0
                    ? node.get("summary").asText()
                    : null;
        } catch (IOException | RuntimeException e) {
            return null; // unreadable or malformed cache: just probe again
        }
    }

    private void writeCache(String summary) {
        try {
            Files.createDirectories(cacheFile.toAbsolutePath().getParent());
            JSON.writeValue(cacheFile.toFile(), Map.of(
                    "checked", clock.instant().toString(), "summary", summary));
        } catch (IOException e) {
            // Not fatal: the summary is still returned, it just isn't cached
        }
    }

    /** A probe failed if the tool is missing or {@link CommandRunner#run} reported a non-zero exit code. */
    private static boolean isFailure(String output) {
        return output == null || looksLikeMissingTool(output)
                || (output.startsWith("exit code: ") && !output.startsWith("exit code: 0"));
    }

    /** The tool's name, for "not installed" lines: the command's first word ({@code mvn -v} gives {@code mvn}). */
    private static String tool(String probe) {
        return probe.split(" ")[0];
    }

    /** The first line that isn't {@link CommandRunner#run}'s framing ("exit code", "--- stdout ---"). */
    private static String firstOutputLine(String output) {
        return output.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("exit code:") && !line.startsWith("---"))
                .findFirst()
                .orElse("(no output)");
    }
}
