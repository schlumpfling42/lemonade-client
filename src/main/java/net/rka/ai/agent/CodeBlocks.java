package net.rka.ai.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Takes the code out of a model's answer. The edit and fix steps ask for one code block with the new text of a
 * section; models add a sentence before it, forget the fence, or put Markdown (with fences of its own) inside it.
 * <ul>
 *   <li>With a fence: the lines between the first line that starts with {@code ```} and the <em>last</em> line that is
 *       only {@code ```}, so a section that contains fenced code (a Markdown file) comes out whole.</li>
 *   <li>Without a fence: nothing. An answer that is not in a block may be an apology or a question, and what is
 *       taken for code is written into a file; the model is told once to answer in a code block.</li>
 * </ul>
 * Blank lines around the code are dropped; an empty answer gives nothing.
 */
final class CodeBlocks {

    private CodeBlocks() {
    }

    static Optional<String> extract(String answer) {
        if (answer == null || answer.isBlank()) return Optional.empty();
        List<String> lines = answer.replace("\r\n", "\n").lines().toList();
        int open = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).stripLeading().startsWith("```")) {
                open = i;
                break;
            }
        }
        if (open < 0) return Optional.empty();
        int close = -1;
        for (int i = lines.size() - 1; i > open; i--) {
            if (lines.get(i).strip().equals("```")) {
                close = i;
                break;
            }
        }
        // No closing fence (the answer was cut or the model forgot it): everything after the opening one
        return text(lines.subList(open + 1, close < 0 ? lines.size() : close));
    }

    /**
     * All fenced blocks of the answer, in order (the answer to several changes at once): a block is the lines from one
     * line that starts with {@code ```} to the next line that is only {@code ```}; one the answer left open takes the
     * rest. Blocks without text are left out.
     */
    static List<String> all(String answer) {
        List<String> blocks = new ArrayList<>();
        if (answer == null || answer.isBlank()) return blocks;
        List<String> lines = answer.replace("\r\n", "\n").lines().toList();
        int open = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (open < 0 && line.stripLeading().startsWith("```")) {
                open = i;
            } else if (open >= 0 && line.strip().equals("```")) {
                text(lines.subList(open + 1, i)).ifPresent(blocks::add);
                open = -1;
            }
        }
        if (open >= 0) text(lines.subList(open + 1, lines.size())).ifPresent(blocks::add);
        return blocks;
    }

    private static Optional<String> text(List<String> lines) {
        int from = 0;
        int to = lines.size();
        while (from < to && lines.get(from).isBlank()) from++;
        while (to > from && lines.get(to - 1).isBlank()) to--;
        return from == to ? Optional.empty() : Optional.of(String.join("\n", lines.subList(from, to)));
    }
}
