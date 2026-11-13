package net.rka.ai.tools.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Changes a file's text by section: replaces a section (or deletes it), adds text before or after it, or adds a
 * new part to a class where parts of its kind belong.
 * <p>
 * How it works: the section's lines are looked up in the {@link Outline} the edit was checked against, the new
 * text is moved to the indentation the section has in the file (a local model often indents wrongly or not at
 * all, and the part that matters is the code), and the lines are put together again with the file's own line
 * breaks and its final line break kept. Nothing is parsed again here; {@code OutlineTools} makes the new outline
 * from the result.
 */
public final class SectionEdit {

    /** What to do with the section and the new text. */
    public enum Mode {
        /** The new text takes the section's place; empty text deletes the section. */
        REPLACE,
        /** The new text goes in front of the section. */
        BEFORE,
        /** The new text goes after the section. */
        AFTER,
        /**
         * The new text is a new part of a class (named as the section) and goes where parts of its kind belong: a
         * method below the last method, a field below the last field, a constructor below the last constructor,
         * always above the class's closing brace.
         */
        ADD,
        /**
         * The new text is statements for the start of a method's or constructor's body: right after the opening
         * brace (after an explicit {@code super(...)} / {@code this(...)} call of a constructor).
         */
        START,
        /**
         * The new text is statements for the end of a method's or constructor's body: right before the closing
         * brace (before a last {@code return} / {@code throw} line, which would make them unreachable).
         */
        END;

        public static Mode parse(String name) {
            if (name == null || name.isBlank()) return REPLACE;
            try {
                return valueOf(name.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("mode must be replace, before, after, add, start or end, not '" + name + "'");
            }
        }
    }

    private SectionEdit() {
    }

    /**
     * @param text     the file as it is now (the one {@code outline} was made from)
     * @param newText  the new lines, or empty to delete the section with {@link Mode#REPLACE}
     * @return the file's new text
     * @throws IllegalArgumentException when the mode doesn't fit the section
     */
    public static String apply(String text, Outline outline, Section section, Mode mode, String newText) {
        String lineBreak = text.contains("\r\n") ? "\r\n" : "\n";
        boolean endsWithBreak = text.endsWith("\n");
        List<String> lines = new ArrayList<>(text.lines().toList());
        int first = section.start() - 1; // index of the section's first line
        int last = section.end() - 1;
        List<String> added = trimBlankEnds(newText == null ? List.of() : newText.replace("\r\n", "\n").lines().toList());

        switch (mode) {
            case REPLACE -> {
                for (int i = last; i >= first; i--) lines.remove(i);
                if (!added.isEmpty()) {
                    lines.addAll(first, reindent(added, indentOf(text, first)));
                } else {
                    tidyAfterDelete(lines, first);
                }
            }
            case BEFORE -> {
                if (added.isEmpty()) throw new IllegalArgumentException("newText is empty: nothing to add");
                List<String> insert = new ArrayList<>(reindent(added, indentOf(text, first)));
                if (!tight(section, added)) insert.add("");
                lines.addAll(first, insert);
            }
            case AFTER -> {
                if (added.isEmpty()) throw new IllegalArgumentException("newText is empty: nothing to add");
                List<String> insert = new ArrayList<>();
                if (!tight(section, added)) insert.add("");
                insert.addAll(reindent(added, indentOf(text, first)));
                lines.addAll(last + 1, insert);
            }
            case START, END -> {
                if (added.isEmpty()) throw new IllegalArgumentException("newText is empty: nothing to add");
                int open = bodyOpening(lines, first, last);
                if (open < 0 || open >= last) {
                    throw new IllegalArgumentException(section.id() + " has no body of its own lines to put statements into: replace the"
                            + " whole method instead");
                }
                int at;
                if (mode == Mode.START) {
                    at = open + 1;
                    String next = at < last ? lines.get(at).strip() : "";
                    if (next.startsWith("super(") || next.startsWith("this(")) { // an explicit constructor call comes first
                        while (at < last && !lines.get(at).strip().endsWith(";")) at++;
                        at++;
                    }
                } else {
                    at = last;
                    int before = last - 1;
                    while (before > open && lines.get(before).isBlank()) before--;
                    String lastStatement = before > open ? lines.get(before).strip() : "";
                    if (lastStatement.startsWith("return") || lastStatement.startsWith("throw ")) at = before;
                }
                String indent = bodyIndent(lines, open, last, indentOf(text, open));
                lines.addAll(at, reindent(added, indent));
            }
            case ADD -> {
                if (!section.container()) {
                    throw new IllegalArgumentException(section.id() + " is not a class: name the class to add to, "
                            + "or use mode after on one of its parts");
                }
                if (added.isEmpty()) throw new IllegalArgumentException("newText is empty: nothing to add");
                List<Section> parts = outline.partsOf(section).stream().filter(s -> !s.kind().equals("header")).toList();
                String id = section.id().replaceAll("#\\d+$", "");
                String kind = kindOf(added, id.substring(id.lastIndexOf('.') + 1));
                Section anchor = anchorFor(parts, kind);
                if (anchor != null) {
                    // below the last part of its kind (or of the kind that comes before it), above the closing brace
                    List<String> insert = new ArrayList<>();
                    if (!(anchor.kind().equals("field") && kind.equals("field") && added.size() == 1)) insert.add("");
                    insert.addAll(reindent(added, indentOf(text, anchor.start() - 1)));
                    lines.addAll(anchor.end(), insert);
                } else {
                    // no part comes before it: right below the class's header
                    Section header = outline.byId(section.id() + ".<header>");
                    String indent = parts.isEmpty() ? indentOf(text, first) + "    " : indentOf(text, parts.get(0).start() - 1);
                    List<String> insert = new ArrayList<>(reindent(added, indent));
                    if (!parts.isEmpty()) insert.add("");
                    lines.addAll(header == null ? section.start() : header.end(), insert);
                }
            }
        }
        return lines.isEmpty() ? "" : String.join(lineBreak, lines) + (endsWithBreak ? lineBreak : "");
    }

    /** The index of the line with the body's opening brace: the first line of the section that ends in "{", not a comment or annotation; -1 if none. */
    private static int bodyOpening(List<String> lines, int first, int last) {
        for (int i = first; i <= last; i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("@") || line.startsWith("//") || line.startsWith("/*") || line.startsWith("*")) continue;
            if (line.endsWith("{")) return i;
            if (line.contains("{")) return last; // a body on one line: nothing to insert into
        }
        return -1;
    }

    /** The indentation of the body's first statement, or one level in from the declaration when the body is empty. */
    private static String bodyIndent(List<String> lines, int open, int last, String declarationIndent) {
        for (int i = open + 1; i < last; i++) {
            if (!lines.get(i).isBlank()) return indentOf(String.join("\n", lines), i);
        }
        String unit = declarationIndent.contains("\t") ? "\t" : "    ";
        return declarationIndent + unit;
    }

    /** What the new text is (field, constructor, method, a nested type...), found by outlining it as the body of a class. */
    static String kindOf(List<String> added, String typeName) {
        // The class has its real name: a constructor is a member named like its class
        Outline wrapper = Outlines.of(typeName + ".java", "class " + typeName + " {\n" + String.join("\n", added) + "\n}\n");
        Section type = wrapper.byId(typeName);
        return type == null ? "method" : wrapper.partsOf(type).stream().filter(s -> !s.kind().equals("header"))
                .map(Section::kind).findFirst().orElse("method");
    }

    /** The order the parts of a class are kept in: constants, fields, initializers, constructors, methods, nested types. */
    private static int rank(String kind) {
        return switch (kind) {
            case "constants" -> 0;
            case "field" -> 1;
            case "initializer" -> 2;
            case "constructor" -> 3;
            case "method" -> 4;
            default -> 5; // class, interface, enum, record, annotation
        };
    }

    /**
     * The part the new one goes below: the last part of its kind, else the last part of the kinds before it (a
     * constructor goes below the last field when there is no constructor yet); a nested type goes below the last
     * part. Null when nothing comes before it: it goes right below the class's header.
     */
    private static Section anchorFor(List<Section> parts, String kind) {
        int wanted = rank(kind);
        Section anchor = null;
        for (Section part : parts) {
            int r = rank(part.kind());
            if (wanted == 5 || (r <= wanted && (anchor == null || rank(anchor.kind()) <= r))) anchor = part;
        }
        return anchor;
    }

    /**
     * No stray blank line where a part was removed: two in a row (it was between blank lines), one right below an
     * opening brace (it was the first part), one right above a closing brace (it was the last).
     */
    private static void tidyAfterDelete(List<String> lines, int at) {
        if (at >= lines.size()) return;
        boolean blankBelow = lines.get(at).isBlank();
        boolean blankAbove = at > 0 && lines.get(at - 1).isBlank();
        if (blankBelow && (at == 0 || blankAbove || lines.get(at - 1).stripTrailing().endsWith("{"))) {
            lines.remove(at);
        } else if (blankAbove && lines.get(at).strip().startsWith("}")) {
            lines.remove(at - 1);
        }
    }

    /** One field next to another, one import next to another: no blank line between them. */
    private static boolean tight(Section section, List<String> added) {
        return section.kind().equals("field") && added.size() == 1;
    }

    private static List<String> trimBlankEnds(List<String> lines) {
        int from = 0;
        int to = lines.size();
        while (from < to && lines.get(from).isBlank()) from++;
        while (to > from && lines.get(to - 1).isBlank()) to--;
        return lines.subList(from, to);
    }

    private static String indentOf(String text, int lineIndex) {
        String line = text.lines().skip(lineIndex).findFirst().orElse("");
        return line.substring(0, line.length() - line.stripLeading().length());
    }

    /**
     * The lines with their common indentation replaced by {@code indent}: what the model wrote is moved as a
     * whole, the indentation inside it (a method's body deeper than its signature) stays.
     */
    static List<String> reindent(List<String> lines, String indent) {
        int common = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) continue;
            common = Math.min(common, line.length() - line.stripLeading().length());
        }
        if (common == Integer.MAX_VALUE) common = 0;
        List<String> moved = new ArrayList<>(lines.size());
        for (String line : lines) moved.add(line.isBlank() ? "" : indent + line.substring(Math.min(common, line.length())));
        return moved;
    }
}
