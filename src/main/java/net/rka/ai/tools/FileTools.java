package net.rka.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;

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

    private static final int MAX_READ_CHARS = 24_000;  // ~7k tokens per read: the default model context is 16k tokens
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
        if (!Files.isDirectory(dir)) throw new IllegalArgumentException("Not a directory: " + path);
        // Files.list holds the directory open until the stream is closed, hence try-with-resources
        try (Stream<Path> entries = Files.list(dir)) {
            // Take one more than the limit, so we can tell whether entries were cut off
            List<String> lines = entries.sorted() // alphabetical, so the output is stable
                    .map(p -> p.getFileName() + (Files.isDirectory(p) ? "/" : "")) // trailing / marks folders
                    .limit(MAX_LISTED + 1).toList();
            if (lines.isEmpty()) return "(empty)";
            return lines.size() > MAX_LISTED
                    ? String.join("\n", lines.subList(0, MAX_LISTED)) + "\n… more entries not shown"
                    : String.join("\n", lines);
        }
    }

    @Tool("Finds files inside the working folder whose path matches a glob, e.g. '**/*.java' or '*.txt'")
    public String findFiles(
            @P("glob relative to the working folder, e.g. **/*.md") String glob,
            InvocationParameters parameters) throws IOException {
        Path root = WorkFolder.resolveInside(parameters, ""); // the folder's real path
        // Glob syntax: * matches within one directory level, ** across levels, {a,b} alternatives
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<String> found = new ArrayList<>();
        // walkFileTree visits every file below root and calls visitFile for each
        Files.walkFileTree(root, new SimpleFileVisitor<>() { // doesn't follow symlinks out of the folder
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path relative = root.relativize(file); // match "src/Main.java", not the absolute path
                if (matcher.matches(relative)) found.add(relative.toString());
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

    @Tool("Reads a text file inside the working folder. Big files come in parts: pass startLine "
            + "(and optionally endLine) to read further")
    public String readFile(@P("file path relative to the working folder") String path,
                           @P(value = "first line to read, starting at 1; omit to start at the top", required = false)
                           Integer startLine,
                           @P(value = "last line to read; omit to read as much as fits", required = false)
                           Integer endLine,
                           InvocationParameters parameters) throws IOException {
        Path file = WorkFolder.resolveInside(parameters, path);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("Not a file: " + path);
        // Reads the file as UTF-8; a binary or other-encoded file throws (→ error result for the model)
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (startLine == null && endLine == null && text.length() <= MAX_READ_CHARS) return text; // fits whole
        List<String> lines = text.lines().toList();
        int first = Math.max(1, startLine == null ? 1 : startLine);
        int last = Math.min(lines.size(), endLine == null ? lines.size() : endLine);
        if (lines.isEmpty()) return "";
        if (first > lines.size()) {
            throw new IllegalArgumentException("The file has only " + lines.size() + " lines");
        }
        if (first > last) throw new IllegalArgumentException("startLine is after endLine");
        // Whole lines up to MAX_READ_CHARS, so one read never floods the model's context
        StringBuilder out = new StringBuilder();
        int line = first;
        while (line <= last && out.length() + lines.get(line - 1).length() + 1 <= MAX_READ_CHARS) {
            out.append(lines.get(line - 1)).append('\n');
            line++;
        }
        if (line == first && line <= last) { // a single line longer than the cap: show its start, move on
            out.append(lines.get(line - 1), 0, MAX_READ_CHARS).append(" …\n");
            line++;
        }
        boolean whole = first == 1 && line > lines.size();
        return whole ? out.toString()
                : out + "… lines " + first + "–" + Math.max(first, line - 1) + " of " + lines.size()
                        + (line <= last ? "; call readFile again with startLine " + line + " for more" : "");
    }

    @WritesFiles
    @Tool("Writes a text file inside the working folder, creating it and its parent directories, "
            + "or replacing it; set append to add to the end instead")
    public String writeFile(
            @P("file path relative to the working folder") String path,
            @P("the text to write") String content,
            @P(value = "true to append instead of replacing", required = false) Boolean append,
            InvocationParameters parameters) throws IOException {
        Path file = WorkFolder.resolveInside(parameters, path);
        if (Files.isDirectory(file)) throw new IllegalArgumentException("Is a directory: " + path);
        Files.createDirectories(file.getParent()); // "notes/2026/today.md" creates notes/2026 as needed
        // Boolean (not boolean): the argument is optional, so it may be null = replace
        if (Boolean.TRUE.equals(append)) {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.writeString(file, content, StandardCharsets.UTF_8); // default options: create or overwrite
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
