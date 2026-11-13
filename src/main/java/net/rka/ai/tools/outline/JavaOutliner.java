package net.rka.ai.tools.outline;

import net.rka.ai.tools.code.java.JavaClass;
import net.rka.ai.tools.code.java.JavaComment;
import net.rka.ai.tools.code.java.JavaConstructor;
import net.rka.ai.tools.code.java.JavaField;
import net.rka.ai.tools.code.java.JavaInitializer;
import net.rka.ai.tools.code.java.JavaMember;
import net.rka.ai.tools.code.java.JavaMethod;
import net.rka.ai.tools.code.java.JavaParameter;
import net.rka.ai.tools.code.java.JavaSourceReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sections of a Java file: {@code package}, the {@code imports} block, then per type its header (the
 * lines up to the opening brace, with annotations and the doc comment), enum constants, fields, constructors,
 * methods and initializers, and the nested types the same way.
 *
 * <h2>Where the sections come from</h2>
 * From the structure of the file ({@link JavaSourceReader}: the same types, members and lines that the Java tools use), so a section is
 * a member and can never be a statement of a long method. A file that does not read as Java (a half-written one the model has to
 * repair) falls back to the scan below, which finds what it can.
 *
 * <h2>How the scan works</h2>
 * One pass over the text, no parser library:
 * <ol>
 *   <li><b>Masking:</b> a copy of the text where comments, string and character literals and text blocks are
 *       blanked out (newlines stay), so a brace or semicolon in a string or a comment can't confuse the scan
 *       and every offset still points at the same place in the original.</li>
 *   <li><b>Members:</b> in a body, a member's header runs to the first {@code ;} or {@code {} outside
 *       parentheses; a {@code {} after an {@code =} belongs to an initializer ({@code = {1, 2}}, a lambda),
 *       so the scan goes on to its {@code ;}. A {@code {} ends the header of a type, method, constructor or
 *       initializer, and the matching {@code }} ends the member.</li>
 *   <li><b>Classifying</b> the header (annotations removed): a {@code class}, {@code interface},
 *       {@code enum}, {@code record} or {@code @interface} keyword makes a type, whose body is scanned the
 *       same way; a {@code (} before any {@code =} makes a method, or a constructor when its name is the
 *       type's; otherwise a field.</li>
 *   <li><b>Lines:</b> a member starts at its doc comment or annotation (the first thing after the previous
 *       member) and ends at the line of its closing {@code ;} or {@code }}.</li>
 * </ol>
 * Ids are {@code Type.field}, {@code Type.method(ParamType,ParamType)} (generics left out, so overloads differ)
 * and so on; a repeated id gets {@code #2}. Sections are whole lines, so two members on one line are one
 * section as far as an edit is concerned.
 * <p>
 * Not implemented yet: other brace languages (Kotlin, C#, TypeScript) could reuse the masking and the
 * member scan with their own header rules.
 */
final class JavaOutliner implements Outliner {

    private static final Pattern TYPE = Pattern.compile("(?<![\\w$])(class|interface|enum|record|@interface)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern LAST_IDENTIFIER = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*$");

    @Override
    public boolean structured() {
        return true;
    }

    @Override
    public List<Section> sections(String text, List<String> lines) {
        try {
            return new ModelScan(text, JavaSourceReader.read(text)).run();
        } catch (RuntimeException e) {
            return new Scan(text).run(); // not Java the reader understands: what the scan can find
        }
    }

    /** The sections the scan finds, for the file as it is whether or not it reads as Java: what the model-based sections are compared with. */
    static List<Section> scanOnly(String text) {
        return new Scan(text).run();
    }

    /** The sections of a file read into types: package, imports, then per type its header, constants and members. */
    private static final class ModelScan {
        private final char[] code;
        private final int[] lineStarts;
        private final String[] lines;
        private final List<JavaClass> types;
        private final List<Section> found = new ArrayList<>();
        private final Set<String> ids = new HashSet<>();

        ModelScan(String text, List<JavaClass> types) {
            this.code = mask(text);
            this.lineStarts = Scan.lineStarts(text);
            this.lines = text.split("\r?\n", -1);
            this.types = types;
        }

        List<Section> run() {
            if (!types.isEmpty()) {
                JavaClass first = types.getFirst();
                if (!first.packageLines().isNone()) {
                    found.add(new Section(unique("package"), "package", first.packageLines().start(), first.packageLines().end(), null, false));
                }
                if (!first.importLines().isEmpty()) {
                    found.add(new Section(unique("imports"), "imports", first.importLines().getFirst().start(), first.importLines().getLast().end(), null, false));
                }
            }
            for (JavaClass type : types) type(type, null);
            found.sort(Comparator.comparingInt(Section::start).thenComparing(Comparator.comparingInt(Section::end).reversed()));
            return found;
        }

        private void type(JavaClass type, String parentId) {
            String id = unique(parentId == null ? type.name() : parentId + "." + type.name());
            found.add(new Section(id, type.kind() == net.rka.ai.tools.code.java.JavaKind.ANNOTATION ? "annotation" : type.kind().keyword(),
                    type.startLine(), type.endLine(), parentId, true));
            found.add(new Section(unique(id + ".<header>"), "header", type.startLine(), Math.min(type.endLine(), headerEnd(type.startLine())), id, false));
            if (!type.enumConstants().isEmpty()) {
                found.add(new Section(unique(id + ".constants"), "constants", type.enumConstants().getFirst().startLine(),
                        type.enumConstants().getLast().endLine(), id, false));
            }
            JavaField previous = null;
            for (JavaMember member : type.members()) {
                switch (member) {
                    case JavaClass nested -> type(nested, id);
                    case JavaField field -> {
                        // the declarators of one declaration (int a, b;) are one section, named after the first
                        if (previous != null && previous.endLine() >= field.startLine()) {
                            replaceEnd(previous, field.endLine());
                        } else {
                            found.add(new Section(unique(id + "." + field.name()), "field", field.startLine(), field.endLine(), id, false));
                        }
                        previous = field;
                        continue;
                    }
                    case JavaMethod method -> found.add(new Section(unique(id + "." + method.name() + "(" + types(method.parameters()) + ")"), "method",
                            method.startLine(), method.endLine(), id, false));
                    case JavaConstructor constructor -> found.add(new Section(unique(id + "." + type.name()
                            + (constructor.compact() ? "" : "(" + types(constructor.parameters()) + ")")), "constructor",
                            constructor.startLine(), constructor.endLine(), id, false));
                    case JavaInitializer initializer -> found.add(new Section(unique(id + "." + (initializer.isStatic() ? "static{}" : "{}")),
                            "initializer", initializer.startLine(), initializer.endLine(), id, false));
                    case JavaComment comment -> {
                        // comments belong to the member next to them
                    }
                }
                previous = null;
            }
        }

        /** The section of the field made longer: its next declarator ends later. */
        private void replaceEnd(JavaField field, int end) {
            for (int i = found.size() - 1; i >= 0; i--) {
                Section section = found.get(i);
                if (section.kind().equals("field") && section.start() == field.startLine()) {
                    found.set(i, new Section(section.id(), section.kind(), section.start(), Math.max(section.end(), end), section.parentId(), false));
                    return;
                }
            }
        }

        /** The parameter types the way ids show them: no generics, no annotations, no blanks. */
        private static String types(List<JavaParameter> parameters) {
            List<String> out = new ArrayList<>();
            for (JavaParameter p : parameters) {
                String type = withoutAnnotations(p.type()).replaceAll("\\bfinal\\s+", "");
                StringBuilder plain = new StringBuilder();
                int depth = 0;
                for (char c : type.toCharArray()) {
                    if (c == '<') depth++;
                    else if (c == '>' && depth > 0) depth--;
                    else if (depth == 0 && !Character.isWhitespace(c)) plain.append(c);
                }
                out.add(plain.toString());
            }
            return String.join(",", out);
        }

        /** The type of a parameter without its annotations; their arguments hold strings, parentheses and commas of their own. */
        private static String withoutAnnotations(String text) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c != '@') {
                    out.append(c);
                    i++;
                    continue;
                }
                i++;
                while (i < text.length() && (Character.isJavaIdentifierPart(text.charAt(i)) || text.charAt(i) == '.')) i++;
                int j = i;
                while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                if (j < text.length() && text.charAt(j) == '(') {
                    int depth = 0;
                    for (; j < text.length(); j++) {
                        char d = text.charAt(j);
                        if (d == '"' || d == '\'') { // a string or character: to its end
                            for (j++; j < text.length() && text.charAt(j) != d; j++) if (text.charAt(j) == '\\') j++;
                        } else if (d == '(') {
                            depth++;
                        } else if (d == ')' && --depth == 0) {
                            break;
                        }
                    }
                    i = j + 1;
                }
                out.append(' ');
            }
            return out.toString();
        }

        /** The line of the brace that opens the type's body: the first one outside parentheses after the type's first line. */
        private int headerEnd(int startLine) {
            int parens = 0;
            for (int i = lineStarts[startLine - 1]; i < code.length; i++) {
                char c = code[i];
                if (c == '(') parens++;
                else if (c == ')') parens--;
                else if (c == '{' && parens <= 0) return lineOfOffset(i);
            }
            return startLine;
        }

        private int lineOfOffset(int offset) {
            int at = Arrays.binarySearch(lineStarts, offset);
            return (at >= 0 ? at : -at - 2) + 1;
        }

        private String unique(String id) {
            String candidate = id;
            for (int n = 2; !ids.add(candidate); n++) candidate = id + "#" + n;
            return candidate;
        }
    }

    /** One file's scan: the original text, its masked copy, and the sections found so far. */
    private static final class Scan {
        private final String text;
        private final char[] code;
        private final int[] lineStarts;
        private final List<Section> found = new ArrayList<>();
        private final Set<String> ids = new HashSet<>();
        private int firstImportLine = -1;
        private int lastImportLine = -1;

        Scan(String text) {
            this.text = text;
            this.code = mask(text);
            this.lineStarts = lineStarts(text);
        }

        List<Section> run() {
            members(0, code.length, null, null, false);
            if (firstImportLine > 0) {
                found.add(new Section(unique("imports"), "imports", firstImportLine, lastImportLine, null, false));
            }
            found.sort(Comparator.comparingInt(Section::start).thenComparing(Comparator.comparingInt(Section::end).reversed()));
            return found;
        }

        // ---- the members of one body ----

        /**
         * @param from       first offset of the body
         * @param to         offset after the body's last character (the closing brace, or the end of the file)
         * @param parentId   the enclosing type's id, null at the top of the file
         * @param typeName   the enclosing type's simple name
         * @param enumBody   whether the body starts with enum constants
         */
        private void members(int from, int to, String parentId, String typeName, boolean enumBody) {
            int cursor = lineEndIfBlank(from);
            if (enumBody) cursor = enumConstants(cursor, to, parentId);
            while (true) {
                int codeStart = skipBlanks(cursor, to);
                if (codeStart >= to) return;
                Header header = header(codeStart, to);
                int startLine = lineOf(skipWhitespaceInText(cursor, codeStart));
                int endOffset = header.terminator == '{' ? header.bodyEnd : header.end;
                int endLine = lineOf(Math.min(endOffset, code.length - 1));
                member(header, startLine, endLine, parentId, typeName);
                cursor = lineEndIfBlank(Math.min(endOffset + 1, code.length));
            }
        }

        /** The constants before an enum's first {@code ;} as one section; returns where the members go on. */
        private int enumConstants(int from, int to, String parentId) {
            int start = skipBlanks(from, to);
            if (start >= to || code[start] == ';') return start >= to ? to : lineEndIfBlank(start + 1);
            int depth = 0;
            int parens = 0;
            int last = start;
            int i = start;
            for (; i < to; i++) {
                char c = code[i];
                if (c == '{') depth++;
                else if (c == '}') depth--;
                else if (c == '(') parens++;
                else if (c == ')') parens--;
                else if (c == ';' && depth == 0 && parens == 0) break;
                if (!Character.isWhitespace(c)) last = i;
            }
            int startLine = lineOf(skipWhitespaceInText(from, start));
            found.add(new Section(unique(parentId + ".constants"), "constants", startLine, lineOf(last), parentId, false));
            return i < to ? lineEndIfBlank(i + 1) : to;
        }

        /** What ended a member's header, and the masked text of the header. */
        private record Header(int start, int end, char terminator, int bodyEnd, String text) {
        }

        private Header header(int start, int to) {
            int parens = 0;
            boolean assigned = false;
            for (int i = start; i < to; i++) {
                char c = code[i];
                if (c == '(') parens++;
                else if (c == ')') parens--;
                else if (parens <= 0) {
                    if (c == '=' && !(i + 1 < to && code[i + 1] == '=')) assigned = true;
                    else if (c == ';') return new Header(start, i, ';', i, new String(code, start, i - start));
                    else if (c == '{') {
                        int close = matching(i, to);
                        if (!assigned) return new Header(start, i, '{', close, new String(code, start, i - start));
                        i = close; // an initializer's braces ({1, 2}, a lambda body): on to its ';'
                    }
                }
            }
            return new Header(start, to - 1, ';', to - 1, new String(code, start, Math.max(0, to - start)));
        }

        private void member(Header header, int startLine, int endLine, String parentId, String typeName) {
            String head = collapse(withoutAnnotations(header.text));
            int paren = head.indexOf('(');
            int equals = head.indexOf('=');
            int prefixEnd = paren < 0 ? (equals < 0 ? head.length() : equals) : (equals < 0 ? paren : Math.min(paren, equals));
            String prefix = head.substring(0, prefixEnd);

            Matcher type = TYPE.matcher(prefix);
            if (header.terminator == '{' && type.find()) {
                type(header, startLine, endLine, parentId, type.group(1), type.group(2));
                return;
            }
            if (parentId == null) { // at the top of the file: package, imports, and anything else
                if (head.startsWith("package ")) {
                    found.add(new Section(unique("package"), "package", startLine, endLine, null, false));
                } else if (head.startsWith("import ")) {
                    if (firstImportLine < 0) firstImportLine = startLine;
                    lastImportLine = endLine;
                } else {
                    found.add(new Section(unique("declaration@" + startLine), "declaration", startLine, endLine, null, false));
                }
                return;
            }
            if (header.terminator == ';') {
                if (paren >= 0 && (equals < 0 || paren < equals)) { // an abstract or interface method
                    method(head, paren, startLine, endLine, parentId, typeName);
                } else {
                    String name = fieldName(prefix);
                    found.add(new Section(unique(parentId + "." + name), "field", startLine, endLine, parentId, false));
                }
                return;
            }
            // a body: initializer block, constructor or method
            if (paren < 0) {
                String lastWord = identifier(prefix);
                if (prefix.isBlank() || prefix.strip().equals("static") || prefix.strip().endsWith(" static")) {
                    boolean isStatic = prefix.contains("static");
                    found.add(new Section(unique(parentId + "." + (isStatic ? "static{}" : "{}")), "initializer",
                            startLine, endLine, parentId, false));
                } else if (lastWord.equals(typeName)) { // a record's compact constructor
                    found.add(new Section(unique(parentId + "." + typeName), "constructor", startLine, endLine, parentId, false));
                } else {
                    found.add(new Section(unique(parentId + ".block@" + startLine), "block", startLine, endLine, parentId, false));
                }
                return;
            }
            method(head, paren, startLine, endLine, parentId, typeName);
        }

        private void type(Header header, int startLine, int endLine, String parentId, String keyword, String name) {
            String id = unique(parentId == null ? name : parentId + "." + name);
            String kind = keyword.equals("@interface") ? "annotation" : keyword;
            found.add(new Section(id, kind, startLine, endLine, parentId, true));
            found.add(new Section(unique(id + ".<header>"), "header", startLine, lineOf(header.end), id, false));
            members(header.end + 1, header.bodyEnd, id, name, keyword.equals("enum"));
        }

        private void method(String head, int paren, int startLine, int endLine, String parentId, String typeName) {
            String name = identifier(head.substring(0, paren));
            boolean constructor = name.equals(typeName);
            String id = unique(parentId + "." + name + "(" + String.join(",", parameterTypes(head, paren)) + ")");
            found.add(new Section(id, constructor ? "constructor" : "method", startLine, endLine, parentId, false));
        }

        // ---- reading headers ----

        /** The parameter types of the parenthesis that starts at {@code paren}, without generics, annotations and names. */
        private static List<String> parameterTypes(String head, int paren) {
            int depth = 0;
            int close = head.length();
            for (int i = paren; i < head.length(); i++) {
                if (head.charAt(i) == '(') depth++;
                else if (head.charAt(i) == ')' && --depth == 0) {
                    close = i;
                    break;
                }
            }
            String inside = withoutGenerics(head.substring(paren + 1, Math.min(close, head.length())));
            List<String> types = new ArrayList<>();
            if (inside.isBlank()) return types;
            for (String parameter : inside.split(",")) {
                List<String> words = new ArrayList<>(Arrays.asList(parameter.strip().split("\\s+")));
                words.removeIf(w -> w.equals("final") || w.isEmpty());
                if (words.size() > 1) words.removeLast(); // the parameter's name
                types.add(String.join("", words));
            }
            return types;
        }

        private static String fieldName(String prefix) {
            String first = withoutGenerics(prefix);
            int comma = first.indexOf(',');
            if (comma >= 0) first = first.substring(0, comma); // int a, b;  -> a
            return identifier(first.replace("[]", " "));
        }

        private static String identifier(String text) {
            Matcher m = LAST_IDENTIFIER.matcher(withoutGenerics(text).strip());
            return m.find() ? m.group(1) : "?";
        }

        private static String withoutGenerics(String text) {
            StringBuilder out = new StringBuilder(text.length());
            int depth = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '<') depth++;
                else if (c == '>' && depth > 0) depth--;
                else if (depth == 0) out.append(c);
            }
            return out.toString();
        }

        /** The header without annotations ({@code @Override}, {@code @SuppressWarnings("x")}), which hold parentheses and words of their own. */
        private static String withoutAnnotations(String text) {
            StringBuilder out = new StringBuilder(text.length());
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '@' && !text.startsWith("@interface", i)) {
                    i++;
                    while (i < text.length() && (Character.isJavaIdentifierPart(text.charAt(i)) || text.charAt(i) == '.')) i++;
                    int j = i;
                    while (j < text.length() && Character.isWhitespace(text.charAt(j))) j++;
                    if (j < text.length() && text.charAt(j) == '(') {
                        int depth = 0;
                        for (; j < text.length(); j++) {
                            if (text.charAt(j) == '(') depth++;
                            else if (text.charAt(j) == ')' && --depth == 0) break;
                        }
                        i = j + 1;
                    }
                    out.append(' ');
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }

        private static String collapse(String text) {
            return text.strip().replaceAll("\\s+", " ");
        }

        private String unique(String id) {
            String candidate = id;
            for (int n = 2; !ids.add(candidate); n++) candidate = id + "#" + n;
            return candidate;
        }

        // ---- offsets and lines ----

        private int matching(int open, int to) {
            int depth = 0;
            for (int i = open; i < to; i++) {
                if (code[i] == '{') depth++;
                else if (code[i] == '}' && --depth == 0) return i;
            }
            return to - 1;
        }

        private int skipBlanks(int from, int to) {
            int i = from;
            while (i < to && Character.isWhitespace(code[i])) i++;
            return i;
        }

        /** The first non-space character of the original text in {@code from}..{@code to}: a doc comment counts. */
        private int skipWhitespaceInText(int from, int to) {
            int i = from;
            while (i < to && Character.isWhitespace(text.charAt(i))) i++;
            return i;
        }

        /** The end of the line when only blanks (or a comment, which is masked) follow {@code offset} on it, else {@code offset}. */
        private int lineEndIfBlank(int offset) {
            int j = offset;
            while (j < code.length && code[j] != '\n' && Character.isWhitespace(code[j])) j++;
            return j >= code.length ? code.length : code[j] == '\n' ? j + 1 : offset;
        }

        private int lineOf(int offset) {
            int at = Arrays.binarySearch(lineStarts, offset);
            return (at >= 0 ? at : -at - 2) + 1;
        }

        static int[] lineStarts(String text) {
            int count = 1;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
            int[] starts = new int[count];
            int n = 1;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts[n++] = i + 1;
            return starts;
        }
    }

    // ---- masking ----

    /** The text with comments and string, character and text block literals blanked out; newlines stay, so offsets match. */
    static char[] mask(String text) {
        char[] c = text.toCharArray();
        int n = c.length;
        int i = 0;
        while (i < n) {
            char ch = c[i];
            if (ch == '/' && i + 1 < n && c[i + 1] == '/') {
                while (i < n && c[i] != '\n') blank(c, i++);
            } else if (ch == '/' && i + 1 < n && c[i + 1] == '*') {
                blank(c, i++);
                blank(c, i++);
                while (i < n && !(c[i] == '*' && i + 1 < n && c[i + 1] == '/')) blank(c, i++);
                if (i < n) blank(c, i++);
                if (i < n) blank(c, i++);
            } else if (ch == '"' && i + 2 < n && c[i + 1] == '"' && c[i + 2] == '"') { // a text block
                blank(c, i++);
                blank(c, i++);
                blank(c, i++);
                while (i < n && !(c[i] == '"' && i + 2 < n && c[i + 1] == '"' && c[i + 2] == '"')) {
                    if (c[i] == '\\' && i + 1 < n) blank(c, i++);
                    blank(c, i++);
                }
                for (int k = 0; k < 3 && i < n; k++) blank(c, i++);
            } else if (ch == '"' || ch == '\'') {
                blank(c, i++);
                while (i < n && c[i] != ch && c[i] != '\n') {
                    if (c[i] == '\\' && i + 1 < n) blank(c, i++);
                    blank(c, i++);
                }
                if (i < n && c[i] == ch) blank(c, i++);
            } else {
                i++;
            }
        }
        return c;
    }

    private static void blank(char[] c, int i) {
        if (c[i] != '\n' && c[i] != '\r') c[i] = ' ';
    }
}
