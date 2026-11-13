package net.rka.ai.tools.code.java;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the class names in code a change writes that exist nowhere: not imported, not a class of the project, not of
 * {@code java.lang}, not declared in the file. A model invents such names (a {@code MermaidRenderer} that nobody wrote); the
 * build would find it only after a slow run, this finds it before the file changes.
 * <p>
 * Only names the check can prove missing are reported: with a wildcard import it can't read (a library the app doesn't
 * have), nothing is reported.
 */
public final class JavaUnknownTypes {

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;", Pattern.MULTILINE);
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern DECLARED = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Z]\\w*)");
    private static final Pattern TYPE_PARAM = Pattern.compile("<\\s*([A-Z]\\w*)(?:\\s+extends\\b[^>]*)?\\s*[,>]");
    // a capitalised name used as a type or qualifier: `new Foo(`, `Foo.bar`, `Foo<`, `Foo x =`, `Foo[]`
    private static final Pattern USE = Pattern.compile("(?<![\\w.@\"])([A-Z][a-z]\\w*)(?=\\s*(?:\\.|<|\\(|\\[|\\s+[a-z_]\\w*\\s*[=;,)(:]))");

    private JavaUnknownTypes() {
    }

    /**
     * @param fileText     the file as it would be after the change
     * @param written      the text the change writes: only the names in it are checked
     * @param projectTypes the simple names of the classes of the project
     * @return the unknown names, in order of appearance; empty when everything is known or can't be told
     */
    public static List<String> unknown(String fileText, String written, Set<String> projectTypes) {
        Set<String> known = new LinkedHashSet<>(projectTypes);
        List<String> wildcards = new ArrayList<>();
        Matcher imports = IMPORT.matcher(fileText);
        while (imports.find()) {
            String name = imports.group(2);
            if (imports.group(3) != null) wildcards.add(name);
            else known.add(name.substring(name.lastIndexOf('.') + 1));
        }
        Matcher declared = DECLARED.matcher(fileText);
        while (declared.find()) known.add(declared.group(1));
        Matcher params = TYPE_PARAM.matcher(fileText);
        while (params.find()) known.add(params.group(1));
        Matcher pkgMatch = PACKAGE.matcher(fileText);
        String pkg = pkgMatch.find() ? pkgMatch.group(1) : "";
        Set<String> unknown = new LinkedHashSet<>();
        Matcher uses = USE.matcher(withoutCommentsAndStrings(written));
        while (uses.find()) {
            String name = uses.group(1);
            if (known.contains(name) || exists("java.lang." + name) || (!pkg.isEmpty() && exists(pkg + "." + name))) continue;
            boolean viaWildcard = false;
            for (String wildcard : wildcards) {
                if (exists(wildcard + "." + name)) viaWildcard = true;
            }
            if (viaWildcard) continue;
            for (String wildcard : wildcards) {
                if (!wildcard.startsWith("java.") && !wildcard.startsWith("javafx.") && !wildcard.startsWith("javax.")) return List.of(); // can't tell
            }
            unknown.add(name);
        }
        return List.copyOf(unknown);
    }

    private static boolean exists(String qualified) {
        try {
            Class.forName(qualified, false, JavaUnknownTypes.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static String withoutCommentsAndStrings(String code) {
        return code.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ")
                .replaceAll("\"(?:\\\\.|[^\"\\\\\\n])*\"", "\"\"");
    }
}
