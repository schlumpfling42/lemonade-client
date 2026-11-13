package net.rka.ai.tools.code.java;

import net.rka.ai.tools.FileTools;
import net.rka.ai.tools.code.java.JavaUnit.DiskStamp;
import net.rka.ai.tools.outline.Outline;
import net.rka.ai.tools.outline.Section;
import net.rka.ai.tools.outline.SectionEdit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * All the Java files of a project folder, read into memory: each file's text and outline, the types in it
 * ({@link JavaClass}), indexes to find them, and the references between them. Changes are made here, in memory, and
 * written to the files by {@link #flush}.
 *
 * <h2>How it works</h2>
 * <ul>
 *   <li><b>Units.</b> A file is a {@link JavaUnit}: text, outline, types, the names it uses, and the {@link DiskStamp}
 *       (modified time and size) of the file when the text was read. A unit is immutable; every change replaces it.</li>
 *   <li><b>Indexes.</b> Hash maps from qualified name to type, from simple name to qualified names, and from every word of
 *       a dotted name to the units that use it. All are updated per changed unit, not rebuilt.</li>
 *   <li><b>References.</b> Each dotted name in a unit's declarations and code ({@code Ledger.parse}, {@code Map.Entry}) is
 *       resolved against the types of the project the way Java resolves it (own types, single imports, the same package,
 *       imports on demand, a nested type after its outer one) into a {@link Reference}, kept per unit and also by the type
 *       it points at, so "who uses this type" is one lookup. When a type appears or goes, only the units that use its name
 *       are resolved again.</li>
 *   <li><b>Changes in memory.</b> {@link #edit} applies a {@link SectionEdit} to the text, {@link #setText} replaces or
 *       creates a file, {@link #delete} removes one. Nothing touches the disk until {@link #flush}.</li>
 *   <li><b>The file system.</b> {@link #sync} compares the files with the stamps (one directory walk, no reading) and reads
 *       what changed; a background watcher ({@link #watch}) calls it a few milliseconds after any change, and every read
 *       calls it when the last look is older than {@link #STALE_MILLIS}. A file that changed on disk while the unit has
 *       changes in memory is a <i>conflict</i>: both are kept, {@link #flush} writes neither, and the caller is told.</li>
 *   <li><b>Writing.</b> {@link #flush} writes the changed units (new files with their folders, deleted files removed) and
 *       takes the new stamp, so the watcher's event for our own write changes nothing.</li>
 * </ul>
 * Thread safety: one read-write lock; the watcher and the callers share it.
 * <p>
 * Not implemented yet: references through a variable ({@code ledger.parse()}) are not resolved to a type (that needs the
 * types of local variables); {@link #possibleCallers} answers by name instead. Two types of one qualified name (a copy in a
 * test folder) keep the first. Only {@code .java} files are held; other files stay with the file tools.
 */
public final class JavaWorkspace implements AutoCloseable {

    /** How old the last look at the file system may be before a read looks again. */
    static final long STALE_MILLIS = 1_000;

    /** How long the watcher waits for more events before it looks, so a save that writes twice is one look. */
    private static final long WATCH_SETTLE_MILLIS = 40;

    /** A type of the project and where it is. */
    public record TypeInfo(String qualifiedName, String path, JavaClass type) {
    }

    /** What a look at the file system found. */
    public record SyncResult(List<String> added, List<String> changed, List<String> removed, List<String> conflicts) {
        public boolean any() {
            return !(added.isEmpty() && changed.isEmpty() && removed.isEmpty() && conflicts.isEmpty());
        }
    }

    /** What writing the changes did. */
    public record FlushResult(List<String> written, List<String> deleted, List<String> conflicts) {
        public boolean ok() {
            return conflicts.isEmpty();
        }
    }

    /** The result of an edit in memory: the new md5 and the parts that appeared and went. */
    public record EditResult(String path, String md5, int lines, List<String> addedSections, List<String> removedSections) {
    }

    private final Path root;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, JavaUnit> units = new TreeMap<>();
    private final Map<String, TypeInfo> types = new HashMap<>();
    private final Map<String, Set<String>> bySimpleName = new HashMap<>();
    private final Map<String, Set<String>> unitsByWord = new HashMap<>();
    private final Map<String, List<Reference>> resolved = new HashMap<>();
    private final Map<String, Map<String, List<Reference>>> incoming = new HashMap<>();
    private volatile long lastSyncNanos;
    private WatchService watcher;
    private Thread watchThread;
    private final Map<WatchKey, Path> watched = new HashMap<>();

    /** Reads all Java files below {@code root}. */
    public static JavaWorkspace open(Path root) throws IOException {
        JavaWorkspace workspace = new JavaWorkspace(root.toRealPath());
        workspace.sync();
        return workspace;
    }

    private JavaWorkspace(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    // ---- reading ----

    /** The project-relative paths of all Java files in memory (also new ones, not deleted ones). */
    public List<String> paths() {
        look();
        return read(() -> units.values().stream().filter(u -> !u.deleted()).map(JavaUnit::path).toList());
    }

    public boolean has(String path) {
        look();
        return read(() -> units.containsKey(path) && !units.get(path).deleted());
    }

    /** The text of the file in memory. */
    public Optional<String> text(String path) {
        look();
        return read(() -> Optional.ofNullable(units.get(path)).filter(u -> !u.deleted()).map(JavaUnit::text));
    }

    /** The outline of the text in memory. */
    public Optional<Outline> outline(String path) {
        look();
        return read(() -> Optional.ofNullable(units.get(path)).filter(u -> !u.deleted()).map(JavaUnit::outline));
    }

    /** The top-level types read from the file; empty when it doesn't parse (see {@link #parseError}). */
    public List<JavaClass> typesIn(String path) {
        look();
        return read(() -> Optional.ofNullable(units.get(path)).map(JavaUnit::types).orElse(List.of()));
    }

    /** Why the file in memory is not read as Java, or "". */
    public String parseError(String path) {
        look();
        return read(() -> Optional.ofNullable(units.get(path)).map(JavaUnit::error).orElse(""));
    }

    /** Whether a word is somewhere in the project's Java code (as a name, in any case written the same or all in small letters). */
    public boolean mentions(String word) {
        look();
        return read(() -> unitsByWord.containsKey(word) || unitsByWord.containsKey(word.toLowerCase()) || types.containsKey(word)
                || bySimpleName.containsKey(word));
    }

    /** A type by qualified name ({@code net.rka.Foo}, {@code net.rka.Foo.Inner}). */
    public Optional<TypeInfo> type(String qualifiedName) {
        look();
        return read(() -> Optional.ofNullable(types.get(qualifiedName)));
    }

    /** A type by qualified or simple name or by the end of its qualified name; empty when none or more than one match. */
    public Optional<TypeInfo> find(String name) {
        List<TypeInfo> matches = findAll(name);
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /** All types a name can mean: the qualified name itself, else those whose qualified name ends with it. */
    public List<TypeInfo> findAll(String name) {
        look();
        return read(() -> {
            if (types.containsKey(name)) return List.of(types.get(name));
            String suffix = "." + name;
            return types.values().stream().filter(t -> t.qualifiedName().endsWith(suffix) || t.qualifiedName().equals(name))
                    .sorted(java.util.Comparator.comparing(TypeInfo::qualifiedName)).toList();
        });
    }

    /** All types in memory, sorted by qualified name. */
    public List<TypeInfo> allTypes() {
        look();
        return read(() -> types.values().stream().sorted(java.util.Comparator.comparing(TypeInfo::qualifiedName)).toList());
    }

    /** The references that point at the type: where it is used. Sorted by the using type and member. */
    public List<Reference> usagesOf(String qualifiedType) {
        look();
        return read(() -> sorted(incoming.getOrDefault(qualifiedType, Map.of()).values().stream().flatMap(List::stream)));
    }

    /** The references the type (and its nested types) make: what it uses. */
    public List<Reference> referencesOf(String qualifiedType) {
        look();
        return read(() -> {
            TypeInfo info = types.get(qualifiedType);
            if (info == null) return List.of();
            String prefix = qualifiedType + ".";
            return sorted(resolved.getOrDefault(info.path(), List.of()).stream()
                    .filter(r -> r.fromType().equals(qualifiedType) || r.fromType().startsWith(prefix)));
        });
    }

    /** The qualified names of the types that use the type, other than itself. */
    public Set<String> usersOf(String qualifiedType) {
        return usagesOf(qualifiedType).stream().map(Reference::fromType).filter(t -> !t.equals(qualifiedType))
                .map(this::outermost).collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * Members that may call {@code member} of the type: those that name it as {@code Type.member} (a certain call of a
     * static member), and, from types that use the type at all, those with the name after a dot anywhere in their code
     * ({@code something.member(...)}): a call through a variable, whose type the workspace doesn't know. Each result is
     * {@code fromMember} of a unit; the second kind is marked "(by name)".
     */
    public List<String> possibleCallers(String qualifiedType, String member) {
        look();
        return read(() -> {
            Set<String> certain = new TreeSet<>();
            Set<String> byName = new TreeSet<>();
            Set<String> users = new HashSet<>();
            for (Reference r : incoming.getOrDefault(qualifiedType, Map.of()).values().stream().flatMap(List::stream).toList()) {
                users.add(outermost(r.fromType()));
                if (r.toMember().equals(member)) certain.add(label(r));
            }
            for (JavaUnit unit : units.values()) {
                for (JavaUses.Use use : unit.uses()) {
                    if (!users.contains(outermost(use.fromType())) || use.chain().size() < 2) continue;
                    if (use.chain().subList(1, use.chain().size()).contains(member)) {
                        String place = use.fromMember().isEmpty() ? use.fromType() : use.fromMember();
                        if (!certain.contains(place)) byName.add(place + " (by name)");
                    }
                }
            }
            List<String> all = new ArrayList<>(certain);
            all.addAll(byName);
            return all;
        });
    }

    /**
     * The type of the project that a type name written in the code of {@code fromType} means: {@code Ledger}, {@code List<Foo>}
     * (generics and array brackets are left out), {@code Outer.Inner}, a qualified name; empty for a type of the JDK or a library.
     */
    public Optional<String> resolveType(String fromType, String name) {
        look();
        return read(() -> resolveTypeIn(fromType, name));
    }

    /** The types of the project that {@code qualifiedType} extends or implements, directly. */
    public List<String> supertypesOf(String qualifiedType) {
        look();
        return read(() -> supertypesIn(qualifiedType));
    }

    /** The types of the project that extend or implement {@code qualifiedType}, directly. */
    public List<String> subtypesOf(String qualifiedType) {
        look();
        return read(() -> types.keySet().stream().sorted().filter(q -> supertypesIn(q).contains(qualifiedType)).toList());
    }

    private List<String> supertypesIn(String qualifiedType) {
        TypeInfo info = types.get(qualifiedType);
        if (info == null) return List.of();
        List<String> names = new ArrayList<>(info.type().implementsInterfaces());
        if (!info.type().extendsClass().isEmpty()) names.add(info.type().extendsClass());
        return names.stream().map(n -> resolveTypeIn(qualifiedType, n)).flatMap(Optional::stream).distinct().toList();
    }

    private Optional<String> resolveTypeIn(String fromType, String name) {
        TypeInfo from = types.get(fromType);
        JavaUnit unit = from == null ? null : units.get(from.path());
        if (unit == null) return Optional.empty();
        String clean = name.replaceAll("<.*>", "").replace("[]", "").replace("...", "").replaceAll("@\\w+(\\.\\w+)*\\s*", "")
                .replaceAll("\\bfinal\\b", "").replaceAll("\\s+", "");
        if (clean.isEmpty()) return Optional.empty();
        String[] parts = clean.split("\\.");
        String type = resolveName(unit.scope(), parts[0]);
        int next = 1;
        if (type == null) { // a qualified name: the shortest prefix that is a type
            StringBuilder prefix = new StringBuilder(parts[0]);
            for (int i = 1; i < parts.length && type == null; i++) {
                prefix.append('.').append(parts[i]);
                if (types.containsKey(prefix.toString())) {
                    type = prefix.toString();
                    next = i + 1;
                }
            }
        }
        if (type == null) return Optional.empty();
        while (next < parts.length && types.containsKey(type + "." + parts[next])) type = type + "." + parts[next++];
        return next < parts.length ? Optional.empty() : Optional.of(type);
    }

    private static String label(Reference r) {
        return r.fromMember().isEmpty() ? r.fromType() : r.fromMember();
    }

    private String outermost(String qualified) {
        String best = qualified;
        for (String candidate = qualified; candidate.contains("."); ) {
            candidate = candidate.substring(0, candidate.lastIndexOf('.'));
            if (types.containsKey(candidate)) best = candidate;
        }
        return best;
    }

    private static List<Reference> sorted(java.util.stream.Stream<Reference> references) {
        return references.sorted(java.util.Comparator.comparing(Reference::fromType).thenComparing(Reference::fromMember)
                .thenComparing(Reference::toType).thenComparing(Reference::toMember).thenComparing(Reference::kind)).toList();
    }

    /** The paths with changes that are not written yet. */
    public List<String> dirtyPaths() {
        return read(() -> units.values().stream().filter(JavaUnit::dirty).map(JavaUnit::path).toList());
    }

    /** The paths whose file changed on disk while they had changes in memory. */
    public List<String> conflictPaths() {
        return read(() -> units.values().stream().filter(JavaUnit::conflict).map(JavaUnit::path).toList());
    }

    // ---- changes in memory ----

    /**
     * Changes one section of a file's text in memory. {@code md5} must be the md5 of the text now (from the outline); the
     * file on disk is not touched.
     *
     * @throws IllegalArgumentException when the file or section is unknown, the md5 is old or the mode doesn't fit
     */
    public EditResult edit(String path, String section, SectionEdit.Mode mode, String newText, String md5) {
        requireJava(path);
        sync();
        return write(() -> {
            JavaUnit unit = existing(path);
            if (md5 != null && !md5.isBlank() && !unit.md5().equalsIgnoreCase(md5.strip())) {
                throw new IllegalArgumentException("The file changed since your md5 " + md5.strip() + " (it is now " + unit.md5()
                        + "). Call outline again and read the part you want to change before editing.");
            }
            Section target = unit.outline().find(section);
            String changed = SectionEdit.apply(unit.text(), unit.outline(), target, mode, newText);
            String problem = JavaStructure.problem(unit.text(), changed, target.start(), target.end(), newText,
                    JavaStructure.effectOf(mode, target.container()));
            if (problem != null) throw new IllegalArgumentException(path + ": " + problem);
            Set<String> before = unit.outline().sections().stream().map(Section::id).collect(Collectors.toCollection(LinkedHashSet::new));
            JavaUnit next = replaceText(unit, changed);
            Set<String> after = next.outline().sections().stream().map(Section::id).collect(Collectors.toCollection(LinkedHashSet::new));
            List<String> added = after.stream().filter(id -> !before.contains(id)).toList();
            List<String> removed = before.stream().filter(id -> !after.contains(id)).toList();
            return new EditResult(path, next.md5(), next.outline().lineCount(), added, removed);
        });
    }

    /**
     * Replaces the lines {@code from} to {@code to} of the file (counted from 1, both included; {@code to == from - 1} inserts before
     * line {@code from}, an empty replacement removes the lines), the way the structure says they are: the lines of a member
     * ({@link JavaMember#startLine()}). The result must pass {@link JavaStructure#problem}, else nothing changes.
     *
     * @param replacement the new text of those lines, without a final line break
     * @param effect      what the change does to the lines, see {@link JavaStructure.Effect}
     * @throws IllegalArgumentException when the file is unknown, the lines are not in it or the result fails the structure check
     */
    public EditResult changeLines(String path, int from, int to, String replacement, JavaStructure.Effect effect) {
        requireJava(path);
        sync();
        return write(() -> {
            JavaUnit unit = existing(path);
            boolean crlf = unit.text().contains("\r\n");
            String text = crlf ? unit.text().replace("\r\n", "\n") : unit.text();
            List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
            if (from < 1 || to < from - 1 || to > lines.size()) {
                throw new IllegalArgumentException("Lines " + from + "-" + to + " are not in " + path + " (" + lines.size() + " lines)");
            }
            List<String> replaced = new ArrayList<>(lines.subList(0, from - 1));
            if (!replacement.isEmpty()) replaced.addAll(List.of(replacement.split("\n", -1)));
            replaced.addAll(lines.subList(to, lines.size()));
            String changed = String.join("\n", replaced);
            String problem = JavaStructure.problem(text, changed, from, to, replacement, effect);
            if (problem != null) throw new IllegalArgumentException(path + ": " + problem);
            JavaUnit next = replaceText(unit, crlf ? changed.replace("\n", "\r\n") : changed);
            return new EditResult(path, next.md5(), next.outline().lineCount(), List.of(), List.of());
        });
    }

    /** Puts the text in memory as the file's text: replaces a file or creates a new one. */
    public void setText(String path, String text) {
        requireJava(path);
        sync();
        write(() -> {
            JavaUnit unit = units.get(path);
            // A Java file is never put into memory in a state that does not read as Java (unless it did not before)
            if (unit == null || unit.deleted() || unit.error() == null) {
                String problem = JavaStructure.unreadable(text);
                if (problem != null) throw new IllegalArgumentException(path + ": " + problem);
            }
            if (unit == null || unit.deleted()) {
                JavaUnit created = JavaUnit.parse(path, text, unit == null ? null : unit.stamp(), true);
                apply(Map.of(path, created));
            } else {
                replaceText(unit, text);
            }
            return null;
        });
    }

    /** Marks the file as deleted; it is removed from disk by {@link #flush}. A file that was only in memory just goes. */
    public void delete(String path) {
        requireJava(path);
        sync();
        write(() -> {
            JavaUnit unit = existing(path);
            if (unit.stamp() == null) {
                apply(removal(path));
            } else {
                apply(Map.of(path, unit.flagged(true, true, unit.conflict())));
            }
            return null;
        });
    }

    /** The workspace holds Java files and nothing else: any other file belongs to the file tools. */
    static void requireJava(String path) {
        if (path == null || !path.endsWith(".java")) {
            throw new IllegalArgumentException("The Java workspace holds only .java files, not " + path + ": use the file tools (outline, readSection, editSection, readFile, writeFile) for it");
        }
    }

    private JavaUnit existing(String path) {
        JavaUnit unit = units.get(path);
        if (unit == null || unit.deleted()) throw new IllegalArgumentException("No Java file " + path + " in the project");
        return unit;
    }

    private JavaUnit replaceText(JavaUnit unit, String text) {
        JavaUnit next = unit.with(text, unit.stamp(), true).flagged(true, false, unit.conflict());
        apply(Map.of(unit.path(), next));
        return next;
    }

    // ---- the file system ----

    /**
     * Looks at the files and takes in what changed: new files, files whose stamp differs (read again unless they have
     * changes in memory, then they are a conflict), files that are gone. Cheap when nothing changed: one walk, no reading.
     */
    public SyncResult sync() {
        Map<String, DiskStamp> onDisk;
        try {
            onDisk = scan();
        } catch (IOException e) {
            throw new IllegalStateException("Could not look at " + root + ": " + e.getMessage(), e);
        }
        // Read what changed outside the lock; the stamp taken before reading says which version the text is
        Map<String, JavaUnit> parsed = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Map<String, JavaUnit> snapshot = read(() -> new LinkedHashMap<>(units));
        List<String> toRead = new ArrayList<>();
        for (Map.Entry<String, DiskStamp> e : onDisk.entrySet()) {
            JavaUnit unit = snapshot.get(e.getKey());
            if (unit == null || (!e.getValue().equals(unit.stamp()) && !unit.dirty())) toRead.add(e.getKey());
        }
        toRead.parallelStream().forEach(path -> {
            try {
                DiskStamp stamp = onDisk.get(path);
                String text = Files.readString(root.resolve(path), StandardCharsets.UTF_8);
                JavaUnit unit = JavaUnit.parse(path, text, stamp, false);
                synchronized (parsed) {
                    parsed.put(path, unit);
                }
            } catch (IOException | RuntimeException e) {
                // unreadable (binary, being written): looked at again the next time
            }
        });
        return write(() -> {
            Map<String, JavaUnit> changes = new LinkedHashMap<>();
            for (Map.Entry<String, JavaUnit> e : parsed.entrySet()) {
                JavaUnit current = units.get(e.getKey());
                if (current != null && current.dirty()) continue; // changed in memory meanwhile: handled below
                changes.put(e.getKey(), e.getValue());
                (current == null ? added : changed).add(e.getKey());
            }
            List<String> removed = new ArrayList<>();
            for (JavaUnit unit : new ArrayList<>(units.values())) {
                DiskStamp stamp = onDisk.get(unit.path());
                if (stamp == null) {
                    if (unit.stamp() == null) continue;            // exists only in memory: waits to be written
                    if (unit.dirty()) {
                        if (!unit.deleted() && !unit.conflict()) {   // changed in memory and gone from disk
                            changes.put(unit.path(), unit.flagged(true, false, true));
                            conflicts.add(unit.path());
                        }
                    } else {
                        changes.put(unit.path(), null);
                        removed.add(unit.path());
                    }
                } else if (unit.dirty() && !stamp.equals(unit.stamp()) && !unit.deleted()) {
                    if (!unit.conflict()) {
                        changes.put(unit.path(), unit.flagged(true, false, true));
                        conflicts.add(unit.path());
                    }
                } else if (unit.dirty() && unit.deleted() && !stamp.equals(unit.stamp()) && !unit.conflict()) {
                    changes.put(unit.path(), unit.flagged(true, true, true));
                    conflicts.add(unit.path());
                }
            }
            apply(changes);
            lastSyncNanos = System.nanoTime();
            return new SyncResult(added, changed, removed, conflicts);
        });
    }

    /** Looks at the file system when the last look is older than {@link #STALE_MILLIS}. */
    private void look() {
        if (System.nanoTime() - lastSyncNanos > TimeUnit.MILLISECONDS.toNanos(STALE_MILLIS)) sync();
    }

    private Map<String, DiskStamp> scan() throws IOException {
        Map<String, DiskStamp> found = new TreeMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return skipped(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                    found.put(relative(file), new DiskStamp(attrs.lastModifiedTime().toMillis(), attrs.size()));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE; // a file that vanished while walking
            }
        });
        return found;
    }

    private boolean skipped(Path dir) {
        if (dir.equals(root)) return false;
        String name = dir.getFileName().toString();
        return FileTools.SKIPPED_FOLDERS.contains(name) || name.startsWith(".");
    }

    private String relative(Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    // ---- writing ----

    /**
     * Writes the changes to the files: changed and new units (with their folders) are written, deleted ones removed. A
     * unit in conflict is left alone and reported (unless {@code force}: then memory wins). Takes the new stamp of each
     * written file so the watcher's event for it changes nothing.
     */
    public FlushResult flush() {
        return flush(false);
    }

    public FlushResult flush(boolean force) {
        sync();
        return write(() -> {
            List<String> written = new ArrayList<>();
            List<String> deleted = new ArrayList<>();
            List<String> conflicts = new ArrayList<>();
            Map<String, JavaUnit> changes = new LinkedHashMap<>();
            for (JavaUnit unit : new ArrayList<>(units.values())) {
                if (!unit.dirty()) continue;
                if (unit.conflict() && !force) {
                    conflicts.add(unit.path());
                    continue;
                }
                Path file = root.resolve(unit.path());
                try {
                    if (unit.deleted()) {
                        Files.deleteIfExists(file);
                        deleted.add(unit.path());
                        changes.put(unit.path(), null);
                    } else {
                        Files.createDirectories(file.getParent());
                        Files.writeString(file, unit.text(), StandardCharsets.UTF_8);
                        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                        written.add(unit.path());
                        changes.put(unit.path(), unit.stamped(new DiskStamp(attrs.lastModifiedTime().toMillis(), attrs.size())));
                    }
                } catch (IOException e) {
                    conflicts.add(unit.path() + " (" + e.getMessage() + ")");
                }
            }
            apply(changes);
            return new FlushResult(written, deleted, conflicts);
        });
    }

    /** Throws the changes in memory away and reads the files again: the given paths, or every unit with changes. */
    public void discard(Collection<String> paths) {
        write(() -> {
            Map<String, JavaUnit> changes = new LinkedHashMap<>();
            for (String path : paths) {
                JavaUnit unit = units.get(path);
                if (unit == null) continue;
                Path file = root.resolve(path);
                if (Files.isRegularFile(file)) {
                    try {
                        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
                        changes.put(path, JavaUnit.parse(path, Files.readString(file, StandardCharsets.UTF_8),
                                new DiskStamp(attrs.lastModifiedTime().toMillis(), attrs.size()), false));
                    } catch (IOException e) {
                        throw new IllegalStateException("Could not read " + path + ": " + e.getMessage(), e);
                    }
                } else {
                    changes.put(path, null);
                }
            }
            apply(changes);
            return null;
        });
    }

    public void discardAll() {
        discard(dirtyPaths());
    }

    // ---- the watcher ----

    /** Starts a background thread that calls {@link #sync} shortly after any change below the folder. Idempotent. */
    public synchronized void watch() {
        if (watchThread != null) return;
        try {
            watcher = root.getFileSystem().newWatchService();
            registerTree(root);
        } catch (IOException | RuntimeException e) {
            System.getLogger(JavaWorkspace.class.getName()).log(System.Logger.Level.WARNING,
                    "Not watching " + root + ": " + e.getMessage());
            return;
        }
        WatchService service = watcher;
        watchThread = Thread.ofPlatform().name("java-workspace-watcher").daemon().start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = service.take();
                    boolean any = handle(key);
                    // wait until it is quiet for a moment, so one save is one look
                    while ((key = service.poll(WATCH_SETTLE_MILLIS, TimeUnit.MILLISECONDS)) != null) any |= handle(key);
                    if (any) sync();
                }
            } catch (InterruptedException | ClosedWatchServiceException e) {
                // closed
            } catch (RuntimeException e) {
                System.getLogger(JavaWorkspace.class.getName()).log(System.Logger.Level.WARNING, "The watcher stopped", e);
            }
        });
    }

    private boolean handle(WatchKey key) {
        Path dir = watched.get(key);
        boolean relevant = false;
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                relevant = true;
                continue;
            }
            Path name = (Path) event.context();
            Path path = dir == null ? null : dir.resolve(name);
            if (path != null && event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path) && !skipped(path)) {
                try {
                    registerTree(path);
                } catch (IOException e) {
                    // looked at by the next full walk
                }
                relevant = true;
            } else if (name.toString().endsWith(".java") || (path != null && !Files.isRegularFile(path))) {
                relevant = true; // a Java file, or a folder that may have held some
            }
        }
        if (!key.reset()) watched.remove(key);
        return relevant;
    }

    private void registerTree(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (skipped(dir)) return FileVisitResult.SKIP_SUBTREE;
                WatchKey key = dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                watched.put(key, dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public synchronized void close() {
        if (watchThread != null) watchThread.interrupt();
        try {
            if (watcher != null) watcher.close();
        } catch (IOException e) {
            // closing
        }
        watchThread = null;
        watcher = null;
    }

    // ---- the indexes ----

    private static Map<String, JavaUnit> removal(String path) {
        Map<String, JavaUnit> map = new HashMap<>();
        map.put(path, null);
        return map;
    }

    /**
     * Puts the changed units in ({@code null} removes one) and updates the indexes: the types of the replaced units go and
     * the new ones come, the words of their names are looked up for the units that use them, and the references of every
     * unit that is new or uses a name that came or went are resolved again.
     */
    private void apply(Map<String, JavaUnit> changes) {
        if (changes.isEmpty()) return;
        Set<String> typesBefore = new HashSet<>();
        Set<String> typesAfter = new HashSet<>();
        for (Map.Entry<String, JavaUnit> e : changes.entrySet()) {
            JavaUnit old = units.get(e.getKey());
            if (old != null) {
                typesBefore.addAll(old.declared().keySet());
                for (String qualified : old.declared().keySet()) {
                    TypeInfo info = types.get(qualified);
                    if (info != null && info.path().equals(old.path())) {
                        types.remove(qualified);
                        removeSimple(qualified);
                    }
                }
                for (String word : old.words()) {
                    Set<String> users = unitsByWord.get(word);
                    if (users != null && users.remove(old.path()) && users.isEmpty()) unitsByWord.remove(word);
                }
                dropResolved(old.path());
            }
            if (e.getValue() == null) {
                units.remove(e.getKey());
            } else if (!e.getValue().deleted()) {
                units.put(e.getKey(), e.getValue());
                for (Map.Entry<String, JavaClass> t : e.getValue().declared().entrySet()) {
                    typesAfter.add(t.getKey());
                    if (types.putIfAbsent(t.getKey(), new TypeInfo(t.getKey(), e.getKey(), t.getValue())) == null) addSimple(t.getKey());
                }
                for (String word : e.getValue().words()) unitsByWord.computeIfAbsent(word, w -> new HashSet<>()).add(e.getKey());
            } else {
                units.put(e.getKey(), e.getValue()); // deleted in memory: kept until written, but it declares and uses nothing
            }
        }
        // The types that appeared or went: the units that use one of their names resolve again (a body edit changes none)
        Set<String> appearedOrGone = new HashSet<>(typesBefore);
        appearedOrGone.addAll(typesAfter);
        Set<String> stayed = new HashSet<>(typesBefore);
        stayed.retainAll(typesAfter);
        appearedOrGone.removeAll(stayed);
        Set<String> toResolve = new TreeSet<>();
        for (Map.Entry<String, JavaUnit> e : changes.entrySet()) if (e.getValue() != null && !e.getValue().deleted()) toResolve.add(e.getKey());
        for (String qualified : appearedOrGone) {
            for (String word : qualified.split("\\.")) toResolve.addAll(unitsByWord.getOrDefault(word, Set.of()));
        }
        for (String path : toResolve) resolve(path);
    }

    private void addSimple(String qualified) {
        bySimpleName.computeIfAbsent(qualified.substring(qualified.lastIndexOf('.') + 1), n -> new TreeSet<>()).add(qualified);
    }

    private void removeSimple(String qualified) {
        String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
        Set<String> all = bySimpleName.get(simple);
        if (all != null && all.remove(qualified) && all.isEmpty()) bySimpleName.remove(simple);
    }

    private void dropResolved(String path) {
        List<Reference> old = resolved.remove(path);
        if (old == null) return;
        for (Reference r : old) {
            Map<String, List<Reference>> byUnit = incoming.get(r.toType());
            if (byUnit == null) continue;
            byUnit.remove(path);
            if (byUnit.isEmpty()) incoming.remove(r.toType());
        }
    }

    private void resolve(String path) {
        dropResolved(path);
        JavaUnit unit = units.get(path);
        if (unit == null || unit.deleted()) return;
        Set<Reference> found = new LinkedHashSet<>();
        for (JavaUses.Use use : unit.uses()) {
            Reference r = resolve(unit, use);
            if (r != null) found.add(r);
        }
        if (found.isEmpty()) return;
        List<Reference> list = List.copyOf(found);
        resolved.put(path, list);
        for (Reference r : list) incoming.computeIfAbsent(r.toType(), t -> new HashMap<>()).computeIfAbsent(path, p -> new ArrayList<>()).add(r);
    }

    /** One use as a reference to a type of the project, or null when it names none. */
    private Reference resolve(JavaUnit unit, JavaUses.Use use) {
        List<String> chain = use.chain();
        String type = resolveName(unit.scope(), chain.getFirst());
        int next = 1;
        if (type == null) { // a fully qualified name: the shortest prefix that is a type
            StringBuilder prefix = new StringBuilder(chain.getFirst());
            for (int i = 1; i < chain.size() && type == null; i++) {
                prefix.append('.').append(chain.get(i));
                if (types.containsKey(prefix.toString())) {
                    type = prefix.toString();
                    next = i + 1;
                }
            }
        }
        if (type == null) return null;
        while (next < chain.size() && types.containsKey(type + "." + chain.get(next))) type = type + "." + chain.get(next++);
        String member = next < chain.size() ? chain.get(next) : "";
        return new Reference(use.fromType(), use.fromMember(), type, member, use.kind());
    }

    private String resolveName(JavaUnit.Scope scope, String word) {
        String own = scope.ownTypes().get(word);
        if (own != null) return own;
        String imported = scope.singleImports().get(word);
        if (imported != null && types.containsKey(imported)) return imported;
        String samePackage = scope.packageName().isEmpty() ? word : scope.packageName() + "." + word;
        if (types.containsKey(samePackage)) return samePackage;
        for (String prefix : scope.onDemand()) if (types.containsKey(prefix + "." + word)) return prefix + "." + word;
        return null;
    }

    // ---- locking ----

    private <T> T read(java.util.function.Supplier<T> action) {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    private <T> T write(java.util.function.Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
