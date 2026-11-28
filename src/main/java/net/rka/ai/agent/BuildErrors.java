package net.rka.ai.agent;

import net.rka.ai.tools.WorkFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds where a build or test run complains: the {@code file:line} positions in its output, with the message that
 * goes with each. The fix step uses them to name the section to repair (the graph looks up which section holds the
 * line), so the model gets one section and its messages instead of the whole log.
 *
 * <pre>
 *   javac, Gradle, Maven, pytest   C:\p\App.java:340: error: cannot find symbol
 *   TypeScript, MSVC               src/app.ts(12,5): error TS2304: Cannot find name 'x'
 *   Python traceback               File "app.py", line 12, in run
 * </pre>
 * Only positions in files inside the trusted folder count (build tools also print library paths); each file and line
 * is listed once.
 */
final class BuildErrors {

    /** One complaint: the file (relative to the folder, with {@code /}), the 1-based line and the message. */
    record Position(String file, int line, String message) {
    }

    private static final String PATH = "(?:[A-Za-z]:)?[^\\s:()'\"<>|]+?\\.\\w{1,6}";
    private static final Pattern COLON = Pattern.compile("(?<path>" + PATH + "):(?<line>\\d+)(?::\\d+)?[: ]\\s*(?<message>.*)");
    private static final Pattern PAREN = Pattern.compile("(?<path>" + PATH + ")\\((?<line>\\d+)(?:,\\d+)?\\)\\s*:\\s*(?<message>.*)");
    /** Maven: {@code [ERROR] /C:/work/App.java:[6,52] package x does not exist} (line and column in brackets after the colon). */
    private static final Pattern BRACKET = Pattern.compile("(?<path>" + PATH + "):\\[(?<line>\\d+)(?:,\\d+)?]\\s*(?<message>.*)");
    private static final Pattern PYTHON = Pattern.compile("File \"(?<path>[^\"]+)\", line (?<line>\\d+)(?<message>.*)");
    private static final int MAX_POSITIONS = 40;

    private BuildErrors() {
    }

    /**
     * @param output the output of the failed commands
     * @param folder the trusted folder: positions elsewhere are left out
     */
    static List<Position> parse(String output, Path folder) {
        Map<String, Position> found = new LinkedHashMap<>();
        List<String> lines = output.replace("\r\n", "\n").lines().toList();
        for (int i = 0; i < lines.size() && found.size() < MAX_POSITIONS; i++) {
            Matcher m = firstMatch(lines.get(i));
            if (m == null) continue;
            String file = relative(m.group("path"), folder);
            if (file == null) continue;
            // javac prints the source line after the message: it helps to see it
            String message = m.group("message").strip();
            if (i + 1 < lines.size() && firstMatch(lines.get(i + 1)) == null && lines.get(i + 1).startsWith(" ")
                    && !lines.get(i + 1).isBlank()) {
                message += "\n" + lines.get(i + 1).strip();
            }
            // and what javac says about the name it could not find: "symbol:   variable INSTANCE" / "location: class X"
            for (int j = i + 2; j < lines.size() && j <= i + 5; j++) {
                String detail = lines.get(j).strip();
                if (detail.startsWith("symbol:") || detail.startsWith("location:")) message += "\n" + detail.replaceAll("\\s+", " ");
            }
            found.putIfAbsent(file + ":" + m.group("line"), new Position(file, Integer.parseInt(m.group("line")), message));
        }
        return new ArrayList<>(found.values());
    }

    private static Matcher firstMatch(String line) {
        for (Pattern pattern : List.of(PAREN, PYTHON, BRACKET, COLON)) {
            Matcher m = pattern.matcher(line);
            if (m.find()) return m;
        }
        return null;
    }

    /** The path relative to the folder with forward slashes, or null when it isn't a file inside the folder. */
    private static String relative(String path, Path folder) {
        try {
            Path file = WorkFolder.resolveInside(folder, path.replace("file:///", "").replace("file://", ""));
            if (!Files.isRegularFile(file)) return null;
            return folder.toRealPath().relativize(file.toRealPath()).toString().replace('\\', '/');
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
