package net.rka.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;

import java.util.stream.Collectors;

/**
 * Tools for the conversation's long-term facts ({@link FactBook}): remember, forget, list.
 *
 * <p>How it works: each method gets the turn's FactBook from the hidden {@link InvocationParameters}
 * argument. remember and forget change what the conversation will remember from now on, so they're
 * marked {@link WritesFiles}: after web content entered the turn they need your approval, which
 * stops a web page from planting facts unnoticed. listFacts only reads.
 *
 * <p>Not implemented yet: editing a fact in place (today: forget + remember).
 */
public class MemoryTools {

    private static final int MAX_FACT_CHARS = 500; // facts are short statements, not documents

    @WritesFiles
    @Tool("Saves a fact to this conversation's long-term memory. Saved facts are shown to you on every "
            + "later turn of this conversation and are never summarized away. Use it for durable details "
            + "the user wants kept (names, preferences, decisions, project details), not for chit-chat.")
    public String remember(@P("the fact, as one short self-contained statement") String fact,
                           InvocationParameters parameters) {
        if (fact == null || fact.isBlank()) throw new IllegalArgumentException("The fact is empty");
        if (fact.length() > MAX_FACT_CHARS) {
            throw new IllegalArgumentException("Facts are limited to " + MAX_FACT_CHARS + " characters; shorten it");
        }
        FactBook.Fact saved = FactBook.of(parameters).add(fact); // deduplicated by text
        return "Remembered as [" + saved.id() + "]: " + saved.text();
    }

    @WritesFiles
    @Tool("Removes a fact from this conversation's long-term memory by its id, e.g. f3")
    public String forget(@P("the fact's id, as shown in brackets, e.g. f3") String id,
                         InvocationParameters parameters) {
        if (!FactBook.of(parameters).remove(id)) throw new IllegalArgumentException("No fact with id " + id);
        return "Forgot " + id;
    }

    @Tool("Lists the facts saved in this conversation's long-term memory, with their ids")
    public String listFacts(InvocationParameters parameters) {
        var facts = FactBook.of(parameters).list();
        if (facts.isEmpty()) return "No facts saved in this conversation.";
        return facts.stream().map(f -> "[" + f.id() + "] " + f.text()).collect(Collectors.joining("\n"));
    }
}
