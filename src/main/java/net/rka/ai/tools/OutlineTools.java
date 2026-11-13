package net.rka.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import net.rka.ai.tools.code.java.JavaWorkspaces;
import net.rka.ai.tools.code.java.ProjectFiles;
import net.rka.ai.tools.outline.Outline;
import net.rka.ai.tools.outline.Outlines;
import net.rka.ai.tools.outline.Section;
import net.rka.ai.tools.outline.SectionEdit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reading and changing a code file by its parts instead of by quoted text: {@code outline} lists the
 * sections of a file with its md5, {@code readSection} reads one, {@code editSection} replaces, adds or
 * deletes one (a new method goes below the last method of its class).
 *
 * <h2>How it works</h2>
 * Every call makes the file's {@link Outline} again ({@link Outlines}: one pass, milliseconds, same result for
 * the same file), so there is nothing to keep in sync. The outline names the parts of the file (for Java: the
 * imports, each class's header, fields, constructors and methods, and nested classes the same way; for
 * Markdown: the headings; anything else: blocks of lines) and carries the file's md5. {@code editSection} must
 * be given the md5 the model last saw (from {@code outline}, {@code readSection} or its previous edit): a file
 * that changed since then is refused, so a section id never points at code the model hasn't seen. After an
 * edit the result gives the new md5, the new section ids, and says when other ids changed.
 * <p>
 * <p>
 * In a Java project the {@code .java} files are read from and changed in the {@link net.rka.ai.tools.code.java.JavaWorkspace}
 * (memory, see {@link ProjectFiles}): an edit is not written to the file at once but when the workspace is flushed (before
 * a command runs, at the end of the turn), and a read sees the edits not yet written.
 * <p>
 * Why: replacing text by quoting the old text exactly is what a local model gets wrong again
 * and again on big files (wrong indentation, text from memory). Here it only names the part and writes the new
 * part; the old text is never quoted.
 */
public class OutlineTools {

    private final JavaWorkspaces workspaces;

    public OutlineTools() {
        this(JavaWorkspaces.shared());
    }

    public OutlineTools(JavaWorkspaces workspaces) {
        this.workspaces = workspaces;
    }

    private ProjectFiles files(InvocationParameters parameters) {
        return new ProjectFiles(WorkFolder.of(parameters), workspaces).fresh();
    }

    private static final int MAX_READ_CHARS = 8_000; // a section is read whole (about 2k tokens) or not at all: a whole class gives its parts

    @Tool("Lists the sections of a text file inside the working folder, with the file's md5: for a Java file the "
            + "imports, then per class its header, fields, constructors and methods (nested classes too), for "
            + "Markdown the headings, otherwise blocks of lines. Use it before changing a file: then read a part "
            + "with readSection and change it with editSection")
    public String outline(@P("file path relative to the working folder") String path,
                          @P(value = "a section id: list only the parts inside it (for a big class); omit for the whole file",
                                  required = false) String section,
                          InvocationParameters parameters) throws IOException {
        Path file = file(parameters, path);
        Outline outline = Outlines.of(file.getFileName().toString(), files(parameters).readBytes(file));
        return outline.render(path, section == null || section.isBlank() ? null : outline.find(section));
    }

    @Tool("Reads one section of a file by its id from outline (e.g. imports, Foo.LIMIT, Foo.run(String,int), "
            + "Setup > Install). An id may be shortened when it is unambiguous (Foo.run). The result starts "
            + "with the file's md5, which editSection needs")
    public String readSection(@P("file path relative to the working folder") String path,
                              @P("the section's id from outline") String section,
                              InvocationParameters parameters) throws IOException {
        Path file = file(parameters, path);
        byte[] bytes = files(parameters).readBytes(file);
        Outline outline = Outlines.of(file.getFileName().toString(), bytes);
        Section found = outline.find(section);
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        String text = String.join("\n", lines.subList(found.start() - 1, found.end()));
        String where = "[" + path + " · " + found.id() + " · " + found.kind() + " · lines " + found.start() + "–" + found.end() + " of "
                + outline.lineCount() + " · md5 " + outline.md5();
        // A part is read whole or not at all: the reader always knows what it has read ("complete") and never has half of a method
        if (text.length() <= MAX_READ_CHARS) return where + " · complete]\n" + text;
        List<String> parts = outline.partsOf(found).stream().map(s -> s.id() + " (lines " + s.start() + "–" + s.end() + ")").limit(30).toList();
        return where + " · NOT read: " + found.lines() + " lines, " + text.length() + " characters, more than one read holds]\n"
                + (parts.isEmpty() ? "This part has no smaller parts: ask for what you need with searchFiles, which places its matches in it."
                : "Its parts, each of which can be read whole with readSection:\n" + String.join("\n", parts));
    }

    /**
     * Changes one section of a file; see the class comment. Only one call per file at a time makes sense: each
     * call needs the md5 the previous one returned.
     */
    @WritesFiles
    @Tool("Changes one section of a text file inside the working folder, without quoting old text: mode replace "
            + "(default) puts newText in the section's place (empty newText deletes the section), before / after "
            + "adds newText next to it, add (section = the class) adds a new part where its kind belongs: a method below "
            + "the last method, a field below the last field. newText is the whole new section, e.g. a "
            + "complete method with its doc comment; its indentation is fixed for you. md5 is the file's md5 from "
            + "outline, readSection or your last editSection: if the file changed, the edit is refused")
    public String editSection(@P("file path relative to the working folder") String path,
                              @P("the section's id from outline") String section,
                              @P("the new text of the section (or the text to add)") String newText,
                              @P("the file's md5 from outline, readSection or the last editSection") String md5,
                              @P(value = "replace (default), before, after, or add (a new part of the class named as section)",
                                      required = false) String mode,
                              InvocationParameters parameters) throws IOException {
        Path file = file(parameters, path);
        ProjectFiles files = files(parameters);
        byte[] bytes = files.readBytes(file);
        Outline before = Outlines.of(file.getFileName().toString(), bytes);
        if (md5 == null || md5.isBlank()) {
            throw new IllegalArgumentException("md5 is missing: pass the md5 from outline, readSection or your last editSection"
                    + " (the file's md5 is now " + before.md5() + ", if you have read its current text)");
        }
        if (!before.md5().equalsIgnoreCase(md5.strip())) {
            throw new IllegalArgumentException("The file changed since your md5 " + md5.strip() + " (it is now "
                    + before.md5() + "). Call outline again and read the part you want to change before editing.");
        }
        SectionEdit.Mode how = SectionEdit.Mode.parse(mode);
        Section target = before.find(section);
        String changed = SectionEdit.apply(new String(bytes, StandardCharsets.UTF_8), before, target, how, newText);
        byte[] written = changed.getBytes(StandardCharsets.UTF_8);
        files.writeString(file, changed); // memory for a Java file of a Java project, else the file

        Outline after = Outlines.of(file.getFileName().toString(), written);
        return describe(path, target, how, newText, before, after);
    }

    private static String describe(String path, Section target, SectionEdit.Mode how, String newText, Outline before, Outline after) {
        Set<String> gone = new LinkedHashSet<>(before.sections().stream().map(Section::id).toList());
        Set<String> now = after.sections().stream().map(Section::id).collect(Collectors.toCollection(LinkedHashSet::new));
        gone.removeAll(now);
        boolean deleted = how == SectionEdit.Mode.REPLACE && (newText == null || newText.isBlank());
        if (deleted) gone.remove(target.id());
        Set<String> added = new LinkedHashSet<>(now);
        added.removeAll(before.sections().stream().map(Section::id).collect(Collectors.toSet()));

        StringBuilder result = new StringBuilder(deleted ? "Deleted " : switch (how) {
            case REPLACE -> "Replaced ";
            case BEFORE -> "Added before ";
            case AFTER -> "Added after ";
            case ADD -> "Added to ";
            case START -> "Added at the start of ";
            case END -> "Added at the end of ";
        }).append(target.id()).append(" in ").append(path).append(". New md5 ").append(after.md5())
                .append(", ").append(after.lineCount()).append(" lines.");
        if (!added.isEmpty()) result.append(" New sections: ").append(String.join(", ", first(added, 6))).append('.');
        if (!gone.isEmpty()) {
            result.append(" These section ids no longer exist: ").append(String.join(", ", first(gone, 6)))
                    .append(". Call outline again before the next edit.");
        }
        return result.toString();
    }

    private static List<String> first(Set<String> ids, int max) {
        List<String> all = new ArrayList<>(ids);
        List<String> shown = new ArrayList<>(all.subList(0, Math.min(max, all.size())));
        if (all.size() > max) shown.add("… " + (all.size() - max) + " more");
        return shown;
    }

    private Path file(InvocationParameters parameters, String path) throws IOException {
        Path file = WorkFolder.resolveInside(parameters, path);
        if (!files(parameters).isRegularFile(file)) throw new IllegalArgumentException("Not a file: " + path);
        return file;
    }
}
