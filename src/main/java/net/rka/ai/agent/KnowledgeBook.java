package net.rka.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.rka.ai.config.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What the client knows that a small local model does not, kept apart from the conversation and given to a job only when the goal is
 * about it. Two kinds:
 * <ul>
 *   <li><b>Recipes</b>: curated know-how for a kind of work, as Markdown with a header
 *       {@code ---\nkeywords: new spring boot project, spring initializr\n---}. The built-in ones are in
 *       {@code net/rka/ai/agent/recipes}, yours in {@code ~/chat/recipes} (the same name replaces a built-in). A recipe matches a goal when
 *       one of its keyword phrases is in it, word for word.</li>
 *   <li><b>Lessons</b>: what failed before, written by the client when something fails ("the setup command
 *       {@code Invoke-WebRequest ...dlcdn.apache.org...} failed: ..."), in {@code ~/chat/lessons.json}. A lesson matches a goal that shares
 *       at least two of its significant words (one when it has only one), so the same mistake is not planned again.</li>
 * </ul>
 * Why: the model once planned to install Maven by downloading it from a mirror that failed, because the environment note said "mvn: not
 * installed"; a person knows that Spring Initializr's project carries the Maven wrapper. Every token sent is read at about 90 a second,
 * so only the matching entries go in, at most {@code maxChars}.
 */
public final class KnowledgeBook {

    /** The built-in recipes, by file name in {@code net/rka/ai/agent/recipes}. */
    static final List<String> BUILT_IN = List.of("spring-boot-new");
    static final int MAX_LESSONS = 100;
    private static final int MAX_RECIPES = 2;
    private static final int MAX_LESSONS_SHOWN = 3;
    private static final Set<String> STOP = Set.of("with", "that", "this", "from", "into", "have", "will", "make", "sure", "then", "also",
            "should", "would", "could", "there", "their", "them", "than", "when", "what", "which", "where", "your", "about", "using", "used",
            "uses", "please", "create", "build", "project", "test", "tests", "and", "the", "for", "new");
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
    private static volatile KnowledgeBook shared;

    /** A recipe: its name, the keyword phrases that select it and its text. */
    record Recipe(String id, List<String> keywords, String text) {
    }

    /** A lesson: the significant words of the goal it came from, what was learned, and when. */
    record Lesson(List<String> keywords, String text, String at) {
    }

    private final List<Recipe> recipes;
    private final Path lessonsFile;

    KnowledgeBook(List<Recipe> recipes, Path lessonsFile) {
        this.recipes = List.copyOf(recipes);
        this.lessonsFile = lessonsFile;
    }

    /** The book of the application: the built-in recipes, {@code ~/chat/recipes} and {@code ~/chat/lessons.json}. */
    public static KnowledgeBook shared() {
        KnowledgeBook book = shared;
        if (book == null) {
            Path chat = Settings.globalFile().resolveSibling("recipes");
            String lessons = System.getProperty("lemonade.lessons"); // set by the tests: they must not write into the person's file
            book = load(chat, lessons == null ? chat.resolveSibling("lessons.json") : Path.of(lessons));
            shared = book;
        }
        return book;
    }

    /** For tests: the book {@link #shared()} returns (null: load it again from the files). */
    static void useForTests(KnowledgeBook book) {
        shared = book;
    }

    /** The built-in recipes with the ones in {@code userRecipes} (a missing folder is fine), and the lessons file. */
    static KnowledgeBook load(Path userRecipes, Path lessonsFile) {
        Map<String, Recipe> byId = new LinkedHashMap<>();
        for (String name : BUILT_IN) {
            try (InputStream in = KnowledgeBook.class.getResourceAsStream("recipes/" + name + ".md")) {
                if (in != null) parse(name, new String(in.readAllBytes(), StandardCharsets.UTF_8)).ifPresent(r -> byId.put(r.id(), r));
            } catch (IOException e) {
                // a built-in that cannot be read is left out: the job runs without it
            }
        }
        if (Files.isDirectory(userRecipes)) {
            try (Stream<Path> files = Files.list(userRecipes)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".md")).sorted().toList()) {
                    String id = file.getFileName().toString().replaceFirst("\\.md$", "");
                    parse(id, Files.readString(file, StandardCharsets.UTF_8)).ifPresent(r -> byId.put(r.id(), r));
                }
            } catch (IOException e) {
                // an unreadable folder: the built-ins stay
            }
        }
        return new KnowledgeBook(new ArrayList<>(byId.values()), lessonsFile);
    }

    /** A recipe file: a header with {@code keywords:} between two {@code ---} lines, then the text. Empty when it has no keywords. */
    static java.util.Optional<Recipe> parse(String id, String markdown) {
        String text = markdown.replace("\r\n", "\n").strip();
        if (!text.startsWith("---\n")) return java.util.Optional.empty();
        int end = text.indexOf("\n---", 4);
        if (end < 0) return java.util.Optional.empty();
        List<String> keywords = new ArrayList<>();
        for (String line : text.substring(4, end).split("\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("keywords:")) {
                for (String phrase : line.substring("keywords:".length()).split(",")) {
                    String normal = normal(phrase);
                    if (!normal.isBlank()) keywords.add(normal);
                }
            }
        }
        String body = text.substring(end + 4).strip();
        return keywords.isEmpty() || body.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(new Recipe(id, keywords, body));
    }

    // ---- what a goal gets ----

    /**
     * The know-how for a goal: the recipes whose keywords it contains (best match first, at most two), then the lessons of earlier
     * failures about the same kind of work, cut to {@code maxChars}. "" when nothing matches.
     */
    public String forGoal(String goal, int maxChars) {
        String normal = " " + normal(goal) + " ";
        List<Recipe> matching = recipes.stream()
                .filter(r -> score(r, normal) > 0)
                .sorted(Comparator.comparingInt((Recipe r) -> score(r, normal)).reversed())
                .limit(MAX_RECIPES).toList();
        List<Lesson> lessons = lessonsFor(goal);
        if (matching.isEmpty() && lessons.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        // What failed before comes first: it is short and decides things, and a cut at the budget takes the end of a recipe, not a lesson
        if (!lessons.isEmpty()) {
            out.append("### What failed before on this kind of work (do not do it again)\n");
            lessons.forEach(l -> out.append("- ").append(l.text()).append('\n'));
            out.append('\n');
        }
        for (Recipe recipe : matching) out.append("### ").append(recipe.id()).append('\n').append(recipe.text()).append("\n\n");
        String text = out.toString().stripTrailing();
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "\n[... cut ...]";
    }

    private static int score(Recipe recipe, String normalGoal) {
        int score = 0;
        for (String keyword : recipe.keywords()) {
            if (normalGoal.contains(" " + keyword + " ")) score += keyword.split(" ").length; // a longer phrase is a better match
        }
        return score;
    }

    /** Lowercase, punctuation and separators as single spaces: "Spring-Boot, new" is "spring boot new". */
    static String normal(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    // ---- lessons ----

    /** The significant words of a goal: four letters or more, no filler, in order, at most ten. */
    static List<String> words(String goal) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : normal(goal).split(" ")) {
            if (word.length() >= 4 && !STOP.contains(word)) words.add(word);
            if (words.size() >= 10) break;
        }
        return new ArrayList<>(words);
    }

    private List<Lesson> lessonsFor(String goal) {
        List<String> words = words(goal);
        return readLessons().stream()
                .filter(l -> l.keywords().stream().filter(words::contains).count() >= Math.min(2, l.keywords().size()))
                .sorted(Comparator.comparing(Lesson::at).reversed())
                .limit(MAX_LESSONS_SHOWN).toList();
    }

    /** Remembers what failed, for goals that share words with {@code goal}; the same lesson twice is kept once. Best effort. */
    public synchronized void learn(String goal, String lesson) {
        String text = lesson.replaceAll("\\s+", " ").strip();
        List<String> words = words(goal);
        if (text.isEmpty() || words.isEmpty() || lessonsFile == null) return;
        List<Lesson> all = new ArrayList<>(readLessons());
        if (all.stream().anyMatch(l -> l.text().equals(text))) return;
        all.add(new Lesson(words, text, Instant.now().toString()));
        if (all.size() > MAX_LESSONS) all = new ArrayList<>(all.subList(all.size() - MAX_LESSONS, all.size()));
        try {
            Files.createDirectories(lessonsFile.toAbsolutePath().getParent());
            ArrayNode array = JSON.createArrayNode();
            for (Lesson l : all) {
                ObjectNode node = array.addObject();
                node.set("keywords", JSON.valueToTree(l.keywords()));
                node.put("text", l.text());
                node.put("at", l.at());
            }
            Files.writeString(lessonsFile, JSON.writeValueAsString(array), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // a lesson that cannot be kept is lost, the job is not
        }
    }

    synchronized List<Lesson> readLessons() {
        if (lessonsFile == null || !Files.isRegularFile(lessonsFile)) return List.of();
        try {
            JsonNode root = JSON.readTree(Files.readString(lessonsFile, StandardCharsets.UTF_8));
            List<Lesson> lessons = new ArrayList<>();
            for (JsonNode node : root) {
                List<String> keywords = new ArrayList<>();
                node.path("keywords").forEach(k -> keywords.add(k.asText()));
                lessons.add(new Lesson(keywords, node.path("text").asText(""), node.path("at").asText("")));
            }
            return lessons;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
