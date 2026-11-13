package net.rka.ai.tools.outline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sections of a Markdown file: the text before the first heading ({@code <top>}), then one section per
 * heading with the lines up to the next heading of any level, so replacing a section never removes the
 * sections under it. The id is the heading's path, {@code Parent > Child}. Headings inside fenced code
 * blocks don't count.
 */
final class MarkdownOutliner implements Outliner {

    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");

    @Override
    public boolean structured() {
        return true;
    }

    @Override
    public List<Section> sections(String text, List<String> lines) {
        record Heading(int line, int level, String title) {
        }
        List<Heading> headings = new ArrayList<>();
        String fence = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.stripLeading();
            if (fence != null) {
                if (stripped.startsWith(fence)) fence = null;
                continue;
            }
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                fence = stripped.substring(0, 3);
                continue;
            }
            Matcher m = HEADING.matcher(line);
            if (m.matches()) headings.add(new Heading(i + 1, m.group(1).length(), m.group(2)));
        }
        List<Section> sections = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int firstHeading = headings.isEmpty() ? lines.size() + 1 : headings.get(0).line();
        int top = firstNonBlank(lines, 1, firstHeading - 1);
        if (top > 0) {
            sections.add(new Section(unique(ids, "<top>"), "preamble", top, lastNonBlank(lines, top, firstHeading - 1), null, false));
        }
        List<Heading> path = new ArrayList<>(); // the headings above the current one
        for (int h = 0; h < headings.size(); h++) {
            Heading heading = headings.get(h);
            while (!path.isEmpty() && path.getLast().level() >= heading.level()) path.removeLast();
            String parentId = path.isEmpty() ? null : String.join(" > ", path.stream().map(Heading::title).toList());
            path.add(heading);
            int next = h + 1 < headings.size() ? headings.get(h + 1).line() : lines.size() + 1;
            int end = Math.max(heading.line(), lastNonBlank(lines, heading.line(), next - 1));
            String id = String.join(" > ", path.stream().map(Heading::title).toList());
            sections.add(new Section(unique(ids, id), "heading", heading.line(), end, parentId, false));
        }
        return sections;
    }

    private static String unique(Set<String> ids, String id) {
        String candidate = id;
        for (int n = 2; !ids.add(candidate); n++) candidate = id + "#" + n;
        return candidate;
    }

    /** The first line in {@code from}..{@code to} (1-based) that isn't blank, or 0. */
    static int firstNonBlank(List<String> lines, int from, int to) {
        for (int i = from; i <= to && i <= lines.size(); i++) if (!lines.get(i - 1).isBlank()) return i;
        return 0;
    }

    /** The last line in {@code from}..{@code to} (1-based) that isn't blank, or {@code from}. */
    static int lastNonBlank(List<String> lines, int from, int to) {
        for (int i = Math.min(to, lines.size()); i >= from; i--) if (!lines.get(i - 1).isBlank()) return i;
        return from;
    }
}
