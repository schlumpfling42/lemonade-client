package net.rka.ai.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import net.rka.ai.tools.ToolRegistry;
import net.rka.ai.tools.TrustedCommands;
import net.rka.ai.tools.WorkFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The plan of a coding job: the commands to run and the file changes to make, step by step, plus
 * the commands that verify the result. The user approves exactly this plan, and {@link #covers}
 * is what keeps the job inside it: a tool call the plan does not cover never runs.
 *
 * <h2>How the plan check works</h2>
 * <pre>
 *   tool call                          | covered when
 *   -----------------------------------+--------------------------------------------------------
 *   read-only tool (no marker)         | always
 *   runCommand                         | (command, working directory) equals a plan command, or the
 *                                      | user's trusted commands allow it
 *   writeFile, createDirectory         | a CREATE/MODIFY entry covers the path
 *   copyFile                           | the source is inside the folder and a CREATE/MODIFY entry
 *                                      | covers the target
 *   movePath                           | a MOVE entry covers "from" and a CREATE entry covers "to"
 *   deletePath                         | a DELETE entry covers the path
 *   anything else (SSH, memory, ...)   | never
 * </pre>
 * A file entry covers its exact path; an entry ending in {@code /} is a folder and covers everything
 * under it. Deleting and moving only follow entries of that kind, and a folder entry never allows
 * deleting or moving the folder itself. Commands match by exact text, with runs of spaces and tabs
 * collapsed ({@link #normalizeCommand}: a newline separates two commands, so it is never normalized
 * away), and resolved working directory, with no wildcards. All paths go through {@link WorkFolder#resolveInside},
 * so anything outside the trusted folder (via {@code ..}, an absolute path or a symlink) is not covered.
 *
 * @param summary what the job does, in a sentence or two
 * @param steps   the ordered steps; at least one
 * @param verify  commands that check the result after the last step
 */
public record CodingPlan(String summary, List<Step> steps, List<Command> verify) {

    /**
     * One step of the plan.
     *
     * @param description what the step achieves
     * @param commands    commands the step may run
     * @param files       file changes the step may make
     */
    public record Step(String description, List<Command> commands, List<FileChange> files) {
        // Models leave out empty lists; treat missing as empty so the rest of the code never sees null
        public Step {
            description = description == null ? "" : description;
            commands = commands == null ? List.of() : List.copyOf(commands);
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /**
     * A command the plan allows.
     *
     * @param command          the command line
     * @param workingDirectory relative to the trusted folder; empty means the folder itself
     */
    public record Command(String command, String workingDirectory) {
        public Command {
            workingDirectory = workingDirectory == null ? "" : workingDirectory;
        }
    }

    /**
     * A file change the plan allows.
     *
     * @param path   relative to the trusted folder; ending in {@code /} means a folder
     * @param change what may be done with it
     */
    public record FileChange(String path, Change change) {
    }

    /** What a plan entry allows. In JSON the values are lower-case ({@code "create"}). */
    public enum Change {
        CREATE, MODIFY, DELETE, MOVE;

        @JsonValue
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static Change fromJson(String value) {
            for (Change change : values()) {
                if (change.json().equalsIgnoreCase(value == null ? "" : value.strip())) return change;
            }
            throw new IllegalArgumentException("change must be one of create, modify, delete, move but was '" + value + "'");
        }
    }

    // Lenient about extra fields: the model may add notes we don't need
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CodingPlan {
        summary = summary == null ? "" : summary;
        steps = steps == null ? List.of() : List.copyOf(steps);
        verify = verify == null ? List.of() : List.copyOf(verify);
    }

    /**
     * Reads the plan from the model's answer. A Markdown code fence and any prose around the JSON
     * object are ignored: only the text from the first {@code {} to the last {@code }} is parsed.
     *
     * @throws IllegalArgumentException with a readable message when there is no valid plan
     */
    public static CodingPlan parse(String modelText) {
        int start = modelText == null ? -1 : modelText.indexOf('{');
        int end = modelText == null ? -1 : modelText.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("The answer contains no JSON object with a plan.");
        CodingPlan plan;
        try {
            plan = JSON.readValue(modelText.substring(start, end + 1), CodingPlan.class);
        } catch (JsonMappingException e) {
            // getOriginalMessage skips Jackson's location/class noise; the cause carries our own wording
            Throwable cause = e.getCause() instanceof IllegalArgumentException iae ? iae : e;
            throw new IllegalArgumentException("The plan is not valid: "
                    + (cause instanceof JsonMappingException jme ? jme.getOriginalMessage() : cause.getMessage()), e);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The plan is not valid JSON: " + e.getOriginalMessage(), e);
        }
        plan.validate();
        return plan;
    }

    /** Checks what the JSON structure cannot: at least one step, no blank commands or paths. */
    private void validate() {
        if (steps.isEmpty()) throw new IllegalArgumentException("The plan needs at least one entry in \"steps\".");
        for (Command command : allCommands().toList()) {
            if (command == null || command.command() == null || command.command().isBlank()) {
                throw new IllegalArgumentException("Every command in the plan needs a non-blank \"command\".");
            }
        }
        for (FileChange file : steps.stream().flatMap(s -> s.files().stream()).toList()) {
            if (file == null || file.path() == null || file.path().isBlank()) {
                throw new IllegalArgumentException("Every file entry in the plan needs a non-blank \"path\".");
            }
            if (file.change() == null) {
                throw new IllegalArgumentException("File entry '" + file.path() + "' needs a \"change\": create, modify, delete or move.");
            }
        }
    }

    /** The plan as JSON, e.g. to keep it in the graph state or send it back to the model. */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e); // records of strings and lists always serialize
        }
    }

    /**
     * The plan for the user: numbered steps with commands in code and file entries with their
     * change kind, steps already finished marked with a check mark, then the verify commands.
     *
     * @param stepsDone how many steps from the start are finished
     */
    public String toMarkdown(int stepsDone) {
        return toMarkdown(stepsDone, Set.of());
    }

    /**
     * Like {@link #toMarkdown(int)}, with skipped steps marked "✗ skipped" instead of "✓": steps the
     * job moved past although none of their commands or file changes ran (see {@code CodingGraph.implement}).
     *
     * @param skipped 1-based numbers of the skipped steps
     */
    public String toMarkdown(int stepsDone, Set<Integer> skipped) {
        StringBuilder out = new StringBuilder();
        if (!summary.isBlank()) out.append(summary).append("\n\n");
        for (int i = 0; i < steps.size(); i++) {
            String mark = i >= stepsDone ? "" : skipped.contains(i + 1) ? "✗ skipped " : "✓ ";
            out.append(i + 1).append(". ").append(mark).append(steps.get(i).description()).append('\n');
            for (String item : items(i)) out.append("   - ").append(item).append('\n');
        }
        if (!verify.isEmpty()) {
            out.append("\nVerify:\n");
            for (Command command : verify) out.append("- ").append(describe(command)).append('\n');
        }
        return out.toString();
    }

    /**
     * The paths the plan creates or modifies (files, and folders ending in "/"), in plan order without
     * duplicates: what the design review reads and what decides which design guide applies.
     */
    public List<String> changedPaths() {
        return steps.stream()
                .flatMap(s -> s.files().stream())
                .filter(f -> f.change() == Change.CREATE || f.change() == Change.MODIFY)
                .map(FileChange::path)
                .distinct()
                .toList();
    }

    /**
     * The commands and file changes of one step as the user sees them ("`npm install` in `frontend`",
     * "create `app/`"). The same text names an item in {@link #itemsDoneBy} and in the job's record of
     * what ran ({@code codingDoneItems}), so it identifies the item across a revision that keeps the step.
     *
     * @param step 0-based index of the step
     */
    public List<String> items(int step) {
        Step s = steps.get(step);
        List<String> items = new ArrayList<>();
        for (Command command : s.commands()) items.add(describe(command));
        for (FileChange file : s.files()) items.add(describe(file));
        return items;
    }

    /**
     * Which items of one step the call carries out: the step's commands it matches and the step's file
     * entries that cover its paths, by the same rules as {@link #covers}, but only this step's entries
     * count. Empty for read-only tools, for calls that only match other steps or the user's trusted
     * commands, and for malformed arguments or paths outside the folder.
     *
     * @param step 0-based index of the step
     */
    public List<String> itemsDoneBy(int step, ToolExecutionRequest call, Path workFolder) {
        Step s = steps.get(step);
        JsonNode arguments = arguments(call);
        if (arguments == null) return List.of();
        Set<String> done = new LinkedHashSet<>(); // a move matches two entries; keep each item once, in order
        try {
            switch (call.name()) {
                case "runCommand" -> matchingCommands(text(arguments, "command"), text(arguments, "workingDirectory"),
                        workFolder, s.commands()).forEach(c -> done.add(describe(c)));
                case "writeFile", "createDirectory" -> matchingEntries(text(arguments, "path"), workFolder, false,
                        s.files(), Change.CREATE, Change.MODIFY).forEach(f -> done.add(describe(f)));
                case "copyFile" -> matchingEntries(text(arguments, "to"), workFolder, false,
                        s.files(), Change.CREATE, Change.MODIFY).forEach(f -> done.add(describe(f)));
                case "movePath" -> {
                    matchingEntries(text(arguments, "from"), workFolder, true, s.files(), Change.MOVE)
                            .forEach(f -> done.add(describe(f)));
                    matchingEntries(text(arguments, "to"), workFolder, false, s.files(), Change.CREATE)
                            .forEach(f -> done.add(describe(f)));
                }
                case "deletePath" -> matchingEntries(text(arguments, "path"), workFolder, true, s.files(), Change.DELETE)
                        .forEach(f -> done.add(describe(f)));
                default -> {
                    // read-only tools (and tools a job never runs) carry out no plan item
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            return List.of(); // resolveInside refused a path: it matches nothing
        }
        return List.copyOf(done);
    }

    private static String describe(Command command) {
        String where = command.workingDirectory().isBlank() ? "" : " in `" + command.workingDirectory() + "`";
        return "`" + command.command() + "`" + where;
    }

    private static String describe(FileChange file) {
        return file.change().json() + " `" + file.path() + "`";
    }

    /**
     * Whether the approved plan allows this tool call (see the class comment for the rules).
     *
     * @param call        the tool call the model wants to make
     * @param workFolder  the trusted folder everything must stay inside
     * @param userTrusted the user's own trusted commands, which run without being in the plan
     * @param tools       tells which tools are read-only
     */
    public boolean covers(ToolExecutionRequest call, Path workFolder, TrustedCommands userTrusted, ToolRegistry tools) {
        String tool = call.name();
        // Read-only tools (neither approval nor file-changing) are harmless: the plan does not need to list them
        if (!tools.requiresApproval(tool) && !tools.writesFiles(tool)) return true;
        // The user's trusted commands run without being in the plan
        if (tool.equals("runCommand") && userTrusted.isTrusted(call)) return true;
        JsonNode arguments = arguments(call);
        if (arguments == null) return false; // missing or malformed arguments from the model: fail closed
        List<FileChange> files = steps.stream().flatMap(s -> s.files().stream()).toList();
        try {
            return switch (tool) {
                case "runCommand" -> !matchingCommands(text(arguments, "command"), text(arguments, "workingDirectory"),
                        workFolder, allCommands().toList()).isEmpty();
                case "writeFile", "createDirectory" -> !matchingEntries(text(arguments, "path"),
                        workFolder, false, files, Change.CREATE, Change.MODIFY).isEmpty();
                case "copyFile" -> isInside(text(arguments, "from"), workFolder) // the source is only read
                        && !matchingEntries(text(arguments, "to"), workFolder, false, files, Change.CREATE, Change.MODIFY).isEmpty();
                case "movePath" -> !matchingEntries(text(arguments, "from"), workFolder, true, files, Change.MOVE).isEmpty()
                        && !matchingEntries(text(arguments, "to"), workFolder, false, files, Change.CREATE).isEmpty();
                case "deletePath" -> !matchingEntries(text(arguments, "path"), workFolder, true, files, Change.DELETE).isEmpty();
                default -> false; // runRemoteCommand, remember, forget, ...: not allowed inside a job
            };
        } catch (IOException | IllegalArgumentException e) {
            return false; // resolveInside refused the path: outside the folder or no folder
        }
    }

    /**
     * The call's arguments as JSON, or null when they are missing or malformed. Jackson throws an
     * {@link IllegalArgumentException}, not a {@link JsonProcessingException}, for null text, so both
     * mean "no arguments" and the callers fail closed.
     */
    private static JsonNode arguments(ToolExecutionRequest call) {
        if (call.arguments() == null) return null;
        try {
            return JSON.readTree(call.arguments());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return null;
        }
    }

    /** The text value of an argument, or null when it is missing or not text. */
    private static String text(JsonNode arguments, String name) {
        JsonNode value = arguments == null ? null : arguments.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private Stream<Command> allCommands() {
        return Stream.concat(steps.stream().flatMap(s -> s.commands().stream()), verify.stream());
    }

    /**
     * A command as plan matching compares it: runs of spaces and tabs ({@code \h}) collapsed to one
     * space, and the ends trimmed. Unlike {@link TrustedCommands#normalize} it keeps newlines inside the
     * command, because a newline separates two statements: {@code "a\nb"} runs a, then b, so it must
     * never match a planned {@code "a b"}. (A newline at either end only adds an empty statement.)
     */
    static String normalizeCommand(String command) {
        return command.replaceAll("\\h+", " ").strip();
    }

    /** The candidates with the same command text ({@link #normalizeCommand}) and the same resolved working directory. */
    private static List<Command> matchingCommands(String command, String workingDirectory, Path folder,
                                                  List<Command> candidates) throws IOException {
        if (command == null || command.isBlank()) return List.of();
        // resolveInside throws for a directory outside the folder, so ".." or an absolute path is never covered
        Path directory = WorkFolder.resolveInside(folder, workingDirectory);
        String normalized = normalizeCommand(command);
        List<Command> matching = new ArrayList<>();
        for (Command planned : candidates) {
            if (!normalizeCommand(planned.command()).equals(normalized)) continue;
            try {
                if (WorkFolder.resolveInside(folder, planned.workingDirectory()).equals(directory)) matching.add(planned);
            } catch (IllegalArgumentException e) {
                // a planned directory outside the folder never matches; keep looking at the other commands
            }
        }
        return matching;
    }

    /** Whether a path that is only read (no plan entry needed) is inside the trusted folder. */
    private static boolean isInside(String path, Path folder) throws IOException {
        if (path == null || path.isBlank()) return false;
        WorkFolder.resolveInside(folder, path); // throws when outside
        return true;
    }

    /**
     * The entries of one of the given kinds that cover the path.
     *
     * @param entries the file entries to look at: the whole plan's (the plan check) or one step's (what ran)
     * @param itself  whether the folder itself must be refused: delete and move act on the path
     *                itself, which a folder entry ({@code dir/}) must never allow, nor the trusted folder
     */
    private static List<FileChange> matchingEntries(String path, Path folder, boolean itself, List<FileChange> entries,
                                                    Change... kinds) throws IOException {
        if (path == null || path.isBlank()) return List.of();
        Path target = WorkFolder.resolveInside(folder, path);
        if (itself && target.equals(WorkFolder.resolveInside(folder, ""))) return List.of(); // never the folder itself
        List<Change> allowed = List.of(kinds);
        List<FileChange> matching = new ArrayList<>();
        for (FileChange entry : entries) {
            if (!allowed.contains(entry.change())) continue;
            Path covered;
            try {
                covered = WorkFolder.resolveInside(folder, entry.path());
            } catch (IllegalArgumentException e) {
                continue; // an entry outside the folder covers nothing
            }
            boolean isFolder = entry.path().endsWith("/") || entry.path().endsWith("\\");
            if (target.equals(covered)) {
                if (!isFolder || !itself) matching.add(entry); // exact entry; a folder entry covers itself except for delete/move
            } else if (isFolder && target.startsWith(covered)) {
                matching.add(entry); // anything under a folder entry
            }
        }
        return matching;
    }
}
