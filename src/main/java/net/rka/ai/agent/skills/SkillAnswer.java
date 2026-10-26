package net.rka.ai.agent.skills;

/**
 * The user's answer to a {@link SkillQuestion}: one of its choices and an optional comment, or
 * {@link #dismissed()} (dialog closed, end of input, no one to ask). A dismissed question finishes
 * the skill with an error ("cancelled by the user"); it is never taken for an answer.
 *
 * @param choice  the chosen button; {@code null} when dismissed
 * @param comment what the user wrote along with it; "" for none
 */
public record SkillAnswer(String choice, String comment) {
    public SkillAnswer {
        comment = comment == null ? "" : comment;
    }

    /** The question was not answered. */
    public static SkillAnswer dismissed() {
        return new SkillAnswer(null, "");
    }

    /** Whether no choice was made: dismissed, or a blank choice (which no question offers). */
    public boolean isDismissed() {
        return choice == null || choice.isBlank();
    }
}
