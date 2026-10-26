package net.rka.ai.tools;

import dev.langchain4j.invocation.InvocationParameters;

import java.util.ArrayList;
import java.util.List;

/**
 * A conversation's long-term facts: things the model was asked (or decided) to remember for the
 * rest of the conversation. Unlike the running summary, facts are never shortened or dropped, and
 * they are listed in the system prompt on every turn. They belong to one conversation only.
 *
 * <h2>How it works</h2>
 * The facts live in the graph state (key {@code facts}). Each node that runs tools makes a FactBook
 * from the state and hands it to the tools as the {@link #PARAMETER} invocation parameter (like
 * {@link WorkFolder}); {@link MemoryTools} add and remove facts in it, and the node returns the
 * book's contents as a state update. After the turn the agent reads them from the final state, and
 * the window saves them with the conversation. Ids are short ("f1", "f2", …) so the model can refer to them;
 * within a turn (or one Memory dialog session) a deleted id is never handed out again.
 * Methods are synchronized because tools could in principle run on another thread.
 *
 * <h2>Not implemented yet</h2>
 * <ul>
 *   <li>Facts shared between conversations (a global or per-project memory), if wanted later.</li>
 *   <li>Searching facts by meaning (embeddings) once there are many.</li>
 *   <li>A size limit with "least recently useful" eviction.</li>
 * </ul>
 */
public final class FactBook {

    /** Name of the {@link InvocationParameters} entry that holds the run's FactBook. */
    public static final String PARAMETER = "facts";

    /** One remembered fact. */
    public record Fact(String id, String text) {
    }

    private final List<Fact> facts = new ArrayList<>();
    // The highest id number handed out so far; only grows, so an id isn't reused while the book is open
    private int lastId;

    public FactBook(List<Fact> initial) {
        if (initial != null) facts.addAll(initial);
        for (Fact fact : facts) lastId = Math.max(lastId, number(fact.id()));
    }

    /** "f12" → 12; ids not in that form count as 0. */
    private static int number(String id) {
        try {
            return Integer.parseInt(id.substring(1));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The book for this tool call; a throwaway empty one when the caller didn't pass any (e.g. tests). */
    public static FactBook of(InvocationParameters parameters) {
        Object book = parameters == null ? null : parameters.get(PARAMETER);
        return book instanceof FactBook b ? b : new FactBook(List.of());
    }

    public synchronized List<Fact> list() {
        return List.copyOf(facts);
    }

    /** Adds a fact (or returns the existing one with the same text) under the next free id. */
    public synchronized Fact add(String text) {
        String clean = text.strip().replaceAll("\\s+", " "); // one line: facts are short statements
        for (Fact fact : facts) {
            if (fact.text().equalsIgnoreCase(clean)) return fact; // already known: don't duplicate
        }
        // Next id = one more than the highest number handed out, even if that fact was deleted since
        Fact fact = new Fact("f" + (++lastId), clean);
        facts.add(fact);
        return fact;
    }

    /** Removes a fact by id; returns whether it existed. */
    public synchronized boolean remove(String id) {
        return facts.removeIf(fact -> fact.id().equalsIgnoreCase(id.strip()));
    }
}
