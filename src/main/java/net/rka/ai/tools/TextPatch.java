package net.rka.ai.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A partial change to a text, written like a patch: hunks of lines that start with a blank (a line that stays, the
 * <i>context</i>), {@code -} (a line that goes) or {@code +} (a line that is added); a line {@code @@} starts a new hunk. A hunk is
 * found by its context and removed lines, which must be in the text exactly once, so the change lands where it says whatever has been
 * added or moved above it since the model last looked (a line number would be wrong by then). Indentation does not have to match: the
 * lines are compared without it, and the added lines are indented like the lines they sit between.
 * <p>
 * A hunk that fits nowhere, or in several places, changes nothing and says why: the line that does not match, or where the lines
 * are, so more context can be added.
 */
public final class TextPatch {

    private TextPatch() {
    }

    /**
     * @param lines   the text's lines with the hunks applied
     * @param added   how many lines were added
     * @param removed how many lines were removed
     * @param hunks   how many hunks the patch had
     */
    public record Result(List<String> lines, int added, int removed, int hunks) {
    }

    private record Line(char sign, String text) {
    }

    private record Hunk(List<Line> lines) {
        List<String> old() {
            return lines.stream().filter(l -> l.sign() != '+').map(Line::text).toList();
        }
    }

    /**
     * @param lines     the lines of the text (or of the part of it the patch is for)
     * @param firstLine the number of the first of them in its file, for the messages
     * @param patch     the hunks
     * @throws IllegalArgumentException when the patch is not understood or does not fit, or changes nothing
     */
    public static Result apply(List<String> lines, int firstLine, String patch) {
        List<Hunk> hunks = parse(patch);
        int[] at = new int[hunks.size()];
        for (int h = 0; h < hunks.size(); h++) at[h] = locate(hunks.get(h), h + 1, lines, firstLine);
        for (int a = 0; a < hunks.size(); a++) {
            for (int b = a + 1; b < hunks.size(); b++) {
                int endA = at[a] + hunks.get(a).old().size();
                int endB = at[b] + hunks.get(b).old().size();
                if (at[a] < endB && at[b] < endA) {
                    throw new IllegalArgumentException("Hunks " + (a + 1) + " and " + (b + 1) + " cover the same lines (around line "
                            + (firstLine + Math.min(at[a], at[b])) + "): make it one hunk");
                }
            }
        }
        // from the last hunk to the first, so the places of the others stay right
        List<String> result = new ArrayList<>(lines);
        List<Integer> order = new ArrayList<>(java.util.stream.IntStream.range(0, hunks.size()).boxed().toList());
        order.sort(Comparator.comparingInt((Integer h) -> at[h]).reversed());
        int added = 0;
        int removed = 0;
        for (int h : order) {
            Hunk hunk = hunks.get(h);
            int position = at[h];
            int delta = indentOf(lines.get(position)) - indentOf(hunk.old().getFirst());
            List<String> replacement = new ArrayList<>();
            int old = 0;
            for (Line line : hunk.lines()) {
                switch (line.sign()) {
                    case ' ' -> replacement.add(lines.get(position + old++)); // the file's own line, as it is written
                    case '-' -> {
                        old++;
                        removed++;
                    }
                    default -> {
                        added++;
                        replacement.add(line.text().isBlank() ? "" : " ".repeat(Math.max(0, indentOf(line.text()) + delta)) + line.text().strip());
                    }
                }
            }
            for (int k = hunk.old().size() - 1; k >= 0; k--) result.remove(position + k);
            result.addAll(position, replacement);
        }
        if (result.equals(lines)) throw new IllegalArgumentException("The patch changes nothing");
        return new Result(result, added, removed, hunks.size());
    }

    // ---- reading the patch ----

    private static List<Hunk> parse(String patch) {
        if (patch == null || patch.isBlank()) throw new IllegalArgumentException("The patch is empty");
        List<Hunk> hunks = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        boolean started = false;
        int number = 0;
        for (String raw : patch.split("\\R", -1)) {
            number++;
            if (raw.startsWith("@@")) {
                if (!current.isEmpty()) hunks.add(new Hunk(trimmed(current)));
                current = new ArrayList<>();
                started = true;
                continue;
            }
            if (!started && (raw.startsWith("--- ") || raw.startsWith("+++ ") || raw.startsWith("diff ") || raw.startsWith("index "))) continue;
            if (raw.isEmpty()) {
                if (!current.isEmpty()) current.add(new Line(' ', "")); // a blank context line
                continue;
            }
            char sign = raw.charAt(0);
            if (sign != ' ' && sign != '-' && sign != '+') {
                // No sign: a line that stays, written without its blank. It must still match the file exactly once, so a wrong guess fails safely
                started = true;
                current.add(new Line(' ', raw));
                continue;
            }
            started = true;
            current.add(new Line(sign, raw.substring(1)));
        }
        if (!current.isEmpty()) hunks.add(new Hunk(trimmed(current)));
        if (hunks.isEmpty()) throw new IllegalArgumentException("The patch has no lines");
        return hunks;
    }

    /** The hunk without blank context lines at its end (the patch's last line break). */
    private static List<Line> trimmed(List<Line> lines) {
        List<Line> kept = new ArrayList<>(lines);
        while (!kept.isEmpty() && kept.getLast().sign() == ' ' && kept.getLast().text().isEmpty()) kept.removeLast();
        return kept;
    }

    // ---- finding the place ----

    /** The index in the lines where the hunk's context and removed lines are, exactly once. */
    private static int locate(Hunk hunk, int number, List<String> lines, int firstLine) {
        List<String> old = hunk.old();
        if (old.isEmpty()) {
            throw new IllegalArgumentException("Hunk " + number + " has only added lines: add the line before or after it as context (a line that starts with a blank)");
        }
        List<Integer> fits = new ArrayList<>();
        int best = -1;
        int bestLength = -1;
        for (int p = 0; p + old.size() <= lines.size(); p++) {
            int matched = 0;
            while (matched < old.size() && lines.get(p + matched).strip().equals(old.get(matched).strip())) matched++;
            if (matched == old.size()) fits.add(p);
            if (matched > bestLength) {
                bestLength = matched;
                best = p;
            }
        }
        if (fits.size() == 1) return fits.getFirst();
        if (fits.size() > 1) {
            throw new IllegalArgumentException("Hunk " + number + " fits at lines " + fits.stream().map(p -> String.valueOf(firstLine + p)).toList()
                    + ": add a line above or below it that only one of them has");
        }
        String where = best < 0 || bestLength == 0 ? "its first line `" + old.getFirst().strip() + "` is not in the text"
                : "line " + (bestLength + 1) + " of it (`" + old.get(bestLength).strip() + "`) is not what is at line " + (firstLine + best + bestLength)
                + " (`" + (best + bestLength < lines.size() ? lines.get(best + bestLength).strip() : "the end") + "`)";
        throw new IllegalArgumentException("Hunk " + number + " does not fit: " + where + ". Read it again and copy the lines that stay");
    }

    private static int indentOf(String line) {
        int width = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') width++;
            else if (c == '\t') width += 4;
            else break;
        }
        return width;
    }
}
