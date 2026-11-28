package net.rka.ai.agent;

import java.util.EnumSet;
import java.util.Set;

/**
 * The model calls of a coding job that are made by the graph itself (no tools), and which parts of the job's opening message each one
 * gets. The calls used to share one opening with everything in it; most phases need a fraction: an edit has the change and the
 * section's code in its own message, and needs neither the file list nor the environment. Every character sent is read by a local
 * model at a few hundred tokens a second, so what a phase does not use is left out, and {@link JobContext} logs what was sent.
 */
enum JobPhase {

    /** Which files does the job need: the file list is the material. */
    PICK_FILES(Set.of(Part.GOAL, Part.FOLDER, Part.MAP), 0, true),
    /** What changes where: the folder's layout (new files), the environment (build and test commands), the research notes. */
    PLAN(Set.of(Part.GOAL, Part.FOLDER, Part.ENVIRONMENT, Part.KNOWLEDGE, Part.MAP, Part.NOTES), 6_000, true),
    /** The check of a plan before the user sees it: the request, the research notes and the plan. */
    PLAN_CHECK(Set.of(Part.GOAL, Part.FOLDER, Part.MAP, Part.NOTES), 6_000),
    /** The plan again after the user's remarks: as the plan. */
    REVISE(Set.of(Part.GOAL, Part.FOLDER, Part.ENVIRONMENT, Part.KNOWLEDGE, Part.MAP, Part.NOTES), 6_000),
    /** The code of one change: its own message has the section and the style; the notes give the names it must use, the guide the style of new code. */
    EDIT(Set.of(Part.GOAL, Part.FOLDER, Part.KNOWLEDGE, Part.GUIDE, Part.NOTES), 3_000),
    /** A repair: the message has the section, the build's messages, the file's outline and imports. */
    FIX(Set.of(Part.GOAL, Part.FOLDER, Part.KNOWLEDGE, Part.NOTES), 3_000),
    /** A review of the changed code against the design guide. */
    DESIGN_REVIEW(Set.of(Part.GOAL, Part.FOLDER, Part.GUIDE), 0),
    /** Does the changed code do what was asked: the message has the request and the code. */
    INTENT(Set.of(Part.GOAL, Part.FOLDER), 0),
    /** The report: the message has the ledger and the build's output. */
    EXPLAIN(Set.of(Part.GOAL, Part.FOLDER), 0);

    /** The parts of the opening, in the order they are written (the same order in every phase, so phases share what they have in common). */
    /** KNOWLEDGE: what the client knows about this kind of work ({@link KnowledgeBook}): recipes, and what failed before. */
    enum Part { GOAL, FOLDER, ENVIRONMENT, KNOWLEDGE, MAP, GUIDE, NOTES }

    private final Set<Part> parts;
    private final int notesChars;
    private final boolean history;

    JobPhase(Set<Part> parts, int notesChars) {
        this(parts, notesChars, false);
    }

    JobPhase(Set<Part> parts, int notesChars, boolean history) {
        this.parts = EnumSet.copyOf(parts);
        this.notesChars = notesChars;
        this.history = history;
    }

    /** Whether the call gets the conversation before the job ({@link JobHistory}): the phases that decide what the job is about do; an edit has its plan. */
    boolean history() {
        return history;
    }

    boolean uses(Part part) {
        return parts.contains(part);
    }

    /** The most characters of the research notes this phase gets. */
    int notesChars() {
        return notesChars;
    }
}
