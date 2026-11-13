package net.rka.ai.tools.code.java;

import net.rka.ai.tools.code.java.JavaSourceReader.Tok;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Who calls what in a Java project: the methods, constructors and initializers of every type of the {@link JavaWorkspace} with
 * the members their code calls, found by reading the code with the types the workspace knows.
 * <p>
 * A call is resolved from what the code says about its receiver: a variable, parameter or field with a declared type, a type
 * name (a static call), {@code this}, {@code new T(...)}, or the result of an earlier call in the chain
 * ({@code Agent.fromEnv(a).run(b)} follows the declared return type of {@code fromEnv}). A call on an interface or an abstract
 * method also reaches the methods of the same name and parameter count in the project's subtypes. What is not followed: calls on
 * values whose type is not declared (a {@code var} from a call, a lambda parameter), on types outside the project (the JDK,
 * libraries), and reflection. They are counted ({@link #unresolvedCalls()}), so a caller can say how complete the graph is.
 * Members are named like {@link Reference#fromMember()}: {@code net.rka.Foo.bar(int,String)}, {@code net.rka.Foo.<init>(int)}.
 */
public final class JavaCallGraph {

    /** A method, constructor or initializer with the file and line it is at. */
    public record Member(String id, String type, String name, JavaMember member, String path) {
        public int line() {
            return member.startLine();
        }
    }

    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized", "return", "throw", "else",
            "do", "try", "case", "default", "instanceof", "assert", "import", "package", "class", "interface", "enum", "record", "extends",
            "implements", "throws", "final", "static", "public", "private", "protected", "abstract", "void", "int", "long", "double",
            "float", "boolean", "char", "byte", "short", "break", "continue", "yield", "new", "this", "super", "null", "true", "false");

    private final JavaWorkspace workspace;
    private final Map<String, Member> members = new LinkedHashMap<>();
    private final Map<String, Map<String, List<Member>>> byName = new HashMap<>(); // type → method name → methods
    private final Map<String, Set<String>> calls = new HashMap<>();
    private final Map<String, Set<String>> callers = new HashMap<>();
    private final Map<String, Set<String>> touched = new HashMap<>(); // member → types it creates or calls statically
    private final Map<String, List<String>> subtypes = new HashMap<>();
    private int unresolved;
    private int resolvedCalls;

    public JavaCallGraph(JavaWorkspace workspace) {
        this.workspace = workspace;
        List<JavaWorkspace.TypeInfo> all = workspace.allTypes();
        for (JavaWorkspace.TypeInfo info : all) register(info);
        for (JavaWorkspace.TypeInfo info : all) scanType(info);
    }

    // ---- the members ----

    private void register(JavaWorkspace.TypeInfo info) {
        JavaClass type = info.type();
        for (JavaMember m : type.members()) {
            switch (m) {
                case JavaMethod method -> {
                    Member member = new Member(info.qualifiedName() + "." + method.name() + "(" + JavaUses.erased(method.parameters()) + ")",
                            info.qualifiedName(), method.name(), method, info.path());
                    members.put(member.id(), member);
                    byName.computeIfAbsent(info.qualifiedName(), t -> new HashMap<>()).computeIfAbsent(method.name(), n -> new ArrayList<>()).add(member);
                }
                case JavaConstructor c -> {
                    Member member = new Member(info.qualifiedName() + ".<init>(" + JavaUses.erased(c.parameters()) + ")", info.qualifiedName(),
                            "<init>", c, info.path());
                    members.put(member.id(), member);
                    byName.computeIfAbsent(info.qualifiedName(), t -> new HashMap<>()).computeIfAbsent("<init>", n -> new ArrayList<>()).add(member);
                }
                case JavaInitializer i -> members.putIfAbsent(initId(info.qualifiedName(), i.isStatic()),
                        new Member(initId(info.qualifiedName(), i.isStatic()), info.qualifiedName(), i.isStatic() ? "<clinit>" : "<init>", i, info.path()));
                case JavaField f -> {
                    if (!f.value().isBlank()) {
                        members.putIfAbsent(initId(info.qualifiedName(), f.isStatic()), new Member(initId(info.qualifiedName(), f.isStatic()),
                                info.qualifiedName(), f.isStatic() ? "<clinit>" : "<init>", f, info.path()));
                    }
                }
                default -> {
                }
            }
        }
    }

    private static String initId(String type, boolean isStatic) {
        return type + (isStatic ? ".<clinit>" : ".<init>");
    }

    // ---- reading the code ----

    private void scanType(JavaWorkspace.TypeInfo info) {
        String type = info.qualifiedName();
        for (JavaMember m : info.type().members()) {
            switch (m) {
                case JavaMethod method -> scan(type, type + "." + method.name() + "(" + JavaUses.erased(method.parameters()) + ")", method.parameters(), method.code());
                case JavaConstructor c -> scan(type, type + ".<init>(" + JavaUses.erased(c.parameters()) + ")", c.parameters(), c.code());
                case JavaInitializer i -> scan(type, initId(type, i.isStatic()), List.of(), i.code());
                case JavaField f -> scan(type, initId(type, f.isStatic()), List.of(), f.value());
                default -> {
                }
            }
        }
    }

    /** One piece of code, in the type {@code type}, as the member {@code from}. */
    private void scan(String type, String from, List<JavaParameter> parameters, String code) {
        if (code == null || code.isBlank()) return;
        List<Tok> t = JavaSourceReader.tokens(code);
        Map<String, String> vars = new HashMap<>();
        for (JavaParameter p : parameters) vars.put(p.name(), p.type());
        declarations(t, vars);
        for (int i = 0; i < t.size(); i++) {
            Tok tok = t.get(i);
            if (!tok.kind().equals("word") || Character.isDigit(tok.text().charAt(0))) continue;
            if (i > 0 && (t.get(i - 1).text().equals(".") || t.get(i - 1).text().equals("@"))) continue;
            if (KEYWORDS.contains(tok.text()) && !Set.of("new", "this", "super").contains(tok.text())) continue;
            chain(new Ctx(type, from, t, vars), i);
        }
    }

    private record Ctx(String type, String from, List<Tok> t, Map<String, String> vars) {
    }

    /** The local variables of a piece of code with their declared types: {@code Ledger ledger =}, {@code Map<A,B> m;}, {@code var x = new T()}. */
    private static void declarations(List<Tok> t, Map<String, String> vars) {
        for (int i = 0; i + 1 < t.size(); i++) {
            if (!t.get(i).kind().equals("word") || KEYWORDS.contains(t.get(i).text()) && !t.get(i).text().equals("final")) continue;
            if (i > 0 && (t.get(i - 1).text().equals(".") || t.get(i - 1).text().equals("new"))) continue;
            int p = i + 1;
            while (p + 1 < t.size() && t.get(p).text().equals(".") && t.get(p + 1).kind().equals("word")) p += 2; // a.b.Type
            if (p < t.size() && t.get(p).text().equals("<")) {
                int close = closing(t, p, "<", ">");
                if (close < 0) continue;
                p = close + 1;
            }
            while (p + 1 < t.size() && t.get(p).text().equals("[") && t.get(p + 1).text().equals("]")) p += 2;
            if (p + 1 >= t.size() || !t.get(p).kind().equals("word") || KEYWORDS.contains(t.get(p).text())) continue;
            String after = t.get(p + 1).text();
            if (!Set.of("=", ";", ",", ":", ")").contains(after)) continue;
            String type = join(t, i, p);
            if (type.equals("var")) {
                // var x = new T(...): the type of the new object
                type = after.equals("=") && p + 3 < t.size() && t.get(p + 2).text().equals("new") ? join(t, p + 3, nameEnd(t, p + 3)) : "";
            }
            vars.put(t.get(p).text(), type);
        }
    }

    private static int nameEnd(List<Tok> t, int from) {
        int p = from + 1;
        while (p + 1 < t.size() && t.get(p).text().equals(".") && t.get(p + 1).kind().equals("word")) p += 2;
        return p;
    }

    private static String join(List<Tok> t, int from, int toExclusive) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < toExclusive; i++) out.append(t.get(i).text());
        return out.toString();
    }

    private static int closing(List<Tok> t, int open, String opener, String closer) {
        int depth = 0;
        for (int i = open; i < t.size(); i++) {
            if (t.get(i).text().equals(opener)) depth++;
            else if (t.get(i).text().equals(closer) && --depth == 0) return i;
            else if (t.get(i).text().equals(";") || t.get(i).text().equals("{")) return -1;
        }
        return -1;
    }

    /** The index of the ')' (or ']') that closes the bracket at {@code open}. */
    private static int matching(List<Tok> t, int open) {
        String opener = t.get(open).text();
        String closer = opener.equals("(") ? ")" : opener.equals("[") ? "]" : "}";
        int depth = 0;
        for (int i = open; i < t.size(); i++) {
            String s = t.get(i).text();
            if (!t.get(i).kind().equals("punct")) continue;
            if (s.equals(opener)) depth++;
            if (s.equals(closer) && --depth == 0) return i;
        }
        return t.size() - 1;
    }

    private static int arity(List<Tok> t, int open, int close) {
        if (close == open + 1) return 0;
        int depth = 0;
        int count = 1;
        for (int i = open + 1; i < close; i++) {
            if (!t.get(i).kind().equals("punct")) continue;
            String s = t.get(i).text();
            if (s.equals("(") || s.equals("[") || s.equals("{") || s.equals("<")) depth++;
            if (s.equals(")") || s.equals("]") || s.equals("}") || s.equals(">")) depth--;
            if (s.equals(",") && depth == 0) count++;
        }
        return count;
    }

    /** Reads the chain that starts at token {@code i}: {@code a.b(x).c}, {@code new T(x).run()}, {@code Type::name}, {@code name(x)}. */
    private void chain(Ctx c, int i) {
        List<Tok> t = c.t();
        String cur = null;
        int p = i;
        String first = t.get(p).text();
        switch (first) {
            case "new" -> {
                int end = nameEnd(t, p + 1);
                String name = p + 1 < t.size() && t.get(p + 1).kind().equals("word") ? join(t, p + 1, end) : "";
                p = end;
                if (p < t.size() && t.get(p).text().equals("<")) {
                    int close = closing(t, p, "<", ">");
                    if (close > 0) p = close + 1;
                }
                Optional<String> type = name.isEmpty() ? Optional.empty() : workspace.resolveType(c.type(), name);
                if (type.isPresent() && p < t.size() && t.get(p).text().equals("(")) {
                    int close = matching(t, p);
                    constructorCall(c, type.get(), arity(t, p, close));
                    cur = type.get();
                    p = close + 1;
                } else {
                    return; // an array, or a type of the JDK
                }
            }
            case "this" -> {
                cur = c.type();
                p++;
            }
            case "super" -> {
                cur = workspace.supertypesOf(c.type()).stream().findFirst().orElse(null);
                p++;
            }
            default -> {
                if (p + 1 < t.size() && t.get(p + 1).text().equals("(")) { // name(...): a method of this type, its supertypes or outer types
                    int close = matching(t, p + 1);
                    cur = call(c, scopeOrder(c.type()), first, arity(t, p + 1, close));
                    p = close + 1;
                } else if (c.vars().containsKey(first)) {
                    cur = workspace.resolveType(c.type(), c.vars().get(first)).orElse(null);
                    p++;
                } else if (fieldType(c.type(), first).isPresent()) {
                    cur = fieldType(c.type(), first).get().orElse(null);
                    p++;
                } else {
                    Optional<String> type = workspace.resolveType(c.type(), first);
                    if (type.isPresent()) {
                        cur = type.get();
                        touch(c, cur);
                    }
                    p++;
                }
            }
        }
        while (p < t.size()) {
            String s = t.get(p).text();
            if (s.equals("[")) { // an element of an array: its type is not followed
                p = matching(t, p) + 1;
                cur = null;
            } else if (s.equals(".") && p + 1 < t.size() && t.get(p + 1).kind().equals("word")) {
                String name = t.get(p + 1).text();
                p += 2;
                if (p < t.size() && t.get(p).text().equals("(")) {
                    int close = matching(t, p);
                    cur = cur == null ? unresolved() : call(c, supertypeOrder(cur), name, arity(t, p, close));
                    p = close + 1;
                } else if (cur != null) {
                    String nested = cur + "." + name;
                    Optional<Optional<String>> field = fieldType(cur, name);
                    cur = field.isPresent() ? field.get().orElse(null) : workspace.type(nested).isPresent() ? nested : null;
                }
            } else if (s.equals(":") && p + 2 < t.size() && t.get(p + 1).text().equals(":") && t.get(p + 2).kind().equals("word")) {
                String name = t.get(p + 2).text();
                if (cur != null) {
                    if (name.equals("new")) constructorCall(c, cur, -1);
                    else call(c, supertypeOrder(cur), name, -1);
                }
                return;
            } else {
                return;
            }
        }
    }

    private String unresolved() {
        unresolved++;
        return null;
    }

    private void touch(Ctx c, String type) {
        touched.computeIfAbsent(c.from(), f -> new TreeSet<>()).add(type);
    }

    private void constructorCall(Ctx c, String type, int arity) {
        touch(c, type);
        List<Member> ctors = byName.getOrDefault(type, Map.of()).getOrDefault("<init>", List.of());
        List<Member> fit = ctors.stream().filter(m -> arity < 0 || ((JavaConstructor) m.member()).parameters().size() == arity).toList();
        for (Member m : fit.isEmpty() ? ctors : fit) edge(c.from(), m.id());
        if (!ctors.isEmpty()) resolvedCalls++;
    }

    /**
     * The calls of the methods named {@code name} in the first of {@code order} that has some (with the parameter count when there
     * are several): edges to them and to what overrides them. Returns the type the call gives back (a type of the project), or null.
     */
    private String call(Ctx c, List<String> order, String name, int arity) {
        for (String type : order) {
            List<Member> named = byName.getOrDefault(type, Map.of()).getOrDefault(name, List.of());
            if (named.isEmpty()) continue;
            List<Member> fit = named.stream().filter(m -> arity < 0 || ((JavaMethod) m.member()).parameters().size() == arity).toList();
            List<Member> picked = fit.isEmpty() ? named : fit;
            for (Member m : picked) {
                edge(c.from(), m.id());
                overriders(c.from(), m);
            }
            resolvedCalls++;
            touch(c, type);
            JavaMethod method = (JavaMethod) picked.getFirst().member();
            return workspace.resolveType(type, method.returnType()).orElse(null);
        }
        if (!order.isEmpty() && workspace.type(order.getFirst()).isPresent()) unresolved++; // a project type whose method is not in the project
        return null;
    }

    /** A call of an interface method or an abstract one also reaches the same method in every subtype of the project. */
    private void overriders(String from, Member m) {
        JavaWorkspace.TypeInfo owner = workspace.type(m.type()).orElse(null);
        if (owner == null) return;
        JavaMethod method = (JavaMethod) m.member();
        boolean open = owner.type().kind() == JavaKind.INTERFACE || owner.type().isAbstract() || method.isAbstract();
        if (!open) return;
        for (String sub : allSubtypes(m.type())) {
            for (Member candidate : byName.getOrDefault(sub, Map.of()).getOrDefault(m.name(), List.of())) {
                if (((JavaMethod) candidate.member()).parameters().size() == method.parameters().size()) edge(from, candidate.id());
            }
        }
    }

    private List<String> allSubtypes(String type) {
        return subtypes.computeIfAbsent(type, q -> {
            Set<String> out = new LinkedHashSet<>();
            Deque<String> todo = new ArrayDeque<>(workspace.subtypesOf(q));
            while (!todo.isEmpty()) {
                String next = todo.poll();
                if (out.add(next)) todo.addAll(workspace.subtypesOf(next));
            }
            return List.copyOf(out);
        });
    }

    private void edge(String from, String to) {
        if (from.equals(to)) return;
        calls.computeIfAbsent(from, f -> new TreeSet<>()).add(to);
        callers.computeIfAbsent(to, f -> new TreeSet<>()).add(from);
    }

    /** The type and its supertypes, nearest first: where a method called on a value of the type can be declared. */
    private List<String> supertypeOrder(String type) {
        Set<String> out = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>(List.of(type));
        while (!todo.isEmpty()) {
            String next = todo.poll();
            if (out.add(next)) todo.addAll(workspace.supertypesOf(next));
        }
        return List.copyOf(out);
    }

    /** Where an unqualified name can be declared: the type and its supertypes, then each outer type and its supertypes. */
    private List<String> scopeOrder(String type) {
        Set<String> out = new LinkedHashSet<>();
        for (String t = type; t != null; t = outerOf(t)) out.addAll(supertypeOrder(t));
        return List.copyOf(out);
    }

    private String outerOf(String type) {
        int dot = type.lastIndexOf('.');
        while (dot > 0) {
            String outer = type.substring(0, dot);
            if (workspace.type(outer).isPresent()) return outer;
            dot = outer.lastIndexOf('.');
        }
        return null;
    }

    /** The declared type of the field of that name in the type, its supertypes or outer types: the type if it is a project type. */
    private Optional<Optional<String>> fieldType(String type, String name) {
        for (String t : scopeOrder(type)) {
            Optional<JavaWorkspace.TypeInfo> info = workspace.type(t);
            if (info.isEmpty()) continue;
            for (JavaField f : info.get().type().fields()) {
                if (f.name().equals(name)) return Optional.of(workspace.resolveType(t, f.type()));
            }
            for (JavaEnumConstant e : info.get().type().enumConstants()) {
                if (e.name().equals(name)) return Optional.of(Optional.of(t));
            }
        }
        return Optional.empty();
    }

    // ---- questions ----

    public Optional<Member> member(String id) {
        return Optional.ofNullable(members.get(id));
    }

    /** The members of a type (methods, constructors, initializers), in source order. */
    public List<Member> membersOf(String type) {
        return members.values().stream().filter(m -> m.type().equals(type)).toList();
    }

    /** The members that start a program: {@code public static void main(String[])}. */
    public List<Member> entryPoints() {
        return members.values().stream().filter(m -> m.member() instanceof JavaMethod method && method.name().equals("main") && method.isStatic()
                && method.parameters().size() == 1 && method.parameters().getFirst().type().replace(" ", "").matches("String(\\[\\]|\\.\\.\\.)")).toList();
    }

    public Set<String> callsFrom(String id) {
        return calls.getOrDefault(id, Set.of());
    }

    public Set<String> callersOf(String id) {
        return callers.getOrDefault(id, Set.of());
    }

    /** The types a member creates or calls statically or through a value, besides those of its calls. */
    public Set<String> typesTouchedBy(String id) {
        return touched.getOrDefault(id, Set.of());
    }

    /** Calls on a type of the project that could not be matched to a method, and calls on values of unknown type. */
    public int unresolvedCalls() {
        return unresolved;
    }

    public int resolvedCalls() {
        return resolvedCalls;
    }

    // ---- showing it ----

    /**
     * The calls from the roots (or to them, with {@code callers}) as an indented tree, {@code depth} levels deep and at most
     * {@code maxLines} lines; a member that was shown already is marked ↺ and not followed again.
     */
    public String render(List<Member> roots, int depth, boolean callersOf, int maxLines) {
        StringBuilder out = new StringBuilder();
        Set<String> shown = new HashSet<>();
        int[] lines = {0};
        int[] cut = {0};
        for (Member root : roots) show(out, root.id(), 0, depth, callersOf, shown, lines, cut, maxLines);
        if (cut[0] > 0) out.append("… ").append(cut[0]).append(" more not shown; narrow it with from or a smaller depth\n");
        return out.toString().stripTrailing();
    }

    private void show(StringBuilder out, String id, int level, int depth, boolean upward, Set<String> shown, int[] lines, int[] cut, int max) {
        if (lines[0] >= max) {
            cut[0]++;
            return;
        }
        Member m = members.get(id);
        boolean seen = !shown.add(id);
        out.append("  ".repeat(level)).append(display(id, m)).append(m == null || seen ? "" : "  " + m.path() + ":" + m.line())
                .append(seen ? "  ↺" : "").append('\n');
        lines[0]++;
        if (seen || level >= depth) return;
        for (String next : upward ? callersOf(id) : callsFrom(id)) show(out, next, level + 1, depth, upward, shown, lines, cut, max);
    }

    /** {@code Outer.Inner.name(params)} without the package. */
    private String display(String id, Member m) {
        if (m == null) return id;
        String type = m.type();
        List<JavaClass> top = workspace.typesIn(m.path());
        String pkg = top.isEmpty() ? "" : top.getFirst().packageName();
        String shortType = pkg.isEmpty() || !type.startsWith(pkg + ".") ? type : type.substring(pkg.length() + 1);
        return shortType + id.substring(type.length());
    }

    /** The members reachable from the roots through calls, with the types they belong to. */
    public Set<String> reachableFrom(List<Member> roots) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        roots.forEach(r -> todo.add(r.id()));
        while (!todo.isEmpty()) {
            String next = todo.poll();
            if (seen.add(next)) todo.addAll(callsFrom(next));
        }
        return seen;
    }
}
