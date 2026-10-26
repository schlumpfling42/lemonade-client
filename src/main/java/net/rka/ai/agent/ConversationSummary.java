package net.rka.ai.agent;

/**
 * The running summary of a conversation's older messages ("summary memory").
 *
 * @param text    the summary; empty until the conversation first grows past the budget
 * @param covered how many messages of the history the summary replaces, counting the system
 *                prompt at index 0: messages {@code 1 .. covered-1} are summarized, and the model
 *                sees {@code messages[covered ..]} in full. 1 means "nothing summarized yet".
 */
public record ConversationSummary(String text, int covered) {

    /** A conversation with nothing summarized yet. */
    public static final ConversationSummary EMPTY = new ConversationSummary("", 1);

    public ConversationSummary {
        text = text == null ? "" : text;
        covered = Math.max(1, covered); // index 0 is always the system prompt, never summarized
    }

    public boolean isEmpty() {
        return text.isBlank();
    }
}
