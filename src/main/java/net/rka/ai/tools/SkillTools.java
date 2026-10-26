package net.rka.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

/**
 * The entry to the skill builder: one tool the model calls to create or change a skill.
 *
 * <h2>How it works</h2>
 * Like {@link CodingTools#startCodingJob}, the tool is only a signal: its specification is sent to the
 * model, but a call never runs. {@code AgentGraph.route} sends any reply with a {@code buildSkill} call
 * to the {@code skillBuilder} subgraph ({@code SkillBuilderGraph}), which drafts the skill file with the
 * model, checks it with the skill validator, shows it to the user for review and, on Save, writes it to
 * the skills folder ({@code ~/chat/skills}); the skills are reloaded after the turn. The builder answers
 * the call with what happened. The method body only exists because a {@code @Tool} needs one; should a
 * call ever reach it (a routing bug), it fails, and the model gets the error as the call's result.
 * Skills and coding jobs never offer or run it.
 */
public class SkillTools {

    @Tool("Creates a new skill, or changes an existing one, from a description: a skill is a saved procedure "
            + "(steps with prompts, tools, questions to the user and branches) that becomes a tool of its own. "
            + "The builder drafts and checks the skill and shows it to the user, who saves, revises or cancels it. "
            + "Use it when the user asks to create, add, change or fix a skill.")
    public String buildSkill(@P("what the skill should do and when it is used; to change a skill, name it "
            + "(e.g. \"change the skill release-notes so that ...\")") String description) {
        throw new IllegalStateException("handled by the skill builder subgraph");
    }
}
