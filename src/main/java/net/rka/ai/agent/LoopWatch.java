package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.bsc.langgraph4j.state.AgentState;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A check for any graph that does not depend on what the nodes do: a node that gives the same result {@link #LIMIT} times in a
 * row is going round in circles (the model calling the same tool with the same arguments, a repair that changes nothing, a plan
 * that comes back unchanged), so the run is stopped with a message instead of going on until the step limit.
 * <p>
 * The graph's own state keeps the count ({@link #KEY}: for each node the fingerprint of its last result and how many times in a
 * row it came), so the check is one more update of the state, not a side channel. The step of the node is applied around every
 * node by {@link NodeTracing#wrap}. Results with ids or times in them never compare equal, so the check can miss a loop but
 * not stop a run that makes progress; a node without updates is not counted.
 */
final class LoopWatch {

    /** The state key: a map from a node to its last result's fingerprint and the number of times in a row it came. */
    static final String KEY = "loopWatch";
    /** How many equal results in a row stop the run. */
    static final int LIMIT = 4;

    /**
     * The plain agent and its tools step: their loop ends by itself after {@link AgentGraph#MAX_AGENT_ROUNDS} rounds with an answer
     * ("what is done and what is left"), which is better than stopping with an error.
     */
    private static final java.util.Set<String> ENDS_BY_ITSELF = java.util.Set.of("agent", "tools");

    private LoopWatch() {
    }

    /** The run is going round in circles. */
    static final class LoopDetectedException extends RuntimeException {
        LoopDetectedException(String message) {
            super(message);
        }
    }

    /**
     * @param node    the node that produced {@code updates}
     * @param state   the state it ran on (holds the earlier counts)
     * @param updates its result; null or empty stays as it is
     * @return the updates with the new counts added
     * @throws LoopDetectedException when this is the {@link #LIMIT}th equal result of the node in a row
     */
    static Map<String, Object> check(String node, AgentState state, Map<String, Object> updates) {
        if (updates == null || updates.isEmpty() || ENDS_BY_ITSELF.contains(node)) return updates;
        // Only empty lists: nothing was added to the state, so there is no result to compare. The approval step answers with an empty
        // list of messages whenever every call was approved, and four approved commands in a turn stopped it as a "loop".
        if (updates.entrySet().stream().allMatch(e -> e.getKey().equals(KEY) || e.getValue() == null
                || e.getValue() instanceof Collection<?> items && items.isEmpty())) return updates;
        String fingerprint = fingerprint(updates);
        Map<String, String> watch = state.<Map<String, String>>value(KEY).orElseGet(Map::of);
        String before = watch.get(node);
        int count = 1;
        if (before != null && before.startsWith(fingerprint + "|")) count = Integer.parseInt(before.substring(fingerprint.length() + 1)) + 1;
        if (count >= LIMIT) {
            throw new LoopDetectedException("Stopped: the step '" + node + "' gave the same result " + count + " times in a row, so the work is going"
                    + " round in circles. Say what should be different (or start with a smaller request).");
        }
        Map<String, String> next = new HashMap<>(watch);
        next.put(node, fingerprint + "|" + count);
        Map<String, Object> result = new HashMap<>(updates);
        result.put(KEY, next);
        return result;
    }

    /** What makes two results equal: their values, with the ids that messages carry left out. */
    static String fingerprint(Map<String, Object> updates) {
        StringBuilder text = new StringBuilder();
        new TreeMap<>(updates).forEach((key, value) -> {
            if (!key.equals(KEY)) text.append(key).append('=').append(normal(value)).append(';');
        });
        return text.length() + ":" + text.toString().hashCode();
    }

    private static String normal(Object value) {
        if (value instanceof AiMessage ai) {
            StringBuilder out = new StringBuilder("ai:").append(ai.text());
            for (ToolExecutionRequest call : ai.toolExecutionRequests()) out.append('[').append(call.name()).append(call.arguments()).append(']');
            return out.toString();
        }
        if (value instanceof ToolExecutionResultMessage result) return "tool:" + result.toolName() + ":" + result.text();
        if (value instanceof ChatMessage message) return message.type() + ":" + message;
        if (value instanceof Collection<?> items) {
            StringBuilder out = new StringBuilder("[");
            items.forEach(item -> out.append(normal(item)).append(','));
            return out.append(']').toString();
        }
        return String.valueOf(value);
    }
}
