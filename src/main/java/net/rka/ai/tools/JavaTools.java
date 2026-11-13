package net.rka.ai.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationParameters;
import net.rka.ai.tools.code.java.JavaBlocks;
import net.rka.ai.tools.code.java.JavaClass;
import net.rka.ai.tools.code.java.JavaEnumConstant;
import net.rka.ai.tools.code.java.JavaMember;
import net.rka.ai.tools.code.java.JavaMembers;
import net.rka.ai.tools.code.java.JavaStructure;
import net.rka.ai.tools.code.java.Span;
import net.rka.ai.tools.code.java.JavaWorkspace;
import net.rka.ai.tools.code.java.JavaWorkspaces;
import net.rka.ai.tools.code.java.Reference;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Questions about the Java project that the file tools can't answer, and the control of its changes in memory. In a Java
 * project all {@code .java} files are held in a {@link JavaWorkspace}: {@code javaFind} and {@code javaUsages} use its
 * indexes (a lookup, not a search through files), and {@code outline} / {@code readSection} / {@code editSection} read and
 * change the workspace's text ({@link OutlineTools}), so an edit is not written to the file until {@code javaFlush} (which
 * also runs before every command and at the end of the turn).
 */
public class JavaTools {

    private static final int MAX_LINES = 60;

    private final JavaWorkspaces workspaces;

    public JavaTools() {
        this(JavaWorkspaces.shared());
    }

    public JavaTools(JavaWorkspaces workspaces) {
        this.workspaces = workspaces;
    }

    private JavaWorkspace workspace(InvocationParameters parameters) {
        Path folder = WorkFolder.of(parameters);
        return workspaces.workspace(folder).orElseThrow(() -> new IllegalArgumentException("The working folder has no Java files"));
    }

    @Tool("Summary of the Java project in the working folder: the packages with their types, and the files that have changes "
            + "in memory which are not written yet")
    public String javaProject(InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        StringBuilder out = new StringBuilder();
        var byPackage = workspace.allTypes().stream().filter(t -> !isNested(workspace, t))
                .collect(Collectors.groupingBy(t -> packageOf(t.qualifiedName(), t.type()), java.util.TreeMap::new, Collectors.toList()));
        byPackage.forEach((pkg, list) -> out.append(pkg.isEmpty() ? "(default package)" : pkg).append(": ")
                .append(list.stream().map(t -> t.type().name()).collect(Collectors.joining(", "))).append('\n'));
        List<String> dirty = workspace.dirtyPaths();
        out.append(workspace.paths().size()).append(" Java files; ");
        out.append(dirty.isEmpty() ? "no changes in memory." : "changes in memory, not written yet: " + String.join(", ", dirty));
        List<String> conflicts = workspace.conflictPaths();
        if (!conflicts.isEmpty()) out.append("\nChanged on disk meanwhile (conflict): ").append(String.join(", ", conflicts));
        return out.toString();
    }

    @Tool("Finds a type of the Java project by simple name, qualified name or the end of it (Ledger, agent.Ledger, "
            + "net.rka.ai.agent.Ledger) and shows its file, kind, supertypes and members with their signatures. Nested types are "
            + "found as Outer.Inner")
    public String javaFind(@P("the type's name") String name, InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        List<JavaWorkspace.TypeInfo> found = workspace.findAll(name);
        if (found.isEmpty()) {
            List<String> similar = workspace.allTypes().stream().map(JavaWorkspace.TypeInfo::qualifiedName)
                    .filter(q -> q.toLowerCase().contains(name.toLowerCase())).limit(10).toList();
            return "No type " + name + " in the project." + (similar.isEmpty() ? "" : " Similar: " + String.join(", ", similar));
        }
        StringBuilder out = new StringBuilder();
        for (JavaWorkspace.TypeInfo info : found.subList(0, Math.min(found.size(), 5))) {
            JavaClass type = info.type();
            out.append(info.qualifiedName()).append(" · ").append(type.kind().keyword()).append(" · ").append(info.path()).append('\n');
            if (!type.extendsClass().isEmpty()) out.append("  extends ").append(type.extendsClass()).append('\n');
            if (!type.implementsInterfaces().isEmpty()) out.append("  implements/extends ").append(String.join(", ", type.implementsInterfaces())).append('\n');
            for (var c : type.enumConstants()) out.append("  constant ").append(c.name()).append('\n');
            for (JavaMember member : type.members()) {
                String line = signature(member);
                if (!line.isEmpty()) out.append("  ").append(line).append('\n');
            }
        }
        if (found.size() > 5) out.append("… ").append(found.size() - 5).append(" more types match");
        return out.toString().strip();
    }

    @Tool("Where a type of the Java project is used: the members of other types that name it in their signature or code, "
            + "and the types that extend or implement it. With member, where that member is called: certain when written as "
            + "Type.member, and by name for calls through a variable. Use it before you change or remove something to see what "
            + "else is affected")
    public String javaUsages(@P("the type's name (simple or qualified)") String type,
                             @P(value = "a method or field of the type", required = false) String member,
                             InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        Optional<JavaWorkspace.TypeInfo> info = workspace.find(type);
        if (info.isEmpty()) {
            List<JavaWorkspace.TypeInfo> all = workspace.findAll(type);
            return all.isEmpty() ? "No type " + type + " in the project."
                    : type + " is ambiguous: " + all.stream().map(JavaWorkspace.TypeInfo::qualifiedName).collect(Collectors.joining(", "));
        }
        String qualified = info.get().qualifiedName();
        StringBuilder out = new StringBuilder();
        if (member != null && !member.isBlank()) {
            List<String> callers = workspace.possibleCallers(qualified, member.strip());
            out.append(callers.isEmpty() ? "No use of " + qualified + "." + member.strip() + " found."
                    : lines(callers, qualified + "." + member.strip() + " may be used by"));
            return out.toString();
        }
        List<Reference> usages = workspace.usagesOf(qualified).stream().filter(r -> !r.fromType().equals(qualified)).toList();
        if (usages.isEmpty()) return qualified + " is not used by any other type of the project.";
        out.append(qualified).append(" is used by ").append(workspace.usersOf(qualified).size()).append(" type(s):\n");
        List<String> rows = usages.stream().map(r -> r.kind().name().toLowerCase() + " · " + (r.fromMember().isEmpty() ? r.fromType() : r.fromMember())
                + (r.toMember().isEmpty() ? "" : " → ." + r.toMember())).distinct().toList();
        return out + lines(rows, null);
    }

    @WritesFiles
    @Tool("Writes the Java changes held in memory (edits made with editSection or writeFile to .java files) to the files. "
            + "This happens by itself before every command and at the end of the turn; call it to write earlier. force true "
            + "writes over a file that changed on disk meanwhile")
    public String javaFlush(@P(value = "true to overwrite files that changed on disk meanwhile", required = false) Boolean force,
                            InvocationParameters parameters) {
        JavaWorkspace.FlushResult result = workspace(parameters).flush(Boolean.TRUE.equals(force));
        return "Wrote " + result.written().size() + " file(s)" + (result.written().isEmpty() ? "" : ": " + String.join(", ", result.written()))
                + (result.deleted().isEmpty() ? "" : "; deleted " + String.join(", ", result.deleted()))
                + (result.conflicts().isEmpty() ? "" : ". NOT written, changed on disk meanwhile: " + String.join(", ", result.conflicts()));
    }

    @WritesFiles
    @Tool("Throws the Java changes held in memory away: the files are read again as they are on disk")
    public String javaDiscard(InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        List<String> dirty = workspace.dirtyPaths();
        workspace.discardAll();
        return dirty.isEmpty() ? "There were no changes in memory." : "Discarded the changes in memory of " + String.join(", ", dirty);
    }

    // ---- create, read, update, delete: members, imports and files, through the structure ----

    private static final int MAX_READ_CHARS = 24_000;
    private static final String IN_MEMORY = " (in memory; written before the next command and at the end of the turn)";

    private static JavaWorkspace.TypeInfo typeNamed(JavaWorkspace workspace, String name) {
        List<JavaWorkspace.TypeInfo> found = workspace.findAll(name);
        if (found.isEmpty()) throw new IllegalArgumentException("No type " + name + " in the project. Create it with javaCreate.");
        if (found.size() > 1) {
            throw new IllegalArgumentException(name + " is ambiguous, use the qualified name: "
                    + found.stream().map(JavaWorkspace.TypeInfo::qualifiedName).collect(Collectors.joining(", ")));
        }
        return found.getFirst();
    }

    @Tool("Reads a type of the Java project, or one member of it, exactly as it is in the file (with the changes made in memory): "
            + "member is a field or method name, name(int,String) to pick an overload, <init> for a constructor, {} or static{} for "
            + "an initializer block, Inner.name for a member of a nested type. Without member the whole type is read")
    public String javaRead(@P("the type's name, e.g. Ledger or net.rka.ai.agent.Ledger") String type,
                           @P(value = "the member, or empty for the whole type", required = false) String member,
                           InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        JavaWorkspace.TypeInfo info = typeNamed(workspace, type);
        JavaMember target = member == null || member.isBlank() ? info.type() : JavaMembers.find(info.type(), member).member();
        String text = info.type().lines(target);
        String head = "[" + info.path() + " · " + (target == info.type() ? info.type().name() : JavaStructure.signature(target)) + " · lines "
                + target.startLine() + "–" + target.endLine() + "]\n";
        return head + (text.length() > MAX_READ_CHARS ? text.substring(0, MAX_READ_CHARS) + "\n… cut; read a member of it" : text);
    }

    @WritesFiles
    @Tool("Adds complete members to a type of the Java project: fields, methods, constructors, initializer blocks or nested types, "
            + "written with their comments and annotations, at the end of the type. Write only what is new, never the code around it. "
            + "The result must still be valid Java and declare nothing twice, else nothing changes and you are told why")
    public String javaAdd(@P("the type's name, e.g. Ledger") String type,
                          @P("the new member(s), complete: signature, braces and all") String code,
                          InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        JavaWorkspace.TypeInfo info = typeNamed(workspace, type);
        JavaClass owner = info.type();
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code is empty");
        if (owner.startLine() == owner.endLine()) {
            throw new IllegalArgumentException(owner.name() + " is written on one line, so there is no place inside it: replace the type with javaReplace");
        }
        String indent = memberIndent(owner);
        boolean gap = !owner.members().isEmpty() || !owner.enumConstants().isEmpty();
        String text = (gap && !blankLine(owner, owner.endLine() - 1) ? "\n" : "") + JavaMembers.indented(code, indent);
        workspace.changeLines(info.path(), owner.endLine(), owner.endLine() - 1, text, JavaStructure.Effect.ADDS);
        return "Added to " + owner.name() + " in " + info.path() + IN_MEMORY + ".";
    }

    @WritesFiles
    @Tool("Replaces one member of a type of the Java project with its complete new text (comment and annotations included; "
            + "empty code deletes it). Name the member like javaRead does. Write only that member, never the code around it. The "
            + "result must still be valid Java and declare nothing twice, else nothing changes and you are told why")
    public String javaReplace(@P("the type's name, e.g. Ledger") String type,
                              @P("the member: name, name(int,String), <init>, {} or static{}, Inner.name") String member,
                              @P("the member's complete new text") String code,
                              InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        JavaWorkspace.TypeInfo info = typeNamed(workspace, type);
        JavaMembers.Found found = JavaMembers.find(info.type(), member);
        if (code == null || code.isBlank()) return delete(workspace, info, found);
        String indent = JavaMembers.indentOf(info.type().lines(found.member().startLine(), found.member().startLine()));
        workspace.changeLines(info.path(), found.member().startLine(), found.member().endLine(), JavaMembers.indented(code, indent),
                JavaStructure.Effect.REPLACES);
        return "Replaced " + JavaStructure.signature(found.member()) + " of " + found.owner().name() + " in " + info.path() + IN_MEMORY + ".";
    }

    @WritesFiles
    @Tool("Deletes one member of a type of the Java project, or, without member, the whole type (its file goes too when it was "
            + "the only type in it). Name the member like javaRead does. Check javaUsages first for what uses it. The result must "
            + "still be valid Java, else nothing changes and you are told why")
    public String javaDelete(@P("the type's name, e.g. Ledger") String type,
                             @P(value = "the member, or empty for the whole type", required = false) String member,
                             InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        JavaWorkspace.TypeInfo info = typeNamed(workspace, type);
        if (member == null || member.isBlank()) {
            JavaClass t = info.type();
            if (workspace.typesIn(info.path()).size() == 1 && workspace.typesIn(info.path()).getFirst() == t) {
                workspace.delete(info.path());
                return "Deleted " + info.path() + " (it held only " + t.name() + ")" + IN_MEMORY + ".";
            }
            return delete(workspace, info, new JavaMembers.Found(null, t));
        }
        return delete(workspace, info, JavaMembers.find(info.type(), member));
    }

    private String delete(JavaWorkspace workspace, JavaWorkspace.TypeInfo info, JavaMembers.Found found) {
        JavaClass root = info.type();
        int from = found.member().startLine();
        int to = found.member().endLine();
        // the blank line that separated it from the next one goes too
        if (blankLine(root, to + 1) && to + 1 < root.lineCount()) to++;
        workspace.changeLines(info.path(), from, to, "", JavaStructure.Effect.REPLACES);
        return "Deleted " + JavaStructure.signature(found.member()) + " from " + info.path() + IN_MEMORY + ".";
    }

    @WritesFiles
    @Tool("Adds an import to the file of a type of the Java project, or removes it (remove true). name is the imported name, "
            + "e.g. java.util.List, or static java.lang.Math.max")
    public String javaImport(@P("a type of the file, e.g. Ledger") String type,
                             @P("the import, e.g. java.util.List") String name,
                             @P(value = "true to remove the import", required = false) Boolean remove,
                             InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        JavaWorkspace.TypeInfo info = typeNamed(workspace, type);
        String wanted = name.strip().replaceFirst("^import\\s+", "").replaceFirst(";$", "").strip();
        JavaClass top = workspace.typesIn(info.path()).getFirst();
        int at = top.imports().indexOf(wanted);
        if (Boolean.TRUE.equals(remove)) {
            if (at < 0) return "The file does not import " + wanted + ".";
            Span lines = top.importLines().get(at);
            workspace.changeLines(info.path(), lines.start(), lines.end(), "", JavaStructure.Effect.BODY);
            return "Removed the import of " + wanted + " from " + info.path() + IN_MEMORY + ".";
        }
        if (at >= 0) return "The file imports " + wanted + " already.";
        String statement = "import " + wanted + ";";
        if (!top.importLines().isEmpty()) {
            workspace.changeLines(info.path(), top.importLines().getLast().end() + 1, top.importLines().getLast().end(), statement, JavaStructure.Effect.BODY);
        } else if (!top.packageLines().isNone()) {
            workspace.changeLines(info.path(), top.packageLines().end() + 1, top.packageLines().end(), "\n" + statement, JavaStructure.Effect.BODY);
        } else {
            workspace.changeLines(info.path(), 1, 0, statement + "\n", JavaStructure.Effect.BODY);
        }
        return "Added the import of " + wanted + " to " + info.path() + IN_MEMORY + ".";
    }

    @WritesFiles
    @Tool("Creates a new Java file with the given text (package, imports and the type; a path that does not exist yet, relative to "
            + "the working folder). The text must be valid Java. Use javaAdd to put members into an existing type")
    public String javaCreate(@P("the new file's path, e.g. src/main/java/net/rka/Ledger.java") String path,
                             @P("the file's complete text") String code,
                             InvocationParameters parameters) throws java.io.IOException {
        JavaWorkspace workspace = workspace(parameters);
        if (!path.endsWith(".java")) throw new IllegalArgumentException("A Java file's name ends with .java");
        Path file = WorkFolder.resolveInside(parameters, path);
        String relative = workspace.root().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        if (workspace.has(relative) || java.nio.file.Files.exists(file)) throw new IllegalArgumentException(path + " exists already: change it with javaAdd or javaReplace");
        workspace.setText(relative, code.endsWith("\n") ? code : code + "\n");
        return "Created " + relative + " with " + workspace.typesIn(relative).stream().map(JavaClass::name).collect(Collectors.joining(", "))
                + IN_MEMORY + ".";
    }

    @Tool("The outline of a Java type or file from the parsed structure: every type and member with its kind, the name to give "
            + "javaRead / javaReplace / javaDelete for it, and its lines in the file. Give a type's name (Ledger, Outer.Inner) or a "
            + "file path ending in .java")
    public String javaOutline(@P("a type's name, or the path of a .java file") String target, InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        String path;
        List<JavaClass> shown;
        String named = target.strip();
        if (!named.endsWith(".java") && (named.contains("/") || named.contains("\\")
                || named.matches(".*\\.(md|txt|json|xml|gradle|properties|ya?ml|html?|css|js|ts|kt|py|sh|csv)$"))) {
            throw new IllegalArgumentException(named + " is not a Java file: the Java tools work on .java files only, use outline for other files");
        }
        if (target.strip().endsWith(".java")) {
            path = target.strip().replace('\\', '/');
            shown = workspace.typesIn(path);
            if (shown.isEmpty()) {
                throw new IllegalArgumentException(workspace.has(path) ? path + " is not read as Java: " + workspace.parseError(path)
                        : "No Java file " + path + " in the project");
            }
        } else {
            JavaWorkspace.TypeInfo info = typeNamed(workspace, target.strip());
            path = info.path();
            shown = List.of(info.type());
        }
        JavaClass top = workspace.typesIn(path).getFirst();
        StringBuilder out = new StringBuilder(path + " · " + top.lineCount() + " lines\n");
        if (!top.packageLines().isNone()) out.append("package ").append(top.packageName()).append(" · line ").append(top.packageLines().start()).append('\n');
        if (!top.importLines().isEmpty()) {
            out.append(top.importLines().size()).append(" imports · lines ").append(top.importLines().getFirst().start()).append('–')
                    .append(top.importLines().getLast().end()).append('\n');
        }
        for (JavaClass type : shown) outline(type, 0, out);
        return out.toString().stripTrailing();
    }

    private static void outline(JavaClass type, int level, StringBuilder out) {
        String pad = "  ".repeat(level);
        out.append(pad).append(type.kind().keyword()).append(' ').append(type.name()).append(" · ").append(type.startLine()).append('–')
                .append(type.endLine()).append('\n');
        for (JavaEnumConstant c : type.enumConstants()) {
            out.append(pad).append("  constant ").append(c.name()).append(" · ").append(c.startLine()).append('–').append(c.endLine()).append('\n');
        }
        for (JavaMember m : type.members()) {
            if (m instanceof JavaClass nested) {
                outline(nested, level + 1, out);
            } else if (!(m instanceof net.rka.ai.tools.code.java.JavaComment)) {
                String kind = JavaStructure.signature(m).replaceFirst("[ (].*$", "");
                out.append(pad).append("  ").append(kind).append(' ').append(JavaMembers.spec(type, m)).append(" · ").append(m.startLine());
                if (m.endLine() != m.startLine()) out.append('–').append(m.endLine());
                out.append('\n');
            }
        }
    }

    @Tool("The call graph of the Java project: which methods and constructors a member calls (direction calls) or is called by "
            + "(direction callers), followed recursively. Without from it starts at the classes that have a main method. from is a "
            + "type (all its members), or Type.member named like javaRead does. Calls are followed through variables, fields, "
            + "parameters, new, static calls and chained calls with declared types, and into the subtypes of an interface or "
            + "abstract method; calls into the JDK or libraries and on untyped values are counted, not followed. Use it to find "
            + "what a change reaches or where a feature starts")
    public String javaGraph(@P(value = "a type or Type.member; empty for the classes with main", required = false) String from,
                            @P(value = "levels to follow, 1 to 8 (default 3)", required = false) Integer depth,
                            @P(value = "calls (default) or callers", required = false) String direction,
                            InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        net.rka.ai.tools.code.java.JavaCallGraph graph = new net.rka.ai.tools.code.java.JavaCallGraph(workspace);
        boolean callers = direction != null && direction.strip().toLowerCase().startsWith("caller");
        int levels = depth == null ? 3 : Math.max(1, Math.min(8, depth));
        List<net.rka.ai.tools.code.java.JavaCallGraph.Member> roots;
        String title;
        if (from == null || from.isBlank()) {
            roots = graph.entryPoints();
            if (roots.isEmpty()) throw new IllegalArgumentException("No class has a main method: name a type or Type.member in from");
            title = "from the main methods";
        } else {
            roots = rootsOf(workspace, graph, from.strip());
            title = (callers ? "to " : "from ") + from.strip();
        }
        String tree = graph.render(roots, levels, callers, 80);
        StringBuilder out = new StringBuilder("Calls " + title + " (" + levels + " levels):\n").append(tree);
        if (!callers) {
            java.util.Set<String> reached = graph.reachableFrom(roots);
            java.util.Set<String> typesReached = new java.util.TreeSet<>();
            for (String id : reached) {
                graph.member(id).ifPresent(m -> typesReached.add(m.type()));
                typesReached.addAll(graph.typesTouchedBy(id));
            }
            out.append("\n\nIn all ").append(reached.size()).append(" members in ").append(typesReached.size()).append(" types are reachable; ")
                    .append(graph.resolvedCalls()).append(" calls were followed, ").append(graph.unresolvedCalls())
                    .append(" were not (JDK, libraries, values of unknown type).");
        }
        return out.toString();
    }

    private static List<net.rka.ai.tools.code.java.JavaCallGraph.Member> rootsOf(JavaWorkspace workspace, net.rka.ai.tools.code.java.JavaCallGraph graph, String from) {
        List<JavaWorkspace.TypeInfo> asType = workspace.findAll(from);
        if (asType.size() == 1) return graph.membersOf(asType.getFirst().qualifiedName());
        int paren = from.indexOf('(');
        int dot = (paren < 0 ? from : from.substring(0, paren)).lastIndexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException(asType.isEmpty() ? "No type " + from + " in the project" : from + " is ambiguous: "
                    + asType.stream().map(JavaWorkspace.TypeInfo::qualifiedName).collect(Collectors.joining(", ")));
        }
        JavaWorkspace.TypeInfo info = typeNamed(workspace, from.substring(0, dot));
        JavaMembers.Found found = JavaMembers.find(info.type(), from.substring(dot + 1));
        String owner = found.owner() == info.type() ? info.qualifiedName() : info.qualifiedName() + "." + found.owner().name();
        List<net.rka.ai.tools.code.java.JavaCallGraph.Member> roots = graph.membersOf(owner).stream().filter(m -> m.member() == found.member()).toList();
        if (roots.isEmpty()) throw new IllegalArgumentException(JavaStructure.signature(found.member()) + " has no code to follow");
        return roots;
    }

    /** The indentation of the members of the type: that of its first member, else one step in from its closing brace. */
    private static String memberIndent(JavaClass type) {
        for (JavaMember member : type.members()) {
            if (member.startLine() > 0) return JavaMembers.indentOf(type.lines(member.startLine(), member.startLine()));
        }
        return JavaMembers.indentOf(type.lines(type.endLine(), type.endLine())) + "    ";
    }

    private static boolean blankLine(JavaClass type, int line) {
        return line >= 1 && line <= type.lineCount() && type.lines(line, line).isBlank();
    }

    // ---- the statements inside a method ----

    private static final int MAX_BLOCK_LINES = 80;

    @Tool("The statements inside a method, constructor or initializer block of the Java project, as a tree: each with its address "
            + "(the number in its list, then / part name / number for the statements inside: 2/body/1, 5/then/2), kind, lines, the "
            + "variables it declares and the calls it makes. Use the addresses with javaReadBlock, javaReplaceBlock, javaInsertBlock and "
            + "javaDeleteBlock to change part of a method without writing the rest of it")
    public String javaBlocks(@P("the type's name, e.g. Ledger") String type,
                             @P("the member: name, name(int,String), <init>, {} or static{}, Inner.name") String member,
                             @P(value = "how many levels of statements to show, 1 to 6 (default 3)", required = false) Integer depth,
                             InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        JavaWorkspace.TypeInfo info = typeNamed(workspace, type);
        JavaMembers.Found found = JavaMembers.find(info.type(), member);
        JavaBlocks.Body body = JavaBlocks.bodyOf(info.type(), found.member());
        if (body.span().isNone()) throw new IllegalArgumentException(JavaStructure.signature(found.member()) + " has no body");
        StringBuilder out = new StringBuilder("[" + info.path() + " · " + JavaStructure.signature(found.member()) + " · lines "
                + found.member().startLine() + "–" + found.member().endLine() + "]\n");
        if (!body.parameters().isEmpty()) {
            out.append("parameters: ").append(body.parameters().stream().map(v -> v.type() + " " + v.name()).collect(Collectors.joining(", "))).append('\n');
        }
        int[] lines = {0};
        blocks(body.statements(), "", Math.max(1, Math.min(6, depth == null ? 3 : depth)), out, lines);
        if (lines[0] > MAX_BLOCK_LINES) out.append("… ").append(lines[0] - MAX_BLOCK_LINES).append(" more lines; ask for a smaller depth or read a block\n");
        return out.toString().stripTrailing();
    }

    private static void blocks(List<net.rka.ai.tools.code.java.Statement> statements, String prefix, int depth, StringBuilder out, int[] count) {
        int number = 0;
        for (net.rka.ai.tools.code.java.Statement statement : statements) {
            number++;
            String address = prefix + number;
            if (++count[0] <= MAX_BLOCK_LINES) {
                out.append("  ".repeat(prefix.isEmpty() ? 0 : prefix.split("/").length / 2)).append(address).append("  ").append(statement.kind().name().toLowerCase())
                        .append(' ').append(statement.startLine());
                if (statement.endLine() != statement.startLine()) out.append('–').append(statement.endLine());
                out.append("  ").append(statement.summary());
                if (!statement.declares().isEmpty()) {
                    out.append("  declares ").append(statement.declares().stream().map(net.rka.ai.tools.code.java.Variable::name).distinct().collect(Collectors.joining(", ")));
                }
                if (!statement.calls().isEmpty()) {
                    out.append("  calls ").append(statement.calls().stream().map(net.rka.ai.tools.code.java.Call::name).distinct().limit(4).collect(Collectors.joining(", ")));
                }
                out.append('\n');
            }
            if (depth > 1) {
                for (net.rka.ai.tools.code.java.Part part : statement.parts()) {
                    if (part.statements().isEmpty()) continue;
                    blocks(part.statements(), address + "/" + part.name() + "/", depth - 1, out, count);
                }
            }
        }
    }

    @Tool("Reads one statement of a method of the Java project, as it is in the file, with the variables a statement there can use "
            + "(the parameters and what is declared above it) and what it reads, writes and calls. Addresses come from javaBlocks")
    public String javaReadBlock(@P("the type's name, e.g. Ledger") String type,
                                @P("the member, e.g. add or add(int)") String member,
                                @P("the statement's address from javaBlocks, e.g. 2/body/1") String address,
                                InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        JavaWorkspace.TypeInfo info = typeNamed(workspace, type);
        JavaMembers.Found found = JavaMembers.find(info.type(), member);
        JavaBlocks.Body body = JavaBlocks.bodyOf(info.type(), found.member());
        if (body.span().isNone()) throw new IllegalArgumentException(JavaStructure.signature(found.member()) + " has no body");
        JavaBlocks.Located at = JavaBlocks.locate(body, address);
        if (at.statement() == null) throw new IllegalArgumentException(address + " names a part, not a statement: give the number of a statement in it");
        net.rka.ai.tools.code.java.Statement statement = at.statement();
        StringBuilder out = new StringBuilder("[" + info.path() + " · " + JavaStructure.signature(found.member()) + " · " + address + " · lines "
                + statement.startLine() + "–" + statement.endLine() + "]\n");
        out.append(info.type().lines(statement.startLine(), statement.endLine())).append('\n');
        out.append("sees: ").append(JavaBlocks.visibleBefore(body, statement).stream().map(v -> v.type() + " " + v.name()).collect(Collectors.joining(", "))).append('\n');
        if (!statement.declares().isEmpty()) out.append("declares: ").append(statement.declares().stream().map(v -> v.type() + " " + v.name()).collect(Collectors.joining(", "))).append('\n');
        if (!statement.reads().isEmpty()) out.append("reads: ").append(String.join(", ", statement.reads())).append('\n');
        if (!statement.writes().isEmpty()) out.append("writes: ").append(String.join(", ", statement.writes())).append('\n');
        if (!statement.calls().isEmpty()) {
            out.append("calls: ").append(statement.calls().stream().map(c -> (c.receiver().isEmpty() ? "" : c.receiverText() + ".") + c.name()).collect(Collectors.joining(", "))).append('\n');
        }
        return out.toString().stripTrailing();
    }

    @WritesFiles
    @Tool("Replaces one statement of a method of the Java project (a whole if, loop or try with everything inside it counts as one) "
            + "with new statements. Write only the new statements. The change is refused, and nothing changes, when it would not leave the "
            + "method as it should be: not valid Java, new code outside the statement it belongs in, a variable used that only the removed "
            + "code declared, a variable declared twice")
    public String javaReplaceBlock(@P("the type's name, e.g. Ledger") String type,
                                   @P("the member, e.g. add or add(int)") String member,
                                   @P("the statement's address from javaBlocks, e.g. 2/body/1") String address,
                                   @P(value = "the first line of the statement at that address, as javaBlocks shows it: the change is refused if the statements moved", required = false) String expect,
                                   @P("the new statement(s), complete") String code,
                                   InvocationParameters parameters) {
        return blockEdit(parameters, type, member, address, net.rka.ai.tools.code.java.JavaBlockEdit.Op.REPLACE, expect, code);
    }

    @WritesFiles
    @Tool("Puts new statements into a method of the Java project: where is before or after the statement at the address, or start or "
            + "end of the part the address names (2/body is the body of the second statement, an empty address is the whole method body). "
            + "Write only the new statements. Refused, with nothing changed, when the result would not be what the change says")
    public String javaInsertBlock(@P("the type's name, e.g. Ledger") String type,
                                  @P("the member, e.g. add or add(int)") String member,
                                  @P("before or after (address = a statement) or start or end (address = a part, or empty for the method body)") String where,
                                  @P(value = "the address from javaBlocks", required = false) String address,
                                  @P(value = "for before or after: the first line of the statement at the address, as javaBlocks shows it", required = false) String expect,
                                  @P("the new statement(s), complete") String code,
                                  InvocationParameters parameters) {
        net.rka.ai.tools.code.java.JavaBlockEdit.Op op;
        try {
            op = net.rka.ai.tools.code.java.JavaBlockEdit.Op.valueOf(where.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("where is before, after, start or end, not '" + where + "'");
        }
        if (op == net.rka.ai.tools.code.java.JavaBlockEdit.Op.REPLACE || op == net.rka.ai.tools.code.java.JavaBlockEdit.Op.DELETE) {
            throw new IllegalArgumentException("where is before, after, start or end: replace and delete have their own tools");
        }
        return blockEdit(parameters, type, member, address, op, expect, code);
    }

    @WritesFiles
    @Tool("Deletes one statement of a method of the Java project, with everything inside it. Refused, with nothing changed, when a "
            + "variable it declares is still used below")
    public String javaDeleteBlock(@P("the type's name, e.g. Ledger") String type,
                                  @P("the member, e.g. add or add(int)") String member,
                                  @P("the statement's address from javaBlocks, e.g. 2/body/1") String address,
                                  @P(value = "the first line of the statement at that address, as javaBlocks shows it: the change is refused if the statements moved", required = false) String expect,
                                  InvocationParameters parameters) {
        return blockEdit(parameters, type, member, address, net.rka.ai.tools.code.java.JavaBlockEdit.Op.DELETE, expect, "");
    }

    @WritesFiles
    @Tool("Changes part of a member of a Java type like a patch. Write hunks: lines that start with a blank are context that stays, "
            + "'-' lines go, '+' lines are added; start a new hunk with a line @@. Each hunk is found by its context and removed lines, "
            + "which must be in the member exactly once, so the change lands in the right place whatever was added or moved above it; "
            + "indentation does not need to match. Copy the lines that stay from javaRead. Refused, with nothing changed, when a hunk fits "
            + "nowhere or in several places (it says where), or when the result would not be valid Java or would lose or repeat a variable")
    public String javaPatch(@P("the type's name, e.g. Ledger") String type,
                            @P("the member, e.g. add or add(int)") String member,
                            @P("the hunks") String patch,
                            InvocationParameters parameters) {
        JavaWorkspace workspace = workspace(parameters);
        return net.rka.ai.tools.code.java.JavaPatch.apply(workspace, typeNamed(workspace, type), member, patch) + IN_MEMORY + ".";
    }

    private String blockEdit(InvocationParameters parameters, String type, String member, String address,
                             net.rka.ai.tools.code.java.JavaBlockEdit.Op op, String expect, String code) {
        JavaWorkspace workspace = workspace(parameters);
        String done = net.rka.ai.tools.code.java.JavaBlockEdit.apply(workspace, typeNamed(workspace, type), member, address, op, expect, code);
        return done + IN_MEMORY + ".";
    }

    // ---- text ----

    private static String lines(List<String> rows, String title) {
        StringBuilder out = new StringBuilder(title == null ? "" : title + ":\n");
        rows.stream().limit(MAX_LINES).forEach(r -> out.append("  ").append(r).append('\n'));
        if (rows.size() > MAX_LINES) out.append("  … ").append(rows.size() - MAX_LINES).append(" more\n");
        return out.toString().stripTrailing();
    }

    private static boolean isNested(JavaWorkspace workspace, JavaWorkspace.TypeInfo info) {
        String q = info.qualifiedName();
        return q.contains(".") && workspace.type(q.substring(0, q.lastIndexOf('.'))).isPresent();
    }

    private static String packageOf(String qualified, JavaClass type) {
        return qualified.length() > type.name().length() ? qualified.substring(0, qualified.length() - type.name().length() - 1) : "";
    }

    private static String signature(JavaMember member) {
        return switch (member) {
            case net.rka.ai.tools.code.java.JavaField f -> (f.visibility().keyword() + " " + f.type() + " " + f.name()).strip();
            case net.rka.ai.tools.code.java.JavaConstructor c -> (c.visibility().keyword() + " <init>(" + params(c.parameters()) + ")").strip();
            case net.rka.ai.tools.code.java.JavaMethod m -> (m.visibility().keyword() + " " + m.returnType() + " " + m.name() + "("
                    + params(m.parameters()) + ")").strip();
            case JavaClass n -> "nested " + n.kind().keyword() + " " + n.name();
            default -> "";
        };
    }

    private static String params(List<net.rka.ai.tools.code.java.JavaParameter> parameters) {
        return parameters.stream().map(p -> p.type() + " " + p.name()).collect(Collectors.joining(", "));
    }
}
