package net.rka.ai.tools.code.java;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Reads Java source text into the {@link JavaClass} model: the top-level types of a file with their members, signatures,
 * annotations and the comments above them. Method, constructor and initializer bodies, field initializers and enum
 * constant arguments are kept as source text, not parsed.
 *
 * <h2>How it works</h2>
 * One pass, no parser library: the text is cut into tokens (words, punctuation, string and character literals, text
 * blocks, comments), so a brace or a semicolon inside a string or a comment never counts. A declaration is read as its
 * <i>prefix</i> (comments, annotations, modifiers), then what it is: a type keyword, an initializer block, a constructor
 * (the type's name before {@code (}), or a field or method (the last word before the first {@code (}, {@code =},
 * {@code ;} or {@code ,} outside of generics is the name, what came before it the type). Bodies are found by matching
 * braces. Bodies and comments are dedented, so the writer can indent them again.
 *
 * <h2>What is not kept</h2>
 * Comments inside the head of a declaration (between the modifiers and the type, inside a parameter list, after an enum
 * constant) and after the last type of a file; blank lines between members. Kept: the comment above a declaration, the
 * header comment before {@code package}, comments at the end of a line after a member or after an opening brace
 * ({@link JavaComment#trailing()}), comments at the end of a body, comments among the annotations of a declaration, and
 * every comment inside a body. The members stay in the order of the source ({@link JavaClass#members()}).
 */
public final class JavaSourceReader {

    private JavaSourceReader() {
    }

    /**
     * @return the top-level types of the source, each with the file's package and imports
     * @throws IllegalArgumentException when the text is not Java the reader understands (it says where)
     */
    public static List<JavaClass> read(String source) {
        return new Parser(source, source.replace("\r\n", "\n")).compilationUnit();
    }

    /** A piece of code: {@code kind} is "word", "punct", "string" or "char". */
    record Tok(String kind, String text) {
    }

    /** The tokens of a piece of code without its comments, for scanning it (see {@link JavaCallGraph}). */
    static List<Tok> tokens(String source) {
        String text = source.replace("\r\n", "\n");
        List<Tok> out = new ArrayList<>();
        for (Token t : tokenize(text)) {
            if (t.kind() != Kind.COMMENT) out.add(new Tok(t.kind().name().toLowerCase(), text.substring(t.start(), t.end())));
        }
        return out;
    }

    /** The comments of the source in order, each with its whitespace collapsed: for checking that none is lost. */
    public static List<String> comments(String source) {
        String text = source.replace("\r\n", "\n");
        List<String> out = new ArrayList<>();
        for (Token t : tokenize(text)) if (t.kind() == Kind.COMMENT) out.add(text.substring(t.start(), t.end()).replaceAll("\\s+", " ").strip());
        return out;
    }

    /**
     * The dotted names in a piece of source in order ({@code a.b.C} is [a, b, C], {@code Foo::bar} is [Foo, bar]); comments,
     * strings and numbers are skipped. The workspace resolves them to types of the project.
     */
    static List<List<String>> chains(String source) {
        String text = source.replace("\r\n", "\n");
        List<Token> tokens = tokenize(text);
        List<List<String>> out = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            Token t = tokens.get(i);
            if (!isName(text, t)) {
                i++;
                continue;
            }
            List<String> chain = new ArrayList<>();
            chain.add(text.substring(t.start(), t.end()));
            int j = i + 1;
            while (j + 1 < tokens.size() && isPunct(text, tokens.get(j), '.') && isName(text, tokens.get(j + 1))) {
                chain.add(text.substring(tokens.get(j + 1).start(), tokens.get(j + 1).end()));
                j += 2;
            }
            if (j + 2 < tokens.size() && isPunct(text, tokens.get(j), ':') && isPunct(text, tokens.get(j + 1), ':')
                    && isName(text, tokens.get(j + 2))) {
                chain.add(text.substring(tokens.get(j + 2).start(), tokens.get(j + 2).end()));
                j += 3;
            }
            out.add(chain);
            i = j;
        }
        return out;
    }

    private static boolean isName(String text, Token t) {
        return t.kind() == Kind.WORD && !Character.isDigit(text.charAt(t.start()));
    }

    private static boolean isPunct(String text, Token t, char c) {
        return t.kind() == Kind.PUNCT && text.charAt(t.start()) == c;
    }

    // ---- tokens ----

    private enum Kind { WORD, PUNCT, STRING, CHAR, COMMENT }

    private record Token(Kind kind, int start, int end) {
    }

    private static List<Token> tokenize(String src) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (src.startsWith("//", i)) {
                int end = src.indexOf('\n', i);
                end = end < 0 ? n : end;
                tokens.add(new Token(Kind.COMMENT, i, end));
                i = end;
            } else if (src.startsWith("/*", i)) {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                tokens.add(new Token(Kind.COMMENT, i, end));
                i = end;
            } else if (src.startsWith("\"\"\"", i)) {
                int end = i + 3;
                while (end < n && !src.startsWith("\"\"\"", end)) end += src.charAt(end) == '\\' ? 2 : 1;
                end = Math.min(n, end + 3);
                tokens.add(new Token(Kind.STRING, i, end));
                i = end;
            } else if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < n && src.charAt(end) != c && src.charAt(end) != '\n') end += src.charAt(end) == '\\' ? 2 : 1;
                end = Math.min(n, end + 1);
                tokens.add(new Token(c == '"' ? Kind.STRING : Kind.CHAR, i, end));
                i = end;
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int end = i + 1;
                while (end < n && (Character.isLetterOrDigit(src.charAt(end)) || src.charAt(end) == '_' || src.charAt(end) == '$')) end++;
                tokens.add(new Token(Kind.WORD, i, end));
                i = end;
            } else {
                tokens.add(new Token(Kind.PUNCT, i, i + 1));
                i++;
            }
        }
        return tokens;
    }

    // ---- parser ----

    /** What comes before a declaration's keyword or type: its comment, annotations and modifiers. */
    private record Prefix(String comment, List<String> annotations, Set<Modifier> modifiers, Visibility visibility, int startOffset) {
    }

    private static final class Parser {
        private final String original; // the text as it was given, kept in the types
        private final String src;      // the same with \n line ends: the tokens' offsets and the lines are counted in it
        private final List<Token> tokens;
        private final int[] lineStarts;
        private int pos;

        Parser(String original, String src) {
            this.original = original;
            this.src = src;
            this.tokens = tokenize(src);
            List<Integer> starts = new ArrayList<>(List.of(0));
            for (int i = src.indexOf('\n'); i >= 0; i = src.indexOf('\n', i + 1)) starts.add(i + 1);
            this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        }

        /** The line (from 1) the character at {@code offset} is on. */
        private int lineAt(int offset) {
            int at = java.util.Arrays.binarySearch(lineStarts, offset);
            return at >= 0 ? at + 1 : -at - 1;
        }

        /** The line of the last token read: where the declaration just read ends. */
        private int lastLine() {
            return lineAt(tokens.get(pos - 1).end() - 1);
        }

        private int lineOf(Token t) {
            return lineAt(t.start());
        }

        // -- basics --

        private boolean atEnd() {
            return pos >= tokens.size();
        }

        private Token peek() {
            return pos < tokens.size() ? tokens.get(pos) : null;
        }

        private Token peek(int ahead) {
            return pos + ahead < tokens.size() ? tokens.get(pos + ahead) : null;
        }

        private String text(Token t) {
            return t == null ? "" : src.substring(t.start(), t.end());
        }

        private boolean isPunct(Token t, char c) {
            return t != null && t.kind() == Kind.PUNCT && src.charAt(t.start()) == c;
        }

        private boolean isWord(Token t, String word) {
            return t != null && t.kind() == Kind.WORD && text(t).equals(word);
        }

        private IllegalArgumentException error(String message) {
            Token t = peek();
            int offset = t == null ? src.length() : t.start();
            int line = 1 + (int) src.substring(0, offset).chars().filter(ch -> ch == '\n').count();
            return new IllegalArgumentException("Line " + line + ": " + message);
        }

        private String word() {
            Token t = peek();
            if (t == null || t.kind() != Kind.WORD) throw error("a name was expected");
            pos++;
            return text(t);
        }

        private void expect(char c) {
            if (!isPunct(peek(), c)) throw error("'" + c + "' was expected");
            pos++;
        }

        /** The index of the token that closes the bracket opened at {@code open}; brackets of other kinds are skipped over. */
        private int matching(int open) {
            char opener = src.charAt(tokens.get(open).start());
            char closer = opener == '(' ? ')' : opener == '[' ? ']' : '}';
            int depth = 0;
            for (int i = open; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (t.kind() != Kind.PUNCT) continue;
                char c = src.charAt(t.start());
                if (c == opener) depth++;
                if (c == closer && --depth == 0) return i;
            }
            pos = open;
            throw error("'" + opener + "' is never closed");
        }

        /** The index of the '>' that closes the '<' at {@code open}. */
        private int matchingAngle(int open) {
            int depth = 0;
            for (int i = open; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (isPunct(t, '<')) depth++;
                if (isPunct(t, '>') && --depth == 0) return i;
            }
            pos = open;
            throw error("'<' is never closed");
        }

        /** The source between two tokens, with the whitespace collapsed to single spaces (for signatures). */
        private String collapsed(int fromToken, int toTokenExclusive) {
            if (fromToken >= toTokenExclusive) return "";
            return src.substring(tokens.get(fromToken).start(), tokens.get(toTokenExclusive - 1).end()).replaceAll("\\s+", " ").strip();
        }

        /** The source text from a token to another (inclusive), kept as written but without the declaration's own indentation. */
        private String raw(int fromToken, int toTokenInclusive) {
            Token first = tokens.get(fromToken);
            return raw(fromToken, toTokenInclusive, column(first.start()));
        }

        /** The same, without {@code column} blanks of indentation on the following lines (the declaration's own indentation). */
        private String raw(int fromToken, int toTokenInclusive, int column) {
            String text = src.substring(tokens.get(fromToken).start(), tokens.get(toTokenInclusive).end());
            return dedentFromColumn(text, column);
        }

        /** The indentation (blanks at the start) of the line the token is on. */
        private int lineIndent(Token t) {
            int lineStart = src.lastIndexOf('\n', t.start() - 1) + 1;
            int i = lineStart;
            while (i < src.length() && (src.charAt(i) == ' ' || src.charAt(i) == '\t')) i++;
            return i - lineStart;
        }

        private int column(int offset) {
            int lineStart = src.lastIndexOf('\n', offset - 1) + 1;
            return offset - lineStart;
        }

        // -- compilation unit --

        List<JavaClass> compilationUnit() {
            String pkg = "";
            List<String> imports = new ArrayList<>();
            List<JavaClass> types = new ArrayList<>();
            String header = "";
            Span headerLines = Span.NONE;
            Span packageLines = Span.NONE;
            List<Span> importLines = new ArrayList<>();
            while (!atEnd()) {
                if (isPunct(peek(), ';')) {
                    pos++;
                    continue;
                }
                Prefix prefix = prefix();
                if (atEnd()) break;
                if (isWord(peek(), "package")) {
                    header = prefix.comment();
                    headerLines = header.isEmpty() ? Span.NONE : new Span(lineAt(prefix.startOffset()), lineAt(tokens.get(pos - 1).end() - 1));
                    int first = lineOf(peek());
                    pos++;
                    pkg = until(';');
                    packageLines = new Span(first, lastLine());
                } else if (isWord(peek(), "import")) {
                    int first = lineOf(peek());
                    pos++;
                    imports.add(until(';'));
                    importLines.add(new Span(first, lastLine()));
                } else {
                    types.add(type(prefix, pkg, imports, header, headerLines, packageLines, importLines));
                    header = "";
                    headerLines = Span.NONE;
                }
            }
            return types;
        }

        /** The source up to the next ';' at this level, collapsed; the ';' is consumed. */
        private String until(char end) {
            int from = pos;
            while (!atEnd() && !isPunct(peek(), end)) pos++;
            if (atEnd()) throw error("'" + end + "' was expected");
            String text = collapsed(from, pos).replaceAll("\\s*\\.\\s*", ".");
            pos++;
            return text;
        }

        // -- declarations --

        private Prefix prefix() {
            int commentFrom = -1;
            int commentTo = -1;
            List<String> annotations = new ArrayList<>();
            Set<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
            Visibility visibility = Visibility.PACKAGE;
            int startOffset = atEnd() ? src.length() : peek().start();
            while (!atEnd()) {
                Token t = peek();
                if (t.kind() == Kind.COMMENT) {
                    if (!annotations.isEmpty() && modifiers.isEmpty() && visibility == Visibility.PACKAGE) {
                        // a comment between the annotations: on the line of the annotation before it, it stays with it; else it is a line
                        // of its own among them
                        Token before = tokens.get(pos - 1);
                        boolean sameLine = !src.substring(before.end(), t.start()).contains("\n");
                        if (sameLine && before.kind() != Kind.COMMENT) {
                            annotations.set(annotations.size() - 1, annotations.getLast() + " " + text(t));
                        } else {
                            annotations.add(dedentFromColumn(text(t), column(t.start())));
                        }
                    } else if (annotations.isEmpty() && modifiers.isEmpty() && visibility == Visibility.PACKAGE && !trailing(t)) {
                        if (commentFrom < 0) commentFrom = t.start();
                        commentTo = t.end();
                    }
                    pos++;
                } else if (isPunct(t, '@') && !isWord(peek(1), "interface")) {
                    int from = pos;
                    pos++;
                    word();
                    while (isPunct(peek(), '.')) {
                        pos++;
                        word();
                    }
                    if (isPunct(peek(), '(')) pos = matching(pos) + 1;
                    annotations.add(raw(from, pos - 1));
                } else if (t.kind() == Kind.WORD) {
                    String w = text(t);
                    if (w.equals("non") && isPunct(peek(1), '-') && isWord(peek(2), "sealed")) {
                        modifiers.add(Modifier.NON_SEALED);
                        pos += 3;
                        continue;
                    }
                    Visibility v = switch (w) {
                        case "public" -> Visibility.PUBLIC;
                        case "protected" -> Visibility.PROTECTED;
                        case "private" -> Visibility.PRIVATE;
                        default -> null;
                    };
                    if (v != null) {
                        visibility = v;
                        pos++;
                        continue;
                    }
                    Modifier m = modifierOf(w);
                    if (m == null) break;
                    modifiers.add(m);
                    pos++;
                } else {
                    break;
                }
            }
            String comment = commentFrom < 0 ? "" : dedentFromColumn(src.substring(commentFrom, commentTo), column(commentFrom));
            return new Prefix(comment, annotations, modifiers, visibility, startOffset);
        }

        /** The comments on the line of the member just read (or of the opening brace): they follow it in the model. */
        private void trailingComments(List<JavaMember> members) {
            while (!atEnd() && peek().kind() == Kind.COMMENT && trailing(peek())) {
                members.add(new JavaComment(text(peek()), true, lineOf(peek()), lineAt(peek().end() - 1)));
                pos++;
            }
        }

        /** Whether a comment token sits on the line of the token before it (a note after a member, not above the next one). */
        private boolean trailing(Token comment) {
            Token before = pos > 0 ? tokens.get(pos - 1) : null;
            return before != null && before.kind() == Kind.PUNCT && ";{},".indexOf(src.charAt(before.start())) >= 0
                    && !src.substring(before.end(), comment.start()).contains("\n");
        }

        private static Modifier modifierOf(String word) {
            return switch (word) {
                case "static" -> Modifier.STATIC;
                case "final" -> Modifier.FINAL;
                case "abstract" -> Modifier.ABSTRACT;
                case "synchronized" -> Modifier.SYNCHRONIZED;
                case "transient" -> Modifier.TRANSIENT;
                case "volatile" -> Modifier.VOLATILE;
                case "native" -> Modifier.NATIVE;
                case "strictfp" -> Modifier.STRICTFP;
                case "default" -> Modifier.DEFAULT;
                case "sealed" -> Modifier.SEALED;
                default -> null;
            };
        }

        private boolean atTypeKeyword() {
            Token t = peek();
            if (isPunct(t, '@') && isWord(peek(1), "interface")) return true;
            if (t == null || t.kind() != Kind.WORD) return false;
            return switch (text(t)) {
                case "class", "interface", "enum" -> true;
                case "record" -> peek(1) != null && peek(1).kind() == Kind.WORD && (isPunct(peek(2), '(') || isPunct(peek(2), '<'));
                default -> false;
            };
        }

        private JavaClass type(Prefix prefix, String pkg, List<String> imports, String header, Span headerLines, Span packageLines,
                               List<Span> importLines) {
            if (!atTypeKeyword()) throw error("a class, interface, enum or record was expected");
            JavaKind kind;
            if (isPunct(peek(), '@')) {
                pos += 2;
                kind = JavaKind.ANNOTATION;
            } else {
                kind = switch (word()) {
                    case "class" -> JavaKind.CLASS;
                    case "interface" -> JavaKind.INTERFACE;
                    case "enum" -> JavaKind.ENUM;
                    default -> JavaKind.RECORD;
                };
            }
            String name = word();
            String typeParameters = "";
            if (isPunct(peek(), '<')) {
                int close = matchingAngle(pos);
                typeParameters = collapsed(pos, close + 1);
                pos = close + 1;
            }
            List<JavaParameter> components = List.of();
            if (kind == JavaKind.RECORD) {
                if (!isPunct(peek(), '(')) throw error("the record components were expected");
                components = parameters(pos);
                pos = matching(pos) + 1;
            }
            String extendsClass = "";
            List<String> implementsInterfaces = new ArrayList<>();
            List<String> permits = new ArrayList<>();
            while (!atEnd() && !isPunct(peek(), '{')) {
                String clause = word();
                List<String> names = typeList();
                switch (clause) {
                    case "extends" -> {
                        if (kind == JavaKind.INTERFACE) implementsInterfaces.addAll(names);
                        else extendsClass = names.isEmpty() ? "" : names.getFirst();
                    }
                    case "implements" -> implementsInterfaces.addAll(names);
                    case "permits" -> permits.addAll(names);
                    default -> throw error("unexpected '" + clause + "' in the declaration of " + name);
                }
            }
            expect('{');

            List<JavaEnumConstant> constants = new ArrayList<>();
            List<JavaMember> members = new ArrayList<>();
            trailingComments(members);
            if (kind == JavaKind.ENUM) {
                enumConstants(constants);
                trailingComments(members);
            }
            while (!atEnd() && !isPunct(peek(), '}')) {
                if (isPunct(peek(), ';')) {
                    pos++;
                    continue;
                }
                member(name, kind, members);
                trailingComments(members);
            }
            expect('}');
            return new JavaClass(header, prefix.comment(), pkg, imports, prefix.annotations(), prefix.visibility(), prefix.modifiers(), kind,
                    name, typeParameters, extendsClass, implementsInterfaces, permits, components, constants, members, original,
                    lineAt(prefix.startOffset()), lastLine(), headerLines, packageLines, importLines);
        }

        /** A comma-separated list of types (generics kept as written) up to the next clause keyword or '{'. */
        private List<String> typeList() {
            List<String> names = new ArrayList<>();
            int from = pos;
            int depth = 0;
            while (!atEnd()) {
                Token t = peek();
                if (isPunct(t, '<')) depth++;
                if (isPunct(t, '>')) depth--;
                boolean clause = depth == 0 && t.kind() == Kind.WORD && Set.of("extends", "implements", "permits").contains(text(t))
                        && pos > from;
                if (isPunct(t, '{') || clause) break;
                if (isPunct(t, ',') && depth == 0) {
                    names.add(collapsed(from, pos));
                    from = pos + 1;
                }
                pos++;
            }
            if (pos > from) names.add(collapsed(from, pos));
            return names;
        }

        private void enumConstants(List<JavaEnumConstant> constants) {
            while (!atEnd() && !isPunct(peek(), '}') && !isPunct(peek(), ';')) {
                Prefix prefix = prefix();
                if (isPunct(peek(), '}') || isPunct(peek(), ';')) break;
                int nameToken = pos;
                String name = word();
                String arguments = "";
                if (isPunct(peek(), '(')) {
                    int close = matching(pos);
                    arguments = close == pos + 1 ? "" : raw(pos + 1, close - 1, lineIndent(tokens.get(nameToken))).strip();
                    pos = close + 1;
                }
                boolean hasBody = isPunct(peek(), '{');
                String body = hasBody ? braced() : "";
                constants.add(new JavaEnumConstant(prefix.comment(), prefix.annotations(), name, arguments, hasBody, body,
                        lineAt(prefix.startOffset()), lastLine()));
                if (isPunct(peek(), ',')) {
                    pos++;
                } else if (!isPunct(peek(), ';') && !isPunct(peek(), '}')) {
                    pos = nameToken;
                    throw error("',' or ';' was expected after the enum constant " + name);
                }
            }
            if (isPunct(peek(), ';')) pos++;
        }

        private void member(String className, JavaKind kind, List<JavaMember> members) {
            Prefix prefix = prefix();
            if (isPunct(peek(), '}')) { // comments at the end of the body
                if (!prefix.comment().isEmpty()) {
                    members.add(new JavaComment(prefix.comment(), false, lineAt(prefix.startOffset()), lastLine()));
                }
                return;
            }
            if (isPunct(peek(), '{')) {
                String block = braced();
                members.add(new JavaInitializer(prefix.comment(), prefix.modifiers().contains(Modifier.STATIC), block,
                        lineAt(prefix.startOffset()), lastLine()));
                return;
            }
            if (atTypeKeyword()) {
                members.add(type(prefix, "", List.of(), "", Span.NONE, Span.NONE, List.of()));
                return;
            }
            String typeParameters = "";
            if (isPunct(peek(), '<')) {
                int close = matchingAngle(pos);
                typeParameters = collapsed(pos, close + 1);
                pos = close + 1;
            }
            Token first = peek();
            if (isWord(first, className) && isPunct(peek(1), '(')) {
                pos++;
                int open = pos;
                List<JavaParameter> parameters = parameters(open);
                pos = matching(open) + 1;
                List<String> throwsTypes = throwsClause();
                String body = braced();
                members.add(new JavaConstructor(prefix.comment(), prefix.annotations(), prefix.visibility(), typeParameters,
                        parameters, throwsTypes, body, false, lineAt(prefix.startOffset()), lastLine()));
                return;
            }
            if (kind == JavaKind.RECORD && isWord(first, className) && isPunct(peek(1), '{')) {
                pos++;
                String body = braced();
                members.add(new JavaConstructor(prefix.comment(), prefix.annotations(), prefix.visibility(), "", List.of(),
                        List.of(), body, true, lineAt(prefix.startOffset()), lastLine()));
                return;
            }
            // A field or a method: the name is the last word before the first '(', '=', ';' or ',' outside of generics
            int typeFrom = pos;
            int depth = 0;
            int stop = pos;
            while (stop < tokens.size()) {
                Token t = tokens.get(stop);
                if (isPunct(t, '<')) depth++;
                else if (isPunct(t, '>')) depth--;
                else if (depth == 0 && (isPunct(t, '(') || isPunct(t, '=') || isPunct(t, ';') || isPunct(t, ','))) break;
                stop++;
            }
            if (stop >= tokens.size()) throw error("a field or method was expected");
            int nameToken = stop - 1;
            while (nameToken > typeFrom && (isPunct(tokens.get(nameToken), ']') || isPunct(tokens.get(nameToken), '['))) nameToken--;
            if (tokens.get(nameToken).kind() != Kind.WORD || nameToken == typeFrom) throw error("a type and a name were expected");
            String name = text(tokens.get(nameToken));
            String dims = collapsed(nameToken + 1, stop).replace(" ", "");
            String type = collapsed(typeFrom, nameToken) + dims;
            if (isPunct(tokens.get(stop), '(')) {
                pos = stop;
                List<JavaParameter> parameters = parameters(stop);
                pos = matching(stop) + 1;
                int dimsFrom = pos; // the old way to write an array result: int f()[] means int[] f()
                while (isPunct(peek(), '[') && isPunct(peek(1), ']')) pos += 2;
                type += "[]".repeat((pos - dimsFrom) / 2);
                List<String> throwsTypes = throwsClause();
                String code = "";
                String defaultValue = "";
                if (isPunct(peek(), '{')) {
                    code = braced();
                } else if (isWord(peek(), "default")) { // an annotation element
                    pos++;
                    int from = pos;
                    int nesting = 0;
                    while (!atEnd() && !(nesting == 0 && isPunct(peek(), ';'))) {
                        if (isPunct(peek(), '{') || isPunct(peek(), '(')) nesting++;
                        if (isPunct(peek(), '}') || isPunct(peek(), ')')) nesting--;
                        pos++;
                    }
                    if (pos == from) throw error("the default value is empty");
                    defaultValue = raw(from, pos - 1).strip();
                    expect(';');
                } else {
                    expect(';');
                }
                members.add(new JavaMethod(prefix.comment(), prefix.annotations(), prefix.visibility(), prefix.modifiers(),
                        typeParameters, type, name, parameters, throwsTypes, code, defaultValue, lineAt(prefix.startOffset()), lastLine()));
            } else {
                pos = stop;
                fieldDeclarators(prefix, collapsed(typeFrom, nameToken), name, dims, lineIndent(tokens.get(typeFrom)), members);
            }
        }

        /** The declarators of a field declaration, starting at the token after the first name (a '=', ',' or ';'). */
        private void fieldDeclarators(Prefix prefix, String baseType, String firstName, String firstDims, int indent,
                                      List<JavaMember> members) {
            String name = firstName;
            String dims = firstDims;
            String comment = prefix.comment();
            int startLine = lineAt(prefix.startOffset());
            while (true) {
                String value = "";
                if (isPunct(peek(), '=')) {
                    pos++;
                    int from = pos;
                    int end = initializerEnd();
                    if (end == from) throw error("the initializer is empty");
                    value = raw(from, end - 1, indent).strip();
                    pos = end;
                }
                members.add(new JavaField(comment, prefix.annotations(), prefix.visibility(), prefix.modifiers(), baseType + dims, name,
                        value, startLine, lineOf(peek())));
                comment = "";
                if (isPunct(peek(), ',')) {
                    pos++;
                    startLine = lineOf(peek());
                    name = word();
                    dims = "";
                    int from = pos;
                    while (isPunct(peek(), '[') || isPunct(peek(), ']')) pos++;
                    dims = collapsed(from, pos).replace(" ", "");
                    continue;
                }
                expect(';');
                return;
            }
        }

        /** The index of the ';' or of the ',' that starts the next declarator, after an initializer that starts at {@code pos}. */
        private int initializerEnd() {
            int depth = 0;
            for (int i = pos; i < tokens.size(); i++) {
                Token t = tokens.get(i);
                if (t.kind() != Kind.PUNCT) continue;
                char c = src.charAt(t.start());
                if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') depth--;
                else if (depth == 0 && c == ';') return i;
                else if (depth == 0 && c == ',' && nextDeclarator(i)) return i;
            }
            throw error("';' was expected at the end of the field");
        }

        /** Whether the comma at {@code i} separates declarators ({@code , other = 1}) and not generic arguments or a call. */
        private boolean nextDeclarator(int i) {
            Token name = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
            Token after = i + 2 < tokens.size() ? tokens.get(i + 2) : null;
            return name != null && name.kind() == Kind.WORD
                    && (isPunct(after, '=') || isPunct(after, ',') || isPunct(after, ';') || isPunct(after, '['));
        }

        /** The parameters of the list that opens at token {@code open}: type as written (collapsed) and name. */
        private List<JavaParameter> parameters(int open) {
            int close = matching(open);
            List<JavaParameter> parameters = new ArrayList<>();
            int from = open + 1;
            int depth = 0;
            for (int i = open + 1; i <= close; i++) {
                Token t = tokens.get(i);
                if (isPunct(t, '<') || isPunct(t, '(') || isPunct(t, '[') || isPunct(t, '{')) depth++;
                if (isPunct(t, '>') || isPunct(t, ']') || isPunct(t, '}') || (isPunct(t, ')') && i < close)) depth--;
                if ((isPunct(t, ',') && depth == 0) || i == close) {
                    if (i > from) parameters.add(parameter(from, i));
                    from = i + 1;
                }
            }
            return parameters;
        }

        private JavaParameter parameter(int from, int to) {
            int nameToken = to - 1;
            while (nameToken > from && (isPunct(tokens.get(nameToken), ']') || isPunct(tokens.get(nameToken), '['))) nameToken--;
            if (tokens.get(nameToken).kind() != Kind.WORD || nameToken == from) {
                pos = from;
                throw error("a parameter needs a type and a name");
            }
            return new JavaParameter(collapsed(from, nameToken) + collapsed(nameToken + 1, to).replace(" ", ""),
                    text(tokens.get(nameToken)));
        }

        private List<String> throwsClause() {
            if (!isWord(peek(), "throws")) return List.of();
            pos++;
            List<String> types = new ArrayList<>();
            int from = pos;
            while (!atEnd() && !isPunct(peek(), '{') && !isPunct(peek(), ';')) {
                if (isPunct(peek(), ',')) {
                    types.add(collapsed(from, pos));
                    from = pos + 1;
                }
                pos++;
            }
            types.add(collapsed(from, pos));
            return types;
        }

        /** A block at {@code pos}: its inner text, dedented; {@code pos} is after the closing brace. */
        private String braced() {
            if (!isPunct(peek(), '{')) throw error("'{' was expected");
            int close = matching(pos);
            Token open = tokens.get(pos);
            String inner = src.substring(open.end(), tokens.get(close).start());
            pos = close + 1;
            return dedent(inner);
        }
    }

    // ---- text helpers ----

    /** The block's lines without the first line when it is blank, the last when it is blank and their common indentation. */
    static String dedent(String inner) {
        List<String> lines = new ArrayList<>(List.of(inner.split("\n", -1)));
        if (!lines.isEmpty() && lines.getFirst().isBlank()) lines.removeFirst();
        if (!lines.isEmpty() && lines.getLast().isBlank()) lines.removeLast();
        int common = Integer.MAX_VALUE;
        for (String line : lines) {
            if (line.isBlank()) continue;
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) indent++;
            common = Math.min(common, indent);
        }
        if (common == Integer.MAX_VALUE) return "";
        List<String> out = new ArrayList<>();
        for (String line : lines) out.add(line.isBlank() ? "" : line.substring(Math.min(common, line.length())));
        return String.join("\n", out).stripTrailing();
    }

    /**
     * The text without the first {@code column} blanks of every line but the first, or as many as the least indented of
     * those lines has when that is fewer: the same number from every line, so the text inside a text block keeps its
     * meaning (a text block's content is measured against its least indented line).
     */
    static String dedentFromColumn(String text, int column) {
        if (!text.contains("\n")) return text;
        String[] lines = text.split("\n", -1);
        int strip = column;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            int indent = 0;
            while (indent < lines[i].length() && (lines[i].charAt(indent) == ' ' || lines[i].charAt(indent) == '\t')) indent++;
            strip = Math.min(strip, indent);
        }
        List<String> out = new ArrayList<>();
        out.add(lines[0]);
        for (int i = 1; i < lines.length; i++) out.add(lines[i].isBlank() ? "" : lines[i].substring(strip));
        return String.join("\n", out);
    }
}
