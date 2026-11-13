package net.rka.ai.tools.code.java;

import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds the imports a change needs, without a model: a class name that the written code uses and that the file does not import
 * is looked up among the classes of the project, the exported packages of the JDK and the jars on the class path; when exactly one
 * class (after the narrowing below) has that name, its import is added. A name that cannot be told (two candidates, or none) is left
 * alone, the build then reports it. So the order of a plan's changes no longer matters for imports.
 */
public final class JavaImports {

    /** Packages that win a tie: the usual ones of everyday code. */
    private static final List<String> PREFERRED = List.of("java.util", "java.io", "java.nio.file", "java.time", "java.util.function",
            "java.util.stream", "java.net", "java.nio", "java.util.concurrent", "java.util.regex");

    private static final Pattern IMPORT_LINE = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+?)(?:\\.\\*)?\\s*;", Pattern.MULTILINE);
    private static final Pattern PACKAGE_LINE = Pattern.compile("^package\\s+[\\w.]+\\s*;[^\\n]*\\n?", Pattern.MULTILINE);

    private static volatile Map<String, Set<String>> library;

    private JavaImports() {
    }

    /**
     * @param fileText     the file after the change
     * @param written      the text the change writes
     * @param projectTypes the project's classes by qualified name (nested ones as {@code Outer.Inner})
     * @return the file with the imports added (the same text when none was needed or none could be told)
     */
    public static String add(String fileText, String written, Set<String> projectTypes) {
        // project classes of the file's own package need no import; the others do
        Matcher pkg = PACKAGE_LINE.matcher(fileText);
        String own = pkg.find() ? pkg.group().replaceAll("^package\\s+|\\s*;.*\\s*$", "") : "";
        Set<String> samePackage = new LinkedHashSet<>();
        for (String q : projectTypes) if (packageOf(q).equals(own)) samePackage.add(q.substring(q.lastIndexOf('.') + 1));
        List<String> names = JavaUnknownTypes.unknown(fileText, written, samePackage);
        if (names.isEmpty()) return fileText;
        List<String> imported = importedPackages(fileText);
        Set<String> toAdd = new TreeSet<>();
        for (String name : names) {
            String found = resolve(name, projectTypes, imported);
            if (found != null) toAdd.add(found);
        }
        return toAdd.isEmpty() ? fileText : insert(fileText, toAdd);
    }

    private static final Pattern PLAIN_IMPORT = Pattern.compile("^import\\s+([\\w.]+)\\.(\\w+)\\s*;[^\\n]*\\n?", Pattern.MULTILINE);

    /**
     * Removes the imports a model invented: {@code import renderer.Renderer;} for a class that is in the project, in the file's own package
     * or elsewhere under another name, when no such class is known anywhere (not in the project, not in the JDK, not on the class path).
     * The build would report "package renderer does not exist" and a repair that is only a model's guess at the line keeps failing.
     *
     * @param projectTypes the project's classes by qualified name
     * @return the text without those import lines (the same text when there are none)
     */
    public static String dropInvented(String fileText, Set<String> projectTypes) {
        Set<String> simple = new LinkedHashSet<>();
        for (String q : projectTypes) simple.add(q.substring(q.lastIndexOf('.') + 1));
        Matcher m = PLAIN_IMPORT.matcher(fileText);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String name = m.group(2);
            String qualified = m.group(1) + "." + name;
            boolean known = projectTypes.contains(qualified) || library().getOrDefault(name, Set.of()).contains(qualified);
            if (m.group().startsWith("import static") || known || !simple.contains(name)) continue;
            out.append(fileText, last, m.start());
            last = m.end();
        }
        return last == 0 ? fileText : out.append(fileText.substring(last)).toString();
    }

    /** The qualified name for a simple name, or null when it cannot be told. */
    static String resolve(String name, Set<String> projectTypes, List<String> importedPackages) {
        List<String> project = projectTypes.stream().filter(q -> q.equals(name) || q.endsWith("." + name)).sorted().toList();
        if (project.size() == 1) return project.getFirst();
        if (project.size() > 1) return null;
        List<String> candidates = new ArrayList<>(library().getOrDefault(name, Set.of()));
        if (candidates.size() <= 1) return candidates.isEmpty() ? null : candidates.getFirst();
        candidates = narrow(candidates, c -> importedPackages.contains(packageOf(c)));
        if (candidates.size() > 1) candidates = narrow(candidates, c -> PREFERRED.contains(packageOf(c)));
        if (candidates.size() > 1) {
            String root = importedPackages.stream().map(p -> p.split("\\.")[0]).filter(r -> !r.equals("java")).findFirst().orElse("");
            if (!root.isEmpty()) candidates = narrow(candidates, c -> c.startsWith(root + "."));
        }
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static List<String> narrow(List<String> candidates, java.util.function.Predicate<String> keep) {
        List<String> kept = candidates.stream().filter(keep).toList();
        return kept.isEmpty() ? candidates : kept;
    }

    private static String packageOf(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? "" : qualified.substring(0, dot);
    }

    private static List<String> importedPackages(String fileText) {
        List<String> packages = new ArrayList<>();
        Matcher m = IMPORT_LINE.matcher(fileText);
        while (m.find()) {
            String imported = m.group(1);
            boolean wildcard = m.group().replace(" ", "").contains(".*;");
            packages.add(wildcard ? imported : packageOf(imported));
        }
        return packages;
    }

    /** The import lines go after the last import of the file, else after the package line. */
    static String insert(String text, Set<String> qualified) {
        boolean crlf = text.contains("\r\n");
        String nl = crlf ? "\r\n" : "\n";
        StringBuilder lines = new StringBuilder();
        for (String q : qualified) {
            if (!text.contains("import " + q + ";")) lines.append("import ").append(q).append(';').append(nl);
        }
        if (lines.isEmpty()) return text;
        int lastImport = -1;
        Matcher m = Pattern.compile("^import\\s[^\\n]*\\n", Pattern.MULTILINE).matcher(text);
        while (m.find()) lastImport = m.end();
        if (lastImport >= 0) return text.substring(0, lastImport) + lines + text.substring(lastImport);
        Matcher pkg = PACKAGE_LINE.matcher(text);
        if (pkg.find()) return text.substring(0, pkg.end()) + nl + lines + text.substring(pkg.end());
        return lines + nl + text;
    }

    /** Simple name → the qualified names of the public classes with it, from the JDK's exported packages and the class path's jars. */
    private static Map<String, Set<String>> library() {
        Map<String, Set<String>> index = library;
        if (index != null) return index;
        synchronized (JavaImports.class) {
            if (library == null) library = build();
            return library;
        }
    }

    private static Map<String, Set<String>> build() {
        Map<String, Set<String>> index = new HashMap<>();
        try {
            for (ModuleReference reference : ModuleFinder.ofSystem().findAll()) {
                Set<String> exported = new LinkedHashSet<>();
                for (ModuleDescriptor.Exports e : reference.descriptor().exports()) {
                    if (!e.isQualified()) exported.add(e.source());
                }
                try (var reader = reference.open()) {
                    reader.list().forEach(resource -> {
                        if (!resource.endsWith(".class") || resource.contains("$") || resource.startsWith("META-INF")) return;
                        String qualified = resource.substring(0, resource.length() - 6).replace('/', '.');
                        if (exported.contains(packageOf(qualified))) put(index, qualified);
                    });
                }
            }
        } catch (IOException | RuntimeException e) {
            // the JDK's classes stay out of the index: names are then left for the build to report
        }
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (!entry.endsWith(".jar")) continue;
            try (JarFile jar = new JarFile(entry)) {
                jar.stream().forEach(e -> {
                    String resource = e.getName();
                    if (!resource.endsWith(".class") || resource.contains("$") || resource.startsWith("META-INF")
                            || resource.endsWith("module-info.class") || resource.endsWith("package-info.class")) return;
                    put(index, resource.substring(0, resource.length() - 6).replace('/', '.'));
                });
            } catch (IOException | RuntimeException e) {
                // an unreadable jar is left out
            }
        }
        return index;
    }

    private static void put(Map<String, Set<String>> index, String qualified) {
        index.computeIfAbsent(qualified.substring(qualified.lastIndexOf('.') + 1), k -> new TreeSet<>()).add(qualified);
    }
}
