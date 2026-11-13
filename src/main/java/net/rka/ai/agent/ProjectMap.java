package net.rka.ai.agent;

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
import java.util.TreeMap;

/**
 * The files of the trusted folder as text a coding job starts from: per folder its files with their line
 * counts, build output, version control and dependency folders left out ({@link FileTools#SKIPPED_FOLDERS}).
 * <p>
 * Why: a job that has to find its way by listing one folder per model call (the log showed {@code src},
 * {@code src/main}, {@code src/main/java}, ... until the investigation's rounds ran out) never sees a source
 * file and plans from nothing. With the map in front of it from the first request, it goes straight to the
 * files, and the line counts tell it which ones are big enough to need {@code outline} instead of a read.
 * It is made once per job, in one walk of the folder, in sorted order, so it is the same for the whole job and
 * the model server can keep it in its cache with the rest of the job's opening message.
 */
final class ProjectMap {

    static final int MAX_FILES = 400;
    static final int MAX_CHARS = 8_000;
    private static final long MAX_COUNTED_BYTES = 1_000_000;

    private ProjectMap() {
    }

    /** The map of {@code folder}, or a one-line note when it can't be read or is empty. */
    static String of(Path folder) {
        Map<String, List<String>> byFolder = new TreeMap<>();
        int[] files = {0};
        boolean[] cut = {false};
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(folder)) return FileVisitResult.CONTINUE;
                    String name = dir.getFileName().toString();
                    return FileTools.SKIPPED_FOLDERS.contains(name) || FileTools.generatedOutput(dir) || name.startsWith(".")
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (files[0] >= MAX_FILES) {
                        cut[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    files[0]++;
                    Path relative = folder.relativize(file);
                    String dir = relative.getParent() == null ? "" : relative.getParent().toString().replace('\\', '/');
                    byFolder.computeIfAbsent(dir, d -> new ArrayList<>()).add(describe(file, attrs));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE; // unreadable entries are skipped
                }
            });
        } catch (IOException | RuntimeException e) {
            return "(the folder's files could not be listed: " + e.getMessage() + ")";
        }
        if (byFolder.isEmpty()) return "(the folder has no files yet)";
        StringBuilder map = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : byFolder.entrySet()) {
            String line = (entry.getKey().isEmpty() ? "./" : entry.getKey() + "/") + ": "
                    + String.join(", ", entry.getValue().stream().sorted().toList()) + "\n";
            if (map.length() + line.length() > MAX_CHARS) {
                cut[0] = true;
                break;
            }
            map.append(line);
        }
        if (cut[0]) map.append("… more files: list them with findFiles or listFiles\n");
        return map.toString().stripTrailing();
    }

    /** {@code Name.java 120}: the file's name and its number of lines, left out for a binary or big file. */
    private static String describe(Path file, BasicFileAttributes attrs) {
        String name = file.getFileName().toString();
        if (attrs.size() == 0) return name + " 0";
        if (attrs.size() > MAX_COUNTED_BYTES) return name;
        try {
            byte[] bytes = Files.readAllBytes(file);
            int lines = 0;
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == 0) return name; // binary
                if (bytes[i] == '\n') lines++;
            }
            if (bytes[bytes.length - 1] != '\n') lines++;
            return name + " " + lines;
        } catch (IOException | RuntimeException e) {
            return name;
        }
    }
}
