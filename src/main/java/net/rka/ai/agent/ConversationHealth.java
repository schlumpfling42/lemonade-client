package net.rka.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether a conversation is still in a state to work from, read off its messages: what has gone wrong in it lately, and how full it is.
 * A conversation does not break at one moment, it drifts: answers the program had to replace, jobs that stopped, names that were not in
 * the project, a context that is mostly old text. This counts those in the last {@link #RECENT} messages so the user can see it coming
 * and reset the context ({@link ContextReset}) before the work goes wrong, instead of finding out from a wrong answer.
 *
 * @param level   how it is
 * @param reasons what the level is based on, one line each, worst first; empty when it is fine
 */
public record ConversationHealth(Level level, List<String> reasons) {

    /** How it is. */
    public enum Level {
        /** Nothing worrying. */
        OK,
        /** Something went wrong lately: worth watching. */
        WATCH,
        /** Several things went wrong, or the context is nearly full: a reset is advised. */
        DRIFTING
    }

    /** The latest messages that count. */
    static final int RECENT = 40;
    /** About how many characters a token is in the messages. */
    private static final double CHARS_PER_TOKEN = 3.0;

    public ConversationHealth {
        reasons = List.copyOf(reasons);
    }

    /**
     * The health of what the model really works from: the messages the summary does not cover. After a reset the older ones are in the
     * summary and what went wrong in them no longer counts (the transcript on screen is the same, the model's context is not).
     */
    public static ConversationHealth of(List<ChatMessage> messages, ConversationSummary summary, int contextTokens) {
        int from = summary.isEmpty() ? 0 : Math.min(Math.max(summary.covered(), 0), messages.size());
        return of(messages.subList(from, messages.size()), summary.text().length(), contextTokens);
    }

    /**
     * @param messages      the conversation's messages
     * @param summaryChars  the characters of the summary that stands for the older messages
     * @param contextTokens the model server's context size
     */
    public static ConversationHealth of(List<ChatMessage> messages, int summaryChars, int contextTokens) {
        List<ChatMessage> recent = messages.subList(Math.max(0, messages.size() - RECENT), messages.size());
        int ended = 0;
        int unnamed = 0;
        int stoppedJobs = 0;
        int failedCalls = 0;
        for (ChatMessage message : recent) {
            if (message instanceof AiMessage ai && !ai.hasToolExecutionRequests() && ai.text() != null) {
                if (ai.text().startsWith(AgentGraph.NO_CHANGES_AFTER_JOB) || ai.text().equals(AgentGraph.TOO_MANY_ROUNDS)
                        || ai.text().equals(ContextFittingChatModel.EMPTY_REPLY)) ended++;
                if (ai.text().contains(NameCheck.MARK)) unnamed++;
            }
            if (message instanceof ToolExecutionResultMessage result) {
                if (CodingGraph.START_TOOL.equals(result.toolName())) {
                    String first = result.text().lines().findFirst().orElse("");
                    if (first.startsWith("Stopped") || first.startsWith("Still failing") || first.startsWith("Denied")) stoppedJobs++;
                } else if (Boolean.TRUE.equals(result.isError())) {
                    failedCalls++;
                }
            }
        }
        long chars = summaryChars;
        for (ChatMessage message : messages) chars += length(message);
        int percent = contextTokens <= 0 ? 0 : (int) Math.min(999, Math.round(chars / CHARS_PER_TOKEN / contextTokens * 100));

        List<String> reasons = new ArrayList<>();
        int score = 0;
        if (unnamed > 0) {
            reasons.add(unnamed + " answer" + (unnamed == 1 ? "" : "s") + " named things that are not in the project");
            score += 2 * unnamed;
        }
        if (stoppedJobs > 0) {
            reasons.add(stoppedJobs + " coding job" + (stoppedJobs == 1 ? "" : "s") + " stopped without finishing");
            score += stoppedJobs;
        }
        if (ended > 0) {
            reasons.add(ended + " turn" + (ended == 1 ? "" : "s") + " ended without an answer (a limit, an empty reply or a refused edit)");
            score += ended;
        }
        if (failedCalls >= 5) {
            reasons.add(failedCalls + " tool calls failed lately");
            score += 1;
        }
        if (percent >= 100) {
            reasons.add("the conversation is " + percent + "% of the model's context (older parts are summarized)");
            score += 1;
        }
        Level level = score >= 3 ? Level.DRIFTING : score >= 1 ? Level.WATCH : Level.OK;
        return new ConversationHealth(level, reasons);
    }

    private static int length(ChatMessage message) {
        if (message instanceof UserMessage user) return user.hasSingleText() ? user.singleText().length() : String.valueOf(user.contents()).length();
        if (message instanceof AiMessage ai) return (ai.text() == null ? 0 : ai.text().length()) + ai.toolExecutionRequests().stream()
                .mapToInt(c -> c.arguments() == null ? 0 : c.arguments().length()).sum();
        if (message instanceof ToolExecutionResultMessage result) return result.text().length();
        return 0;
    }
}
