package net.rka.ai.tools.code.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Puts statements into the body of a method next to one of its statements, named by the text of its first line (the
 * <i>anchor</i>), so a change of ten lines in a 150-line method is written as ten lines. The anchor is found in the lines
 * of the method, the end of the statement it starts is found by reading the brackets and strings of the code, and the new
 * statements are indented like the anchor's line.
 */
public final class JavaAnchors {

    private JavaAnchors() {
    }

    private static final Set<String> CONTINUES = Set.of("else", "catch", "finally");
    private static final List<String> CONTINUATION_STARTS = List.of(".", ")", "}", "]", ",", "+", "-", "*", "/", "?", ":", "&&", "||", "=", "else",
            "catch", "finally", "->");

    /**
     * The line (counted from 1) of the statement that starts with the anchor, found among the lines {@code from} to {@code to} of the text.
     *
     * @throws IllegalArgumentException when no line, or more than one, fits, or the line continues a statement (the message says what to do)
     */
    public static int find(String text, int from, int to, String anchor) {
        String wanted = anchor == null ? "" : anchor.strip();
        if (wanted.isEmpty()) throw new IllegalArgumentException("The anchor is empty: name a line of the method, the first line of a statement.");
        List<String> lines = lines(text);
        List<Integer> exact = new ArrayList<>();
        List<Integer> partial = new ArrayList<>();
        for (int i = Math.max(1, from); i <= Math.min(to, lines.size()); i++) {
            String line = lines.get(i - 1).strip();
            if (line.equals(wanted)) exact.add(i);
            else if (line.contains(wanted)) partial.add(i);
        }
        List<Integer> fits = exact.isEmpty() ? partial : exact;
        if (fits.isEmpty()) {
            throw new IllegalArgumentException("No line of the method has the anchor `" + wanted + "`. Copy a line of the method as it is written.");
        }
        if (fits.size() > 1) {
            throw new IllegalArgumentException("The anchor `" + wanted + "` is on " + fits.size() + " lines (" + fits.stream()
                    .map(i -> i + ": " + lines.get(i - 1).strip()).limit(4).reduce((a, b) -> a + "; " + b).orElse("")
                    + "). Make it longer so that it names one line.");
        }
        int line = fits.getFirst();
        String stripped = lines.get(line - 1).strip();
        for (String start : CONTINUATION_STARTS) {
            boolean increment = stripped.startsWith("++") || stripped.startsWith("--"); // ++i; is a statement of its own
            if (stripped.startsWith(start) && !increment && !(start.equals("-") && stripped.startsWith("->")) && !stripped.startsWith("//")) {
                throw new IllegalArgumentException("Line " + line + " (`" + stripped + "`) continues a statement that starts above it: "
                        + "name the first line of the statement as the anchor.");
            }
        }
        return line;
    }

    /** The last line of the statement that starts on line {@code start}, found within the lines up to {@code to}. */
    public static int statementEnd(String text, int start, int to) {
        List<String> lines = lines(text);
        StringBuilder code = new StringBuilder();
        List<Integer> lineOf = new ArrayList<>(); // for each char of code, its line
        for (int i = start; i <= Math.min(to, lines.size()); i++) {
            String line = lines.get(i - 1);
            for (int c = 0; c < line.length(); c++) lineOf.add(i);
            code.append(line);
            lineOf.add(i);
            code.append('\n');
        }
        String s = code.toString();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (s.startsWith("//", i)) {
                i = Math.max(i, s.indexOf('\n', i) - 1);
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 1;
            } else if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                i = end < 0 ? s.length() : end + 2;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < s.length() && s.charAt(i) != c && s.charAt(i) != '\n') i += s.charAt(i) == '\\' ? 2 : 1;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == '}') {
                depth--;
                if (depth <= 0 && !continues(s, i + 1)) return lineOf.get(i);
            } else if (c == ';' && depth <= 0) {
                return lineOf.get(i);
            }
        }
        throw new IllegalArgumentException("The statement on line " + start + " does not end inside the method.");
    }

    private static boolean continues(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        int end = i;
        while (end < s.length() && Character.isLetter(s.charAt(end))) end++;
        return CONTINUES.contains(s.substring(i, end));
    }

    /**
     * The text with {@code code} put before the statement that starts with the anchor, or after it.
     *
     * @param from first line of the method, {@code to} its last line
     */
    public static String insert(String text, int from, int to, String anchor, boolean after, String code) {
        int line = find(text, from, to, anchor);
        boolean crlf = text.contains("\r\n");
        List<String> lines = new ArrayList<>(lines(text));
        String indent = JavaMembers.indentOf(lines.get(line - 1));
        int at = after ? statementEnd(text, line, to) : line - 1; // insert after this many lines
        List<String> added = List.of(JavaMembers.indented(code, indent).split("\n", -1));
        lines.addAll(at, added);
        String joined = String.join("\n", lines);
        return crlf ? joined.replace("\n", "\r\n") : joined;
    }

    private static List<String> lines(String text) {
        return List.of(text.replace("\r\n", "\n").split("\n", -1));
    }
}
