package net.rka.ai.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Design guides for coding jobs: per language, the rules code should follow to be readable and easy to
 * change (instances over static, small classes, immutability, ...), so the model writes and reviews code
 * against them instead of stopping at "it builds".
 * <p>
 * How it works:
 * <ul>
 *   <li>A guide is Markdown named after its language: {@code java.md}. Built-in guides ship with the
 *       app ({@link #BUILT_IN}, under {@code net/rka/ai/agent/guides/}); your own rules live in
 *       {@code ~/chat/guides/<language>.md} (created as a starter file for Java). A language can have
 *       only your file, e.g. a {@code svelte.md} you write yourself.</li>
 *   <li>A job uses the guide of every language its goal or findings name as a word ("java"), or whose
 *       extension a plan file has ({@code .java}). Your rules are appended after the built-in ones and
 *       win where they conflict.</li>
 *   <li>Files are read each time a job asks, so edits apply to the next job without a restart.</li>
 * </ul>
 * Not implemented yet (could be added): choosing only the rules relevant to the job (the guides are
 * kept short instead), guides per project (a {@code .guides/} folder in the trusted folder), and
 * guides for frameworks within a language (Spring, JavaFX).
 */
final class DesignGuides {

    /** Languages with a built-in guide. */
    static final List<String> BUILT_IN = List.of("java");

    /** No guides at all (tests, or when design review is off). */
    static final DesignGuides NONE = new DesignGuides(null, List.of());

    private static final String STARTER = """
            <!-- Your Java design rules. Coding jobs follow them on top of the built-in guide, and yours
                 win where they conflict. Write one rule per line or paragraph, with the reason, e.g.:
            - Services get their collaborators through the constructor; no field injection.
            -->
            """;

    private static final Pattern LANGUAGE_NAME = Pattern.compile("[a-z][a-z0-9]*");

    private final Path userDir;
    private final List<String> builtIn;

    /** @param userDir your guides ({@code ~/chat/guides}); null for the built-in guides only */
    DesignGuides(Path userDir) {
        this(userDir, BUILT_IN);
    }

    private DesignGuides(Path userDir, List<String> builtIn) {
        this.userDir = userDir;
        this.builtIn = builtIn;
    }

    /** Creates {@code java.md} in your guides folder with a short note, if it doesn't exist yet. */
    void createStarterFiles() {
        if (userDir == null) return;
        try {
            Files.createDirectories(userDir);
            Path java = userDir.resolve("java.md");
            if (!Files.exists(java)) Files.writeString(java, STARTER);
        } catch (IOException e) {
            System.getLogger(DesignGuides.class.getName())
                    .log(System.Logger.Level.WARNING, "Could not create the starter design guide in " + userDir, e);
        }
    }

    /** Every language with a guide: built-in ones and the ones in your folder. */
    Set<String> languages() {
        Set<String> all = new TreeSet<>();
        all.addAll(builtIn);
        if (userDir != null && Files.isDirectory(userDir)) {
            try (Stream<Path> files = Files.list(userDir)) {
                files.map(f -> f.getFileName().toString())
                        .filter(n -> n.endsWith(".md"))
                        .map(n -> n.substring(0, n.length() - 3).toLowerCase(Locale.ROOT))
                        // A language name is a word (java, svelte, kotlin); ".md" or "my notes.md" isn't one
                        .filter(LANGUAGE_NAME.asMatchPredicate())
                        .forEach(all::add);
            } catch (IOException ignored) {
                // no user guides then
            }
        }
        return all;
    }

    /**
     * The languages a job is about: named as a word in its goal or findings, or the extension of a file
     * the plan creates or modifies.
     */
    Set<String> languagesFor(String goalAndFindings, List<String> planPaths) {
        Set<String> found = new TreeSet<>();
        String text = goalAndFindings == null ? "" : goalAndFindings.toLowerCase(Locale.ROOT);
        for (String language : languages()) {
            boolean named = Pattern.compile("\\b" + Pattern.quote(language) + "\\b").matcher(text).find();
            boolean file = planPaths.stream().anyMatch(p -> p.toLowerCase(Locale.ROOT).endsWith("." + language));
            if (named || file) found.add(language);
        }
        return found;
    }

    /** The guide text for these languages ("" when none has a guide): built-in rules, then yours. */
    String text(Set<String> languages) {
        List<String> parts = new ArrayList<>();
        for (String language : languages) {
            String builtInText = builtIn.contains(language) ? builtIn(language) : "";
            String yours = userRules(language);
            if (builtInText.isBlank() && yours.isBlank()) continue;
            StringBuilder guide = new StringBuilder("## ").append(title(language)).append(" design guide\n\n");
            if (!builtInText.isBlank()) guide.append(builtInText.strip()).append("\n");
            if (!yours.isBlank()) {
                guide.append("\n### The user's own rules (these win over the rules above)\n\n").append(yours.strip()).append("\n");
            }
            parts.add(guide.toString());
        }
        return String.join("\n", parts);
    }

    /** Whether a file is one these languages' guides are about (its extension is the language). */
    static boolean isAbout(String path, Set<String> languages) {
        String lower = path.toLowerCase(Locale.ROOT);
        return languages.stream().anyMatch(language -> lower.endsWith("." + language));
    }

    private static String builtIn(String language) {
        try (InputStream in = DesignGuides.class.getResourceAsStream("guides/" + language + ".md")) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Your file without HTML comments (the starter note), so an untouched starter adds nothing. */
    private String userRules(String language) {
        if (userDir == null) return "";
        Path file = userDir.resolve(language + ".md");
        try {
            return Files.isRegularFile(file)
                    ? Files.readString(file).replaceAll("(?s)<!--.*?-->", "").strip()
                    : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static String title(String language) {
        return language.substring(0, 1).toUpperCase(Locale.ROOT) + language.substring(1);
    }
}
