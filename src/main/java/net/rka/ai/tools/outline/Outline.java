package net.rka.ai.tools.outline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The outline of one file at one moment: its md5, its line count and its {@link Section}s. The md5 is the
 * proof of which version the section ids and lines belong to: {@code editSection} refuses an md5 that isn't
 * the file's current one, so an edit never lands in a file that changed after the model looked at it.
 *
 * @param structured whether the sections follow the file's own structure; false for plain blocks of lines
 */
public record Outline(String md5, int lineCount, boolean structured, List<Section> sections) {

    /** The section with exactly this id. */
    public Section byId(String id) {
        return sections.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * The section a model means by {@code query}: its id, or an id the model had to shorten or misspell a
     * little: without the parameter list ({@code Foo.run}), without the type ({@code run(String)}), other case,
     * other spacing. When several sections fit, none is guessed: the error lists them.
     *
     * @throws IllegalArgumentException when there is no such section or the name fits several
     */
    public Section find(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("section is empty: pass an id from the outline");
        Section exact = byId(query.strip());
        if (exact != null) return exact;
        String wanted = simple(query);
        List<Section> fits = sections.stream().filter(s -> fits(s.id(), wanted)).toList();
        if (fits.size() > 1 && wanted.indexOf('(') < 0) {
            // A name without parentheses means the part that has no parameter list (a field, a class), not the
            // method of the same name: that one is asked for as name()
            List<Section> named = fits.stream().filter(s -> s.id().indexOf('(') < 0).toList();
            if (named.size() == 1) return named.get(0);
        }
        if (fits.size() == 1) return fits.get(0);
        if (fits.isEmpty()) {
            Section byCount = byNameAndParameterCount(wanted);
            if (byCount != null) return byCount;
            List<String> near = sections.stream().map(Section::id)
                    .filter(id -> simple(id).contains(wanted) || wanted.contains(simple(withoutParameters(id)))).limit(8).toList();
            String all = sections.stream().filter(s -> !s.kind().equals("header")).map(Section::id).limit(40)
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("No section '" + query.strip() + "' in this file."
                    + (near.isEmpty() ? "" : " Did you mean: " + String.join(", ", near) + "?")
                    + (all.isEmpty() ? "" : " Its sections: " + all + (sections.size() > 40 ? ", ..." : ".")));
        }
        throw new IllegalArgumentException("'" + query.strip() + "' fits " + fits.size() + " sections: "
                + fits.stream().map(Section::id).limit(8).collect(Collectors.joining(", "))
                + ". Pass the full id of one.");
    }

    /**
     * A method asked for with parameter types that are not the real ones ({@code block(Node,Consumer,String)} for
     * {@code block(org.commonmark.node.Node,int,StyleAttributeMap,String)}): the one method of that name, or the one with that many parameters.
     */
    private Section byNameAndParameterCount(String wanted) {
        int paren = wanted.indexOf('(');
        if (paren < 0) return null;
        String name = wanted.substring(0, paren);
        List<Section> named = sections.stream().filter(s -> s.id().indexOf('(') > 0 && simple(withoutParameters(s.id())).endsWith("." + name)).toList();
        if (named.size() == 1) return named.get(0);
        int count = parameterCount(wanted);
        List<Section> same = named.stream().filter(s -> parameterCount(simple(s.id())) == count).toList();
        return same.size() == 1 ? same.get(0) : null;
    }

    private static int parameterCount(String signature) {
        int open = signature.indexOf('(');
        int close = signature.lastIndexOf(')');
        if (open < 0 || close <= open + 1) return 0;
        int depth = 0;
        int count = 1;
        for (char c : signature.substring(open + 1, close).toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        return count;
    }

    private static boolean fits(String id, String wanted) {
        String simple = simple(id);
        if (simple.equals(wanted)) return true;
        String bare = simple(withoutParameters(id));
        if (wanted.indexOf('(') < 0) return bare.equals(wanted) || bare.endsWith("." + wanted) || bare.endsWith(">" + wanted);
        return simple.endsWith("." + wanted);
    }

    private static String simple(String id) {
        return id.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String withoutParameters(String id) {
        int paren = id.indexOf('(');
        return paren < 0 ? id : id.substring(0, paren);
    }

    /** The sections inside {@code section}, in file order. */
    public List<Section> partsOf(Section section) {
        return sections.stream().filter(s -> section.id().equals(s.parentId())).toList();
    }

    /**
     * The outline as the model reads it: a header with the md5, then one line per section. A file with so many
     * sections that the list would cost more than reading the code (one class with hundreds of methods) shows
     * its classes and top-level parts with how many parts each holds; the parts of one are listed with
     * {@code within}. A list that is still too long is cut, and says so.
     *
     * @param within the section whose parts to list, or null for the whole file
     */
    public String render(String path, Section within) {
        StringBuilder out = new StringBuilder(path).append(" · ").append(lineCount).append(" lines · md5 ").append(md5).append('\n');
        if (sections.isEmpty()) return out.append("(no sections: the file is empty)").toString();
        out.append(String.format("%-9s %-12s %s%n", "lines", "kind", "section"));
        List<Section> rows = within == null ? sections : parts(within);
        String note = "";
        if (within == null && listed(rows, false).length() > BUDGET_CHARS) {
            rows = sections.stream().filter(s -> s.parentId() == null || s.container()).toList();
            note = "\nThis file has too many sections to list: only its classes and top-level parts are shown. "
                    + "List the parts of one with outline and its id as section.";
            out.append(listedWithCounts(rows, BUDGET_CHARS));
        } else {
            out.append(listed(rows, true));
        }
        if (within != null && listed(rows, false).length() > BUDGET_CHARS) {
            note = "\n… more sections not shown. Any section can still be read by its id (a method as Class.name), and searchFiles finds names.";
        }
        if (!structured) note += "\n(no code structure is known for this file type: the sections are plain blocks of lines)";
        return out + note;
    }

    /** The section itself and the sections inside it, in file order. */
    private List<Section> parts(Section within) {
        List<Section> parts = new ArrayList<>(List.of(within));
        parts.addAll(partsOf(within));
        return parts;
    }

    private static final int BUDGET_CHARS = 12_000;

    /** One line per section; cut at the budget when {@code cut}. */
    private static String listed(List<Section> rows, boolean cut) {
        StringBuilder out = new StringBuilder();
        for (Section s : rows) {
            String row = row(s, s.label());
            if (cut && out.length() + row.length() > BUDGET_CHARS) break;
            out.append(out.isEmpty() ? "" : "\n").append(row);
        }
        return out.toString();
    }

    /** Like {@link #listed}, with how many parts each class holds. */
    private String listedWithCounts(List<Section> rows, int budget) {
        StringBuilder out = new StringBuilder();
        for (Section s : rows) {
            long parts = s.container() ? partsOf(s).stream().filter(p -> !p.kind().equals("header")).count() : 0;
            String row = row(s, parts > 0 ? parts + " parts" : s.label());
            if (out.length() + row.length() > budget) break;
            out.append(out.isEmpty() ? "" : "\n").append(row);
        }
        return out.toString();
    }

    private static String row(Section s, String label) {
        String lines = s.start() == s.end() ? String.valueOf(s.start()) : s.start() + "-" + s.end();
        return String.format("%-9s %-12s %s%s", lines, s.kind(), s.id(), label.isEmpty() ? "" : "  — " + label);
    }
}
