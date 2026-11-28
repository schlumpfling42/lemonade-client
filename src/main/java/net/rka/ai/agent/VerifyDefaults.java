package net.rka.ai.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The command that checks a project when the plan names none, found from the files in the trusted folder: the
 * Gradle or Maven build, {@code npm test} / {@code npm run build}, Cargo, Go. The graph fills it into the plan before
 * the user sees it, so the approval shows what will run. Nothing is guessed for a project it doesn't know (no
 * command: the job then has nothing to verify and says so).
 */
final class VerifyDefaults {

    private VerifyDefaults() {
    }

    static List<CodingPlan.Command> detect(Path folder) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows && Files.isRegularFile(folder.resolve("gradlew.bat"))) return one(".\\gradlew.bat build");
        if (!windows && Files.isRegularFile(folder.resolve("gradlew"))) return one("./gradlew build");
        if (Files.isRegularFile(folder.resolve("build.gradle")) || Files.isRegularFile(folder.resolve("build.gradle.kts"))) {
            return one("gradle build");
        }
        if (Files.isRegularFile(folder.resolve("pom.xml"))) return one("mvn -q test");
        Path packageJson = folder.resolve("package.json");
        if (Files.isRegularFile(packageJson)) {
            String text = read(packageJson);
            if (text.contains("\"test\"")) return one("npm test");
            if (text.contains("\"build\"")) return one("npm run build");
        }
        if (Files.isRegularFile(folder.resolve("Cargo.toml"))) return one("cargo build");
        if (Files.isRegularFile(folder.resolve("go.mod"))) return one("go build ./...");
        if (holdsJava(folder)) {
            // Java files and no build tool: compile them all into a temporary folder, so a job that breaks the code is never "verified"
            return one(windows
                    ? "javac -d $env:TEMP\\verify-javac (Get-ChildItem -Recurse -Filter *.java | ForEach-Object { $_.FullName })"
                    : "javac -d /tmp/verify-javac $(find . -name '*.java')");
        }
        return List.of();
    }

    /** The most Java files a folder without a build tool may hold to be compiled as one small project. */
    static final int MAX_PLAIN_JAVA_FILES = 200;
    private static final java.util.Set<String> SKIPPED = java.util.Set.of("node_modules", "build", "target", "out", "bin", "dist", "venv", "__pycache__");

    /**
     * Whether the folder is a small plain Java project: Java files (not in hidden folders such as {@code .gradle}, nor in build output or
     * dependencies) and at most {@link #MAX_PLAIN_JAVA_FILES} of them. A user's home folder with a few projects and a cache of sources
     * is not one: compiling "all its Java files" fails on files that have nothing to do with the work.
     */
    static boolean holdsJava(Path folder) {
        int[] count = {0};
        try {
            Files.walkFileTree(folder, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 6, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    boolean skip = !dir.equals(folder) && (name.startsWith(".") || SKIPPED.contains(name));
                    return skip ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (file.getFileName().toString().endsWith(".java")) count[0]++;
                    return count[0] > MAX_PLAIN_JAVA_FILES ? java.nio.file.FileVisitResult.TERMINATE : java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException e) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return count[0] > 0 && count[0] <= MAX_PLAIN_JAVA_FILES;
    }

    /** The files that mark a folder as a project of its own: the commands of {@link #detect} apply to it. */
    private static boolean hasBuildFile(Path dir) {
        for (String name : List.of("gradlew", "gradlew.bat", "build.gradle", "build.gradle.kts", "pom.xml", "package.json", "Cargo.toml", "go.mod")) {
            if (Files.isRegularFile(dir.resolve(name))) return true;
        }
        return false;
    }

    /**
     * The build commands that check <i>what a job changed</i>, not everything under the folder: for each changed file the nearest folder
     * above it (up to the trusted folder) that has a build file is a project, and its own build command runs there (working directory
     * relative to the trusted folder; at most 3 projects). With no such folder the trusted folder itself is checked if it is a small
     * plain Java project ({@link #holdsJava}); otherwise nothing is known, and the list is empty.
     *
     * @param changed the changed files, relative to {@code folder}
     */
    static List<CodingPlan.Command> detectFor(Path folder, List<String> changed) {
        List<CodingPlan.Command> near = detectNear(folder, changed);
        return near.isEmpty() ? detect(folder) : near;
    }

    /**
     * Like {@link #detectFor}, but only the projects that hold a changed file: no fallback to the trusted folder. A folder of several projects
     * is not one project, and a job that creates its project has no build file yet (the plan's setup makes it): the build is known only then.
     */
    static List<CodingPlan.Command> detectNear(Path folder, List<String> changed) {
        java.util.Set<String> roots = new java.util.LinkedHashSet<>();
        for (String file : changed) {
            Path dir = folder.resolve(file).normalize().getParent();
            while (dir != null && dir.startsWith(folder)) {
                if (hasBuildFile(dir)) {
                    roots.add(folder.relativize(dir).toString().replace('\\', '/'));
                    break;
                }
                dir = dir.equals(folder) ? null : dir.getParent();
            }
        }
        if (roots.isEmpty()) return List.of();
        List<CodingPlan.Command> commands = new java.util.ArrayList<>();
        for (String root : roots.stream().limit(3).toList()) {
            Path dir = root.isEmpty() ? folder : folder.resolve(root);
            for (CodingPlan.Command command : detect(dir)) commands.add(new CodingPlan.Command(command.command(), root));
        }
        return commands;
    }

    /**
     * The command as this computer runs it. A model writes {@code gradlew build} or {@code gradle build}, which in
     * PowerShell is "not recognized" (the wrapper needs {@code .\gradlew.bat}, a program in the current folder is
     * never found by its bare name): when the working directory holds the project's wrapper, the first word becomes
     * the wrapper's own invocation for the OS ({@code .\gradlew.bat} / {@code ./gradlew}, {@code mvnw} alike).
     */
    /** {@code javac path/File.java}: one file and no options. */
    private static final java.util.regex.Pattern SINGLE_JAVAC = java.util.regex.Pattern.compile("javac\\s+([^\\s*?-][^\\s*?]*\\.java)");

    static CodingPlan.Command normalize(Path folder, CodingPlan.Command command, boolean windows) {
        return normalize(folder, command, windows, true);
    }

    /** @param rewriteJavac whether a lone {@code javac File.java} may be rewritten (not when a later command runs the compiled classes) */
    private static CodingPlan.Command normalize(Path folder, CodingPlan.Command command, boolean windows, boolean rewriteJavac) {
        String text = command.command().strip();
        java.util.regex.Matcher single = SINGLE_JAVAC.matcher(text);
        if (rewriteJavac && single.matches()) {
            // javac of one file finds the other classes of its folder only from the current folder: look next to the file, write nowhere
            String file = single.group(1).replace('\\', '/');
            int slash = file.lastIndexOf('/');
            String source = slash < 0 ? "." : file.substring(0, slash);
            String out = windows ? "$env:TEMP\\verify-javac" : "/tmp/verify-javac";
            return new CodingPlan.Command("javac -d " + out + " -sourcepath " + source + " " + single.group(1), command.workingDirectory());
        }
        String[] parts = text.split("\\s+", 2);
        String word = parts[0].replace('\\', '/').replaceFirst("^\\./", "");
        String tool = switch (word) {
            case "gradlew", "gradlew.bat", "gradle" -> "gradlew";
            case "mvnw", "mvnw.cmd", "mvn" -> "mvnw";
            default -> null;
        };
        if (tool == null) return command;
        Path directory = command.workingDirectory().isBlank() ? folder : folder.resolve(command.workingDirectory());
        String file = tool + (windows ? (tool.equals("gradlew") ? ".bat" : ".cmd") : "");
        if (!Files.isRegularFile(directory.resolve(file))) return command;
        String invocation = (windows ? ".\\" : "./") + file;
        return new CodingPlan.Command(invocation + (parts.length > 1 ? " " + parts[1] : ""), command.workingDirectory());
    }

    static List<CodingPlan.Command> normalize(Path folder, List<CodingPlan.Command> commands) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        // A command that runs the compiled classes (java Main) needs them where javac put them: then javac is left as written
        boolean runsClasses = commands.stream().anyMatch(c -> c.command().strip().startsWith("java "));
        List<CodingPlan.Command> normalized = commands.stream().map(c -> normalize(folder, c, windows, !runsClasses)).toList();
        // A project with a build tool is built by it: a bare javac knows neither its libraries nor its sources, and "verifies" with
        // hundreds of errors in files that have nothing to do with the job
        boolean bareJavac = normalized.stream().anyMatch(c -> c.workingDirectory().isBlank() && c.command().startsWith("javac "));
        if (!bareJavac || !hasBuildFile(folder)) return normalized;
        List<CodingPlan.Command> build = detect(folder);
        if (build.isEmpty()) return normalized;
        List<CodingPlan.Command> replaced = new java.util.ArrayList<>();
        boolean added = false;
        for (CodingPlan.Command c : normalized) {
            if (c.workingDirectory().isBlank() && c.command().startsWith("javac ")) {
                if (!added) replaced.addAll(build);
                added = true;
            } else {
                replaced.add(c);
            }
        }
        return replaced;
    }

    private static List<CodingPlan.Command> one(String command) {
        return List.of(new CodingPlan.Command(command, ""));
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
