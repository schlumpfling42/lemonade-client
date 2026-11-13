package net.rka.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Notices a model that has fallen into a loop and writes the same lines again and again (a local model can do that until its
 * output limit, minutes of nothing). The text is a loop when its last lines are one line, or a block of up to three lines,
 * repeated {@link #REPEATS} times in a row.
 */
final class RepeatGuard {

    /** How many times in a row a line or block must come before the text counts as a loop. */
    static final int REPEATS = 6;
    private static final int MAX_BLOCK = 3;

    private RepeatGuard() {
    }

    /**
     * @return the text without the repeats (one copy of the repeated lines is kept) when it ends in a loop, else empty
     */
    static Optional<String> loop(String text) {
        Optional<String> lines = lineLoop(text);
        return lines.isPresent() ? lines : periodicLoop(text);
    }

    /** The shortest piece that may repeat, in characters: a few words; and the longest: a list of twenty lines or so that is written again. */
    private static final int MIN_PERIOD = 24;
    private static final int MAX_PERIOD = 3000;
    /** How many times in a row a piece of text must come when it is not a line (a model thinking in one long paragraph). */
    static final int TEXT_REPEATS = 4;

    /**
     * A text that ends in the same piece of text four times in a row, whatever the line breaks (a long thought that goes round in
     * circles: "so we need to change to X; but Y is Z. So we need to change to X; but ..."). The piece is found by looking for the
     * last words again earlier in the text, so a check costs one pass.
     */
    private static Optional<String> periodicLoop(String text) {
        int length = text.length();
        if (length < MIN_PERIOD * TEXT_REPEATS) return Optional.empty();
        int probe = Math.min(MIN_PERIOD, length / TEXT_REPEATS);
        String tail = text.substring(length - probe);
        int from = length - probe - 1;
        while (true) {
            int earlier = text.lastIndexOf(tail, from);
            if (earlier < 0) return Optional.empty();
            int period = length - probe - earlier;
            if (period > MAX_PERIOD) return Optional.empty();
            if (period >= MIN_PERIOD && period * TEXT_REPEATS <= length) {
                int repeats = 1;
                while (length - (repeats + 1) * period >= 0 && text.regionMatches(length - (repeats + 1) * period, text, length - period, period)) repeats++;
                if (repeats >= TEXT_REPEATS) return Optional.of(endAtASentence(text.substring(0, length - (repeats - 1) * period), period));
            }
            from = earlier - 1;
            if (from < 0) return Optional.empty();
        }
    }

    /** The text cut after its last sentence or line when that is within the last {@code window} characters: no half sentence at the end. */
    private static String endAtASentence(String text, int window) {
        int end = -1;
        for (int i = text.length() - 1; i >= Math.max(0, text.length() - window); i--) {
            if (".;!?\n".indexOf(text.charAt(i)) >= 0) {
                end = i + 1;
                break;
            }
        }
        return (end < 0 ? text : text.substring(0, end)).stripTrailing();
    }

    private static Optional<String> lineLoop(String text) {
        if (text.length() < 60) return Optional.empty();
        List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
        // the last line may still be growing: it takes no part in the comparison
        List<String> done = lines.subList(0, lines.size() - 1);
        for (int block = 1; block <= MAX_BLOCK; block++) {
            int needed = block * REPEATS;
            if (done.size() < needed) continue;
            boolean repeats = true;
            for (int i = done.size() - needed; i < done.size() - block && repeats; i++) {
                repeats = done.get(i).strip().equals(done.get(i + block).strip()) && !done.get(i).isBlank();
            }
            if (!repeats) continue;
            int keep = done.size();
            while (keep - 2 * block >= 0 && sameBlock(done, keep - block, keep - 2 * block, block)) keep -= block;
            return Optional.of(String.join("\n", done.subList(0, keep)));
        }
        return Optional.empty();
    }

    private static boolean sameBlock(List<String> lines, int a, int b, int length) {
        for (int i = 0; i < length; i++) if (!lines.get(a + i).strip().equals(lines.get(b + i).strip())) return false;
        return true;
    }
}
