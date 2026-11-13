package net.rka.ai.agent;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import net.rka.ai.tools.FactBook;

import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds what the model knows of a conversation from the record, without its help: the summary that replaces all the messages so far is
 * made of the user's own words (their latest requests, whole, oldest first), the task ({@link Objective}) and the coding jobs and how they
 * ended ({@link JobLedger}). Nothing the model wrote about the work is in it. The user keeps the whole transcript on screen; only the model's
 * context starts clean. It is the same mechanism as the summary of older messages ({@link SummaryMemory}), made by the program instead of by
 * the model.
 */
public final class ContextReset {

    /** How many of the user's latest messages are kept. */
    static final int MAX_REQUESTS = 12;
    static final int MAX_REQUEST_CHARS = 500;

    private ContextReset() {
    }

    /**
     * @param history the conversation's messages as the window keeps them (with the system prompt at index 0), or without it
     * @param facts   the conversation's facts (the task is one of them)
     * @return the summary that stands for all of {@code history}
     */
    public static ConversationSummary summary(List<ChatMessage> history, List<FactBook.Fact> facts) {
        List<String> requests = new ArrayList<>();
        for (ChatMessage message : history) {
            if (!(message instanceof UserMessage user)) continue;
            String text = (user.hasSingleText() ? user.singleText() : String.valueOf(user.contents())).strip();
            if (!text.isBlank()) requests.add(text.length() > MAX_REQUEST_CHARS ? text.substring(0, MAX_REQUEST_CHARS) + "…" : text);
        }
        List<String> latest = requests.subList(Math.max(0, requests.size() - MAX_REQUESTS), requests.size());
        StringBuilder text = new StringBuilder("The context was rebuilt from the record, without what the assistant wrote: nothing below is a paraphrase.\n");
        String task = Objective.of(facts);
        if (!task.isBlank()) text.append("\nThe task the user is working on, in their words: ").append(task).append('\n');
        if (!latest.isEmpty()) {
            text.append("\nThe user's messages, oldest first:\n");
            for (int i = 0; i < latest.size(); i++) text.append(i + 1).append(". ").append(latest.get(i)).append('\n');
        }
        String jobs = JobLedger.note(history);
        if (!jobs.isBlank()) text.append('\n').append(jobs).append('\n');
        // The summary covers messages 1 .. covered-1 and the model sees the rest: the window's history starts with the system prompt (index 0),
        // so it covers all of it when covered is its size. Counting one more hid the next message the user wrote, and after a reset the
        // model answered without having been asked anything.
        boolean withSystem = !history.isEmpty() && history.get(0) instanceof dev.langchain4j.data.message.SystemMessage;
        return new ConversationSummary(text.toString().stripTrailing(), withSystem ? history.size() : 1 + history.size());
    }
}
