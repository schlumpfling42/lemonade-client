package net.rka.ai.eval;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import net.rka.ai.agent.Agent;
import net.rka.ai.agent.Approver;
import net.rka.ai.agent.ConversationSummary;
import net.rka.ai.agent.PlanDecision;
import net.rka.ai.agent.PlanReview;
import net.rka.ai.config.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Simple coding tasks run against the real model, to see what the coding flow achieves (the unit tests use scripted models and only prove
 * the plumbing). Not part of the build: it needs the model server of {@code .env} and takes minutes.
 * <pre>
 *   $env:CODING_EVAL = "true"; .\gradlew.bat test --tests "*CodingEval*"            (all tasks)
 *   $env:CODING_EVAL = "true"; $env:CODING_EVAL_ONLY = "fix-bug"; .\gradlew.bat test --tests "*CodingEval*"
 * </pre>
 * Each task is a few files, a goal written as a person would write it, and a check of the files afterwards (and that the Java still
 * compiles). The result table is printed and written to {@code build/eval/coding-eval.md} with what the model said and the final files'
 * state, so a change to prompts, limits or models can be judged by how many tasks pass and how long they take.
 */
@EnabledIfEnvironmentVariable(named = "CODING_EVAL", matches = "true")
class CodingEval {

    private record Task(String name, Map<String, String> files, String goal, Function<Path, String> check) {
    }

    private static final String GREETER = "public class Greeter {\n    public String greet(String name) {\n        return \"Hello, \" + name;\n    }\n}\n";
    private static final String COUNTER = "public class Counter {\n    private int count;\n\n    public void increment() {\n        count--;\n    }\n\n    public int value() {\n        return count;\n    }\n}\n";
    private static final String MENU_BUTTON = "import java.util.ArrayList;\nimport java.util.List;\n\npublic class MenuButton {\n    private final List<String> styleClass = new ArrayList<>();\n\n    public List<String> getStyleClass() {\n        return styleClass;\n    }\n}\n";
    private static final String MENU = "public class Menu {\n    public MenuButton folderMenu() {\n        MenuButton menu = new MenuButton();\n        menu.getStyleClass().add(\"folder-menu\");\n        return menu;\n    }\n\n    public MenuButton rowMenu() {\n        MenuButton menu = new MenuButton();\n        return menu;\n    }\n}\n";
    private static final String MAIN = "public class Main {\n    public static void main(String[] args) {\n        Greeter greeter = new Greeter();\n        System.out.println(greeter.greet(\"Ann\"));\n    }\n}\n";

    private static List<Task> tasks() {
        List<Task> tasks = new ArrayList<>();
        tasks.add(new Task("change-text", Map.of("Greeter.java", GREETER),
                "In Greeter.greet change the greeting from Hello to Hi.",
                dir -> contains(dir, "Greeter.java", "\"Hi, \"") + notContains(dir, "Greeter.java", "\"Hello, \"") + compiles(dir)));
        tasks.add(new Task("add-method", Map.of("Counter.java", COUNTER),
                "Add a method reset() to Counter that sets the count back to 0.",
                dir -> contains(dir, "Counter.java", "void reset()") + contains(dir, "Counter.java", "count = 0") + compiles(dir)));
        tasks.add(new Task("fix-bug", Map.of("Counter.java", COUNTER),
                "Counter.increment goes down instead of up. Fix it.",
                dir -> notContains(dir, "Counter.java", "count--") + (read(dir, "Counter.java").contains("count++") || read(dir, "Counter.java").contains("count += 1")
                        || read(dir, "Counter.java").contains("count = count + 1") ? "" : "increment does not add one; ") + compiles(dir)));
        tasks.add(new Task("style-like-reference", Map.of("MenuButton.java", MENU_BUTTON, "Menu.java", MENU),
                "The menu button that rowMenu in Menu creates should look like the one folderMenu creates: give it the same style class.",
                dir -> {
                    String menu = read(dir, "Menu.java");
                    int row = menu.indexOf("rowMenu");
                    return (row >= 0 && menu.indexOf("folder-menu", row) > row ? "" : "rowMenu has no folder-menu style class; ")
                            + (menu.indexOf("folder-menu") < menu.indexOf("rowMenu") ? "" : "the style class was moved out of folderMenu; ") + compiles(dir);
                }));
        tasks.add(new Task("build-file", Map.of("build.gradle", "plugins {\n    id 'java'\n}\n\ngroup = 'demo'\nversion = '1.0'\n"),
                "Change the version in build.gradle to 1.1.",
                dir -> contains(dir, "build.gradle", "version = '1.1'") + notContains(dir, "build.gradle", "'1.0'") + contains(dir, "build.gradle", "id 'java'")));
        tasks.add(new Task("css-only", Map.of("style.css", ".title {\n    color: black;\n    font-size: 12px;\n}\n\n.footer {\n    color: gray;\n}\n"),
                "Make the title in style.css bigger: 18px.",
                dir -> contains(dir, "style.css", "font-size: 18px") + notContains(dir, "style.css", "12px") + contains(dir, "style.css", ".footer")));
        tasks.add(new Task("java-and-css", Map.of("Page.java", "public class Page {\n    public String render() {\n        return \"<h1>Hi</h1>\";\n    }\n}\n",
                "style.css", ".title {\n    color: black;\n}\n"),
                "The heading Page.render produces should be red: give it the class title in Page and make .title red in style.css.",
                dir -> contains(dir, "style.css", "red") + contains(dir, "Page.java", "title") + compiles(dir)));
        tasks.add(new Task("two-files", Map.of("Greeter.java", GREETER, "Main.java", MAIN),
                "Add a method farewell(String name) to Greeter that returns \"Bye, \" and the name, and print it in Main.main after the greeting.",
                dir -> contains(dir, "Greeter.java", "farewell(String name)") + contains(dir, "Greeter.java", "\"Bye, \"")
                        + contains(dir, "Main.java", "farewell(") + compiles(dir)));
        return tasks;
    }

    @Test
    void theCodingFlowOnSimpleTasks() throws Exception {
        String only = System.getenv("CODING_EVAL_ONLY");
        Path out = Files.createDirectories(Path.of("build", "eval"));
        Approver approveAll = new Approver() {
            @Override
            public Map<String, Boolean> review(List<ToolExecutionRequest> calls) {
                Map<String, Boolean> decisions = new HashMap<>();
                calls.forEach(call -> decisions.put(call.id(), true));
                return decisions;
            }

            @Override
            public PlanDecision reviewPlan(PlanReview review) {
                return new PlanDecision(PlanDecision.Kind.APPROVE, "");
            }
        };
        StringBuilder report = new StringBuilder("# Coding eval\n\n| task | result | seconds | note |\n|---|---|---|---|\n");
        StringBuilder details = new StringBuilder();
        int passed = 0;
        int total = 0;
        try (Agent agent = Agent.fromEnv(approveAll)) {
            report.insert(0, "Model: " + agent.modelName() + " @ " + agent.baseUrl() + "\n\n");
            for (Task task : tasks()) {
                if (only != null && !only.isBlank() && !List.of(only.split(",")).contains(task.name())) continue;
                total++;
                Path dir = fresh(out.resolve("work").resolve(task.name()));
                for (Map.Entry<String, String> file : task.files().entrySet()) Files.writeString(dir.resolve(file.getKey()), file.getValue());
                long start = System.nanoTime();
                String note = "";
                String answer = "";
                try {
                    List<ChatMessage> history = Agent.newConversation();
                    history.add(UserMessage.from(task.goal()));
                    List<ChatMessage> produced = agent.run(history, Agent.newThreadId(), new Settings(dir, List.of()), ConversationSummary.EMPTY, List.of()).messages();
                    answer = produced.isEmpty() ? "" : produced.get(produced.size() - 1) instanceof AiMessage ai && ai.text() != null ? ai.text() : "";
                } catch (RuntimeException e) {
                    note = "the turn failed: " + e.getMessage();
                }
                long seconds = Math.round((System.nanoTime() - start) / 1e9);
                String failure = note.isEmpty() ? task.check().apply(dir) : note;
                boolean ok = failure.isBlank();
                if (ok) passed++;
                report.append("| ").append(task.name()).append(" | ").append(ok ? "pass" : "FAIL").append(" | ").append(seconds).append(" | ")
                        .append(failure.replace('\n', ' ').replace("|", "/")).append(" |\n");
                details.append("\n## ").append(task.name()).append(" (").append(ok ? "pass" : "FAIL").append(")\n\nGoal: ").append(task.goal())
                        .append("\n\nWhat the assistant said:\n\n").append(answer.strip()).append("\n\nFiles after:\n");
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.sorted().toList()) {
                        if (Files.isRegularFile(file)) details.append("\n### ").append(file.getFileName()).append("\n```\n").append(Files.readString(file)).append("```\n");
                    }
                }
            }
        }
        report.append("\n**").append(passed).append(" of ").append(total).append(" passed**\n").append(details);
        Files.writeString(out.resolve("coding-eval.md"), report.toString(), StandardCharsets.UTF_8);
        System.out.println(report.substring(0, report.indexOf("\n## ") < 0 ? report.length() : report.indexOf("\n## ")));
    }

    // ---- checks ----

    private static Path fresh(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
        return Files.createDirectories(dir);
    }

    private static String read(Path dir, String name) {
        try {
            return Files.readString(dir.resolve(name));
        } catch (Exception e) {
            return "";
        }
    }

    private static String contains(Path dir, String name, String text) {
        return read(dir, name).contains(text) ? "" : name + " lacks " + text + "; ";
    }

    private static String notContains(Path dir, String name, String text) {
        return read(dir, name).contains(text) ? name + " still has " + text + "; " : "";
    }

    /** The Java files of the folder compile (a build file is not Java: nothing to compile). */
    private static String compiles(Path dir) {
        try (Stream<Path> files = Files.list(dir)) {
            List<String> sources = files.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
            if (sources.isEmpty()) return "";
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            Path classes = Files.createTempDirectory("eval-classes");
            List<String> args = new ArrayList<>(List.of("-proc:none", "-d", classes.toString()));
            args.addAll(sources);
            java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
            int code = compiler.run(null, null, errors, args.toArray(String[]::new));
            return code == 0 ? "" : "does not compile: " + errors.toString(StandardCharsets.UTF_8).lines().findFirst().orElse("") + "; ";
        } catch (Exception e) {
            return "could not check the compile: " + e.getMessage() + "; ";
        }
    }
}
