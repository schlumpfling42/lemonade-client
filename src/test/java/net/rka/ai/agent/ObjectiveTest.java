package net.rka.ai.agent;

import net.rka.ai.tools.FactBook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObjectiveTest {

    @Test
    void theTaskIsAFactThatTheNextTaskReplacesAndTheOtherFactsStay() {
        List<FactBook.Fact> facts = List.of(new FactBook.Fact("f1", "The user prefers tabs"));
        assertEquals("", Objective.of(facts));

        List<FactBook.Fact> first = Objective.with(facts, "make the  conversation menu\nlook like the folder menu");
        assertEquals("make the conversation menu look like the folder menu", Objective.of(first));
        List<FactBook.Fact> second = Objective.with(first, "add a rename entry");

        assertEquals(2, second.size());
        assertEquals("The user prefers tabs", second.get(0).text());
        assertEquals("add a rename entry", Objective.of(second));
        assertEquals(Objective.MAX_CHARS + 1, Objective.of(Objective.with(List.of(), "x".repeat(2000))).length());
    }
}
