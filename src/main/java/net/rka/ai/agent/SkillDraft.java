package net.rka.ai.agent;

import net.rka.ai.agent.skills.SkillDiagram;
import net.rka.ai.agent.skills.SkillFile;

import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What the user reviews when the skill builder pauses ({@link Approver#reviewSkill}): a valid draft,
 * shown as the user can judge it before anything is saved. {@link Agent} builds it from the paused
 * graph's state ({@code skillDraft}).
 *
 * @param skill                the parsed draft, valid by the same rules the loader applies
 * @param json                 the file as Save writes it ({@code ~/chat/skills/<name>.json}), for "Show JSON"
 * @param outline              the steps and where each outcome leads, as text ({@link SkillDiagram#outline})
 * @param toolsNeedingApproval the listed tools that ask the user before they run, unless trusted
 * @param replaces             whether saving replaces an existing skill of the same name (a built-in or a file
 *                             of the skills folder); a draft only says so when that skill exists
 * @param replacesBuiltIn      whether the skill it replaces is one of the loader's built-ins (the user's own
 *                             replacement of a built-in counts too: saving replaces what stands in for it)
 * @param tool                 the tool that will start the skill: {@code skill_<name>}, or the pinned tool of a
 *                             replaced built-in (e.g. {@code startCodingJob}), see {@code SkillLoader.toolOf}
 */
public record SkillDraft(SkillFile skill, String json, String outline, List<String> toolsNeedingApproval,
                         boolean replaces, boolean replacesBuiltIn, String tool) {

    public SkillDraft {
        Objects.requireNonNull(skill, "skill");
        json = json == null ? "" : json;
        outline = outline == null ? "" : outline;
        toolsNeedingApproval = List.copyOf(toolsNeedingApproval);
        tool = tool == null || tool.isBlank() ? skill.toolName() : tool;
    }

    /**
     * What replacing does, as a sentence for the review: "Replaces the built-in coding skill." for a
     * built-in, else "Replaces your skill ..."; "" for a new skill.
     */
    public String replacement() {
        if (!replaces) return "";
        return replacesBuiltIn
                ? "Replaces the built-in " + skill.name() + " skill."
                : "Replaces your skill `" + skill.name() + "` (" + skill.name() + ".json in the skills folder).";
    }

    /**
     * The draft as the dialog and the CLI show it: name and tool, what replacing does, the description
     * (when the model will use it), the limits, the outline of the steps, then the inputs, each step's
     * prompt and the tools with those that need approval ({@link SkillDiagram#details}).
     */
    public String toMarkdown() {
        StringBuilder out = new StringBuilder();
        out.append("**Skill `").append(skill.name()).append("`** · tool `").append(tool).append("`\n\n");
        if (replaces) out.append("**").append(replacement()).append("**\n\n");
        out.append(skill.description()).append("\n\n");
        out.append(limits()).append("\n\n");
        out.append("### Steps\n\n```text\n").append(outline.strip()).append("\n```\n\n");
        out.append(SkillDiagram.details(skill, toolsNeedingApproval));
        return out.toString();
    }

    /**
     * The skill's limits as one line, defaults included, e.g. "**Limits:** maxSteps 40 · maxToolRounds 8 ·
     * maxStepTurns 15": how long a run may go on, which the user should see before saving.
     */
    String limits() {
        SkillFile.Limits limits = skill.limits();
        StringBuilder out = new StringBuilder("**Limits:** maxSteps ").append(limits.maxSteps())
                .append(" · maxToolRounds ").append(limits.maxToolRounds());
        new TreeMap<>(limits.extra()).forEach((name, value) -> out.append(" · ").append(name).append(' ').append(value));
        return out.toString();
    }
}
