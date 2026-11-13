package net.rka.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import net.rka.ai.tools.WorkFolder;
import net.rka.ai.tools.code.java.JavaClass;
import net.rka.ai.tools.code.java.JavaMember;
import net.rka.ai.tools.code.java.JavaSourceReader;
import net.rka.ai.tools.code.java.JavaStructure;
import net.rka.ai.tools.code.java.JavaWorkspaces;
import net.rka.ai.tools.code.java.ProjectFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the conversation has been shown of the files, and at which version: so a change made outside the conversation (in the
 * editor, by a job, by a command) is noticed and the model is told what changed, without anything being read again.
 * <p>
 * The state holds a version for each file a tool call touched ({@link #KEY}, one line per file). A version is small: for a Java file
 * the hash of each member's lines (so a change can be named: "{@code run()} changed, {@code stop()} added"), for another file its size,
 * modified time and number of lines. {@link #changes} compares them with the files as they are now, which costs a look at the tracked
 * files (the Java workspace has them in memory) and no reading of anything that did not change.
 */
final class Knowledge {

    /** The state key: "path TAB version" per line, the files the tools touched last at the end. */
    static final String KEY = "knowledge";
    /** The files kept in the ledger: older ones drop out first. */
    static final int MAX_FILES = 40;

    private static final ObjectMapper JSON = new ObjectMapper();

    private Knowledge() {
    }

    // ---- the ledger ----

    static Map<String, String> decode(String text) {
        Map<String, String> known = new LinkedHashMap<>();
        if (text == null) return known;
        for (String line : text.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) known.put(line.substring(0, tab), line.substring(tab + 1));
        }
        return known;
    }

    static String encode(Map<String, String> known) {
        StringBuilder out = new StringBuilder();
        known.forEach((path, version) -> out.append(path).append('\t').append(version).append('\n'));
        return out.toString();
    }

    /**
     * The ledger after the calls ran: the files they name at their versions as they are now (so what a call changed itself is not
     * reported as a change).
     */
    static String after(String ledger, List<ToolExecutionRequest> calls, Path folder) {
        return after(ledger, calls, folder, JavaWorkspaces.shared());
    }

    static String after(String ledger, List<ToolExecutionRequest> calls, Path folder, JavaWorkspaces workspaces) {
        Map<String, String> known = decode(ledger);
        ProjectFiles files = files(folder, workspaces);
        for (ToolExecutionRequest call : calls) {
            for (String path : pathsOf(call, folder, workspaces)) {
                String version = version(files, folder, path);
                if (version == null) continue;
                known.remove(path); // the latest touched goes last
                known.put(path, version);
            }
        }
        while (known.size() > MAX_FILES) known.remove(known.keySet().iterator().next());
        return encode(known);
    }

    /** The ledger with these files at their versions now (the job's picked files, after their outlines were built). */
    static String afterPaths(String ledger, java.util.Collection<String> paths, Path folder, JavaWorkspaces workspaces) {
        Map<String, String> known = decode(ledger);
        ProjectFiles files = files(folder, workspaces);
        for (String path : paths) {
            String version = version(files, folder, path.replace('\\', '/'));
            if (version == null) continue;
            known.remove(path);
            known.put(path, version);
        }
        while (known.size() > MAX_FILES) known.remove(known.keySet().iterator().next());
        return encode(known);
    }

    /** The files among {@code paths} that changed since the ledger saw them. */
    static List<String> changesIn(String ledger, java.util.Collection<String> paths, Path folder, JavaWorkspaces workspaces) {
        Set<String> wanted = new java.util.HashSet<>();
        paths.forEach(p -> wanted.add(p.replace('\\', '/')));
        List<String> changed = new ArrayList<>();
        for (String change : changes(ledger, folder, workspaces)) {
            if (wanted.contains(change.substring(0, Math.max(0, change.indexOf(": "))))) changed.add(change);
        }
        return changed;
    }

    /** What changed in the tracked files since they were last shown, as sentences; empty when nothing did. */
    static List<String> changes(String ledger, Path folder) {
        return changes(ledger, folder, JavaWorkspaces.shared());
    }

    static List<String> changes(String ledger, Path folder, JavaWorkspaces workspaces) {
        Map<String, String> known = decode(ledger);
        if (known.isEmpty()) return List.of();
        ProjectFiles files = files(folder, workspaces);
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : known.entrySet()) {
            String now = version(files, folder, entry.getKey());
            if (entry.getValue().equals(now)) continue;
            out.add(entry.getKey() + ": " + describe(entry.getValue(), now));
        }
        return out;
    }

    /** The ledger with the versions the files have now, for the files that {@link #changes} reported. */
    static String refreshed(String ledger, Path folder) {
        return refreshed(ledger, folder, JavaWorkspaces.shared());
    }

    static String refreshed(String ledger, Path folder, JavaWorkspaces workspaces) {
        Map<String, String> known = decode(ledger);
        ProjectFiles files = files(folder, workspaces);
        for (Map.Entry<String, String> entry : known.entrySet()) {
            String now = version(files, folder, entry.getKey());
            entry.setValue(now == null ? "gone" : now);
        }
        known.values().removeIf("gone"::equals);
        return encode(known);
    }

    // ---- files and versions ----

    private static ProjectFiles files(Path folder, JavaWorkspaces workspaces) {
        return new ProjectFiles(folder, workspaces).fresh();
    }

    /** The files a call is about: its {@code path}, and the file of the type it names (the Java tools). */
    static Set<String> pathsOf(ToolExecutionRequest call, Path folder, JavaWorkspaces workspaces) {
        Set<String> paths = new LinkedHashSet<>();
        try {
            JsonNode arguments = JSON.readTree(call.arguments() == null ? "{}" : call.arguments());
            if (arguments.hasNonNull("path") && !arguments.get("path").asText().isBlank()) paths.add(arguments.get("path").asText().replace('\\', '/'));
            for (String name : List.of("type", "target")) {
                if (!arguments.hasNonNull(name)) continue;
                String value = arguments.get(name).asText().strip();
                if (value.endsWith(".java")) {
                    paths.add(value.replace('\\', '/'));
                } else {
                    workspaces.workspace(folder).flatMap(w -> w.find(value)).ifPresent(info -> paths.add(info.path()));
                }
            }
        } catch (IOException | RuntimeException e) {
            // arguments that are not JSON name no file
        }
        return paths;
    }

    /** The version of the file now, or null when it is not a file (or not text). */
    static String version(ProjectFiles files, Path folder, String path) {
        try {
            Path file = WorkFolder.resolveInside(folder, path);
            if (!files.isRegularFile(file)) return null;
            if (path.endsWith(".java")) {
                String text = files.readString(file);
                return "J " + memberHashes(text);
            }
            if (Files.size(file) > 2_000_000) return "F " + Files.size(file) + " " + Files.getLastModifiedTime(file).toMillis();
            long modified = Files.getLastModifiedTime(file).toMillis();
            return "F " + Files.size(file) + " " + modified + " " + Files.readString(file, StandardCharsets.UTF_8).lines().count();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** For a Java file: each member and the hash of its lines, e.g. {@code Ledger.run()=1a2b;Ledger.stop()=3c4d}. */
    private static String memberHashes(String text) {
        List<String> parts = new ArrayList<>();
        try {
            for (JavaClass type : JavaSourceReader.read(text)) collect(type, type, "", parts);
        } catch (RuntimeException e) {
            return "unreadable " + Integer.toHexString(text.hashCode());
        }
        return String.join(";", parts);
    }

    private static void collect(JavaClass root, JavaClass type, String prefix, List<String> out) {
        String owner = prefix + type.name() + ".";
        out.add(prefix + type.name() + "=" + Integer.toHexString(root.lines(type.startLine(), type.startLine()).hashCode()));
        for (JavaMember member : type.members()) {
            if (member instanceof JavaClass nested) {
                collect(root, nested, owner, out);
            } else if (!(member instanceof net.rka.ai.tools.code.java.JavaComment)) {
                out.add(owner + JavaStructure.signature(member).replaceFirst("^(field|method|constructor|type) ?", "") + "="
                        + Integer.toHexString(root.lines(member).hashCode()));
            }
        }
    }

    // ---- what changed ----

    private static String describe(String before, String now) {
        if (now == null) return "deleted";
        if (before.startsWith("J ") && now.startsWith("J ")) {
            Map<String, String> was = members(before.substring(2));
            Map<String, String> is = members(now.substring(2));
            Set<String> added = new TreeSet<>(is.keySet());
            added.removeAll(was.keySet());
            Set<String> removed = new TreeSet<>(was.keySet());
            removed.removeAll(is.keySet());
            Set<String> changed = new TreeSet<>();
            is.forEach((name, hash) -> {
                if (was.containsKey(name) && !was.get(name).equals(hash)) changed.add(name);
            });
            List<String> parts = new ArrayList<>();
            if (!changed.isEmpty()) parts.add("changed " + shorten(changed));
            if (!added.isEmpty()) parts.add("added " + shorten(added));
            if (!removed.isEmpty()) parts.add("removed " + shorten(removed));
            return parts.isEmpty() ? "changed" : String.join("; ", parts);
        }
        if (before.startsWith("F ") && now.startsWith("F ")) {
            String[] was = before.split(" ");
            String[] is = now.split(" ");
            return was.length > 3 && is.length > 3 ? "changed, " + is[3] + " lines now (was " + was[3] + ")" : "changed";
        }
        return "changed";
    }

    private static Map<String, String> members(String hashes) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : hashes.split(";")) {
            int equals = part.lastIndexOf('=');
            if (equals > 0) out.put(part.substring(0, equals), part.substring(equals + 1));
        }
        return out;
    }

    private static String shorten(Set<String> names) {
        List<String> all = new ArrayList<>(names);
        String shown = String.join(", ", all.subList(0, Math.min(5, all.size())));
        return all.size() > 5 ? shown + " and " + (all.size() - 5) + " more" : shown;
    }
}
