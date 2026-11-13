package net.rka.ai.agent;

import net.rka.ai.agent.CodingPlan.Action;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading the model's plan and showing it to the user. */
class CodingPlanTest {

    private static final String PLAN = """
            {"summary": "Adds stop()",
             "setup": [{"command": "npm install", "workingDirectory": "web"}],
             "changes": [
               {"file": "src/App.java", "section": "App", "action": "add", "intent": "a stop() method"},
               {"file": "src/App.java", "section": "App.run()", "action": "replace", "intent": "check the flag"},
               {"file": "src/New.java", "action": "create", "intent": "a helper"}],
             "verify": [{"command": "gradle build"}]}""";

    @Test
    void readsAPlanFromTheAnswerWithFenceAndProse() {
        CodingPlan plan = CodingPlan.parse("Here is the plan:\n```json\n" + PLAN + "\n```\nHope it helps.");

        assertEquals("Adds stop()", plan.summary());
        assertEquals(3, plan.changes().size());
        assertEquals(new CodingPlan.Change("src/App.java", "App", Action.ADD, "a stop() method"), plan.changes().get(0));
        assertEquals("", plan.changes().get(2).section()); // a new file has no section
        assertEquals(List.of(new CodingPlan.Command("npm install", "web")), plan.setup());
        assertEquals(List.of(new CodingPlan.Command("gradle build", "")), plan.verify());
        assertEquals(List.of("src/App.java", "src/New.java"), plan.changedFiles()); // each file once, in order
    }

    @Test
    void usualSynonymsOfAnActionAreUnderstood() {
        assertEquals(Action.REPLACE, Action.fromJson("modify"));
        assertEquals(Action.REPLACE, Action.fromJson(" Edit "));
        assertEquals(Action.ADD, Action.fromJson("insert"));
        assertEquals(Action.DELETE, Action.fromJson("remove"));
        assertEquals(Action.CREATE, Action.fromJson("new"));
    }

    @Test
    void anUnusablePlanSaysWhy() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> CodingPlan.parse("I would add a button."))
                .getMessage().contains("no JSON object"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CodingPlan.parse("{\"changes\":[{\"file\":\"A.java\",\"action\":\"polish\"}]}"))
                .getMessage().contains("action must be one of"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CodingPlan.parse("{\"changes\":[{\"file\":\"A.java\",\"section\":\"A\"}]}"))
                .getMessage().contains("needs an \"action\""));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CodingPlan.parse("{\"changes\":[{\"action\":\"replace\"}]}"))
                .getMessage().contains("needs a \"file\""));
    }

    @Test
    void missingListsAreEmptyAndExtraFieldsAreIgnored() {
        CodingPlan plan = CodingPlan.parse("{\"summary\":\"s\",\"notes\":\"x\","
                + "\"changes\":[{\"file\":\"A.java\",\"section\":\"A.x\",\"action\":\"delete\",\"why\":\"y\"}]}");
        assertEquals(List.of(), plan.setup());
        assertEquals(List.of(), plan.read());
        assertEquals(List.of(), plan.verify());
    }

    @Test
    void jsonRoundTripKeepsEverything() {
        CodingPlan plan = CodingPlan.parse(PLAN);
        assertEquals(plan, CodingPlan.parse(plan.toJson()));
    }

    @Test
    void theMarkdownListsChangesWithTheirMarks() {
        CodingPlan plan = CodingPlan.parse(PLAN);

        assertEquals("""
                Adds stop()

                Setup, before the changes:
                - `npm install` in `web`

                1. add `src/App.java` · `App`: a stop() method
                2. replace `src/App.java` · `App.run()`: check the flag
                3. create `src/New.java`: a helper

                Verify:
                - `gradle build`
                """, plan.toMarkdown());
        String marked = plan.toMarkdown(Set.of(0), Set.of(1));
        assertTrue(marked.contains("1. ✓ add `src/App.java`") && marked.contains("2. ✗ failed replace"), marked);
    }
}
