package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Conversation summary memory: keeps what's sent to the model small by replacing older messages
 * with a running summary, while the full history stays in the conversation (and on screen).
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>At the start of each turn the graph's "memory" node calls {@link #cutoff}: if the messages
 *       after the current summary are estimated at more than {@code triggerTokens}, it picks a new
 *       cut point so that roughly {@code recentTokens} of the newest messages stay verbatim.</li>
 *   <li>The cut always lands on a {@link UserMessage}. That keeps every tool call together with its
 *       result (they're always between two user messages), and the recent part starts cleanly with
 *       a question.</li>
 *   <li>{@link #summarize} asks the model to merge the previous summary with the messages being cut
 *       off into a new summary.</li>
 *   <li>{@link #view} builds what the model actually sees: the system prompt with the summary
 *       appended, followed by the messages after the cut. The summary goes <em>into</em> the system
 *       message (not a second system message) because some chat templates, e.g. Qwen's, accept only
 *       one system message at the start.</li>
 * </ol>
 * Token counts are estimated as characters / 4, which is close enough for English text and avoids
 * depending on the model's tokenizer.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>A real tokenizer (LangChain4j's {@code TokenCountEstimator}) for exact budgets.</li>
 *   <li>Summarizing inside a long turn too (today only at the start of a turn), for turns with many
 *       large tool results.</li>
 *   <li>Showing the summary in the window, and letting you edit it.</li>
 *   <li>Retrieval instead of only summarizing: embed old messages and pull back the relevant ones.</li>
 * </ul>
 */
final class SummaryMemory {

    /** Instructions for the summarizing call. */
    static final String INSTRUCTIONS = "You maintain the memory of a conversation between a user and an "
            + "AI assistant that uses tools. Write a concise summary that preserves everything needed to "
            + "continue the conversation: the user's goals and requests, decisions made, facts learned, "
            + "important tool results (file names, paths, command output, URLs), and open tasks. "
            + "Merge the previous summary with the new messages. Use short bullet points. "
            + "Write only the summary, no preamble.";

    private static final int MAX_TOOL_TEXT = 600; // characters of each tool result fed to the summarizer

    private SummaryMemory() {
    }

    /** Rough token estimate: about 4 characters per token. */
    static int estimateTokens(ChatMessage message) {
        return Math.max(1, length(message) / 4);
    }

    private static int length(ChatMessage message) {
        if (message instanceof SystemMessage s) return s.text().length();
        if (message instanceof UserMessage u) return u.hasSingleText() ? u.singleText().length() : u.contents().toString().length();
        if (message instanceof ToolExecutionResultMessage r) return r.text().length();
        if (message instanceof AiMessage ai) {
            int n = ai.text() == null ? 0 : ai.text().length();
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest call : ai.toolExecutionRequests()) n += call.name().length() + call.arguments().length();
            }
            return n;
        }
        return message.toString().length();
    }

    /**
     * Where the summary should end after this turn's start.
     *
     * @param messages      the whole history, system prompt at index 0, the new question last
     * @param covered       the current summary's {@link ConversationSummary#covered()}
     * @param triggerTokens summarize only when the unsummarized part is bigger than this
     * @param recentTokens  keep about this much of the newest messages verbatim
     * @return the new {@code covered} index (always at a UserMessage), or {@code covered} unchanged
     *         when nothing needs to be summarized
     */
    static int cutoff(List<ChatMessage> messages, int covered, int triggerTokens, int recentTokens) {
        int start = Math.min(Math.max(1, covered), messages.size());
        int unsummarized = 0;
        for (int i = start; i < messages.size(); i++) unsummarized += estimateTokens(messages.get(i));
        if (unsummarized <= triggerTokens) return start; // still small: nothing to do

        // Walk back from the newest message, adding up tokens. Cut at the first user message (going
        // backwards) by which at least recentTokens are kept: everything from there on stays verbatim.
        int kept = 0;
        int lastUser = -1;
        for (int i = messages.size() - 1; i > start; i--) {
            kept += estimateTokens(messages.get(i));
            if (messages.get(i) instanceof UserMessage) {
                lastUser = i;
                if (kept >= recentTokens) return i;
            }
        }
        // The budget wasn't reached before the summary's start: cut at the earliest user message found
        return lastUser > start ? lastUser : start;
    }

    /** Asks the model for a new summary: the previous one merged with {@code messages}. */
    static String summarize(ChatModel model, String previousSummary, List<ChatMessage> messages) {
        StringBuilder prompt = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            prompt.append("Previous summary:\n").append(previousSummary.strip()).append("\n\n");
        }
        prompt.append("New messages to add to the summary:\n").append(transcript(messages));
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(INSTRUCTIONS), UserMessage.from(prompt.toString()))
                .build();
        String summary = model.chat(request).aiMessage().text();
        return summary == null ? "" : summary.strip();
    }

    /** Incremental update: merge the previous summary with just the new messages. ~30% of a full summary. */
    static String incrementalUpdate(ChatModel model, String previousSummary, List<ChatMessage> newMessages) {
        if (previousSummary.isBlank()) return summarize(model, "", newMessages);
        StringBuilder prompt = new StringBuilder();
        prompt.append("Update this summary with the new messages. Keep the existing structure and add only what's new.\n\n");
        prompt.append("Current summary:\n").append(previousSummary.strip()).append("\n\n");
        prompt.append("New messages to add:\n").append(transcript(newMessages));
        ChatRequest request = ChatRequest.builder()
                .messages(SystemMessage.from(INSTRUCTIONS), UserMessage.from(prompt.toString()))
                .build();
        String summary = model.chat(request).aiMessage().text();
        return summary == null ? "" : summary.strip();
    }

    /** The messages as plain text for the summarizer: "User: ...", "Assistant: ...", tool calls and (cut) results. */
    static String transcript(List<ChatMessage> messages) {
        StringBuilder out = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage u) {
                out.append("User: ").append(u.hasSingleText() ? u.singleText() : u.contents()).append('\n');
            } else if (message instanceof AiMessage ai) {
                if (ai.text() != null && !ai.text().isBlank()) out.append("Assistant: ").append(ai.text().strip()).append('\n');
                if (ai.hasToolExecutionRequests()) {
                    for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                        out.append("Tool call: ").append(call.name()).append(' ').append(call.arguments()).append('\n');
                    }
                }
            } else if (message instanceof ToolExecutionResultMessage r) {
                String text = r.text();
                if (text.length() > MAX_TOOL_TEXT) text = text.substring(0, MAX_TOOL_TEXT) + " …";
                out.append("Tool result (").append(r.toolName()).append("): ").append(text).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * What the model is sent: the system prompt (with the summary appended, if any) followed by the
     * messages from {@code covered} on. Messages before {@code covered} are represented by the summary.
     */
    /** Compact tool results and arguments from earlier turns — delegated to {@link ContextGroom}. */
    static List<ChatMessage> compactEarlierTurns(List<ChatMessage> messages) {
        return ContextGroom.compactEarlierTurns(messages);
    }

    static List<ChatMessage> view(List<ChatMessage> messages, ConversationSummary summary) {
        if (summary.isEmpty() || summary.covered() <= 1 || messages.isEmpty()
                || !(messages.get(0) instanceof SystemMessage system)) {
            return messages; // nothing summarized: send everything
        }
        int from = Math.min(summary.covered(), messages.size());
        List<ChatMessage> view = new ArrayList<>();
        view.add(SystemMessage.from(system.text() + "\n\nSummary of the earlier part of this conversation "
                + "(older messages are not shown):\n" + summary.text()));
        view.addAll(messages.subList(from, messages.size()));
        return view;
    }
}
