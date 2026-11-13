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
        // A plain request without tools: the model can only answer with text
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
    /** Tool results and call arguments of earlier turns longer than this are shortened when sent again. */
    static final int EARLIER_TURN_CHARS = 400;

    /**
     * The messages with every turn before the current one compacted: a tool result longer than
     * {@link #EARLIER_TURN_CHARS} keeps only its start plus a note that the rest was sent in an earlier
     * turn, and long string values in tool call arguments (the content of a {@code writeFile}, the new text
     * of an {@code editSection}) become "[N characters]". The current turn (from the last user message on) is
     * unchanged, and so are the questions, the answers and the saved conversation.
     * <p>
     * Why: old file contents, web pages and written files were sent again, word for word, on every request
     * of every later turn: one session reached 124,000 characters, of which a few thousand were still
     * needed. A local model server reads a few hundred tokens a second, so re-reading that took minutes
     * whenever it couldn't use its cache, and it kept summary memory over its limit, so a new summary
     * changed the start of the prompt on every turn and the cache was lost again. The model can call the
     * tool again when it needs an old result in full.
     * <p>
     * The result keeps one message per message (same indices, so {@link #cutoff} can work on it), and a
     * compacted message depends only on itself, so every request of a turn sends the same text and the
     * server's cache stays valid.
     */
    static List<ChatMessage> compactEarlierTurns(List<ChatMessage> messages) {
        int currentTurn = -1;
        for (int i = messages.size() - 1; i >= 0 && currentTurn < 0; i--) {
            if (messages.get(i) instanceof UserMessage) currentTurn = i;
        }
        if (currentTurn <= 0) return messages;
        List<ChatMessage> compacted = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            compacted.add(i < currentTurn ? compact(message) : message);
        }
        return compacted;
    }

    private static ChatMessage compact(ChatMessage message) {
        if (message instanceof ToolExecutionResultMessage result && result.text().length() > EARLIER_TURN_CHARS) {
            String text = result.text();
            int keep = EARLIER_TURN_CHARS;
            return ToolExecutionResultMessage.builder()
                    .id(result.id())
                    .toolName(result.toolName())
                    .text(text.substring(0, keep) + "\n[… " + (text.length() - keep) + " more characters of this "
                            + result.toolName() + " result were shown in an earlier turn; call the tool again if you"
                            + " need them]")
                    .isError(result.isError())
                    .build();
        }
        if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
            List<ToolExecutionRequest> calls = new ArrayList<>();
            boolean changed = false;
            for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                String arguments = compactArguments(call.arguments());
                changed |= !arguments.equals(call.arguments());
                calls.add(ToolExecutionRequest.builder().id(call.id()).name(call.name()).arguments(arguments).build());
            }
            if (!changed) return message;
            return AiMessage.builder().text(ai.text()).thinking(ai.thinking()).toolExecutionRequests(calls).build();
        }
        return message;
    }

    /** The arguments JSON with every string value longer than {@link #EARLIER_TURN_CHARS} replaced by "[N characters]". */
    private static String compactArguments(String arguments) {
        if (arguments == null || arguments.length() <= EARLIER_TURN_CHARS) return arguments;
        try {
            com.fasterxml.jackson.databind.JsonNode tree = JSON.readTree(arguments);
            if (!(tree instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) return arguments;
            boolean changed = false;
            for (var field : object.properties()) {
                if (field.getValue().isTextual() && field.getValue().asText().length() > EARLIER_TURN_CHARS) {
                    field.setValue(new com.fasterxml.jackson.databind.node.TextNode(
                            "[" + field.getValue().asText().length() + " characters, sent in an earlier turn]"));
                    changed = true;
                }
            }
            return changed ? JSON.writeValueAsString(object) : arguments;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return arguments; // not JSON: leave it
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

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
