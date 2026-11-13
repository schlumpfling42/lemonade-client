package net.rka.ai.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The plan of a coding job: which sections of which files change and how, the commands to run before and after,
 * as the model writes it and the user approves it. It is data: the graph carries it out (the model never calls a
 * tool inside a job), so nothing here decides what may run; {@link PlanValidator} checks it against the folder
 * and the approved plan is what the {@code edit}, {@code setup} and {@code verify} steps work from.
 *
 * <pre>
 * { "summary": "what the job does",
 *   "read":    [ {"file": "src/App.java", "section": "App.run()"} ],       // only while more code is needed
 *   "setup":   [ {"command": "npm install", "workingDirectory": ""} ],      // run before the changes
 *   "changes": [ {"file": "src/App.java", "section": "App", "action": "add", "intent": "a stop() method"} ],
 *   "verify":  [ {"command": "gradle build", "workingDirectory": ""} ],     // run after the last change
 *   "requirements": ["the request as short points"], "approach": "the way chosen, and the alternative" }
 * </pre>
 *
 * @param summary what the job does, in a sentence or two
 * @param read    sections the model wants to see before it can plan (the graph reads them and asks again)
 * @param setup   commands that run before the changes
 * @param changes the ordered changes; at least one in a plan to approve
 * @param verify  commands that check the result after the last change
 * @param requirements the request broken into short points, each of which some change serves (checked by the plan check)
 * @param approach     in a line: the way chosen, and the main alternative if there was one (new things only)
 */
public record CodingPlan(String summary, List<Read> read, List<Command> setup, List<Change> changes, List<Command> verify,
                         List<String> requirements, String approach) {

    /** A plan without requirements and approach. */
    public CodingPlan(String summary, List<Read> read, List<Command> setup, List<Change> changes, List<Command> verify) {
        this(summary, read, setup, changes, verify, List.of(), "");
    }

    /** The same plan (summary, requirements, approach) with other reads, setup, changes and verify commands. */
    public CodingPlan with(List<Read> read, List<Command> setup, List<Change> changes, List<Command> verify) {
        return new CodingPlan(summary, read, setup, changes, verify, requirements, approach);
    }

    /** A section the model wants to read; an empty {@code section} means the file's outline. */
    public record Read(String file, String section) {
        public Read {
            file = file == null ? "" : file.strip();
            section = section == null ? "" : section.strip();
        }
    }

    /**
     * A command the plan runs, exactly as written.
     *
     * @param workingDirectory relative to the trusted folder; empty means the folder itself
     */
    public record Command(String command, String workingDirectory) {
        public Command {
            command = command == null ? "" : command.strip();
            workingDirectory = workingDirectory == null ? "" : workingDirectory.strip();
        }
    }

    /**
     * One change.
     *
     * @param file    relative to the trusted folder
     * @param section the section's id in the file's outline (for {@code add}: the class to add to); empty for {@code create}
     * @param action  what to do with it
     * @param intent  one sentence on what the change achieves: what the edit step is told and the user reads
     * @param anchor  for {@code before} / {@code after} in a method or constructor: the text of the line inside it where the
     *                statements go (the first line of a statement: they go before it, or after the whole statement); empty otherwise
     * @param patch   for {@code patch}: the hunks, written like a patch (lines that stay start with a blank, lines that go with
     *                {@code -}, lines that are added with {@code +}; see {@link net.rka.ai.tools.TextPatch}); empty otherwise
     */
    public record Change(String file, String section, Action action, String intent, String anchor, String patch) {
        public Change {
            file = file == null ? "" : file.strip();
            section = section == null ? "" : section.strip();
            intent = intent == null ? "" : intent.strip();
            anchor = anchor == null ? "" : anchor.strip();
            patch = patch == null ? "" : patch.stripTrailing();
        }

        /** A change without a patch. */
        public Change(String file, String section, Action action, String intent, String anchor) {
            this(file, section, action, intent, anchor, "");
        }

        /** A change without an anchor. */
        public Change(String file, String section, Action action, String intent) {
            this(file, section, action, intent, "", "");
        }

        /** Whether the change puts statements next to one of the statements of a method. */
        public boolean anchored() {
            return !anchor.isEmpty();
        }
    }

    /** What a change does. In JSON the values are lower-case ({@code "replace"}); a few usual synonyms are accepted. */
    public enum Action {
        /** The section's new text takes its place. */
        REPLACE,
        /** A new part of the class named by the section, placed where its kind belongs. */
        ADD,
        /** New text in front of the section. */
        BEFORE,
        /** New text after the section. */
        AFTER,
        /** New statements at the start of the body of the method or constructor named by the section. */
        START,
        /** New statements at the end of the body of the method or constructor named by the section. */
        END,
        /** The section is removed. */
        DELETE,
        /** A few lines of the section change: the plan carries them as a patch, and the edit step needs no model call. */
        PATCH,
        /** A new file. */
        CREATE;

        @JsonValue
        public String json() {
            return name().toLowerCase(Locale.ROOT);
        }

        @JsonCreator
        public static Action fromJson(String value) {
            String name = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            return switch (name) {
                case "replace", "modify", "change", "edit", "update", "rewrite" -> REPLACE;
                case "add", "insert", "append" -> ADD;
                case "start", "prepend", "begin", "insert_start", "insert-start", "at_start", "at-start" -> START;
                case "end", "insert_end", "insert-end", "at_end", "at-end" -> END;
                case "before" -> BEFORE;
                case "after" -> AFTER;
                case "delete", "remove" -> DELETE;
                case "create", "new" -> CREATE;
                case "patch", "diff", "edit_lines", "edit-lines" -> PATCH;
                default -> throw new IllegalArgumentException("action must be one of replace, patch, add, before, after, start, end, delete, "
                        + "create but was '" + value + "'");
            };
        }
    }

    // Lenient about extra fields: the model may add notes we don't need
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public CodingPlan {
        summary = summary == null ? "" : summary;
        read = read == null ? List.of() : List.copyOf(read);
        setup = setup == null ? List.of() : List.copyOf(setup);
        changes = changes == null ? List.of() : List.copyOf(changes);
        verify = verify == null ? List.of() : List.copyOf(verify);
        requirements = requirements == null ? List.of() : requirements.stream().filter(r -> r != null && !r.isBlank()).map(String::strip).toList();
        approach = approach == null ? "" : approach.strip();
    }

    /**
     * Reads the plan from the model's answer. A Markdown code fence and any prose around the JSON object are
     * ignored: only the text from the first {@code {} to the last {@code }} is parsed.
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
        for (Change change : plan.changes()) {
            if (change == null || change.file().isBlank()) throw new IllegalArgumentException("Every change needs a \"file\".");
            if (change.action() == null) {
                throw new IllegalArgumentException("Change of '" + change.file() + "' needs an \"action\": replace, patch, add, before, "
                        + "after, start, end, delete or create.");
            }
        }
        return plan;
    }

    /** The plan as JSON, to keep it in the graph state or send it back to the model. */
    public String toJson() {
        try {
            return JSON.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e); // records of strings and lists always serialize
        }
    }

    /** The files the changes touch (existing and new), in plan order without duplicates. */
    public List<String> changedFiles() {
        Set<String> files = new LinkedHashSet<>();
        for (Change change : changes) files.add(change.file());
        return List.copyOf(files);
    }

    /** The plan for the user, nothing done yet. */
    public String toMarkdown() {
        return toMarkdown(Set.of(), Set.of());
    }

    /**
     * The plan for the user: the summary, the changes as a numbered list (file, section, action, intent), applied
     * ones marked ✓ and failed ones ✗, then the setup and verify commands.
     *
     * @param applied 0-based indexes of the changes that were made
     * @param failed  0-based indexes of the changes that could not be made
     */
    public String toMarkdown(Set<Integer> applied, Set<Integer> failed) {
        StringBuilder out = new StringBuilder();
        if (!summary.isBlank()) out.append(summary).append("\n\n");
        if (!approach.isBlank()) out.append("Approach: ").append(approach).append("\n\n");
        if (!requirements.isEmpty()) {
            out.append("The request, in points:\n");
            for (String requirement : requirements) out.append("- ").append(requirement).append('\n');
            out.append('\n');
        }
        if (!setup.isEmpty()) {
            out.append("Setup, before the changes:\n");
            for (Command command : setup) out.append("- ").append(describe(command)).append('\n');
            out.append('\n');
        }
        for (int i = 0; i < changes.size(); i++) {
            Change change = changes.get(i);
            String mark = applied.contains(i) ? "✓ " : failed.contains(i) ? "✗ failed " : "";
            out.append(i + 1).append(". ").append(mark).append(change.action().json()).append(" `").append(change.file()).append('`');
            if (!change.section().isBlank()) out.append(" · `").append(change.section()).append('`');
            if (!change.intent().isBlank()) out.append(": ").append(change.intent());
            out.append('\n');
            if (!change.patch().isBlank()) out.append("```diff\n").append(change.patch()).append("\n```\n");
        }
        if (!verify.isEmpty()) {
            out.append("\nVerify:\n");
            for (Command command : verify) out.append("- ").append(describe(command)).append('\n');
        }
        return out.toString();
    }

    /** {@code `npm install` in `frontend`}. */
    static String describe(Command command) {
        String where = command.workingDirectory().isBlank() ? "" : " in `" + command.workingDirectory() + "`";
        return "`" + command.command() + "`" + where;
    }
}
