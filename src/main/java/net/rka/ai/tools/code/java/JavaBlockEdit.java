package net.rka.ai.tools.code.java;

import net.rka.ai.tools.code.java.JavaBlocks.Body;
import net.rka.ai.tools.code.java.JavaBlocks.Located;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Changes one statement of a method body, or puts statements next to it, by its address ({@link JavaBlocks#locate}), and
 * checks the result against the structure before anything is written: the statement list it lands in must hold exactly the
 * statements it should (new code that lands outside an {@code if} without braces is caught here), a variable that only the
 * removed code declared must not be used any more, and no variable may be declared twice in one scope.
 */
public final class JavaBlockEdit {

    private JavaBlockEdit() {
    }

    /** What is done at the address. */
    public enum Op {
        /** The statement is replaced by the code. */
        REPLACE,
        /** The statement is removed. */
        DELETE,
        /** The code goes above the statement. */
        BEFORE,
        /** The code goes below the statement. */
        AFTER,
        /** The code goes first into the part (or the body) the address names. */
        START,
        /** The code goes last into the part (or the body). */
        END
    }

    /**
     * @param type       the type that holds the member
     * @param memberSpec the member, named like {@link JavaMembers#find}
     * @param address    the statement (or part), see {@link JavaBlocks#locate}; empty for the body
     * @param expect     what the statement at the address starts with (its first line, as javaBlocks shows it), or blank: when it is not
     *                   that, the statements have moved since the model looked, and nothing is changed
     * @param code       the new statements, for every operation but DELETE
     * @return what was done, in a sentence
     * @throws IllegalArgumentException when the address, the code or the result is not right (nothing is changed then)
     */
    public static String apply(JavaWorkspace workspace, JavaWorkspace.TypeInfo type, String memberSpec, String address, Op op, String expect, String code) {
        JavaClass root = type.type();
        JavaMembers.Found found = JavaMembers.find(root, memberSpec);
        Body body = JavaBlocks.bodyOf(root, found.member());
        if (body.span().isNone()) throw new IllegalArgumentException(JavaStructure.signature(found.member()) + " has no body");
        Located at = JavaBlocks.locate(body, address == null ? "" : address);
        if (at.statement() != null && expect != null && !expect.isBlank()
                && !normalized(at.statement().summary()).startsWith(normalized(expect))) {
            throw new IllegalArgumentException("Statement " + address + " is now `" + at.statement().summary() + "`, not `" + expect.strip()
                    + "`: the statements have moved. Read them again with javaBlocks, or use javaPatch, which finds its place by the lines around it");
        }
        boolean needsStatement = op == Op.REPLACE || op == Op.DELETE || op == Op.BEFORE || op == Op.AFTER;
        if (needsStatement && at.statement() == null) {
            throw new IllegalArgumentException(op.name().toLowerCase() + " needs the address of a statement, not of a part or the body: " + describe(at));
        }
        if (!needsStatement && at.statement() != null) {
            throw new IllegalArgumentException(op.name().toLowerCase() + " needs the address of a part or the body ('" + (address.isBlank() ? "" : address + "/body")
                    + "'), not of a statement");
        }
        List<Statement> written = List.of();
        if (op != Op.DELETE) {
            if (code == null || code.isBlank()) throw new IllegalArgumentException("code is empty");
            try {
                written = JavaBlocks.statementsOf(code);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("the code is not statements that read as Java (" + e.getMessage() + "). Write complete statements, braces included");
            }
            if (written.isEmpty()) throw new IllegalArgumentException("the code holds no statement");
        }

        int from;
        int to;
        String indent;
        switch (op) {
            case REPLACE, DELETE -> {
                from = at.statement().startLine();
                to = at.statement().endLine();
                indent = indentAt(root, from);
            }
            case BEFORE -> {
                from = at.statement().startLine();
                to = from - 1;
                indent = indentAt(root, from);
            }
            case AFTER -> {
                from = at.statement().endLine() + 1;
                to = from - 1;
                indent = indentAt(root, at.statement().startLine());
            }
            case START, END -> {
                List<Statement> inside = at.container();
                if (inside.isEmpty()) {
                    if (at.part() != null) {
                        throw new IllegalArgumentException("the part '" + at.part().name() + "' is empty: replace the statement around it instead");
                    }
                    if (body.span().start() == body.span().end()) throw new IllegalArgumentException("the body is on one line: replace the whole member instead");
                    from = body.span().end();
                    to = from - 1;
                    indent = indentAt(root, body.span().end()) + "    ";
                } else if (op == Op.START) {
                    from = inside.getFirst().startLine();
                    to = from - 1;
                    indent = indentAt(root, inside.getFirst().startLine());
                } else {
                    from = inside.getLast().endLine() + 1;
                    to = from - 1;
                    indent = indentAt(root, inside.getLast().startLine());
                }
            }
            default -> throw new IllegalStateException();
        }
        String text = op == Op.DELETE ? "" : JavaMembers.indented(code, indent);

        // the result, read again: the member's new body
        String before = root.source().replace("\r\n", "\n");
        String after = JavaStructure.splice(before, from, to, text);
        Body changed = bodyAfter(after, root, found, memberSpec);
        Located here = JavaBlocks.locate(changed, containerAddress(address == null ? "" : address, at));
        int expected = at.container().size() + switch (op) {
            case REPLACE -> written.size() - 1;
            case DELETE -> -1;
            default -> written.size();
        };
        if (here.container().size() != expected) {
            throw new IllegalArgumentException("the code would not become " + (op == Op.DELETE ? "" : written.size() + " statement(s) ") + "of "
                    + (at.part() == null ? "the body" : "the part '" + at.part().name() + "'") + ": there would be " + here.container().size()
                    + " there instead of " + expected + ". A statement without braces (an if or a loop without { }) takes only one statement: "
                    + "replace it with a block");
        }
        String variables = variableProblem(body, changed, from, found, root);
        if (variables != null) throw new IllegalArgumentException(variables);

        workspace.changeLines(type.path(), from, to, text, JavaStructure.Effect.BODY);
        return switch (op) {
            case REPLACE -> "Replaced statement " + address + " of " + JavaStructure.signature(found.member());
            case DELETE -> "Deleted statement " + address + " of " + JavaStructure.signature(found.member());
            case BEFORE -> "Inserted " + written.size() + " statement(s) before " + address + " in " + JavaStructure.signature(found.member());
            case AFTER -> "Inserted " + written.size() + " statement(s) after " + address + " in " + JavaStructure.signature(found.member());
            default -> "Put " + written.size() + " statement(s) at the " + (op == Op.START ? "start" : "end") + " of "
                    + (address == null || address.isBlank() ? "the body" : address) + " in " + JavaStructure.signature(found.member());
        };
    }

    /** The member (method, constructor or initializer) of the text that holds the lines, with the type it is in. */
    private record Holder(JavaClass owner, JavaMember member) {
    }

    private static Holder holderOf(List<JavaClass> types, int from, int to) {
        List<JavaClass> all = new ArrayList<>();
        types.forEach(t -> flatten(t, all));
        Holder best = null;
        for (JavaClass type : all) {
            for (JavaMember m : type.members()) {
                if (!(m instanceof JavaMethod || m instanceof JavaConstructor || m instanceof JavaInitializer)) continue;
                if (m.startLine() <= from && to <= m.endLine() && (best == null || m.endLine() - m.startLine() < best.member().endLine() - best.member().startLine())) {
                    best = new Holder(type, m);
                }
            }
        }
        return best;
    }

    /**
     * What a change to the lines {@code from} to {@code to} of a Java file does wrong to the variables of the method it is in, or
     * null: a variable that only the changed code declared is still used, or a name is declared twice where both are visible.
     * Null too when the lines are not in a method, or the method can't be found again in the new text.
     *
     * @param before the file's text before the change
     * @param after  its text with the change
     */
    public static String memberProblem(String before, String after, int from, int to) {
        try {
            Holder old = holderOf(JavaSourceReader.read(before), from, to);
            if (old == null) return null;
            List<JavaClass> now = new ArrayList<>();
            JavaSourceReader.read(after).forEach(t -> flatten(t, now));
            JavaClass owner = now.stream().filter(t -> t.name().equals(old.owner().name()) && t.kind() == old.owner().kind())
                    .min(java.util.Comparator.comparingInt(t -> Math.abs(t.startLine() - old.owner().startLine()))).orElse(null);
            if (owner == null) return null;
            String spec = JavaMembers.spec(old.owner(), old.member());
            JavaMember member = JavaMembers.find(owner, spec).member();
            Body oldBody = JavaBlocks.bodyOf(old.owner(), old.member());
            Body newBody = JavaBlocks.bodyOf(owner, member);
            if (oldBody.span().isNone() || newBody.span().isNone()) return null;
            return variableProblem(oldBody, newBody, from, new JavaMembers.Found(old.owner(), old.member()), old.owner());
        } catch (RuntimeException e) {
            return null; // the structure check reports files that do not read; a member that can't be found again has nothing to compare
        }
    }

    /**
     * The variables a statement can use at a place in a method, as a sentence for a prompt: the parameters, the variables declared
     * above the place and still in scope, and the fields of the type. Empty when the line is not in a method of the text.
     *
     * @param line the line the new statements go before (a line of the method's body)
     */
    public static String visibleAt(String text, int line) {
        try {
            Holder holder = holderOf(JavaSourceReader.read(text), line, line);
            if (holder == null) return "";
            Body body = JavaBlocks.bodyOf(holder.owner(), holder.member());
            if (body.span().isNone()) return "";
            List<Variable> all = new ArrayList<>(body.parameters());
            collect(body.statements(), all);
            List<String> locals = all.stream().filter(v -> v.scope().start() <= line && line <= v.scope().end() && v.line() < line)
                    .map(v -> v.type() + " " + v.name()).distinct().toList();
            List<String> fields = holder.owner().fields().stream().map(f -> f.type() + " " + f.name()).toList();
            if (locals.isEmpty() && fields.isEmpty()) return "";
            return (locals.isEmpty() ? "" : "Variables in scope there: " + String.join(", ", locals) + ".")
                    + (fields.isEmpty() ? "" : (locals.isEmpty() ? "" : " ") + "Fields of " + holder.owner().name() + ": " + String.join(", ", fields) + ".");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String normalized(String line) {
        return line.replaceAll("\\s+", " ").strip();
    }

    private static String describe(Located at) {
        return at.part() == null ? "the body has " + at.container().size() + " statement(s)"
                : "the part '" + at.part().name() + "' has " + at.container().size() + " statement(s)";
    }

    private static String indentAt(JavaClass root, int line) {
        return JavaMembers.indentOf(root.lines(line, line));
    }

    /** The address of the list of statements the address is in: the part or the body. */
    private static String containerAddress(String address, Located at) {
        if (at.statement() == null) return address; // the address names the part or the body already
        int slash = address.lastIndexOf('/');
        return slash < 0 ? "" : address.substring(0, slash);
    }

    /** The body of the member in the text with the change, found again by the member's name. */
    private static Body bodyAfter(String text, JavaClass oldRoot, JavaMembers.Found found, String memberSpec) {
        List<JavaClass> types;
        try {
            types = JavaSourceReader.read(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the file would no longer be readable as Java (" + e.getMessage() + "). Nothing was changed");
        }
        List<JavaClass> candidates = new ArrayList<>();
        types.forEach(t -> flatten(t, candidates));
        JavaClass owner = candidates.stream().filter(t -> t.name().equals(found.owner().name()) && t.kind() == found.owner().kind())
                .min(java.util.Comparator.comparingInt(t -> Math.abs(t.startLine() - found.owner().startLine())))
                .orElseThrow(() -> new IllegalArgumentException("the change would make " + found.owner().name() + " disappear"));
        // the member by the same spec, without the part that names a nested type (the owner is already the innermost type)
        String simple = memberSpec.contains("(") ? memberSpec.substring(memberSpec.lastIndexOf('.', memberSpec.indexOf('(')) + 1) : memberSpec.substring(memberSpec.lastIndexOf('.') + 1);
        JavaMember member = JavaMembers.find(owner, simple).member();
        return JavaBlocks.bodyOf(owner, member);
    }

    private static void flatten(JavaClass type, List<JavaClass> out) {
        out.add(type);
        type.nestedClasses().forEach(n -> flatten(n, out));
    }

    /** What is wrong with the variables of the new body, or null. */
    private static String variableProblem(Body oldBody, Body newBody, int fromLine, JavaMembers.Found found, JavaClass root) {
        Set<String> declaredBefore = names(oldBody);
        Set<String> declaredAfter = names(newBody);
        Set<String> fields = found.owner().fields().stream().map(JavaField::name).collect(Collectors.toSet());
        Set<String> lost = new LinkedHashSet<>(declaredBefore);
        lost.removeAll(declaredAfter);
        lost.removeAll(fields);
        for (Statement s : JavaBlocks.flatten(newBody)) {
            for (String name : s.reads()) {
                if (lost.contains(name)) {
                    return "'" + name + "' was declared by the code that is removed, but line " + s.startLine() + " (" + s.summary() + ") still uses it."
                            + " Declare it in the new code, or change that statement too. Nothing was changed";
                }
            }
        }
        List<Variable> all = new ArrayList<>(newBody.parameters());
        collect(newBody.statements(), all);
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                Variable a = all.get(i);
                Variable b = all.get(j);
                if (a.name().equals(b.name()) && a.line() != b.line() && a.scope().start() <= b.scope().end() && b.scope().start() <= a.scope().end()) {
                    return "'" + a.name() + "' would be declared twice where both are visible (lines " + Math.min(a.line(), b.line()) + " and "
                            + Math.max(a.line(), b.line()) + "). Nothing was changed";
                }
            }
        }
        return null;
    }

    private static Set<String> names(Body body) {
        List<Variable> all = new ArrayList<>(body.parameters());
        collect(body.statements(), all);
        return all.stream().map(Variable::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static void collect(List<Statement> statements, List<Variable> out) {
        for (Statement s : statements) {
            for (Variable v : s.declares()) if (out.stream().noneMatch(o -> o.name().equals(v.name()) && o.line() == v.line())) out.add(v);
            for (Part part : s.parts()) collect(part.statements(), out);
        }
    }
}
