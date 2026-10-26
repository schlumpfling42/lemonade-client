package net.rka.ai.agent.skills;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Rendering {@code {{var}}} templates and listing what they refer to. */
class TemplatesTest {

    @Test
    void rendersVariablesFieldsAndInputs() {
        Map<String, Object> vars = Map.of(
                "notes", "- fixed a bug",
                "answer", Map.of("choice", "revise", "comment", "shorter"),
                "input", Map.of("since", "v1.2"),
                "count", 3,
                "items", List.of("a", "b"));
        assertEquals("Since v1.2:\n- fixed a bug", Templates.render("Since {{input.since}}:\n{{notes}}", vars));
        assertEquals("revise (shorter)", Templates.render("{{answer.choice}} ({{ answer.comment }})", vars));
        // non-text values are inserted as JSON
        assertEquals("3 [\"a\",\"b\"]", Templates.render("{{count}} {{items}}", vars));
        assertEquals("{\"since\":\"v1.2\"}", Templates.render("{{input}}", vars));
    }

    @Test
    void missingIsEmpty() {
        Map<String, Object> vars = Map.of("answer", Map.of("choice", "accept"), "text", "plain");
        assertEquals("[][][]", Templates.render("[{{nothing}}][{{answer.comment}}][{{text.field}}]", vars));
        // no expression language: anything that isn't a variable path stays as written
        assertEquals("{{ a + b }} {x}", Templates.render("{{ a + b }} {x}", vars));
        assertEquals("", Templates.render(null, vars));
    }

    @Test
    void referencesAreListed() {
        assertEquals(Set.of("input.since", "notes", "answer.choice"),
                Templates.references("Since {{input.since}}: {{notes}} {{ answer.choice }} {{notes}} {{not valid}}"));
        assertEquals(Set.of(), Templates.references("no variables"));
        assertEquals(Set.of(), Templates.references(null));
    }
}
