package net.rka.ai.agent;

import net.rka.ai.tools.code.java.JavaClass;
import net.rka.ai.tools.code.java.JavaMember;
import net.rka.ai.tools.code.java.JavaMethod;
import net.rka.ai.tools.code.java.JavaConstructor;
import net.rka.ai.tools.code.java.JavaField;
import net.rka.ai.tools.code.java.JavaWorkspace;
import net.rka.ai.tools.outline.Outline;
import net.rka.ai.tools.outline.Outlines;
import net.rka.ai.tools.outline.Section;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finds the places in a Java project a goal is about, without a model: the members whose names and code use the words of the goal
 * ("conversation row menu button like the folder row" finds {@code conversationRow} and the folder row's code), each with its code as it
 * is now. The plan is made from these places, so it is made from code the planner has seen and not from names it guesses from
 * outlines; a change to a few lines can then be written against lines that are in front of it.
 */
public final class Locator {

    /** A member that fits the goal. */
    public record Candidate(String path, String id, int startLine, int endLine, int score, String excerpt) {
    }

    private static final Set<String> STOP = Set.copyOf(java.util.List.of("the", "and", "for", "with", "that", "this", "from", "into", "than", "then", "make", "makes",
            "add", "adds", "fix", "fixes", "change", "changes", "look", "looks", "like", "same", "also", "should", "must", "need", "needs",
            "want", "does", "not", "all", "any", "are", "was", "were", "has", "have", "its", "one", "new", "use", "uses", "using", "when",
            "should", "would", "could", "just", "only", "each", "every", "still", "now", "too", "can", "will", "user", "latest", "message",
            "task", "working", "their", "words", "points", "back", "earlier", "about", "what", "which", "where", "how", "why", "please",
            "implement", "update", "set", "get", "show", "shows", "displays", "display"));
    private static final int MAX_EXCERPT_LINES = 40;
    private static final int MAX_MEMBER_CHARS = 6000;

    private Locator() {
    }

    /** The words of a text worth looking for: lower case, from three letters, without the usual filler, with the parts of camel-case names. */
    public static Set<String> words(String text) {
        Set<String> words = new LinkedHashSet<>();
        for (String token : text.split("[^A-Za-z0-9]+")) {
            for (String part : token.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                String word = stem(part.toLowerCase(Locale.ROOT));
                if (word.length() >= 3 && !STOP.contains(word)) words.add(word);
            }
        }
        return words;
    }

    private static String stem(String word) {
        return word.length() > 4 && word.endsWith("s") && !word.endsWith("ss") ? word.substring(0, word.length() - 1) : word;
    }

    /**
     * @param goal the job's goal (the task and the latest message)
     * @param max  how many places at most
     * @return the best places first; empty when nothing fits well enough
     */
    public static List<Candidate> find(JavaWorkspace workspace, String goal, int max) {
        Set<String> wanted = words(goal);
        if (wanted.size() < 1) return List.of();
        List<Candidate> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JavaWorkspace.TypeInfo info : workspace.allTypes()) {
            JavaClass type = info.type();
            if (type.source().isEmpty() || !seen.add(info.path() + "#" + type.startLine())) continue;
            String[] lines = type.source().split("\\R", -1);
            Outline outline = null;
            for (JavaMember member : type.members()) {
                if (!(member instanceof JavaMethod || member instanceof JavaConstructor || member instanceof JavaField)) continue;
                String name = member instanceof JavaMethod m ? m.name() : member instanceof JavaField f ? f.name() : type.name();
                int from = member.startLine();
                int to = Math.min(member.endLine(), lines.length);
                if (from < 1 || to < from) continue;
                String text = String.join("\n", java.util.Arrays.copyOfRange(lines, from - 1, to));
                if (text.length() > MAX_MEMBER_CHARS * 4) continue;
                Set<String> nameWords = words(info.qualifiedName() + " " + name);
                Set<String> bodyWords = words(text.length() > MAX_MEMBER_CHARS ? text.substring(0, MAX_MEMBER_CHARS) : text);
                int nameHits = (int) wanted.stream().filter(nameWords::contains).count();
                int bodyHits = (int) wanted.stream().filter(bodyWords::contains).count();
                int score = 3 * nameHits + bodyHits;
                // A member is a place when its name says something of the goal, or its code uses several of the goal's words
                if (score < Math.min(4, 2 * wanted.size()) || (nameHits == 0 && bodyHits < Math.min(4, wanted.size()))) continue;
                if (outline == null) outline = Outlines.of(info.path().substring(info.path().lastIndexOf('/') + 1), type.source().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                int start = from;
                String id = outline.sections().stream().filter(s -> s.start() == start && !s.kind().equals("header")).map(Section::id).findFirst()
                        .orElse(info.qualifiedName() + "." + name);
                found.add(new Candidate(info.path(), id, from, to, score, excerpt(lines, from, to, wanted)));
            }
        }
        found.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparingInt(c -> c.endLine() - c.startLine()));
        return found.subList(0, Math.min(max, found.size()));
    }

    private static final long MAX_TEXT_FILE_BYTES = 300_000;
    private static final int MAX_FILES_WALKED = 3_000;
    private static final int MAX_BLOCK_LINES = 30;

    /**
     * The same for the files that are not Java (style sheets, build files, JSON, Markdown, scripts…): their text is cut into blocks
     * (the lines between blank lines, long ones in pieces) and a block is a place when it uses several words of the goal or its
     * file's name says something of it. The id is empty: a change there is a patch of the file, with these lines as its context.
     */
    public static List<Candidate> findInTextFiles(java.nio.file.Path root, String goal, int max) {
        Set<String> wanted = words(goal);
        if (wanted.isEmpty() || root == null || !java.nio.file.Files.isDirectory(root)) return List.of();
        List<Candidate> found = new ArrayList<>();
        int[] walked = {0};
        try {
            java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    return !dir.equals(root) && net.rka.ai.tools.FileTools.SKIPPED_FOLDERS.contains(dir.getFileName().toString())
                            ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (++walked[0] > MAX_FILES_WALKED) return java.nio.file.FileVisitResult.TERMINATE;
                    if (attrs.isRegularFile() && attrs.size() <= MAX_TEXT_FILE_BYTES && !file.getFileName().toString().endsWith(".java")) {
                        scanText(root, file, wanted, found);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException | RuntimeException e) {
            // an unreadable folder: the places found so far are used
        }
        found.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparingInt(c -> c.endLine() - c.startLine()));
        return found.subList(0, Math.min(max, found.size()));
    }

    private static void scanText(java.nio.file.Path root, java.nio.file.Path file, Set<String> wanted, List<Candidate> found) {
        String content;
        try {
            content = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException | RuntimeException e) {
            return; // not text
        }
        if (content.indexOf(0) >= 0) return;
        String path = root.relativize(file).toString().replace('\\', '/');
        Set<String> nameWords = words(path);
        String[] lines = content.split("\\R", -1);
        int from = 0;
        while (from < lines.length) {
            if (lines[from].isBlank()) {
                from++;
                continue;
            }
            int to = from;
            while (to + 1 < lines.length && !lines[to + 1].isBlank() && to + 1 - from < MAX_BLOCK_LINES) to++;
            String block = String.join("\n", java.util.Arrays.copyOfRange(lines, from, to + 1));
            Set<String> blockWords = words(block);
            int bodyHits = (int) wanted.stream().filter(blockWords::contains).count();
            int nameHits = (int) wanted.stream().filter(nameWords::contains).count();
            int score = 2 * nameHits + bodyHits;
            if (bodyHits >= Math.min(2, wanted.size()) && score >= Math.min(3, 2 * wanted.size() - 1)) found.add(new Candidate(path, "", from + 1, to + 1, score, block));
            from = to + 1;
        }
    }

    /** The member's lines; a long one only around the lines that use the goal's words. */
    private static String excerpt(String[] lines, int from, int to, Set<String> wanted) {
        if (to - from + 1 <= MAX_EXCERPT_LINES) return String.join("\n", java.util.Arrays.copyOfRange(lines, from - 1, to));
        Set<Integer> keep = new LinkedHashSet<>();
        keep.add(from);
        for (int line = from; line <= to; line++) {
            if (!words(lines[line - 1]).stream().anyMatch(wanted::contains)) continue;
            for (int near = Math.max(from, line - 1); near <= Math.min(to, line + 1); near++) keep.add(near);
            if (keep.size() >= MAX_EXCERPT_LINES) break;
        }
        StringBuilder out = new StringBuilder();
        int last = from - 1;
        for (int line : keep.stream().sorted().toList()) {
            if (line > last + 1) out.append("    …\n");
            out.append(lines[line - 1]).append('\n');
            last = line;
        }
        return out.toString().stripTrailing();
    }

    /** The places as the plan prompt shows them. */
    static String render(List<Candidate> places, int maxChars) {
        StringBuilder out = new StringBuilder();
        for (Candidate place : places) {
            String block = "--- " + place.path() + " · " + (place.id().isEmpty() ? "" : place.id() + " · ") + "lines " + place.startLine() + "–" + place.endLine() + "\n" + place.excerpt() + "\n\n";
            if (out.length() + block.length() > maxChars) break;
            out.append(block);
        }
        return out.toString().stripTrailing();
    }
}
