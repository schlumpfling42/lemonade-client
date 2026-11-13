package net.rka.ai.tools.code.java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The files of a project folder as the tools and the coding job read and write them: a {@code .java} file in a Java project
 * comes from and goes to the {@link JavaWorkspace} (memory; nothing is written until {@link #flush}), every other file
 * is read from and written to disk as it is. One place decides, so a Java file never has two versions in play.
 */
public final class ProjectFiles {

    private final Path root;
    private final JavaWorkspaces workspaces;

    public ProjectFiles(Path root, JavaWorkspaces workspaces) {
        this.root = root;
        this.workspaces = workspaces;
    }

    /** The workspace when the file is a Java file of a Java project, with the file's path in it. */
    private record Held(JavaWorkspace workspace, String path) {
    }

    private Optional<Held> held(Path file) {
        if (!file.getFileName().toString().endsWith(".java")) return Optional.empty();
        Optional<JavaWorkspace> workspace = workspaces.workspace(root);
        if (workspace.isEmpty()) return Optional.empty();
        try {
            Path relative = workspace.get().root().relativize(file.toAbsolutePath().normalize());
            if (relative.startsWith("..")) return Optional.empty();
            return Optional.of(new Held(workspace.get(), relative.toString().replace('\\', '/')));
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // not below the workspace's folder (another root form)
        }
    }

    public boolean isRegularFile(Path file) {
        Optional<Held> held = held(file);
        if (held.isPresent() && held.get().workspace().has(held.get().path())) return true;
        return Files.isRegularFile(file) && !(held.isPresent() && heldDeleted(held.get()));
    }

    /** A file in memory marked deleted but still on disk until the next write counts as gone. */
    private static boolean heldDeleted(Held held) {
        return held.workspace().dirtyPaths().contains(held.path()) && !held.workspace().has(held.path());
    }

    public boolean exists(Path file) {
        return isRegularFile(file) || Files.isDirectory(file);
    }

    public String readString(Path file) throws IOException {
        Optional<Held> held = held(file);
        if (held.isPresent()) {
            Optional<String> text = held.get().workspace().text(held.get().path());
            if (text.isPresent()) return text.get();
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    public byte[] readBytes(Path file) throws IOException {
        return readString(file).getBytes(StandardCharsets.UTF_8);
    }

    /** Writes the text: into memory for a Java file of a Java project, else to disk (with the folders above it). */
    public void writeString(Path file, String text) throws IOException {
        Optional<Held> held = held(file);
        if (held.isPresent()) {
            held.get().workspace().setText(held.get().path(), text);
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** Deletes a file: a Java file of the workspace is marked deleted and removed from disk by the next flush, any other file at once. */
    public void deleteFile(Path file) throws IOException {
        Optional<Held> held = held(file);
        if (held.isPresent() && held.get().workspace().has(held.get().path())) {
            held.get().workspace().delete(held.get().path());
            return;
        }
        Files.delete(file);
    }

    /**
     * Takes in what changed on disk now (the workspace otherwise looks when its last look is a second old; the watcher
     * is quicker): for the start of a tool call or a step, which should not work from a stale view. Returns this.
     */
    public ProjectFiles fresh() {
        sync();
        return this;
    }

    /** Whether the folder is a Java project, so that its {@code .java} files are listed and searched from memory, not from disk. */
    public boolean holdsJava() {
        return workspaces.workspace(root).isPresent();
    }

    /** The Java files in memory, relative to the folder and with {@code /}; empty outside a Java project. */
    public List<String> javaPaths() {
        return workspaces.workspace(root).map(JavaWorkspace::paths).orElse(List.of());
    }

    /** The simple names of the classes of the Java project (empty outside one). */
    public java.util.Set<String> typeNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        workspaces.workspace(root).ifPresent(w -> w.allTypes().forEach(t -> names.add(t.type().name())));
        return names;
    }

    /** The qualified names of the classes of the Java project (empty outside one). */
    public java.util.Set<String> qualifiedTypeNames() {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        workspaces.workspace(root).ifPresent(w -> w.allTypes().forEach(t -> names.add(t.qualifiedName())));
        return names;
    }

    /** Writes the changes in memory to the files; nothing to do outside a Java project. */
    public JavaWorkspace.FlushResult flush() {
        return workspaces.flush(root);
    }

    /** Throws the changes in memory away; the files are read again. */
    public void discardAll() {
        workspaces.workspace(root).ifPresent(JavaWorkspace::discardAll);
    }

    /** Takes in what changed on disk since the last look (after something else wrote files). */
    public void sync() {
        workspaces.workspace(root).ifPresent(JavaWorkspace::sync);
    }

    /** The paths with changes not written yet; empty outside a Java project. */
    public List<String> dirtyPaths() {
        return workspaces.workspace(root).map(JavaWorkspace::dirtyPaths).orElse(List.of());
    }

    public JavaWorkspaces workspaces() {
        return workspaces;
    }

    public Path root() {
        return root;
    }
}
