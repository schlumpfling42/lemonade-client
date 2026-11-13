package net.rka.ai.tracing;

import net.rka.ai.config.Env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A readable log of what the agent does: every model request and reply (how big, how long, whether the
 * model server could reuse its cache, what came back) and every tool call (arguments, result, duration).
 * <p>
 * Why next to the traces: the JSONL traces ({@link JsonlFileSpanExporter}) hold everything, but as one
 * JSON object per span, which is hard to read while waiting for a slow turn. This log is meant to be
 * read as it grows ({@code Get-Content -Wait} / {@code tail -f}): one line per event with a readable
 * timestamp, durations in ms or s, and the numbers that explain a wait.
 * <p>
 * Where and how much ({@code .env}):
 * <ul>
 *   <li>{@code AGENT_LOG}: {@code summary} (default: one line per request, reply and tool call),
 *       {@code full} (also the complete messages sent, replies and tool results), or {@code off}.</li>
 *   <li>{@code AGENT_LOG_DIR}: the folder (default: {@code TRACE_DIR}, i.e. {@code traces}); the file is
 *       {@code agent-YYYY-MM-DD.log}, one per day.</li>
 *   <li>{@code AGENT_LOG_CONSOLE}: {@code true} (default) also prints the lines to the console.</li>
 * </ul>
 * The log is set once at startup ({@link #configure}, from {@code Agent.fromEnv}); until then, and in
 * tests, {@link #current()} is {@link #OFF}, so nothing is written.
 * <p>
 * Not implemented yet (could be added): rotating or deleting old files; a log viewer in the window.
 */
public final class AgentLog {

    public enum Level { OFF, SUMMARY, FULL }

    /** Writes nothing. */
    public static final AgentLog OFF = new AgentLog(Level.OFF, null, false);

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static volatile AgentLog current = OFF;

    private final Level level;
    private final Path dir;
    private final boolean console;

    public AgentLog(Level level, Path dir, boolean console) {
        this.level = level;
        this.dir = dir;
        this.console = console;
    }

    /** The log from {@code .env} (see the class comment). */
    public static AgentLog fromEnv() {
        Level level = switch (Env.get("AGENT_LOG", "summary").toLowerCase(Locale.ROOT)) {
            case "off", "false", "none" -> Level.OFF;
            case "full", "all", "debug" -> Level.FULL;
            default -> Level.SUMMARY;
        };
        Path dir = Path.of(Env.get("AGENT_LOG_DIR", Env.get("TRACE_DIR", "traces")));
        return new AgentLog(level, dir, !"false".equalsIgnoreCase(Env.get("AGENT_LOG_CONSOLE", "true")));
    }

    /** Makes {@code log} the one {@link #current()} returns (called once at startup). */
    public static void configure(AgentLog log) {
        current = log == null ? OFF : log;
    }

    public static AgentLog current() {
        return current;
    }

    public boolean on() {
        return level != Level.OFF;
    }

    /** Whether complete messages and results are logged too ({@code AGENT_LOG=full}). */
    public boolean full() {
        return level == Level.FULL;
    }

    /** The file of today's log ({@code null} when off). */
    public Path file() {
        return dir == null ? null : dir.resolve("agent-" + LocalDate.now() + ".log");
    }

    /** One event line, with the time in front. */
    public void line(String text) {
        if (!on()) return;
        write(LocalDateTime.now().format(TIME) + "  " + text + System.lineSeparator());
    }

    /** A multi-line block (full level only): a heading line, then the text indented, so it stands apart. */
    public void block(String heading, String text) {
        if (!full()) return;
        StringBuilder out = new StringBuilder(LocalDateTime.now().format(TIME)).append("  ").append(heading)
                .append(System.lineSeparator());
        for (String l : String.valueOf(text).split("\\R", -1)) out.append("      │ ").append(l).append(System.lineSeparator());
        write(out.toString());
    }

    private synchronized void write(String text) {
        if (console) System.out.print(text);
        Path file = file();
        if (file == null) return;
        try {
            Files.createDirectories(dir);
            Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the log is a help, never a reason for a turn to fail
        }
    }

    /** "1.234 s" for a second or more, else "56 ms". */
    public static String duration(long nanos) {
        long ms = nanos / 1_000_000;
        return ms >= 1000 ? String.format(Locale.ROOT, "%.1f s", ms / 1000.0) : ms + " ms";
    }

    /** The text on one line, cut to {@code max} characters, for summary lines. */
    public static String oneLine(String text, int max) {
        String flat = String.valueOf(text).replaceAll("\\s+", " ").strip();
        return flat.length() > max ? flat.substring(0, max - 1) + "…" : flat;
    }
}
