package net.rka.ai.agent;

import java.util.Objects;

/**
 * The user's answer to a skill draft, from {@link Approver#reviewSkill}. {@link Agent} writes it into
 * the paused graph's state ({@code skillDecision}, {@code skillComment}) and resumes; the skill
 * builder's router after {@code review} then saves the skill, drafts it again or ends without saving.
 *
 * @param kind    save, revise or cancel
 * @param comment what should change, with {@link Kind#REVISE}; "" otherwise (null is read as "")
 */
public record SkillDecision(Kind kind, String comment) {

    public enum Kind {
        /** Write the skill file ({@code ~/chat/skills/<name>.json}) and reload the skills after the turn. */
        SAVE,
        /** Draft the skill again, taking the comment into account; it is reviewed again. */
        REVISE,
        /** End the builder without saving anything. */
        CANCEL
    }

    public SkillDecision {
        Objects.requireNonNull(kind, "kind");
        comment = comment == null ? "" : comment;
    }

    /** Cancels; used when there's no one to ask, the dialog was closed or the approver failed. */
    public static final SkillDecision CANCELLED = new SkillDecision(Kind.CANCEL, "");

    /** The value of the state key {@code skillDecision} that the skill builder's router reads. */
    String stateValue() {
        return switch (kind) {
            case SAVE -> SkillBuilderGraph.SAVE;
            case REVISE -> SkillBuilderGraph.REVISE;
            case CANCEL -> SkillBuilderGraph.CANCEL;
        };
    }
}
