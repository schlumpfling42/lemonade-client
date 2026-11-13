package net.rka.ai.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Grooms the conversation as the model sees it, every time a request is built (so after each answer, for the next question): the
 * transcript the user reads is not touched, and the same messages always give the same groomed text, so the model server's cache stays
 * valid between the requests of a turn.
 * <ol>
 *   <li><b>The user's words stay</b>, always, in full: they say what is wanted.</li>
 *   <li><b>Notes of the program, not of the model, go</b>: "a coding job ran in this turn, so I'm not changing files myself" and the like
 *       are what the graph said to stop something; kept in the history, the model imitates them in the next turn.</li>
 *   <li><b>Older answers shrink.</b> The model's last {@link #KEEP_ANSWERS} answers stay as they are; older long ones keep their first
 *       lines: what the model wrote about the work (and sometimes guessed) is not what is true of it, the files and the jobs' reports
 *       are, and they can be read again.</li>
 *   <li>Then the results of earlier turns are shortened ({@link SummaryMemory#compactEarlierTurns}) and reads that were done again or made
 *       out of date leave ({@link StaleReads}).</li>
 * </ol>
 * The messages keep their places (nothing is removed), so summary memory, which counts messages, is not disturbed.
 */
public final class ContextGroom {

    /** How many of the model's last answers are kept whole. */
    static final int KEEP_ANSWERS = 2;
    /** An older answer longer than this is cut to {@link #KEPT_CHARS}. */
    static final int LONG_ANSWER = 600;
    static final int KEPT_CHARS = 300;

    private ContextGroom() {
    }

    /** The messages as the model should see them. */
    public static List<ChatMessage> apply(List<ChatMessage> messages) {
        return StaleReads.apply(SummaryMemory.compactEarlierTurns(shrinkOlderAnswers(withoutProgramNotes(messages))));
    }

    /** What the program itself said instead of the model (see {@link AgentGraph#NO_CHANGES_AFTER_JOB}). */
    private static boolean programNote(ChatMessage message) {
        if (!(message instanceof AiMessage ai) || ai.hasToolExecutionRequests() || ai.text() == null) return false;
        String text = ai.text();
        return text.startsWith(AgentGraph.NO_CHANGES_AFTER_JOB) || text.equals(AgentGraph.TOO_MANY_ROUNDS) || text.equals(ContextFittingChatModel.EMPTY_REPLY);
    }

    private static List<ChatMessage> withoutProgramNotes(List<ChatMessage> messages) {
        if (messages.stream().noneMatch(ContextGroom::programNote)) return messages;
        List<ChatMessage> out = new ArrayList<>(messages.size());
        for (ChatMessage message : messages) {
            // the place stays, the words go: a short marker that the turn ended there
            out.add(programNote(message) ? AiMessage.from("(this turn ended without an answer)") : message);
        }
        return out;
    }

    private static List<ChatMessage> shrinkOlderAnswers(List<ChatMessage> messages) {
        int currentTurn = -1;
        for (int i = messages.size() - 1; i >= 0 && currentTurn < 0; i--) {
            if (messages.get(i) instanceof UserMessage) currentTurn = i;
        }
        if (currentTurn <= 0) return messages;
        // the model's answers before the current turn, newest first: the last few stay whole
        List<Integer> answers = new ArrayList<>();
        for (int i = currentTurn - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage ai && !ai.hasToolExecutionRequests() && ai.text() != null && !ai.text().isBlank()) answers.add(i);
        }
        if (answers.size() <= KEEP_ANSWERS) return messages;
        List<ChatMessage> out = new ArrayList<>(messages);
        for (int k = KEEP_ANSWERS; k < answers.size(); k++) {
            int index = answers.get(k);
            String text = ((AiMessage) messages.get(index)).text();
            if (text.length() <= LONG_ANSWER) continue;
            out.set(index, AiMessage.from(text.substring(0, KEPT_CHARS).stripTrailing() + "\n[… " + (text.length() - KEPT_CHARS)
                    + " more characters of this earlier answer are left out; the user can still read them]"));
        }
        return out;
    }
}
