package net.rka.ai.agent;

import net.rka.ai.agent.skills.SkillFile;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The free route: the build is checked by the graph after the work, repaired once, and always reported. */
class FreeRouteCheckTest {

    private static SkillFile coding() throws Exception {
        try (InputStream in = FreeRouteCheckTest.class.getResourceAsStream("/net/rka/ai/agent/skills/coding.json")) {
            assertNotNull(in);
            return SkillFile.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void theWorkIsFollowedByACheckThatRepairsAtMostOnceAndEveryPathEndsInTheReport() throws Exception {
        SkillFile skill = coding();
        Map<String, SkillFile.NodeSpec> nodes = new java.util.HashMap<>();
        skill.nodes().forEach(n -> nodes.put(n.id(), n));

        assertEquals("check", nodes.get("work").next());
        assertEquals("workDone", nodes.get("check").on().get("passed"));
        assertEquals("workDone", nodes.get("check").on().get("none"));
        assertEquals("repair", nodes.get("check").on().get("failing"));
        assertEquals("recheck", nodes.get("repair").next());
        // the second check never goes back to the repair: one repair at most
        assertEquals(List.of("workDone"), nodes.get("recheck").on().values().stream().distinct().toList());
    }
}
