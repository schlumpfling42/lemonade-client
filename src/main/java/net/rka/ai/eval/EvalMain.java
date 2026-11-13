package net.rka.ai.eval;

import net.rka.ai.agent.Agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Runs the evaluation cases against the real agent and model and writes a report.
 * <pre>
 * .\gradlew.bat runEval                                  every case in evals/cases
 * .\gradlew.bat runEval --args="--only add-method,fix-bug"   some cases
 * .\gradlew.bat runEval --args="--run after-groom --baseline before-groom"
 * .\gradlew.bat runEval --args="--list"                  the cases, without running them
 * </pre>
 * Results are in {@code evals/results/<run>/}: {@code report.md} and {@code summary.json}, and per case the agent log and the project folder as
 * the agent left it. With no {@code --baseline} the run is compared with the newest earlier run. The exit code is 1 when a case failed.
 */
public final class EvalMain {

    private EvalMain() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("evals");
        Path casesFolder = root.resolve("cases");
        String only = "";
        String runName = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String baselineName = "";
        boolean list = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--only" -> only = args[++i];
                case "--run" -> runName = args[++i];
                case "--baseline" -> baselineName = args[++i];
                case "--cases" -> casesFolder = Path.of(args[++i]);
                case "--list" -> list = true;
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        List<EvalCase> cases = load(casesFolder, only);
        if (list) {
            cases.forEach(c -> System.out.println(c.id() + " — " + c.description()));
            return;
        }
        if (cases.isEmpty()) {
            System.out.println("No cases in " + casesFolder.toAbsolutePath());
            return;
        }
        Path results = root.resolve("results");
        Path runFolder = results.resolve(runName);
        EvalReport.Summary baseline = baseline(results, baselineName, runName);

        AutoApprover approver = new AutoApprover();
        List<CaseResult> done = new ArrayList<>();
        String model;
        try (Agent agent = Agent.fromEnv(approver)) {
            model = agent.modelName() + " @ " + agent.baseUrl();
            System.out.println("Model: " + model + " · " + cases.size() + " cases → " + runFolder);
            EvalRunner runner = new EvalRunner(agent, approver, root.resolve("fixtures"));
            for (EvalCase c : cases) {
                System.out.print(c.id() + " … ");
                System.out.flush();
                CaseResult result = runner.run(c, runFolder);
                done.add(result);
                System.out.println((result.passed() ? "pass" : "FAIL") + " in " + Math.round(result.seconds()) + " s · " + result.metrics().summary());
                result.checks().stream().filter(r -> !r.ok()).forEach(r -> System.out.println("    ✗ " + r.check().describe() + ": " + r.detail()));
                if (!result.error().isEmpty()) System.out.println("    ✗ " + result.error());
                // Written after every case: a run stopped halfway still has its results
                EvalReport.write(new EvalReport.Summary(runName, LocalDateTime.now().toString(), model, done), baseline, runFolder);
            }
        }
        EvalReport.Summary summary = new EvalReport.Summary(runName, LocalDateTime.now().toString(), model, done);
        EvalReport.write(summary, baseline, runFolder);
        System.out.println("\n" + summary.passed() + " of " + done.size() + " passed · " + runFolder.resolve("report.md"));
        System.exit(summary.passed() == done.size() ? 0 : 1); // the agent's threads must not keep the process alive
    }

    static List<EvalCase> load(Path folder, String only) throws Exception {
        Set<String> wanted = only.isBlank() ? Set.of() : Set.copyOf(Arrays.asList(only.split("\\s*,\\s*")));
        if (!Files.isDirectory(folder)) return List.of();
        List<EvalCase> cases = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json")).sorted()::iterator) {
                EvalCase c = EvalCase.load(file);
                if (wanted.isEmpty() || wanted.contains(c.id())) cases.add(c);
            }
        }
        return cases;
    }

    /** The named run, or the newest earlier one; null when there is none. */
    static EvalReport.Summary baseline(Path results, String name, String current) throws Exception {
        if (!Files.isDirectory(results)) return null;
        if (!name.isBlank()) return EvalReport.read(results.resolve(name).resolve("summary.json"));
        try (Stream<Path> runs = Files.list(results)) {
            Path newest = runs.filter(p -> !p.getFileName().toString().equals(current) && Files.isRegularFile(p.resolve("summary.json")))
                    .max(Comparator.comparing(p -> p.getFileName().toString())).orElse(null);
            return newest == null ? null : EvalReport.read(newest.resolve("summary.json"));
        }
    }
}
