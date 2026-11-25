package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the model of a job has seen of the conversation: the user's messages and the assistant's, in their own roles, as messages in front
 * of the job's own request. A job used to start from the goal alone, so it did not know what the user had asked before, what an earlier
 * job had done, or that it had failed ("continue" after a lost job started everything again, and re-read a folder the conversation had
 * already seen).
 * <p>
 * The tool exchanges of the conversation are left out (the history is then valid whatever tools the job is offered, and the job's model
 * has no tool-call text to copy, see {@link #assistantText}); the assistant's own words about them stay. The newest messages are kept
 * when the budget is not enough.
 */
public final class JobHistory {

    /** The most characters of history. */
    static final int MAX_CHARS = 6_000;
    /** The most characters of one message of the history. */
    static final int MAX_MESSAGE = 1_500;
    /** The most characters of one tool result. */
    static final int MAX_RESULT = 700;
    /** The most characters of the arguments of one call. */
    static final int MAX_ARGUMENTS = 200;

    private JobHistory() {
    }

    /**
     * @param conversation the conversation of the turn, ending with the call that starts the job (open: it has no result yet)
     * @return the user's and the assistant's messages, oldest first, starting with a user message; empty when there are none
     */
    public static List<ChatMessage> of(List<ChatMessage> conversation) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatMessage message : conversation) {
            if (message instanceof UserMessage user) {
                String text = user.hasSingleText() ? user.singleText() : user.toString();
                if (text.isBlank()) continue;
                // Two user messages in a row (the assistant between them only used tools) become one
                if (!messages.isEmpty() && messages.getLast() instanceof UserMessage before) {
                    messages.set(messages.size() - 1, UserMessage.from(clip(before.singleText() + "\n\n" + text, MAX_MESSAGE)));
                } else {
                    messages.add(UserMessage.from(clip(text, MAX_MESSAGE)));
                }
            } else if (message instanceof AiMessage ai) {
                String text = assistantText(ai);
                if (!text.isBlank()) messages.add(AiMessage.from(clip(text, MAX_MESSAGE)));
            }
        }
        // The newest that fit, starting with a user message
        List<ChatMessage> kept = new ArrayList<>();
        int total = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            int size = length(messages.get(i));
            if (total + size > MAX_CHARS && !kept.isEmpty()) break;
            kept.addFirst(messages.get(i));
            total += size;
        }
        while (!kept.isEmpty() && !(kept.getFirst() instanceof UserMessage)) kept.removeFirst();
        return kept;
    }

    /**
     * What the assistant said, without its tool calls. They were once folded into the text as {@code [readSection {...} -> result]}, and
     * the planner then answered in that form instead of calling its function: it wrote "[submit_plan {...} -> Read results: ..." and made
     * up results for 3,500 tokens. What a job needs of earlier work is what the assistant said about it.
     */
    private static String assistantText(AiMessage ai) {
        return ai.text() == null ? "" : ai.text().strip();
    }

    private static int length(ChatMessage message) {
        return message instanceof UserMessage user && user.hasSingleText() ? user.singleText().length()
                : message instanceof AiMessage ai && ai.text() != null ? ai.text().length() : 0;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + " [... cut ...]";
    }
}
