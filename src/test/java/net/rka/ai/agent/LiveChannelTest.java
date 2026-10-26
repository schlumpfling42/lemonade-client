package net.rka.ai.agent;

import net.rka.ai.agent.skills.CodingKinds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveChannelTest {

    private final List<String> events = new ArrayList<>();
    private final LiveChannel live = new LiveChannel();

    LiveChannelTest() {
        live.set(new Progress.ProgressListener() {
            @Override public void onProgress(Progress progress) { }
            @Override public void onPartialThinking(String delta) { events.add("thinking:" + delta); }
            @Override public void onPartialText(String delta) { events.add("text:" + delta); }
            @Override public void onPartialWork(String label, int characters) { events.add(label + ":" + characters); }
        });
    }

    @Test
    void theAgentsWordsStreamAsTheyAre() {
        live.enter("agent");
        live.thinking("hmm");
        live.text("Hello");
        assertEquals(List.of("thinking:hmm", "text:Hello"), events);
    }

    @Test
    void thePlanStreamsAsProgressWithItsThinking() {
        // As Agent sets it for the coding skill: the plan and review kinds' nodes, by tracing name
        live.skillWork(Map.of("skill/coding/plan", LiveChannel.hiddenWorkOfKind(CodingKinds.PLAN),
                "skill/coding/review", LiveChannel.hiddenWorkOfKind(CodingKinds.REVIEW)));
        live.enter("skill/coding/plan");
        live.thinking("order the steps");
        live.text("{\"summary\":");
        live.text("\"x\"}");
        assertEquals(List.of("thinking:order the steps", "Writing the plan:11", "Writing the plan:15"), events);

        live.enter("skill/coding/review"); // the count starts again for the next step
        live.text("{}");
        assertEquals("Reviewing the plan:2", events.getLast());
    }

    @Test
    void theMemoryStepShowsOnlyProgress() {
        live.enter("memory");
        live.thinking("what to keep");
        live.text("Summary: ...");
        assertEquals(List.of("Updating memory:12"), events);
    }
}
