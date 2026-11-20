package net.rka.ai.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThoughtWindowTest {

    @Test
    void shortThinkingIsShownWhole() {
        assertEquals("The user says hello. I should greet them.", ThoughtWindow.latest("  The user says hello. I should greet them.\n", 600));
        assertEquals("", ThoughtWindow.latest(null, 600));
        assertEquals("", ThoughtWindow.latest("   ", 600));
    }

    @Test
    void longThinkingShowsTheNewestWholeSentencesAndSaysThatEarlierOnesScrolledOut() {
        String thinking = "First I read the request. Then I look at the tools. After that I think about the answer. Finally I write it down.";

        String window = ThoughtWindow.latest(thinking, 70);

        assertEquals("… After that I think about the answer. Finally I write it down.", window);
        assertTrue(window.length() <= 72, window);
    }

    @Test
    void theSentenceStillBeingWrittenIsAlwaysShownAndNoSentenceIsCutInTwo() {
        String thinking = "One short sentence. Another short sentence. The third is still being wri";

        String window = ThoughtWindow.latest(thinking, 50);

        assertTrue(window.endsWith("The third is still being wri"), window);
        assertFalse(window.contains("Another short sentence. The third") && window.length() > 52, window);
        assertTrue(window.startsWith("… "), window);
    }

    @Test
    void aSingleSentenceLongerThanTheBudgetIsShownFromAWordBoundaryNearItsEnd() {
        String sentence = "word ".repeat(100).strip();

        String window = ThoughtWindow.latest(sentence, 40);

        assertTrue(window.startsWith("… word"), window);
        assertTrue(window.length() <= 42, window);
        assertTrue(window.endsWith("word"), window);
    }

    @Test
    void linesAreSentencesToo() {
        String thinking = "Plan:\n- read the file\n- change the method\n- run the build";

        assertEquals("… - change the method - run the build", ThoughtWindow.latest(thinking, 40));
    }

    @Test
    void decimalsAndNamesWithDotsDoNotEndASentence() {
        String thinking = "An introduction sentence comes first. Use version 1.5 of Order.print() here.";

        assertEquals("… Use version 1.5 of Order.print() here.", ThoughtWindow.latest(thinking, 45));
    }
}
