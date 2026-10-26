package net.rka.ai.agent.skills;

import java.util.List;

/**
 * What an {@code askUser} node asks: {@code Agent} builds it while the graph is paused before the node
 * and hands it to {@code Approver.askUser}, which shows it (a dialog in the window, a prompt in the CLI).
 *
 * @param skill    the skill's name
 * @param node     the node's id inside the skill
 * @param question the node's {@code question}, its {@code {{var}}} placeholders filled in
 * @param choices  the buttons, in the file's order; the answer must be one of them
 */
public record SkillQuestion(String skill, String node, String question, List<String> choices) {
    public SkillQuestion {
        choices = List.copyOf(choices);
    }
}
