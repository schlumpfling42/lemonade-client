package net.rka.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamplingTest {

    @Test
    void topKTravelsWithTheOtherExtraFieldsOfARequest() {
        Map<String, Object> extra = Sampling.with(Map.of("chat_template_kwargs", Map.of("reasoning_effort", "low")));

        assertEquals(Map.of("reasoning_effort", "low"), extra.get("chat_template_kwargs"));
        assertTrue(Sampling.topK() <= 0 || extra.containsKey("top_k"), extra.toString());
    }

    @Test
    void aRequestWithAnEffortStillCarriesTopK() {
        var parameters = Effort.parameters("high");

        assertTrue(Sampling.topK() <= 0 || parameters.customParameters().containsKey("top_k"), parameters.customParameters().toString());
    }
}
