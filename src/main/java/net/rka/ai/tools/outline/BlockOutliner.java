package net.rka.ai.tools.outline;

import java.util.ArrayList;
import java.util.List;

/**
 * The sections of a file with no known structure: its runs of lines between blank lines, joined until a
 * section has at least {@value #MIN_LINES} lines, as {@code block 1}, {@code block 2}, ... with the first
 * line as the label. The ids number the blocks, so they change when a block is added or removed: the edit
 * result says so.
 */
final class BlockOutliner implements Outliner {

    static final int MIN_LINES = 20;

    @Override
    public boolean structured() {
        return false;
    }

    @Override
    public List<Section> sections(String text, List<String> lines) {
        List<Section> sections = new ArrayList<>();
        int i = 1;
        int n = lines.size();
        while (i <= n) {
            int start = MarkdownOutliner.firstNonBlank(lines, i, n);
            if (start == 0) break;
            int end = start;
            // take runs until the block is big enough: a run ends at a blank line, the next starts after the blank lines
            while (true) {
                int run = end;
                while (run + 1 <= n && !lines.get(run).isBlank()) run++;
                end = run;
                if (end - start + 1 >= MIN_LINES || end >= n) break;
                int next = MarkdownOutliner.firstNonBlank(lines, end + 1, n);
                if (next == 0) break;
                end = next;
            }
            end = MarkdownOutliner.lastNonBlank(lines, start, end);
            String first = lines.get(start - 1).strip();
            sections.add(new Section("block " + (sections.size() + 1), "block", start, end, null, false,
                    first.length() > 60 ? first.substring(0, 60) + "…" : first));
            i = end + 1;
        }
        return sections;
    }
}
