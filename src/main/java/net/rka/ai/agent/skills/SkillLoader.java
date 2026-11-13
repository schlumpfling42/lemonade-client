package net.rka.ai.agent.skills;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Loads the skills: the built-in ones from the app's resources ({@code net/rka/ai/agent/skills/<name>.json})
 * and the user's from a folder ({@code ~/chat/skills/*.json}).
 *
 * <h2>How loading works</h2>
 * <ol>
 *   <li>Each built-in is parsed and validated as built-in (it may name its tool with {@code "tool"}).</li>
 *   <li>Each {@code *.json} file of the folder, in file-name order, is parsed and validated as a user
 *       skill. Its file name must be its {@code name} + {@code .json}. A file with a built-in's name
 *       replaces that built-in. It is a user file for every rule, but keeps the built-in's tool (it may
 *       repeat it, but not change it). An invalid replacement leaves the built-in in place.</li>
 *   <li>A skill whose node ids in the agent graph ({@link SkillValidator#graphIds}) another skill already
 *       has is skipped: {@code skill-a-b} could be skill {@code a-b} or node {@code b} of skill {@code a}.
 *       Built-ins win: a replacement that collides with any other built-in or an earlier skill gives
 *       way to the built-in it replaces.</li>
 *   <li>Each loaded user skill gets a generated page {@code <name>.md} next to its file
 *       ({@link SkillDiagram#markdown}), never read back.</li>
 * </ol>
 * The skill builder uses the same rules for its drafts ({@link #check}) and starts a change from the
 * skill's current JSON ({@link #source}). Nothing here throws: a file that can't be read, isn't JSON or breaks a rule is listed in
 * {@link Loaded#errors} under its file name and the others load, so one bad file never stops the app.
 */
public final class SkillLoader {

    /** The resource folder of the built-in skills. */
    static final String BUILT_IN_FOLDER = "net/rka/ai/agent/skills/";
    private static final System.Logger LOG = System.getLogger(SkillLoader.class.getName());

    private final Path userDir;
    private final List<String> builtInNames;
    private final SkillValidator validator;
    private final Function<String, String> builtInSource;

    /**
     * @param userDir      the user's skill folder; {@code null} or missing means no user skills
     * @param builtInNames the built-in skills, read from the resources
     * @param validator    checks every skill
     */
    public SkillLoader(Path userDir, List<String> builtInNames, SkillValidator validator) {
        this(userDir, builtInNames, validator, SkillLoader::readResource);
    }

    /** For tests: built-in skill JSON by name ({@code null} when there is none) instead of the resources. */
    public SkillLoader(Path userDir, List<String> builtInNames, SkillValidator validator, Function<String, String> builtInSource) {
        this.userDir = userDir;
        this.builtInNames = List.copyOf(builtInNames);
        this.validator = validator;
        this.builtInSource = builtInSource;
    }

    /** The validator, e.g. for the skill builder to check a draft. */
    public SkillValidator validator() {
        return validator;
    }

    /**
     * The result of a load.
     *
     * @param skills the valid skills: built-ins first (or their replacement), then the user's, by file name
     * @param errors the problems, by file name ({@code <name>.json}, built-ins too), in load order
     */
    public record Loaded(List<SkillFile> skills, Map<String, List<String>> errors) {
        public Loaded {
            skills = List.copyOf(skills);
            errors = Collections.unmodifiableMap(new LinkedHashMap<>(errors));
        }

        /**
         * Every node of the loaded skills by its id in the agent graph ({@code skill-<name>-<id>}, as
         * LangGraph4j inlines the subgraphs): which skill and node it is. Skill names may contain dashes,
         * so an inlined id can't be split back into the two; {@code Agent} (pauses, progress, the message
         * feed) looks ids up here instead. The loader made sure no two skills share an id.
         */
        public Map<String, NodeRef> nodeIndex() {
            Map<String, NodeRef> index = new LinkedHashMap<>();
            for (SkillFile skill : skills) {
                String prefix = "skill-" + skill.name() + "-";
                // The nodes the builder adds to every skill; their "kind" is their own id
                for (String id : List.of(SkillGraphBuilder.START, SkillGraphBuilder.TOOL_STEP,
                        SkillGraphBuilder.TOOL_APPROVAL, SkillGraphBuilder.STOPPED)) {
                    index.put(prefix + id, new NodeRef(skill.name(), id, id));
                }
                for (SkillFile.NodeSpec node : skill.nodes()) {
                    index.put(prefix + node.id(), new NodeRef(skill.name(), node.id(), node.kind()));
                }
            }
            return Collections.unmodifiableMap(index);
        }

        /** The loaded skill with this name, if any. */
        public Optional<SkillFile> skill(String name) {
            return skills.stream().filter(skill -> skill.name().equals(name)).findFirst();
        }

        /**
         * This result without the named skills, each listed under its file name ({@code <name>.json}) with its
         * problem: for skills that loaded but can't be used after all (their subgraph fails to build).
         *
         * @param problems skill name → why it can't be used
         */
        public Loaded without(Map<String, String> problems) {
            Map<String, List<String>> all = new LinkedHashMap<>(errors);
            problems.forEach((name, problem) -> addErrors(all, name + ".json", List.of(problem)));
            return new Loaded(skills.stream().filter(skill -> !problems.containsKey(skill.name())).toList(), all);
        }

        /**
         * What is used instead of this result when the agent graph can't be built with it: the {@code kept}
         * skills (the built-ins only, or none), with this result's errors and {@code problem} listed under the
         * file name of every skill of this result that isn't kept (a user file replacing a built-in too, so
         * {@link SkillLoader#origin} says the built-in is used).
         */
        public Loaded fallback(List<SkillFile> kept, Map<String, List<String>> keptErrors, String problem) {
            Map<String, List<String>> all = new LinkedHashMap<>(errors);
            keptErrors.forEach((file, problems) -> {
                // The built-ins' own errors: already listed when this result loaded them too
                List<String> known = all.getOrDefault(file, List.of());
                addErrors(all, file, problems.stream().filter(p -> !known.contains(p)).toList());
            });
            for (SkillFile skill : skills) {
                if (!kept.contains(skill)) addErrors(all, skill.name() + ".json", List.of(problem));
            }
            return new Loaded(kept, all);
        }
    }

    /**
     * One node of a loaded skill, as {@link Loaded#nodeIndex} finds it from its id in the agent graph.
     *
     * @param skill the skill's name
     * @param node  the node's id inside the skill (a file node, or one the builder adds: {@code start},
     *              {@code toolStep}, {@code toolApproval}, {@code stopped})
     * @param kind  the file node's kind ({@code model}, {@code askUser}, ...); for a built-in node its id
     */
    public record NodeRef(String skill, String node, String kind) {
        /** The node's tracing span name, as {@link SkillGraphBuilder} names it: {@code skill/<name>/<id>}. */
        public String tracingName() {
            return SkillGraphBuilder.tracingName(skill, node);
        }
    }

    /** Where a loaded skill comes from, as the settings list it. */
    public enum Origin {
        BUILT_IN("built-in"),
        USER_FILE("your file"),
        USER_FILE_REPLACING_BUILT_IN("your file replacing built-in");

        private final String label;

        Origin(String label) {
            this.label = label;
        }

        /** The short text the window shows. */
        public String label() {
            return label;
        }
    }

    /**
     * Where the loaded skill with this name comes from: the user's file when the folder has a
     * {@code <name>.json} that loaded (no error is listed for it), else the built-in; a user file that
     * replaced a built-in says so. A user file with errors is not in use, the built-in (if any) is.
     */
    public Origin origin(Loaded loaded, String name) {
        String fileName = name + ".json";
        boolean userFile = userDir != null && Files.isRegularFile(userDir.resolve(fileName))
                && !loaded.errors().containsKey(fileName);
        if (!userFile) return Origin.BUILT_IN;
        return builtInNames.contains(name) ? Origin.USER_FILE_REPLACING_BUILT_IN : Origin.USER_FILE;
    }

    /** The user's skill folder this loader reads ({@code null} for none); file tools refuse paths inside it. */
    public Path folder() {
        return userDir;
    }

    /**
     * Loads every skill (see the class comment) and writes the diagram page of each loaded user skill
     * next to its file ({@link #writeDiagrams}).
     */
    public Loaded load() {
        return load(true, true);
    }

    /**
     * The built-in skills alone, as {@link #load} reads them but without the user's folder: what the agent
     * falls back to when its graph can't be built with the user's skills (see {@code Agent}).
     */
    public Loaded builtInsOnly() {
        return load(false, false);
    }

    /**
     * The skills as {@link #load} would load them from the folder as it is now, without writing the diagram
     * pages: the skill builder checks a draft against these at validate time, so a file added or removed
     * since the turn started counts.
     */
    public Loaded current() {
        return load(true, false);
    }

    /**
     * @param userFiles     whether the folder's files are read (else only the built-ins)
     * @param writeDiagrams whether the loaded user skills' diagram pages are written
     */
    private Loaded load(boolean userFiles, boolean writeDiagrams) {
        // name → skill, in order: built-ins first; a replacement takes its built-in's place
        Map<String, SkillFile> skills = new LinkedHashMap<>();
        Map<String, List<String>> errors = new LinkedHashMap<>();
        Map<String, SkillFile> builtIns = new HashMap<>();
        for (String name : builtInNames) {
            String fileName = name + ".json";
            SkillFile skill = read(fileName, () -> builtInSource.apply(name), true, null, errors);
            if (skill != null) {
                builtIns.put(name, skill);
                skills.put(name, skill);
            }
        }
        // By identity: a user file that collides gives way to its built-in, which is then not a user skill
        Set<SkillFile> fromFolder = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, Path> files = userFiles ? userFiles(errors) : Map.of();
        for (Map.Entry<String, Path> file : files.entrySet()) {
            String fileName = file.getKey();
            String name = fileName.substring(0, fileName.length() - ".json".length());
            SkillFile skill = read(fileName, () -> readFile(file.getValue()), false, builtIns.get(name), errors);
            if (skill != null) {
                skills.put(name, skill);
                fromFolder.add(skill);
            }
        }
        List<SkillFile> loaded = withoutCollisions(skills.values(), builtIns, errors);
        if (writeDiagrams) writeDiagrams(loaded.stream().filter(fromFolder::contains).toList());
        return new Loaded(loaded, errors);
    }

    /**
     * Writes {@code <name>.md} ({@link SkillDiagram#markdown}: the Mermaid diagram, prompts and tools) next
     * to each of the user's loaded skills, so the user can read every skill in one place. Generated and
     * never read back; the built-ins get none (they aren't in the folder, and the folder is the user's).
     * A file is only written when its text changed, and an existing one only when it starts with
     * {@link SkillDiagram#GENERATED_MARKER} (one the user wrote is left alone); a failure only loses the page.
     */
    private void writeDiagrams(List<SkillFile> userSkills) {
        for (SkillFile skill : userSkills) {
            Path page = userDir.resolve(skill.name() + ".md");
            String text = SkillDiagram.markdown(skill, validator.tools());
            try {
                if (Files.exists(page)) {
                    String existing = Files.readString(page, StandardCharsets.UTF_8);
                    if (existing.equals(text)) continue;
                    if (!existing.startsWith(SkillDiagram.GENERATED_MARKER)) {
                        // Expected and harmless (the user's own page), so not worth a line on every load
                        LOG.log(System.Logger.Level.DEBUG, "Not replacing " + page + ": it wasn't generated by the app");
                        continue;
                    }
                }
                Files.writeString(page, text, StandardCharsets.UTF_8);
            } catch (IOException | UncheckedIOException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not write the skill diagram " + page, e);
            }
        }
    }

    /** The built-in skills' names, as given to the loader. */
    public List<String> builtInNames() {
        return builtInNames;
    }

    /**
     * The names a new skill can't take without replacing one: every built-in and every {@code *.json} file
     * of the skill folder (valid or not), sorted.
     */
    public Set<String> existingNames() {
        Set<String> names = new TreeSet<>(builtInNames);
        for (String fileName : userFiles(new LinkedHashMap<>()).keySet()) {
            names.add(fileName.substring(0, fileName.length() - ".json".length()));
        }
        return names;
    }

    /**
     * The JSON of the skill with this name as it would load: the user's file {@code <name>.json} if the
     * folder has one (valid or not), else the built-in's; empty when there is neither or it can't be read.
     * The skill builder starts a change from it.
     */
    public Optional<String> source(String name) {
        try {
            if (userDir != null && Files.isRegularFile(userDir.resolve(name + ".json"))) {
                return Optional.of(readFile(userDir.resolve(name + ".json")));
            }
            return builtInNames.contains(name) ? Optional.ofNullable(builtInSource.apply(name)) : Optional.empty();
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty();
        }
    }

    /**
     * The problems this skill would have as the user's file {@code <name>.json}, by the rules of
     * {@link #load}: validated as a user skill (a replacement of a built-in keeps the built-in's tool), and
     * its node ids in the agent graph not taken by another skill as the folder is now ({@link #current}; the
     * one with the same name is the one the file would replace). Empty when it would load. The skill builder
     * checks its drafts with this before the user sees them.
     */
    public List<String> check(SkillFile skill) {
        return check(skill, current());
    }

    /**
     * Like {@link #check(SkillFile)}, against the given skills instead of the folder as it is now.
     *
     * @param loaded the skills in use; the one with the same name is the one the file would replace
     */
    public List<String> check(SkillFile skill, Loaded loaded) {
        String pinnedTool = builtInTool(skill.name());
        SkillFile checked = pinnedTool != null && skill.tool() == null ? withTool(skill, pinnedTool) : skill;
        List<String> problems = new ArrayList<>(validator.validate(checked, false, pinnedTool));
        if (skill.name() == null) return problems; // reported above; it has no ids yet
        Set<String> ids = SkillValidator.graphIds(skill);
        for (SkillFile other : loaded.skills()) {
            if (other.name().equals(skill.name())) continue;
            SkillValidator.graphIds(other).stream().filter(ids::contains).findFirst().ifPresent(id ->
                    problems.add("The node id `" + id + "` in the agent graph is already taken by the skill `"
                            + other.name() + "`; rename the skill or the node."));
        }
        return problems;
    }

    /**
     * The tool that starts this skill once it is saved as the user's {@code <name>.json} and loaded: its own
     * {@code tool}, else the tool of the built-in it replaces (pinned, e.g. {@code startCodingJob}), else
     * {@code skill_<name>}.
     */
    public String toolOf(SkillFile skill) {
        if (skill.tool() != null) return skill.tool();
        String pinned = builtInTool(skill.name());
        return pinned != null ? pinned : skill.toolName();
    }

    /** The tool of the built-in skill with this name ({@code null} when there is none, or it sets none). */
    private String builtInTool(String name) {
        if (name == null || !builtInNames.contains(name)) return null;
        try {
            String json = builtInSource.apply(name);
            return json == null ? null : SkillFile.parse(json).tool();
        } catch (RuntimeException e) {
            return null; // a broken built-in: load lists it, and the draft is checked without a pinned tool
        }
    }

    /** The folder's {@code *.json} files by file name, sorted; an unreadable folder is listed as an error. */
    private Map<String, Path> userFiles(Map<String, List<String>> errors) {
        Map<String, Path> files = new TreeMap<>();
        if (userDir == null || !Files.isDirectory(userDir)) return files;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(userDir, "*.json")) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) files.put(file.getFileName().toString(), file);
            }
        } catch (IOException | DirectoryIteratorException e) {
            // DirectoryIteratorException: an entry failed while iterating; keep the files read so far
            addErrors(errors, userDir.toString(), List.of("The skill folder can't be read completely: " + e.getMessage()));
        }
        return files;
    }

    /** A source of file text that may fail with an {@link IOException}. */
    private interface Source {
        String text() throws IOException;
    }

    /**
     * Reads, parses and validates one file; {@code null} (with its errors listed under its file name)
     * when it can't be used.
     *
     * @param replaces the built-in this user file replaces, or {@code null}
     */
    private SkillFile read(String fileName, Source source, boolean builtIn, SkillFile replaces,
                           Map<String, List<String>> errors) {
        List<String> problems = new ArrayList<>();
        SkillFile skill = null;
        try {
            String text = source.text();
            if (text == null) {
                problems.add("The built-in skill is missing from the app's resources.");
            } else {
                skill = SkillFile.parse(text);
                if (!fileName.equals(skill.name() + ".json")) {
                    problems.add("The file name must be the skill's name + .json: `" + skill.name() + ".json`.");
                }
                // A replacement is a user file for every rule; only its tool is pinned to the built-in's
                String pinnedTool = replaces == null ? null : replaces.tool();
                if (pinnedTool != null && skill.tool() == null) skill = withTool(skill, pinnedTool);
                problems.addAll(validator.validate(skill, builtIn, pinnedTool));
            }
        } catch (IOException | UncheckedIOException e) {
            problems.add("The file can't be read: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        } catch (RuntimeException e) {
            // A bug in a kind's check must not stop the other skills either
            problems.add("The file can't be checked: " + e);
        }
        if (problems.isEmpty()) return skill;
        addErrors(errors, fileName, problems);
        return null;
    }

    /** Adds to the file's errors (a built-in and the user file replacing it share the file name). */
    private static void addErrors(Map<String, List<String>> errors, String fileName, List<String> problems) {
        if (problems.isEmpty()) return;
        List<String> all = new ArrayList<>(errors.getOrDefault(fileName, List.of()));
        all.addAll(problems);
        errors.put(fileName, List.copyOf(all));
    }

    /** The replacement file with the built-in's tool, which it keeps when it leaves {@code tool} out. */
    private static SkillFile withTool(SkillFile skill, String tool) {
        return new SkillFile(skill.name(), tool, skill.description(), skill.inputs(), skill.limits(),
                skill.start(), skill.nodes());
    }

    /**
     * The skills in order, minus each one whose graph ids are taken (listed as an error). Built-ins win:
     * a replacement may not take the ids of another built-in, even one that loads after it, and a
     * replacement that collides gives way to the built-in it replaces.
     *
     * @param builtIns the built-in skills by name, as loaded from the resources
     */
    private static List<SkillFile> withoutCollisions(Iterable<SkillFile> skills, Map<String, SkillFile> builtIns,
                                                     Map<String, List<String>> errors) {
        Map<String, String> owner = new HashMap<>(); // graph id → file name of the skill that has it
        List<SkillFile> accepted = new ArrayList<>();
        for (SkillFile skill : skills) {
            String fileName = skill.name() + ".json";
            SkillFile builtIn = builtIns.get(skill.name());
            boolean replacement = builtIn != null && builtIn != skill;
            String taken = takenId(skill, owner, replacement ? otherBuiltIns(builtIns, skill.name()) : Map.of());
            if (taken != null) {
                addErrors(errors, fileName, List.of("The node id `" + taken + "` in the agent graph is already taken by "
                        + taken(taken, owner, builtIns) + "; rename the skill or the node."
                        + (replacement ? " The built-in skill is used instead." : "")));
                if (!replacement || takenId(builtIn, owner, Map.of()) != null) continue;
                skill = builtIn;
            }
            String acceptedFile = skill.name() + ".json";
            SkillValidator.graphIds(skill).forEach(id -> owner.put(id, acceptedFile));
            accepted.add(skill);
        }
        return accepted;
    }

    /** The first of the skill's graph ids that an accepted skill or one of {@code reserved} has; {@code null} if none. */
    private static String takenId(SkillFile skill, Map<String, String> owner, Map<String, String> reserved) {
        return SkillValidator.graphIds(skill).stream()
                .filter(id -> owner.containsKey(id) || reserved.containsKey(id))
                .findFirst().orElse(null);
    }

    /** Graph id → file name for every built-in except the named one. */
    private static Map<String, String> otherBuiltIns(Map<String, SkillFile> builtIns, String except) {
        Map<String, String> ids = new HashMap<>();
        builtIns.forEach((name, skill) -> {
            if (!name.equals(except)) SkillValidator.graphIds(skill).forEach(id -> ids.put(id, name + ".json (built-in)"));
        });
        return ids;
    }

    private static String taken(String id, Map<String, String> owner, Map<String, SkillFile> builtIns) {
        if (owner.containsKey(id)) return owner.get(id);
        return otherBuiltIns(builtIns, "").get(id);
    }

    private static String readFile(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** A built-in skill's JSON from the classpath, {@code null} when it isn't there. */
    private static String readResource(String name) {
        try (InputStream in = SkillLoader.class.getClassLoader().getResourceAsStream(BUILT_IN_FOLDER + name + ".json")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
