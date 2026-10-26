package net.rka.ai.agent.skills;

import dev.langchain4j.model.chat.ChatModel;
import net.rka.ai.agent.NodeTracing;
import net.rka.ai.agent.ToolCallRunner;

import java.util.Map;

/**
 * What a skill's nodes run with: the skill file and the app's model, tools and tracing.
 * {@link SkillGraphBuilder#build} hands it to every node kind's {@link NodeKind#action}.
 *
 * @param skill     the validated skill file
 * @param model     the chat model {@code model} nodes ask
 * @param toolCalls runs the tool calls the skill may make (and holds the tool registry)
 * @param tracing   wraps every node in a span named {@code skill/<name>/<node id>}
 * @param kinds     the node kinds by name, as the validator checked the file with
 */
public record SkillRuntime(SkillFile skill, ChatModel model, ToolCallRunner toolCalls, NodeTracing tracing,
                           Map<String, NodeKind> kinds) {
    public SkillRuntime {
        kinds = Map.copyOf(kinds);
    }
}
