package net.rka.ai.tools.code.java;

import net.rka.ai.tools.FileTools;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link JavaWorkspace} of each project folder: opened the first time a folder is asked for if it holds Java files, kept
 * (and watched) until {@link #close}. A folder without Java files has none; it is looked at again after
 * {@link #RECHECK_MILLIS}, so the first Java file of a new project turns it into a Java project.
 */
public final class JavaWorkspaces implements AutoCloseable {

    /** How long "this folder has no Java files" is believed. */
    static final long RECHECK_MILLIS = 2_000;

    private static final JavaWorkspaces SHARED = new JavaWorkspaces(true);

    private final boolean watching;
    private final Map<Path, JavaWorkspace> open = new ConcurrentHashMap<>();
    private final Map<Path, Long> withoutJava = new ConcurrentHashMap<>();

    /** The registry the whole app uses: the tools and the coding job see the same workspace of a folder. */
    public static JavaWorkspaces shared() {
        return SHARED;
    }

    /** A registry of its own; {@code watching} starts a file watcher for each workspace. */
    public JavaWorkspaces(boolean watching) {
        this.watching = watching;
    }

    /** The workspace of the folder, or empty when it has no Java files. */
    public Optional<JavaWorkspace> workspace(Path folder) {
        Path key;
        try {
            key = folder.toRealPath();
        } catch (IOException e) {
            return Optional.empty();
        }
        JavaWorkspace existing = open.get(key);
        if (existing != null) return Optional.of(existing);
        Long checked = withoutJava.get(key);
        if (checked != null && System.currentTimeMillis() - checked < RECHECK_MILLIS) return Optional.empty();
        synchronized (this) {
            existing = open.get(key);
            if (existing != null) return Optional.of(existing);
            try {
                if (!hasJavaFiles(key)) {
                    withoutJava.put(key, System.currentTimeMillis());
                    return Optional.empty();
                }
                JavaWorkspace workspace = JavaWorkspace.open(key);
                if (watching) workspace.watch();
                open.put(key, workspace);
                withoutJava.remove(key);
                return Optional.of(workspace);
            } catch (IOException | RuntimeException e) {
                System.getLogger(JavaWorkspaces.class.getName()).log(System.Logger.Level.WARNING,
                        "No Java workspace for " + key + ": " + e.getMessage());
                withoutJava.put(key, System.currentTimeMillis());
                return Optional.empty();
            }
        }
    }

    /** Writes the changes of the folder's workspace, if it has one; an empty result when there is nothing to write. */
    public JavaWorkspace.FlushResult flush(Path folder) {
        return workspace(folder).map(JavaWorkspace::flush).orElse(new JavaWorkspace.FlushResult(List.of(), List.of(), List.of()));
    }

    /** Writes the changes of every open workspace. */
    public List<JavaWorkspace.FlushResult> flushAll() {
        List<JavaWorkspace.FlushResult> results = new ArrayList<>();
        for (JavaWorkspace workspace : open.values()) results.add(workspace.flush());
        return results;
    }

    private static boolean hasJavaFiles(Path folder) throws IOException {
        boolean[] found = {false};
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (found[0]) return FileVisitResult.TERMINATE;
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                boolean skipped = !dir.equals(folder) && (FileTools.SKIPPED_FOLDERS.contains(name) || name.startsWith("."));
                return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".java")) {
                    found[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return found[0];
    }

    /** Stops the watchers. Changes not written are lost; flush first. */
    @Override
    public void close() {
        open.values().forEach(JavaWorkspace::close);
        open.clear();
    }
}
