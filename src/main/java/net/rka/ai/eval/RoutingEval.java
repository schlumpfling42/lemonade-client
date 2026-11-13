package net.rka.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import net.rka.ai.agent.Agent;
import net.rka.ai.agent.RoutingProbe;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * The routing check: for every case of {@code evals/routing/cases.json} (a conversation and a latest message, with the route they should
 * take) it asks the model what the old question (triage) and the new analysis decide, and reports how many each got right and how long
 * they took. Nothing is run: only the decision is made. The cases come from real messages; the expected routes are a reading of what the
 * user wants and are meant to be corrected.
 *
 * <pre>
 * .\gradlew.bat runRouting                              all cases
 * .\gradlew.bat runRouting --args="--only status-where-are-we,delete-yes"
 * .\gradlew.bat runRouting --args="--run name"          the report goes to evals/results/routing-name.md
 * </pre>
 * A case's {@code expect} is {@code agent}, {@code job} (any job edge) or one edge ({@code job_plan}, {@code job_study}, {@code job_free}).
 */
public final class RoutingEval {

    private static final ObjectMapper JSON = new ObjectMapper();

    private RoutingEval() {
    }

    record Case(String id, List<ChatMessage> conversation, String expect, String source) {
    }

    public static void main(String[] args) throws Exception {
        String only = "";
        String run = "routing";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--only" -> only = args[++i];
                case "--run" -> run = args[++i];
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        Path root = Path.of(System.getProperty("evals.dir", "evals"));
        List<Case> cases = load(root.resolve("routing").resolve("cases.json"), only);
        StringBuilder report = new StringBuilder("# Routing check ").append(run).append("\n\n");
        report.append("| case | expected | old | old s | new | new s | goal (new) |\n|---|---|---|---|---|---|---|\n");
        int oldRight = 0;
        int newRight = 0;
        double oldSeconds = 0;
        double newSeconds = 0;
        try (Agent agent = Agent.fromEnv(new AutoApprover())) {
            System.out.println("Model: " + agent.modelName() + " @ " + agent.baseUrl() + " · " + cases.size() + " cases");
            for (Case c : cases) {
                RoutingProbe.Result old = RoutingProbe.triage(agent.model(), c.conversation());
                RoutingProbe.Result now = RoutingProbe.analyze(agent.model(), c.conversation());
                boolean oldOk = matches(c.expect(), old.route());
                boolean newOk = matches(c.expect(), now.route());
                oldRight += oldOk ? 1 : 0;
                newRight += newOk ? 1 : 0;
                oldSeconds += old.seconds();
                newSeconds += now.seconds();
                System.out.printf("%-34s expected %-9s old %-6s %s (%.0f s)   new %-9s %s (%.0f s)%n", c.id(), c.expect(), old.route(), oldOk ? "ok " : "WRONG",
                        old.seconds(), now.route(), newOk ? "ok " : "WRONG", now.seconds());
                report.append("| ").append(c.id()).append(" | ").append(c.expect()).append(" | ").append(old.route()).append(oldOk ? "" : " ✗")
                        .append(" | ").append(Math.round(old.seconds())).append(" | ").append(now.route()).append(newOk ? "" : " ✗")
                        .append(" | ").append(Math.round(now.seconds())).append(" | ").append(now.goal().replace("|", "/")).append(" |\n");
            }
            int n = Math.max(1, cases.size());
            String summary = String.format("old: %d of %d right, %.1f s on average · new: %d of %d right, %.1f s on average", oldRight, cases.size(), oldSeconds / n,
                    newRight, cases.size(), newSeconds / n);
            System.out.println("\n" + summary);
            report.append("\n").append(summary).append("\n");
            Path results = root.resolve("results");
            Files.createDirectories(results);
            Files.writeString(results.resolve("routing-" + run + ".md"), report.toString());
        }
        System.exit(0); // the agent's threads must not keep the process alive
    }

    static boolean matches(String expect, String route) {
        return expect.equals("job") ? route.startsWith("job") : expect.equals(route);
    }

    static List<Case> load(Path file, String only) throws Exception {
        Set<String> wanted = only.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(only.split("\\s*,\\s*")));
        List<Case> cases = new ArrayList<>();
        for (JsonNode node : JSON.readTree(file.toFile())) {
            String id = node.path("id").asText();
            if (!wanted.isEmpty() && !wanted.contains(id)) continue;
            List<ChatMessage> conversation = new ArrayList<>();
            conversation.add(SystemMessage.from("system"));
            for (JsonNode m : node.path("history")) {
                conversation.add(m.path("role").asText().equals("user") ? UserMessage.from(m.path("text").asText()) : AiMessage.from(m.path("text").asText()));
            }
            conversation.add(UserMessage.from(node.path("message").asText()));
            cases.add(new Case(id, conversation, node.path("expect").asText(), node.path("source").asText()));
        }
        return cases;
    }
}
