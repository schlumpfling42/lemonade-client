package net.rka.ai.agent;

import net.rka.ai.tools.FactBook;

import java.util.List;

/**
 * The task the user is working on, kept as a fact of the conversation ("Current task: ..."), so a follow-up like "fix the buttons" or
 * "implement the changes" means something even many turns after the request that said what it is about. It is set from the last coding
 * request that says what to do by itself, stays until the next one, appears in the model's facts and in the Memory window (where the
 * user can read, correct or delete it), and is not paraphrased by anything: it is the user's own words.
 */
final class Objective {

    static final String PREFIX = "Current task: ";
    /** The longest task kept: a request is a few sentences. */
    static final int MAX_CHARS = 600;

    private Objective() {
    }

    /** The task in the facts, or "". */
    static String of(List<FactBook.Fact> facts) {
        for (FactBook.Fact fact : facts) {
            if (fact.text().startsWith(PREFIX)) return fact.text().substring(PREFIX.length());
        }
        return "";
    }

    /** The facts with the task replaced by {@code task} (the other facts are kept as they are). */
    static List<FactBook.Fact> with(List<FactBook.Fact> facts, String task) {
        FactBook book = new FactBook(facts.stream().filter(f -> !f.text().startsWith(PREFIX)).toList());
        String one = task.replaceAll("\\s+", " ").strip();
        book.add(PREFIX + (one.length() > MAX_CHARS ? one.substring(0, MAX_CHARS) + "…" : one));
        return book.list();
    }
}
