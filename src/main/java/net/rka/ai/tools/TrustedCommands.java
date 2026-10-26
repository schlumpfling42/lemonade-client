package net.rka.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Commands that may run without asking: the global and conversation trusted commands
 * ({@link net.rka.ai.config.Settings}). Each entry is a whole command; {@code *} matches any arguments, e.g.
 * {@code Get-Date, hostname, uname -r, git status*}. A command is never trusted if it contains
 * characters that chain, redirect or substitute commands ({@code ; & | < > ` $ ( ) { }} or a newline),
 * so a trusted prefix can't smuggle in a second command.
 * <p>
 * Entries of the form {@code tool:<name>} (e.g. {@code tool:writeFile}) trust a whole file-changing
 * tool instead ({@link #trustsTool}); those tools are still confined to the trusted folder.
 *
 * <h2>How it works</h2>
 * Each entry becomes a regular expression: the literal text is quoted ({@link Pattern#quote}) and
 * each {@code *} becomes {@code .*}. For example {@code git status*} becomes
 * {@code \Qgit status\E.*}. A call is trusted when its {@code command} argument (read from the
 * call's JSON arguments) matches one pattern <em>completely</em> ({@code matches()}, not {@code find()}).
 * <p>
 * Why the shell-syntax check comes first: {@code git status*} would otherwise also match
 * {@code git status; rm -rf ~}. Rejecting {@code ;}, {@code |}, {@code &&}, {@code $(...)} and friends
 * means the {@code *} can only ever add plain arguments to the trusted command.
 * <p>
 * Whitespace is normalized on both sides (runs of spaces become one), and matching ignores case,
 * which fits PowerShell; bash is case-sensitive, so on Linux {@code UNAME} would also match {@code uname}
 * (harmless: it would simply fail to run).
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Real shell parsing (e.g. with a bash/PowerShell tokenizer) instead of a character blacklist,
 *       which would allow safe quoting like {@code echo "a;b"}.</li>
 *   <li>Per-host trust for SSH: today one list covers both {@code runCommand} and {@code runRemoteCommand}.</li>
 *   <li>Argument constraints, e.g. trust {@code cat *} only for paths inside the trusted folder.</li>
 *   <li>Regex entries for power users ({@code re:^git (status|log)( .*)?$}).</li>
 * </ul>
 */
public final class TrustedCommands {

    /** Characters that could turn one trusted command into several, or write files. */
    private static final Pattern SHELL_SYNTAX = Pattern.compile("[;&|<>`$(){}\\r\\n]");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Prefix of entries that trust a whole tool instead of a command, e.g. {@code tool:writeFile}. */
    public static final String TOOL_PREFIX = "tool:";

    // One compiled pattern per command entry; compiled once, reused for every call in the run
    private final List<Pattern> patterns;
    // Tool names from "tool:<name>" entries (file-changing tools trusted as a whole)
    private final Set<String> tools;

    private TrustedCommands(List<String> entries) {
        this.patterns = entries.stream().filter(e -> !isToolEntry(e)).map(TrustedCommands::toPattern).toList();
        this.tools = entries.stream().filter(TrustedCommands::isToolEntry)
                .map(e -> e.substring(TOOL_PREFIX.length()).strip()).collect(Collectors.toSet());
    }

    /** The trust entry for a whole tool, e.g. {@code tool:writeFile}. */
    public static String toolEntry(String toolName) {
        return TOOL_PREFIX + toolName;
    }

    private static boolean isToolEntry(String entry) {
        return entry.regionMatches(true, 0, TOOL_PREFIX, 0, TOOL_PREFIX.length());
    }

    /** Whether the whole tool is trusted by a {@code tool:<name>} entry (exact name, case-sensitive). */
    public boolean trustsTool(String toolName) {
        return tools.contains(toolName);
    }

    /** @param list comma-separated commands, {@code *} as a wildcard */
    public static TrustedCommands of(String list) {
        return of(Arrays.asList(list.split(",")));
    }

    /** @param commands one command per entry, {@code *} as a wildcard */
    public static TrustedCommands of(List<String> commands) {
        return new TrustedCommands(commands.stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
    }

    public boolean isEmpty() {
        return patterns.isEmpty() && tools.isEmpty();
    }

    /**
     * Whether a command or trust entry can ever be trusted: not blank and free of shell syntax.
     * The approval dialog uses it to offer "always allow" only where it would have an effect.
     */
    public static boolean canBeTrusted(String command) {
        return command != null && !command.isBlank() && !SHELL_SYNTAX.matcher(command).find();
    }

    /** Whether the call's {@code command} argument is on the list. Calls without one are never trusted. */
    public boolean isTrusted(ToolExecutionRequest call) {
        if (patterns.isEmpty()) return false;
        String command = command(call);
        // Fail closed: no command, or any chaining/redirect character, means "not trusted"
        if (!canBeTrusted(command)) return false;
        String normalized = normalize(command);
        return patterns.stream().anyMatch(p -> p.matcher(normalized).matches());
    }

    /** A command with surrounding whitespace removed and runs of whitespace collapsed to one space. */
    public static String normalize(String command) {
        return command.strip().replaceAll("\\s+", " ");
    }

    /** The {@code command} string from the call's JSON arguments, or null if there is none. */
    private static String command(ToolExecutionRequest call) {
        try {
            JsonNode command = JSON.readTree(call.arguments()).get("command");
            return command != null && command.isTextual() ? command.asText() : null;
        } catch (Exception e) {
            return null; // malformed JSON from the model: treat as not trusted
        }
    }

    /** {@code "git status*"} becomes the regex {@code \Qgit status\E.*} (case-insensitive). */
    private static Pattern toPattern(String entry) {
        // split with limit -1 keeps empty strings, so "*foo" and "foo*" both produce the right number of parts
        String[] parts = entry.replaceAll("\\s+", " ").split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) regex.append(".*"); // shell syntax is rejected before matching
            regex.append(Pattern.quote(parts[i])); // literal text: dots, brackets etc. have no regex meaning
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }
}
