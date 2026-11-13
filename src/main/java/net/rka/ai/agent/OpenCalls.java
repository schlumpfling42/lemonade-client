package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A conversation that was saved while a turn was running (the app saves as the turn goes) can end with a tool call that has no result:
 * the app was closed in the middle of it. The model server (or its chat template) may refuse a conversation with such a call, so it is
 * answered with an error result right after the call, which also tells the model what happened.
 */
public final class OpenCalls {

    private OpenCalls() {
    }

    /**
     * @return the messages with an error result after every assistant message that has a call without a result anywhere in the list;
     *         the same list when there is none
     */
    public static List<ChatMessage> closed(List<ChatMessage> messages, String why) {
        Set<String> answered = new HashSet<>();
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage result) answered.add(result.id());
        }
        List<ChatMessage> out = new ArrayList<>(messages.size() + 2);
        boolean changed = false;
        for (ChatMessage message : messages) {
            out.add(message);
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                    if (answered.contains(call.id())) continue;
                    out.add(ToolExecutionResultMessage.builder().id(call.id()).toolName(call.name()).text("Error: " + why).isError(true).build());
                    changed = true;
                }
            }
        }
        return changed ? out : messages;
    }
}
