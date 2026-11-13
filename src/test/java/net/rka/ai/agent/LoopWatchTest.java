package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopWatchTest {

    /** Runs the node's results through the watch one after the other, the way the graph does: each sees the state the last one left. */
    private static Map<String, Object> run(Map<String, Object> state, String node, Map<String, Object> result) {
        Map<String, Object> updates = LoopWatch.check(node, new AgentState(state), result);
        Map<String, Object> next = new HashMap<>(state);
        next.putAll(updates);
        return next;
    }

    private static AiMessage call(String id) {
        return AiMessage.from(List.of(ToolExecutionRequest.builder().id(id).name("listFiles").arguments("{\"path\":\"src\"}").build()));
    }

    @Test
    void theSameResultOverAndOverStopsTheRunAtTheLimitEvenWhenTheCallIdsDiffer() {
        Map<String, Object> state = Map.of();
        for (int i = 1; i < LoopWatch.LIMIT; i++) state = run(state, "fix", Map.of("messages", call("id" + i)));

        Map<String, Object> last = state;
        LoopWatch.LoopDetectedException stop = assertThrows(LoopWatch.LoopDetectedException.class,
                () -> run(last, "fix", Map.of("messages", call("another"))));

        assertTrue(stop.getMessage().contains("'fix' gave the same result " + LoopWatch.LIMIT + " times in a row"), stop.getMessage());
    }

    @Test
    void aResultThatChangesStartsTheCountAgainAndEmptyResultsAreNotCounted() {
        Map<String, Object> state = Map.of();
        for (int i = 0; i < 20; i++) state = run(state, "edit", Map.of("codingLedger", "entry " + i));
        for (int i = 0; i < 20; i++) state = run(state, "memory", Map.of());
        for (int i = 1; i < LoopWatch.LIMIT; i++) state = run(state, "plan", Map.of("codingVerifyOutput", "error: x"));
        state = run(state, "plan", Map.of("codingVerifyOutput", "error: y")); // changed: the count is one again
        for (int i = 2; i < LoopWatch.LIMIT; i++) state = run(state, "plan", Map.of("codingVerifyOutput", "error: y"));

        assertTrue(state.containsKey(LoopWatch.KEY));
    }

    @Test
    void theAgentAndItsToolsStepEndByThemselvesAndAreNotWatched() {
        Map<String, Object> state = Map.of();
        for (int i = 0; i < 3 * LoopWatch.LIMIT; i++) state = run(state, "agent", Map.of("messages", call("id" + i)));

        assertEquals(false, state.containsKey(LoopWatch.KEY));
    }

    @Test
    void eachNodeIsCountedOnItsOwnSoAlternatingNodesStillShowTheLoop() {
        Map<String, Object> state = Map.of();
        for (int i = 1; i < LoopWatch.LIMIT; i++) {
            state = run(state, "fix", Map.of("messages", call("a" + i)));
            state = run(state, "verify", Map.of("messages", "the same result"));
        }

        Map<String, Object> last = state;
        assertThrows(LoopWatch.LoopDetectedException.class, () -> run(last, "fix", Map.of("messages", call("z"))));
    }
}
