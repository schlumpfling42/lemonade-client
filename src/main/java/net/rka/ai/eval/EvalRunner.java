package net.rka.ai.eval;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import net.rka.ai.agent.Agent;
import net.rka.ai.agent.ConversationSummary;
import net.rka.ai.agent.Thought;
import net.rka.ai.config.Settings;
import net.rka.ai.tracing.AgentLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Runs cases against the real agent and model. Each case gets a fresh copy of its fixture in {@code <run folder>/<id>/project}, its own agent
 * log in {@code <run folder>/<id>/}, a new conversation with the prompt as the user's message, and an {@link AutoApprover}. The folder is
 * kept after the run, so a failed case can be opened and looked at.
 */
public final class EvalRunner {

    private final Agent agent;
    private final AutoApprover approver;
    private final Path fixtures;

    /**
     * @param agent    built with {@code approver} as its approver (the agent asks the one it was built with)
     * @param fixtures the folder with one folder per fixture
     */
    EvalRunner(Agent agent, AutoApprover approver, Path fixtures) {
        this.agent = agent;
        this.approver = approver;
        this.fixtures = fixtures;
    }

    CaseResult run(EvalCase evalCase, Path runFolder) {
        Path caseFolder = runFolder.resolve(evalCase.id());
        Path project = caseFolder.resolve("project");
        Path fixture = evalCase.fixture().isBlank() ? null : fixtures.resolve(evalCase.fixture());
        approver.reset();
        long start = System.nanoTime();
        String error = "";
        boolean timedOut = false;
        String answer = "";
        String jobReport = "";
        AgentLog log = new AgentLog(AgentLog.Level.SUMMARY, caseFolder, false);
        try {
            Files.createDirectories(project);
            if (fixture != null) copy(fixture, project);
            AgentLog.configure(log);
            List<ChatMessage> history = Agent.newConversation();
            history.add(UserMessage.from(evalCase.prompt()));
            Settings settings = new Settings(project.toAbsolutePath(), List.of());
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "eval-" + evalCase.id());
                t.setDaemon(true);
                return t;
            });
            Future<Agent.Turn> turn = executor.submit(() -> agent.run(history, Agent.newThreadId(), settings, ConversationSummary.EMPTY, List.of()));
            try {
                List<ChatMessage> produced = turn.get(evalCase.timeoutMinutes(), TimeUnit.MINUTES).messages();
                answer = finalAnswer(produced);
                jobReport = jobReport(produced);
            } catch (TimeoutException e) {
                timedOut = true;
                error = "timed out after " + evalCase.timeoutMinutes() + " minutes";
                turn.cancel(true);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            } finally {
                executor.shutdownNow();
            }
        } catch (IOException | RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        EvalMetrics metrics = metricsOf(log);
        List<EvalChecks.Result> results = new ArrayList<>(EvalChecks.run(evalCase.checks(), project, fixture, answer));
        if (evalCase.expectCodingJob() != null) {
            boolean ran = metrics.triagedCoding() || metrics.callsByPhase().keySet().stream().anyMatch(p -> p.startsWith("skill/coding"));
            boolean ok = ran == evalCase.expectCodingJob();
            results.add(new EvalChecks.Result(new EvalCase.Check("codingJob", null, null, null, null, null, null), ok,
                    ok ? "" : (ran ? "a coding job ran" : "no coding job ran")));
        }
        boolean passed = error.isEmpty() && results.stream().allMatch(EvalChecks.Result::ok);
        return new CaseResult(evalCase.id(), evalCase.description(), passed, seconds, timedOut, error, clip(answer, 3000), clip(jobReport, 3000),
                results, metrics, approver.asked());
    }

    private static EvalMetrics metricsOf(AgentLog log) {
        try {
            Path file = log.file();
            return EvalMetrics.parse(file != null && Files.isRegularFile(file) ? Files.readAllLines(file) : List.of());
        } catch (IOException | RuntimeException e) {
            return EvalMetrics.parse(List.of());
        }
    }

    private static String finalAnswer(List<ChatMessage> produced) {
        for (int i = produced.size() - 1; i >= 0; i--) {
            if (produced.get(i) instanceof AiMessage ai && !ai.hasToolExecutionRequests()) return Thought.of(ai).text();
        }
        return "";
    }

    private static String jobReport(List<ChatMessage> produced) {
        for (ChatMessage message : produced) {
            if (message instanceof ToolExecutionResultMessage result && "startCodingJob".equals(result.toolName())) return result.text();
        }
        return "";
    }

    private static String clip(String text, int max) {
        return text == null ? "" : text.length() > max ? text.substring(0, max) + "…" : text;
    }

    static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : (Iterable<Path>) files::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Deletes a folder tree (used by tests). */
    static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }
}
