package net.rka.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepeatGuardTest {

    private static final String LINE = "- src/main/java/net/rka/ai/agent/Agent.java: section streamToPause: no cancellation.\n";

    @Test
    void aLineRepeatedManyTimesIsALoopAndOneCopyIsKept() {
        String text = "Notes:\n- first point that matters for the change\n" + LINE.repeat(RepeatGuard.REPEATS + 3) + "- a line that is still being wri";

        assertEquals(Optional.of("Notes:\n- first point that matters for the change\n" + LINE.strip()), RepeatGuard.loop(text));
    }

    @Test
    void aBlockOfTwoOrThreeLinesRepeatedIsALoopToo() {
        String block = "- one: the first place where it is\n- two: the second place where it is\n";

        assertEquals(Optional.of("Start\n" + block.strip()), RepeatGuard.loop("Start\n" + block.repeat(RepeatGuard.REPEATS) + "- one"));
    }

    @Test
    void aLongThoughtThatGoesRoundInCirclesWithoutLineBreaksIsALoopToo() {
        String round = "So we need to change to rename.setOnAction(e -> rename(folder)); but folder is Path. "
                + "So we need to change to rename.setOnAction(e -> rename(folder)); but rename expects Summary. ";
        String start = "The user wants a rename entry in the menu of the conversation, so look at how the folder button is built first. ";

        Optional<String> trimmed = RepeatGuard.loop(start + round.repeat(RepeatGuard.TEXT_REPEATS + 2) + "So we need to ch");

        assertEquals(Optional.of((start + round).stripTrailing()), trimmed);
        assertEquals(Optional.empty(), RepeatGuard.loop(start + round + "And then something else entirely follows here, which is new text."));
        assertEquals(Optional.empty(), RepeatGuard.loop(start + round.repeat(2) + "So we need to ch"));
    }

    @Test
    void aWholeListOfLinesThatIsWrittenAgainAndAgainIsALoopToo() {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < 22; i++) list.append("**Section:** `method").append(i).append("(String, String)` - UI update on FX thread.\n");

        Optional<String> trimmed = RepeatGuard.loop("Notes:\n" + list.toString().repeat(RepeatGuard.TEXT_REPEATS) + "**Section:** `meth");

        assertEquals(Optional.of(("Notes:\n" + list).stripTrailing()), trimmed);
        assertEquals(Optional.empty(), RepeatGuard.loop("Notes:\n" + list.toString().repeat(2)));
    }

    @Test
    void textWithFewRepeatsShortTextAndDifferentLinesAreNotALoop() {
        assertEquals(Optional.empty(), RepeatGuard.loop("Start\n" + LINE.repeat(RepeatGuard.REPEATS - 2) + "x"));
        assertEquals(Optional.empty(), RepeatGuard.loop("a\na\na\na\na\na\na\n"));
        StringBuilder varied = new StringBuilder();
        for (int i = 0; i < 40; i++) varied.append("- a different line number ").append(i).append(" of the notes\n");
        assertEquals(Optional.empty(), RepeatGuard.loop(varied.toString()));
    }
}
