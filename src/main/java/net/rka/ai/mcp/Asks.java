package net.rka.ai.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The questions the application has put to the person and that wait for an answer: an approval of a command or a tool call, the
 * approval of a plan, a question of a skill or of the analysis, the review of a drafted skill. Each is registered while its dialog is
 * open, so the MCP server can list what waits and answer it instead of the person; whichever comes first counts (the window closes
 * the dialog when the answer comes from here).
 */
public final class Asks {

    /** What one waiting question looks like from outside. */
    public record Item(String id, String kind, ObjectNode details) {
    }

    /** Turns the arguments of an answer into the application's decision and acts on it; throws {@link IllegalArgumentException} for an answer that does not fit. */
    @FunctionalInterface
    public interface Answerer {
        String answer(JsonNode arguments);
    }

    private record Registered(Item item, Answerer answerer) {
    }

    private final Map<String, Registered> waiting = new LinkedHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    /** Puts a question on the list; returns its id. Call {@link #done} when the dialog is closed, however it was answered. */
    public String register(String kind, ObjectNode details, Answerer answerer) {
        String id = kind + "-" + counter.incrementAndGet();
        synchronized (this) {
            waiting.put(id, new Registered(new Item(id, kind, details), answerer));
        }
        return id;
    }

    public synchronized void done(String id) {
        waiting.remove(id);
    }

    /** The questions that wait, oldest first. */
    public synchronized List<Item> list() {
        List<Item> items = new ArrayList<>();
        waiting.values().forEach(r -> items.add(r.item()));
        return items;
    }

    public synchronized int count() {
        return waiting.size();
    }

    /** Answers a waiting question; the message says what was done. */
    public String answer(String id, JsonNode arguments) {
        Registered registered;
        synchronized (this) {
            registered = waiting.get(id);
        }
        if (registered == null) {
            throw new IllegalArgumentException("No question with the id " + id + " is waiting (it may have been answered already). Waiting: "
                    + list().stream().map(Item::id).toList());
        }
        return registered.answerer().answer(arguments);
    }
}
