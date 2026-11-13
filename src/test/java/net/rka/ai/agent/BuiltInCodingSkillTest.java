package net.rka.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The built-in coding skill is a short line: plan, approve, edit, build, repair, report. The extra checks are optional kinds. */
class BuiltInCodingSkillTest {

    private static JsonNode skill() throws Exception {
        try (var in = BuiltInCodingSkillTest.class.getResourceAsStream("/net/rka/ai/agent/skills/coding.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    private static JsonNode node(JsonNode skill, String id) {
        for (JsonNode n : skill.get("nodes")) if (id.equals(n.get("id").asText())) return n;
        return null;
    }

    @Test
    void theLineHasNoPlanCheckIntentCheckOrDesignReview() throws Exception {
        List<String> kinds = new ArrayList<>();
        skill().get("nodes").forEach(n -> kinds.add(n.get("kind").asText()));
        assertFalse(kinds.contains("coding.planCheck"));
        assertFalse(kinds.contains("coding.intent"));
        assertFalse(kinds.contains("coding.designReview"));
    }

    @Test
    void aValidPlanGoesToTheUserAndAPassingBuildGoesToTheReport() throws Exception {
        assertEquals("planApproval", node(skill(), "plan").get("on").get("approval").asText());
        assertEquals("report", node(skill(), "verify").get("on").get("passed").asText());
        assertEquals("fix", node(skill(), "verify").get("on").get("fix").asText());
    }
}
