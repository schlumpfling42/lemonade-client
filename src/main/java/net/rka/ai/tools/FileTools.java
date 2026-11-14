package net.rka.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import net.rka.ai.tools.outline.Outline;
import net.rka.ai.tools.outline.Outlines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * File operations confined to the conversation's trusted working folder ({@link WorkFolder}).
 * Every path is checked to stay inside the folder; paths outside it are refused. Reading runs
 * without asking; changing files asks for approval unless the tool is trusted. Paths are relative
 * to the folder (absolute paths inside it also work).
 *
 * <h2>How it works</h2>
 * Every method follows the same three steps:
 * <ol>
 *   <li>get the conversation's folder (and the folders to refuse) from the hidden
 *       {@link InvocationParameters} argument ({@link WorkFolder#of});</li>
 *   <li>turn the model's path into a checked absolute path with {@link WorkFolder#resolveInside}, which
 *       throws for anything outside the folder, and for the skill folder even inside it (the exception
 *       becomes an error result the model reads);</li>
 *   <li>do the operation with {@code java.nio.file.Files}.</li>
 * </ol>
 * Reading tools ({@code listFiles}, {@code findFiles}, {@code readFile}) always run freely. Changing
 * tools carry {@link WritesFiles}: the graph asks you before each call, unless you trusted the tool
 * ("Always" in the approval dialog adds {@code tool:writeFile} etc.), and always after web content
 * entered the turn. Output sizes are capped ({@code MAX_READ_CHARS}, {@code MAX_LISTED})
 * so one call can't flood the model's context window.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Editing part of a file (replace a string or a line range) instead of rewriting it entirely;
 *       big files are expensive to send back and forth.</li>
 *   <li>Searching file <em>contents</em> (a grep tool), and reading a line range of a large file.</li>
 *   <li>Binary files: images could be passed to a vision model; today non-UTF-8 files fail to read.</li>
 *   <li>Undo: keep a copy of overwritten/deleted files (e.g. in a {@code .trash} folder) for a while.</li>
 *   <li>Showing a diff in the approval dialog when {@code writeFile} replaces an existing file.</li>
 * </ul>
 */
public class FileTools {

    private final net.rka.ai.tools.code.java.JavaWorkspaces workspaces;

    public FileTools() {
        this(net.rka.ai.tools.code.java.JavaWorkspaces.shared());
    }

    public FileTools(net.rka.ai.tools.code.java.JavaWorkspaces workspaces) {
        this.workspaces = workspaces;
    }

    /** Java files of a Java project are read and written in memory (see {@link OutlineTools}), all other files on disk. */
    private net.rka.ai.tools.code.java.ProjectFiles files(InvocationParameters parameters) {
        return new net.rka.ai.tools.code.java.ProjectFiles(WorkFolder.of(parameters), workspaces).fresh();
    }

    private static final int WHOLE_FILE_CHARS = 6_000;   // a file is read whole only when it is this small; a code file of more is read by its parts
    private static final int MAX_READ_CHARS = 12_000;  // ~3.5k tokens per read: file text stays in the conversation, so a read is kept small
    private static final int MAX_LISTED = 500;         // entries per listing / search

    @Tool("Returns the conversation's working folder. File tools only work inside it, "
            + "and paths are relative to it.")
    public String workFolder(InvocationParameters parameters) {
        // LangChain4j fills `parameters` with the run's values; the model can't see or set it
        return WorkFolder.of(parameters).toString();
    }

    @Tool("Lists a directory inside the working folder: one entry per line, directories end with '/'")
    public String listFiles(
            @P(value = "directory relative to the working folder; empty for the folder itself", required = false) String path,
            InvocationParameters parameters) throws IOException {
        // Throws for anything outside the folder; the message goes back to the model as an error result
        Path dir = WorkFolder.resolveInside(parameters, path);
        net.rka.ai.tools.code.java.ProjectFiles files = files(parameters);
        Path root = WorkFolder.resolveInside(parameters, "");
        String folder = root.relativize(dir).toString().replace('\\', '/');
        String prefix = folder.isEmpty() ? "" : folder + "/";
        // A folder that exists only in memory (a package of new Java files) is a directory too
        boolean onlyInMemory = !Files.isDirectory(dir) && files.javaPaths().stream().anyMatch(p -> p.startsWith(prefix));
        if (!Files.isDirectory(dir) && !onlyInMemory) throw new IllegalArgumentException("Not a directory: " + path);
        // Files.list holds the directory open until the stream is closed, hence try-with-resources
        try (Stream<Path> entries = onlyInMemory ? Stream.<Path>empty() : Files.list(dir)) {
            // Java files are listed as they are in memory: new ones show, ones deleted in memory don't
            java.util.TreeSet<String> names = new java.util.TreeSet<>(); // alphabetical, so the output is stable
            entries.filter(p -> !p.getFileName().toString().endsWith(".java") || !files.holdsJava() || files.isRegularFile(p))
                    .forEach(p -> names.add(p.getFileName() + (Files.isDirectory(p) ? "/" : ""))); // trailing / marks folders
            for (String java : files.javaPaths()) {
                if (!java.startsWith(prefix)) continue;
                String rest = java.substring(prefix.length());
                int slash = rest.indexOf('/');
                names.add(slash < 0 ? rest : rest.substring(0, slash + 1)); // a folder that exists only in memory too
            }
            // Take one more than the limit, so we can tell whether entries were cut off
            List<String> lines = names.stream().limit(MAX_LISTED + 1).toList();
            if (lines.isEmpty()) return "(empty)";
            return lines.size() > MAX_LISTED
                    ? String.join("\n", lines.subList(0, MAX_LISTED)) + "\n… more entries not shown"
                    : String.join("\n", lines);
        }
    }

    /**
     * The matcher for a glob as people write it: in Java's syntax a glob with a "double star, slash" in the middle (ui, double star, slash, *.java)
     * needs a folder at that place, so it would miss ui/MarkdownView.java and a search would answer "no matches" for code that is there.
     * Here every double star with a slash after it also counts as no folder at all.
     */
    static PathMatcher globMatcher(String glob) {
        String plain = glob.replace("**/", "");
        String pattern = glob.contains("**/") && !glob.contains("{") && !glob.contains("}") ? "{" + glob + "," + plain + "}" : glob;
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    /**
     * Folders that searches and globs skip: build output, version control, dependencies and tool caches.
     * They hold thousands of generated files that crowd out the sources (a {@code **}{@code /*.java} glob found
     * {@code build/generated/...} first) and are never what a job should read or change. A glob or search
     * that names such a folder ("build/**") still looks inside it.
     */
    public static final Set<String> SKIPPED_FOLDERS = Set.of("build", "target", "out", "dist", "node_modules", ".git",
            ".gradle", ".idea", ".vscode", ".svelte-kit", "__pycache__", ".venv", ".superpowers", "bin",
            // what the agent itself writes: logs, saved conversations, evaluation runs (copies of whole projects)
            "traces", "conversations");

    /** Whether the folder holds what the agent itself produced and no work of the user: evaluation results ({@code evals/results}). */
    public static boolean generatedOutput(Path dir) {
        return dir.getFileName() != null && dir.getFileName().toString().equals("results")
                && dir.getParent() != null && dir.getParent().getFileName() != null && dir.getParent().getFileName().toString().equals("evals");
    }

    /** Whether a walk below {@code root} should skip {@code dir}, unless {@code pattern} names it. */
    static boolean skipped(Path root, Path dir, String pattern) {
        if (dir.equals(root)) return false;
        String name = dir.getFileName().toString();
        return (SKIPPED_FOLDERS.contains(name) || generatedOutput(dir)) && (pattern == null || !pattern.contains(name));
    }

    private static final int MAX_MATCHES = 40;             // lines a search returns at most (each is up to 200 characters)
    private static final int MAX_MATCH_LINE_CHARS = 200;   // each line cut to this
    private static final long MAX_SEARCHED_FILE_BYTES = 2_000_000; // bigger files aren't sources

    /**
     * Searches file contents: every line that matches, as {@code path:line: text}.
     * <p>
     * Why: to change one method in a big file, reading the file costs thousands of tokens that are sent
     * again on every later request, and a local model is slow at reading them. A search finds the place
     * (a method name, a constant, a message) for a few dozen tokens; {@code readFile} with
     * {@code startLine}/{@code endLine} then reads just the lines around it.
     */
    @Tool("Searches the contents of text files inside the working folder for a regular expression (or plain text). "
            + "The matches are grouped by the part of the file they are in (for Java the method, constructor or field; for "
            + "Markdown the heading; otherwise a block of lines), each as: path · section id · lines, then its matching lines. "
            + "Read a whole part with readSection(path, section id)")
    public String searchFiles(
            @P("regular expression or plain text to look for, e.g. 'class CodingGraph' or 'MAX_STEPS'") String pattern,
            @P(value = "glob of the files to search, e.g. **/*.java; empty for all files", required = false) String glob,
            @P(value = "true to ignore upper/lower case", required = false) Boolean ignoreCase,
            InvocationParameters parameters) throws IOException {
        if (pattern == null || pattern.isEmpty()) throw new IllegalArgumentException("pattern is empty");
        Path root = WorkFolder.resolveInside(parameters, "");
        int flags = Boolean.TRUE.equals(ignoreCase) ? java.util.regex.Pattern.CASE_INSENSITIVE : 0;
        java.util.regex.Pattern regex;
        try {
            regex = java.util.regex.Pattern.compile(pattern, flags);
        } catch (java.util.regex.PatternSyntaxException e) {
            regex = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(pattern), flags); // plain text then
        }
        java.util.regex.Pattern lineRegex = regex;
        PathMatcher matcher = glob == null || glob.isBlank() ? null : globMatcher(glob);
        // The matching lines per file, with the file's lines to place them in its parts afterwards
        Map<String, List<String>> texts = new java.util.LinkedHashMap<>();
        Map<String, List<Integer>> hits = new java.util.LinkedHashMap<>();
        int[] more = {0};
        int[] count = {0};
        net.rka.ai.tools.code.java.ProjectFiles files = files(parameters);
        boolean java = files.holdsJava();
        java.util.function.BiConsumer<String, List<String>> search = (name, lines) -> {
            for (int i = 0; i < lines.size(); i++) {
                if (!lineRegex.matcher(lines.get(i)).find()) continue;
                if (count[0] >= MAX_MATCHES) {
                    more[0]++;
                    continue;
                }
                count[0]++;
                texts.putIfAbsent(name, lines);
                hits.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
            }
        };
        // The Java files of a Java project are searched as they are in memory (changes not written yet included)
        for (String path : files.javaPaths()) {
            if (matcher != null && !matcher.matches(Path.of(path))) continue;
            try {
                String text = files.readString(root.resolve(path));
                if (text.length() <= MAX_SEARCHED_FILE_BYTES) search.accept(path, text.lines().toList());
            } catch (IOException | RuntimeException e) {
                // gone meanwhile: nothing to search
            }
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return skipped(root, dir, glob) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = root.relativize(file);
                if (inMemory(files, java, file, relative)) return FileVisitResult.CONTINUE; // searched from memory, above
                if (matcher != null && !matcher.matches(relative)) return FileVisitResult.CONTINUE;
                if (attrs.size() > MAX_SEARCHED_FILE_BYTES) return FileVisitResult.CONTINUE;
                List<String> lines;
                try {
                    lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException e) {
                    return FileVisitResult.CONTINUE; // binary or another encoding: not a text file to search
                }
                search.accept(relative.toString().replace('\\', '/'), lines);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        if (hits.isEmpty()) return "No matches for " + pattern + (matcher == null ? "" : " in " + glob);
        return placed(hits, texts) + (more[0] > 0 ? "\n… " + more[0] + " more matching lines not shown; narrow the pattern or the glob" : "");
    }

    @Tool("Finds the parts of the project that match SEVERAL terms at once, best first: give every word you would search "
            + "for in one call (names, UI words, style classes), e.g. 'conversation row menu button folder'. A part ranks higher "
            + "the more of the terms its name and code use; each result is path · section id · lines with its first lines, "
            + "ready for readSection. Use this first, instead of one search per term")
    public String findParts(@P("all the terms, separated by spaces") String terms, InvocationParameters parameters) {
        if (terms == null || terms.isBlank()) throw new IllegalArgumentException("terms is empty");
        List<net.rka.ai.agent.Locator.Candidate> found = new ArrayList<>();
        workspaces.workspace(WorkFolder.of(parameters)).ifPresent(w -> found.addAll(net.rka.ai.agent.Locator.find(w, terms, 8)));
        found.addAll(net.rka.ai.agent.Locator.findInTextFiles(WorkFolder.of(parameters), terms, 8));
        found.sort(java.util.Comparator.comparingInt(net.rka.ai.agent.Locator.Candidate::score).reversed());
        if (found.isEmpty()) return "No part of the project matches these terms together. Try fewer or other terms, or where for one name.";
        StringBuilder out = new StringBuilder();
        for (net.rka.ai.agent.Locator.Candidate part : found.subList(0, Math.min(10, found.size()))) {
            out.append(part.path()).append(part.id().isEmpty() ? "" : " · " + part.id()).append(" · lines ").append(part.startLine()).append('–').append(part.endLine()).append('\n');
            part.excerpt().lines().limit(2).forEach(l -> out.append("  ").append(l.strip().length() > 120 ? l.strip().substring(0, 119) + "…" : l.strip()).append('\n'));
        }
        return out.toString().stripTrailing();
    }

    @Tool("Where a name is defined and where it is used: a class, method, field, style class, key or any word. Like searchFiles "
            + "for the whole word, with the parts that define it (for Java a member or type of that name) first and marked as defined, "
            + "then the parts that use it. Each part is path · section id · lines; read one whole with readSection")
    public String where(@P("the name, as written in the code") String name, InvocationParameters parameters) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is empty");
        String word = name.strip();
        String result = searchFiles("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(word) + "(?![A-Za-z0-9_])", null, null, parameters);
        if (result.startsWith("No matches")) return "No match for " + word + " in the project.";
        List<String> defined = new ArrayList<>();
        List<String> used = new ArrayList<>();
        String tail = "";
        for (String block : result.split("\n(?=[^ ])")) {
            if (block.startsWith("…")) {
                tail = "\n" + block;
                continue;
            }
            String header = block.lines().findFirst().orElse("");
            String[] cells = header.split(" · ");
            String id = cells.length > 1 ? cells[1] : "";
            String simple = id.replaceAll("\\(.*$", "");
            simple = simple.substring(simple.lastIndexOf('.') + 1);
            if (simple.equals(word)) defined.add(header + " — defined here" + block.substring(header.length()));
            else used.add(block);
        }
        return String.join("\n", defined) + (defined.isEmpty() || used.isEmpty() ? "" : "\n") + String.join("\n", used) + tail;
    }

    private static final int MAX_LINES_PER_PART = 3;

    /**
     * The matches grouped by the smallest part of their file that holds them, each part as {@code path · id · lines a–b (kind)} (what
     * readSection takes) with its first matching lines: a search says where something is, as a unit that can be read whole.
     */
    private static String placed(Map<String, List<Integer>> hits, Map<String, List<String>> texts) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<Integer>> file : hits.entrySet()) {
            List<String> lines = texts.get(file.getKey());
            Outline outline = null;
            try {
                outline = Outlines.of(Path.of(file.getKey()).getFileName().toString(), String.join("\n", lines));
            } catch (RuntimeException e) {
                // no parts: the lines are shown under the file
            }
            Map<String, List<Integer>> byPart = new java.util.LinkedHashMap<>();
            Map<String, net.rka.ai.tools.outline.Section> parts = new java.util.HashMap<>();
            for (int index : file.getValue()) {
                net.rka.ai.tools.outline.Section part = outline == null ? null : innermost(outline, index + 1);
                String key = part == null ? "" : part.id();
                if (part != null) parts.put(key, part);
                byPart.computeIfAbsent(key, k -> new ArrayList<>()).add(index);
            }
            for (Map.Entry<String, List<Integer>> group : byPart.entrySet()) {
                net.rka.ai.tools.outline.Section part = parts.get(group.getKey());
                out.append(file.getKey());
                if (part != null) out.append(" · ").append(part.id()).append(" · lines ").append(part.start()).append('–').append(part.end()).append(" (").append(part.kind()).append(')');
                out.append('\n');
                List<Integer> shown = group.getValue();
                for (int index : shown.subList(0, Math.min(MAX_LINES_PER_PART, shown.size()))) {
                    String line = lines.get(index).strip();
                    if (line.length() > MAX_MATCH_LINE_CHARS) line = line.substring(0, MAX_MATCH_LINE_CHARS) + " …";
                    out.append("  ").append(index + 1).append(": ").append(line).append('\n');
                }
                if (shown.size() > MAX_LINES_PER_PART) out.append("  … ").append(shown.size() - MAX_LINES_PER_PART).append(" more in this part\n");
            }
        }
        return out.toString().stripTrailing();
    }

    /** The smallest part of the outline that holds the line, or null when none does. */
    private static net.rka.ai.tools.outline.Section innermost(Outline outline, int line) {
        net.rka.ai.tools.outline.Section best = null;
        for (net.rka.ai.tools.outline.Section section : outline.sections()) {
            if (section.start() <= line && line <= section.end() && (best == null || section.end() - section.start() < best.end() - best.start())) best = section;
        }
        return best;
    }

    /** A Java file the workspace holds, or has deleted in memory: the disk walk leaves it out (memory is the truth). */
    private static boolean inMemory(net.rka.ai.tools.code.java.ProjectFiles files, boolean java, Path file, Path relative) {
        if (!java || !file.getFileName().toString().endsWith(".java")) return false;
        return files.javaPaths().contains(relative.toString().replace('\\', '/')) || !files.isRegularFile(file);
    }

    @Tool("Finds files inside the working folder whose path matches a glob, e.g. '**/*.java' or '*.txt'")
    public String findFiles(
            @P("glob relative to the working folder, e.g. **/*.md") String glob,
            InvocationParameters parameters) throws IOException {
        Path root = WorkFolder.resolveInside(parameters, ""); // the folder's real path
        // Glob syntax: * matches within one directory level, ** across levels, {a,b} alternatives
        PathMatcher matcher = globMatcher(glob);
        List<String> found = new ArrayList<>();
        // The Java files of a Java project are those in memory (new ones included, deleted ones not)
        net.rka.ai.tools.code.java.ProjectFiles files = files(parameters);
        boolean java = files.holdsJava();
        files.javaPaths().stream().filter(p -> matcher.matches(Path.of(p))).sorted().map(p -> Path.of(p).toString()).forEach(found::add);
        // walkFileTree visits every file below root and calls visitFile for each
        Files.walkFileTree(root, new SimpleFileVisitor<>() { // doesn't follow symlinks out of the folder
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return skipped(root, dir, glob) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = root.relativize(file); // match "src/Main.java", not the absolute path
                if (matcher.matches(relative) && !inMemory(files, java, file, relative)) found.add(relative.toString());
                return found.size() > MAX_LISTED ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE; // unreadable entries are skipped
            }
        });
        if (found.isEmpty()) return "No files match " + glob;
        return found.size() > MAX_LISTED
                ? String.join("\n", found.subList(0, MAX_LISTED)) + "\n… more matches not shown"
                : String.join("\n", found);
    }

    @Tool("Reads a small text file whole (up to about 150 lines). A bigger code file is not read whole: you get its outline, "
            + "and read the parts you need whole with readSection. For a big file without parts (a log, data) pass startLine "
            + "(and optionally endLine)")
    public String readFile(@P("file path relative to the working folder") String path,
                           @P(value = "first line to read, starting at 1; omit to start at the top", required = false)
                           Integer startLine,
                           @P(value = "last line to read; omit to read as much as fits", required = false)
                           Integer endLine,
                           InvocationParameters parameters) throws IOException {
        Path file = WorkFolder.resolveInside(parameters, path);
        net.rka.ai.tools.code.java.ProjectFiles files = files(parameters);
        if (!files.isRegularFile(file)) throw new IllegalArgumentException("Not a file: " + path);
        // Reads the whole file as UTF-8 in one go: decoding fixed-size chunks separately would split
        // multi-byte characters at chunk edges; a binary or other-encoded file throws (→ error result)
        String text = files.readString(file);
        // A small file is read whole; so is a file without parts (not code) that a read holds
        if (startLine == null && endLine == null && (text.length() <= WHOLE_FILE_CHARS
                || text.length() <= MAX_READ_CHARS && !Outlines.of(file.getFileName().toString(), text).structured())) return text;
        if (startLine == null && endLine == null) {
            // A big code file doesn't fit: its first 240 lines are rarely the ones needed, so the model gets the
            // file's outline (its parts and md5) and reads or changes the parts it wants
            Outline outline = Outlines.of(file.getFileName().toString(), text);
            if (outline.structured() && outline.sections().size() >= 3) {
                return outline.render(path, null) + "\nThis file is big, so this is its outline instead of its text: read a part"
                        + " with readSection, change it with editSection, or read lines with readFile startLine/endLine.";
            }
        }
        List<String> lines = text.lines().toList();
        int first = Math.max(1, startLine == null ? 1 : startLine);
        int last = Math.min(lines.size(), endLine == null ? lines.size() : endLine);
        if (lines.isEmpty()) return "";
        if (first > lines.size()) {
            throw new IllegalArgumentException("The file has only " + lines.size() + " lines");
        }
        if (first > last) throw new IllegalArgumentException("startLine is after endLine");
        // Whole lines up to MAX_READ_CHARS, so one read never floods the model's context
        StringBuilder result = new StringBuilder();
        int line = first;
        while (line <= last && result.length() + lines.get(line - 1).length() + 1 <= MAX_READ_CHARS) {
            result.append(lines.get(line - 1)).append('\n');
            line++;
        }
        if (line == first && line <= last) { // a single line longer than the cap: show its start, move on
            result.append(lines.get(line - 1), 0, MAX_READ_CHARS).append(" …\n");
            line++;
        }
        boolean whole = first == 1 && line > lines.size();
        return whole ? result.toString()
                : result + "… lines " + first + "–" + Math.max(first, line - 1) + " of " + lines.size()
                        + (line <= last ? "; call readFile again with startLine " + line + " for more" : "");
    }

    @WritesFiles
    @Tool("Changes part of a file inside the working folder like a patch. Write hunks: "
            + "lines that start with a blank are context that stays, '-' lines go, '+' lines are added; start a new hunk with a line @@. "
            + "Each hunk is found by its context and removed lines, which must be in the file exactly once, so the change lands in the "
            + "right place whatever was added or moved above it; indentation does not need to match. Copy the lines that stay from "
            + "readFile. Refused, with nothing changed, when a hunk fits nowhere or in several places (it says where)")
    public String patchFile(
            @P("file path relative to the working folder") String path,
            @P("the hunks") String patch,
            InvocationParameters parameters) throws IOException {
        Path file = WorkFolder.resolveInside(parameters, path);
        // A Java file of a Java project is read from and written to memory with the others (see OutlineTools), so nothing is out of step
        net.rka.ai.tools.code.java.ProjectFiles files = files(parameters);
        boolean inMemory = path.endsWith(".java") && workspaces.workspace(WorkFolder.of(parameters)).isPresent();
        if (inMemory ? !files.isRegularFile(file) : !Files.isRegularFile(file)) throw new IllegalArgumentException("Not a file: " + path);
        String text = inMemory ? files.readString(file) : Files.readString(file, StandardCharsets.UTF_8);
        boolean crlf = text.contains("\r\n");
        List<String> lines = List.of(text.replace("\r\n", "\n").split("\n", -1));
        TextPatch.Result result = TextPatch.apply(lines, 1, patch);
        String changed = String.join(crlf ? "\r\n" : "\n", result.lines());
        if (inMemory) files.writeString(file, changed);
        else Files.writeString(file, changed, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        return "Patched " + path + " with " + result.hunks() + " hunk(s): +" + result.added() + " −" + result.removed() + " lines";
    }

    @WritesFiles
    @Tool("Writes a text file inside the working folder, creating it and its parent directories, "
            + "or, with overwrite, replacing an existing file; set append to add to the end instead. To change an "
            + "existing file don't write it again: look at it with outline and change a part with editSection. Write "
            + "a long new file in parts: the first part, then the rest with append")
    public String writeFile(
            @P("file path relative to the working folder") String path,
            @P("the text to write") String content,
            @P(value = "true to append instead of replacing", required = false) Boolean append,
            @P(value = "true to replace a file that already exists (editSection is usually better)",
                    required = false) Boolean overwrite,
            InvocationParameters parameters) throws IOException {
        Path file = WorkFolder.resolveInside(parameters, path);
        BinaryFiles.refusalForPath(path).ifPresent(reason -> { throw new IllegalArgumentException(reason); });
        if (Files.isDirectory(file)) throw new IllegalArgumentException("Is a directory: " + path);
        // A model that writes a file it never read replaces code it hasn't seen (and spends minutes writing
        // every line again): replacing takes an explicit overwrite, and the error points to editSection
        net.rka.ai.tools.code.java.ProjectFiles files = files(parameters);
        if (files.exists(file) && !Boolean.TRUE.equals(append) && !Boolean.TRUE.equals(overwrite)) {
            throw new IllegalArgumentException(path + " already exists. Look at its outline and change the part you need"
                    + " with editSection; set overwrite to true only to replace the whole file");
        }
        if (path.endsWith(".java") && workspaces.workspace(WorkFolder.of(parameters)).isPresent()) {
            // a Java file of a Java project goes to memory (written with the others, see OutlineTools)
            String before = Boolean.TRUE.equals(append) && files.isRegularFile(file) ? files.readString(file) : "";
            files.writeString(file, before + content);
            return "Wrote " + content.length() + " characters to " + file; // written to the file right after this call (ToolRegistry.afterWrite)
        }
        Files.createDirectories(file.getParent()); // "notes/2026/today.md" creates notes/2026 as needed
        // Boolean (not boolean): the argument is optional, so it may be null = replace.
        // Files.writeString encodes as UTF-8 and either appends or truncates first, so a shorter new text
        // never leaves the old file's tail behind (memory-mapping would gain nothing for text this size).
        if (Boolean.TRUE.equals(append)) {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        }
        return "Wrote " + content.length() + " characters to " + file;
    }

    @WritesFiles
    @Tool("Creates a directory (and missing parents) inside the working folder")
    public String createDirectory(@P("directory path relative to the working folder") String path,
                                  InvocationParameters parameters) throws IOException {
        Path dir = WorkFolder.resolveInside(parameters, path);
        Files.createDirectories(dir); // like mkdir -p: no error if it already exists
        return "Created " + dir;
    }

    @WritesFiles
    @Tool("Moves or renames a file or directory; both paths must be inside the working folder")
    public String movePath(@P("existing path relative to the working folder") String from,
                           @P("new path relative to the working folder") String to,
                           InvocationParameters parameters) throws IOException {
        Path folder = WorkFolder.of(parameters);
        // Both ends are checked: you can't move something in from outside, or out of the folder
        Path source = requireNotRoot(folder, WorkFolder.resolveInside(parameters, from), from);
        WorkFolder.requireNoDeniedInside(parameters, source, from); // moving a parent would move the skill folder
        Path target = WorkFolder.resolveInside(parameters, to);
        if (Files.notExists(source)) throw new IllegalArgumentException("Does not exist: " + from);
        Files.createDirectories(target.getParent());
        Files.move(source, target); // fails if the target exists: never silently overwrites
        return "Moved " + source + " to " + target;
    }

    @WritesFiles
    @Tool("Copies a file; both paths must be inside the working folder")
    public String copyFile(@P("existing file relative to the working folder") String from,
                           @P("new path relative to the working folder") String to,
                           @P(value = "true to replace an existing target", required = false) Boolean replace,
                           InvocationParameters parameters) throws IOException {
        Path source = WorkFolder.resolveInside(parameters, from);
        Path target = WorkFolder.resolveInside(parameters, to);
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("Not a file: " + from);
        Files.createDirectories(target.getParent());
        if (Boolean.TRUE.equals(replace)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.copy(source, target); // throws FileAlreadyExistsException if the target exists
        }
        return "Copied " + source + " to " + target;
    }

    @WritesFiles
    @Tool("Deletes a file, or a directory with everything in it, inside the working folder")
    public String deletePath(@P("path relative to the working folder") String path,
                             InvocationParameters parameters) throws IOException {
        Path folder = WorkFolder.of(parameters);
        Path target = requireNotRoot(folder, WorkFolder.resolveInside(parameters, path), path);
        WorkFolder.requireNoDeniedInside(parameters, target, path); // deleting a parent would delete the skills
        if (Files.notExists(target)) throw new IllegalArgumentException("Does not exist: " + path);
        // A one-element array, because the anonymous visitor below can't assign to a local int
        int[] count = {0};
        // Depth-first: files are deleted as they're visited, each directory after its contents
        Files.walkFileTree(target, new SimpleFileVisitor<>() { // symlinks are deleted, not followed
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file); // permanent: no recycle bin
                count[0]++;
                return FileVisitResult.CONTINUE;
            }

            // Called after all of a directory's contents were visited (and deleted), so it's empty now
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) throw e; // something inside couldn't be listed: stop rather than half-delete
                Files.delete(dir);
                count[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return "Deleted " + target + (count[0] > 1 ? " (" + count[0] + " entries)" : "");
    }

    /** Refuses the folder itself as a target: "delete ." or "move . elsewhere" would remove the whole folder. */
    private static Path requireNotRoot(Path folder, Path path, String given) throws IOException {
        if (path.equals(folder.toRealPath())) { // compare real paths: "." and the absolute folder are the same
            throw new IllegalArgumentException("'" + given + "' is the working folder itself; pick something inside it");
        }
        return path;
    }
}
