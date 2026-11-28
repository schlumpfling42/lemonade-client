package net.rka.ai.agent;

import net.rka.ai.agent.CodingPlan.Action;
import net.rka.ai.agent.CodingPlan.Change;
import net.rka.ai.agent.CodingPlan.Command;
import net.rka.ai.tools.WorkFolder;
import net.rka.ai.tracing.AgentLog;
import net.rka.ai.tools.code.java.JavaAnchors;
import net.rka.ai.tools.outline.Outline;
import net.rka.ai.tools.outline.Outlines;
import net.rka.ai.tools.outline.Section;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a plan against the trusted folder before the user sees it, with no model: every file and section a change
 * names must exist (or be a new file inside the folder), a change to a section is spelled with the section's full
 * id from the file's outline (a shortened name the model used is replaced by it), an {@code add} names a class, the
 * commands work inside the folder and don't write files, and the plan is not longer than the limit. The answer is
 * the plan, normalized (an {@code add} that names a class's header means the class; one that names another part
 * means "after it"; a change to a file an earlier change creates becomes part of that change), with the build
 * command filled in when the plan names none ({@link VerifyDefaults}), or a list
 * of errors the model is told once.
 * <p>
 * Why before approval and not while working: the user approves exactly what is checked here, and the edit step can
 * rely on every file and section it is given.
 */
final class PlanValidator {

    /** Changes per plan that are comfortable to review: the model is told to stay within it; more goes to the user with a remark. */
    static final int MAX_CHANGES = 12;
    /** More than this is refused: the plan is split. A refused plan costs minutes of writing, so the limit between is a remark, not a refusal. */
    static final int HARD_MAX_CHANGES = 20;
    /** Changed lines (added and removed) up to which a patch stays in the plan; a longer one is written by the edit step. */
    static final int MAX_PATCH_LINES = 8;

    /** Two commands in one: not every shell has these (Windows PowerShell 5 has neither), and the entries of a list run in order anyway. */
    private static final Pattern CHAINED = Pattern.compile("&&|\\|\\|");

    /** {@code gradle wrapper …}, {@code mvn wrapper:wrapper}: upgrades the build tool itself. */
    private static final Pattern WRAPPER_CHANGE = Pattern.compile("(?i)\\b(gradle|gradlew(\\.bat)?|mvn|mvnw(\\.cmd)?)\\b[^|;]*\\bwrapper\\b");

    // A command that writes a file would bypass the changes the user approves: files are changes, never commands
    private static final Pattern FILE_WRITE = Pattern.compile(
            "(?i)(>>?\\s*[^\\s&|>]|\\btee\\b|\\bsed\\s+-i\\b|\\b(set|add|out)-content\\b|\\bout-file\\b)");

    /** The normalized plan, or the errors (one sentence each) when it can't be used. */
    record Result(CodingPlan plan, List<String> errors, List<String> remarks) {
        Result(CodingPlan plan, List<String> errors) {
            this(plan, errors, List.of());
        }

        boolean valid() {
            return errors.isEmpty();
        }
    }

    private PlanValidator() {
    }

    static Result validate(CodingPlan plan, Path folder) {
        List<String> errors = new ArrayList<>();
        if (plan.changes().isEmpty()) errors.add("The plan has no changes: list at least one in \"changes\".");
        if (plan.changes().size() > HARD_MAX_CHANGES) {
            errors.add("The plan has " + plan.changes().size() + " changes; at most " + HARD_MAX_CHANGES
                    + " fit in one job. Plan the first part of the work only.");
        }
        List<String> remarks = new ArrayList<>();
        if (plan.changes().size() > MAX_CHANGES && plan.changes().size() <= HARD_MAX_CHANGES) {
            remarks.add("this is a big plan (" + plan.changes().size() + " changes, " + MAX_CHANGES + " are comfortable to review): you could approve it, or ask for the first part only.");
        }
        Map<String, Outline> outlines = new HashMap<>();
        List<Change> changes = new ArrayList<>();
        Map<String, Integer> created = new HashMap<>(); // a new file's path -> its create change in {@code changes}
        for (int i = 0; i < plan.changes().size() && i < HARD_MAX_CHANGES; i++) {
            Change change = plan.changes().get(i);
            // The job adds the imports a Java change needs by itself (JavaImports): a change to the imports section is a model call that
            // can only lose imports, and was refused for it ("missing: field Alert, field Button, ..."): it is left out
            if (change.file().endsWith(".java") && change.section().strip().equalsIgnoreCase("imports") && change.action() != Action.CREATE
                    && change.action() != Action.DELETE) {
                AgentLog.current().line("   - a change to the imports of " + change.file() + " is left out of the plan: the job adds imports itself");
                continue;
            }
            Integer creating = created.get(path(change.file()));
            if (creating != null && change.action() != Action.CREATE) {
                // "create Calculator" and then "add add() to Calculator": the file doesn't exist yet to have sections, so
                // what the later change says is part of what the create change writes
                Change base = changes.get(creating);
                String also = change.action().json() + (change.section().isBlank() ? "" : " " + change.section())
                        + (change.intent().isBlank() ? "" : ": " + change.intent());
                changes.set(creating, new Change(base.file(), "", Action.CREATE, (base.intent().isBlank() ? "" : base.intent() + "; ") + also));
                continue;
            }
            Change checked = check(change, i + 1, folder, outlines, errors);
            changes.add(checked == null ? change : checked);
            if (checked != null && checked.action() == Action.CREATE) created.put(path(checked.file()), changes.size() - 1);
        }
        for (Command command : plan.setup()) checkCommand(command, folder, errors);
        for (Command command : plan.verify()) checkCommand(command, folder, errors);
        List<Command> verify = plan.verify().isEmpty() ? defaultVerify(folder, plan, changes) : plan.verify();
        return new Result(plan.with(List.of(), VerifyDefaults.normalize(folder, plan.setup()), changes,
                VerifyDefaults.normalize(folder, verify)), errors, remarks);
    }

    /**
     * The build commands of a plan that names none: those of the project that holds the changed files. A plan that makes its project itself (a
     * setup command, or files in a folder that does not exist yet) has none yet: they are found when the job verifies, after the setup. Only a plan
     * that changes files outside any project falls back to the trusted folder (a small plain Java folder is compiled). Taking the trusted folder
     * first once compiled every Java file of a folder of projects with one {@code javac} line, which was too long for Windows.
     */
    private static List<Command> defaultVerify(Path folder, CodingPlan plan, List<Change> changes) {
        List<String> files = changes.stream().map(Change::file).toList();
        List<Command> near = VerifyDefaults.detectNear(folder, files);
        if (!near.isEmpty()) return near;
        boolean createsProject = !plan.setup().isEmpty() || CodingGraph.newProjectFolder(folder, plan.with(List.of(), List.of(), changes, List.of()));
        return createsProject ? List.of() : VerifyDefaults.detect(folder);
    }

    /** A path as the plan spells it, compared without "./" and with forward slashes. */
    private static String path(String file) {
        return file.strip().replace('\\', '/').replaceFirst("^\\./", "");
    }

    /** The change with its file and section normalized, or null (with the reason added to {@code errors}). */
    private static Change check(Change change, int number, Path folder, Map<String, Outline> outlines, List<String> errors) {
        String where = "Change " + number + " (" + change.file() + "): ";
        Path file;
        String relative;
        try {
            file = WorkFolder.resolveInside(folder, change.file());
            // Both paths come from resolveInside (real paths), so the relative one is the file's place in the folder
            relative = WorkFolder.resolveInside(folder, "").relativize(file).toString().replace('\\', '/');
        } catch (IOException | RuntimeException e) {
            errors.add(where + "the file is outside the trusted folder.");
            return null;
        }
        if (change.action() == Action.CREATE) {
            if (Files.exists(file)) {
                errors.add(where + "the file already exists: change its sections instead of creating it.");
                return null;
            }
            return new Change(relative, "", Action.CREATE, change.intent());
        }
        if (!Files.isRegularFile(file)) {
            errors.add(where + "no such file. " + (change.action() == Action.ADD ? "" : "Use action create for a new file."));
            return null;
        }
        // delete without a section removes the whole file (a file of any kind: no outline is needed)
        if (change.action() == Action.DELETE && change.section().isBlank()) return new Change(relative, "", Action.DELETE, change.intent());
        Outline outline = outlines.get(relative);
        if (outline == null) {
            try {
                outline = Outlines.of(file.getFileName().toString(), Files.readAllBytes(file));
            } catch (IOException | RuntimeException e) {
                errors.add(where + "the file can't be read as text: " + e.getMessage());
                return null;
            }
            outlines.put(relative, outline);
        }
        if (change.action() == Action.PATCH) return patchChange(where, change, file, relative, outline, errors);
        if (change.section().isBlank()) {
            errors.add(where + "name the section (an id from the file's outline).");
            return null;
        }
        Section section;
        try {
            section = outline.find(change.section());
        } catch (IllegalArgumentException e) {
            errors.add(where + e.getMessage());
            return null;
        }
        Action action = change.action();
        if ((action == Action.START || action == Action.END) && !Set.of("method", "constructor").contains(section.kind())) {
            errors.add(where + "start and end put statements into the body of a method or constructor, but `" + section.id() + "` is a "
                    + section.kind() + ". Use replace, add, before or after for it.");
            return null;
        }
        if (action == Action.ADD && !section.container()) {
            // "add a method" with the class's header (or a method) as the section: the model means the class, or "next to it"
            Section owner = section.kind().equals("header") && section.parentId() != null ? outline.byId(section.parentId()) : null;
            if (owner != null) {
                section = owner;
            } else {
                action = Action.AFTER;
            }
        }
        if (change.anchored()) {
            if (action != Action.BEFORE && action != Action.AFTER) {
                errors.add(where + "an anchor goes with before or after: the statements go before / after the line it names.");
                return null;
            }
            if (!Set.of("method", "constructor").contains(section.kind()) || !relative.endsWith(".java")) {
                errors.add(where + "an anchor names a line inside a method or constructor of a Java file, but `" + section.id() + "` is a "
                        + section.kind() + ".");
                return null;
            }
            try {
                String text = Files.readString(file);
                JavaAnchors.find(text, section.start(), section.end(), change.anchor());
            } catch (IOException | RuntimeException e) {
                errors.add(where + e.getMessage());
                return null;
            }
            return new Change(relative, section.id(), action, change.intent(), change.anchor());
        }
        return new Change(relative, section.id(), action, change.intent());
    }

    /**
     * A patch change: the section (a member of a Java file; for another file the whole file when none is named) holds the hunks'
     * context exactly once. The patch is tried on the file's text here, so a hunk that fits nowhere is told to the model while it still
     * has the code in front of it, and the edit step later only has to apply what is known to fit.
     */
    private static Change patchChange(String where, Change change, Path file, String relative, Outline outline, List<String> errors) {
        if (change.patch().isBlank()) {
            errors.add(where + "a patch change needs \"patch\": the hunks (lines that stay start with a blank, lines that go with -, lines that are added with +).");
            return null;
        }
        boolean isJava = relative.endsWith(".java");
        if (isJava && change.section().isBlank()) {
            errors.add(where + "name the section the patch is in (an id from the file's outline): the method, constructor or field it changes.");
            return null;
        }
        try {
            List<String> lines = List.of(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n", -1));
            int from = 1;
            int to = lines.size();
            String id = "";
            if (!change.section().isBlank()) {
                Section section = outline.find(change.section());
                from = section.start();
                to = section.end();
                id = section.id();
            }
            try {
                net.rka.ai.tools.TextPatch.apply(lines.subList(from - 1, Math.min(to, lines.size())), from, change.patch());
            } catch (IllegalArgumentException doesNotFit) {
                // The section exists, only the patch's lines are not in it (often the text as it will be, not as it is): not a reason to send
                // the whole plan back. The model writes the section, with the patch as what the change should do.
                if (id.isEmpty()) throw doesNotFit;
                return new Change(relative, id, Action.REPLACE, change.intent() + " (The planned patch did not fit the text: "
                        + doesNotFit.getMessage().replaceAll("\\s+", " ") + " It wanted this change:\n" + change.patch()
                        + "\nWrite the complete new text of the section.)");
            }
            // The plan is not the place for code: a long patch is written by the edit step (the model is the same, but a plan that is
            // refused after minutes of writing code costs far more than the edit step does)
            long changed = change.patch().lines().filter(l -> (l.startsWith("+") || l.startsWith("-")) && !l.startsWith("+++") && !l.startsWith("---")).count();
            if (changed > MAX_PATCH_LINES && !id.isEmpty()) {
                return new Change(relative, id, Action.REPLACE, change.intent() + " (The plan sketched this change; write the complete new text of the section:\n"
                        + change.patch() + ")");
            }
            return new Change(relative, id, Action.PATCH, change.intent(), "", change.patch());
        } catch (IOException | RuntimeException e) {
            errors.add(where + "the patch does not fit: " + e.getMessage());
            return null;
        }
    }

    private static void checkCommand(Command command, Path folder, List<String> errors) {
        if (command.command().isBlank()) {
            errors.add("A command is blank.");
            return;
        }
        if (CHAINED.matcher(command.command().replaceAll("\"[^\"]*\"|'[^']*'", "")).find()) {
            errors.add("Command `" + command.command() + "` chains commands with && or ||, which Windows PowerShell does not understand"
                    + " (the whole job would fail on the syntax): write each command as its own entry; the entries run in order.");
        }
        if (WRAPPER_CHANGE.matcher(command.command()).find()) {
            errors.add("Command `" + command.command() + "` changes the build tool's wrapper: a plan builds with the project's own build setup and does not change it.");
        }
        if (FILE_WRITE.matcher(command.command()).find()) {
            errors.add("Command `" + command.command() + "` writes a file: files are changes in \"changes\", never commands.");
        }
        try {
            WorkFolder.resolveInside(folder, command.workingDirectory());
        } catch (IOException | RuntimeException e) {
            errors.add("Command `" + command.command() + "`: the working directory is outside the trusted folder.");
        }
    }
}
