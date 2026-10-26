package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import net.rka.ai.config.Settings;
import net.rka.ai.tools.CodingTools;
import net.rka.ai.tools.EnvironmentInfo;
import net.rka.ai.tools.FileTools;
import net.rka.ai.tools.TerminalTools;
import net.rka.ai.tools.ToolRegistry;
import net.rka.ai.tracing.Tracing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole turn through {@link Agent}: the main graph routes {@code startCodingJob} to the coding
 * subgraph, Agent pauses for the plan and asks the {@link Approver}, and the agent answers with the report.
 */
class CodingJobTest {

    private static final String GOAL = "Create a Java project";
    private static final String FINDINGS = "The folder is empty.";
    // One step that changes nothing and no verify commands: the job needs no real command
    private static final String PLAN = "{\"summary\":\"Look\",\"steps\":[{\"description\":\"Look around\"}]}";
    private static final String FINAL = "The coding job is done.";

    @TempDir
    Path dir;

    private Path folder;
    private ScriptedModel model;
    private PlanApprover approver;
    private Agent agent;

    /** Answers from a queue (failing on an unexpected request) and records every request. */
    private static final class ScriptedModel implements ChatModel {
        final Deque<AiMessage> answers = new ArrayDeque<>();
        final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            AiMessage answer = answers.poll();
            if (answer == null) throw new AssertionError("Unexpected model request: " + request.messages());
            return ChatResponse.builder().aiMessage(answer).build();
        }
    }

    /** Answers plans from a queue and records them; tool calls are denied unless {@link #approveCalls}. */
    private static final class PlanApprover implements Approver {
        final Deque<PlanDecision> decisions = new ArrayDeque<>();
        final List<CodingPlan> plans = new ArrayList<>();
        final List<PlanReview> reviews = new ArrayList<>();
        final List<ToolExecutionRequest> reviewedCalls = new ArrayList<>();
        boolean approveCalls;

        @Override
        public Map<String, Boolean> review(List<ToolExecutionRequest> calls) {
            reviewedCalls.addAll(calls);
            Map<String, Boolean> decisions = new HashMap<>();
            calls.forEach(call -> decisions.put(call.id(), approveCalls));
            return decisions;
        }

        @Override
        public PlanDecision reviewPlan(PlanReview review) {
            reviews.add(review);
            plans.add(review.plan());
            return decisions.remove();
        }
    }

    private static final ToolExecutionRequest START = ToolExecutionRequest.builder()
            .id("job1").name(CodingGraph.START_TOOL).arguments("{\"goal\":\"" + GOAL + "\"}").build();

    @BeforeEach
    void setUp() throws Exception {
        folder = Files.createDirectory(dir.resolve("work"));
        model = new ScriptedModel();
        approver = new PlanApprover();
        // The real class with a fake probe runner and a temporary cache file
        EnvironmentInfo environment = new EnvironmentInfo(dir.resolve("environment.json"),
                command -> "exit code: 0\n--- stdout ---\nv1 of " + command, Clock.systemUTC());
        agent = new Agent("test", "scripted", model, Tracing.off(),
                ToolRegistry.of(new FileTools(), new TerminalTools(), new CodingTools(), new CodingGraphTest.FakeWebTools()),
                approver, environment);
    }

    private void answers(String... texts) {
        Arrays.stream(texts).map(AiMessage::from).forEach(model.answers::add);
    }

    /** One turn: the user asks for the goal; returns the messages the turn produced. */
    private List<ChatMessage> run() {
        return run(List.of());
    }

    /** One turn with the given user-trusted commands. */
    private List<ChatMessage> run(List<String> trustedCommands) {
        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from(GOAL)));
        Settings settings = new Settings(folder, trustedCommands);
        return agent.run(history, "thread", settings, ConversationSummary.EMPTY, List.of()).messages();
    }

    private static String text(ChatRequest request) {
        return String.valueOf(request.messages());
    }

    /** The prompt of a node of the coding skill the agent loaded (the built-in coding.json). */
    private String codingPrompt(String node) {
        return agent.skillStatus().skill(CodingGraph.SKILL).orElseThrow().nodes().stream()
                .filter(n -> n.id().equals(node)).map(n -> (String) n.fields().get("prompt")).findFirst().orElseThrow();
    }

    @Test
    void startCodingJobIsADefaultTool() {
        assertTrue(ToolRegistry.defaultTools().names().contains(CodingGraph.START_TOOL));
    }

    @Test
    void theCodingSkillOffersStartCodingJobAsBefore() {
        // coding.json's description and input make the same specification as the @Tool method did
        ToolRegistry tools = ToolRegistry.of(new CodingTools());
        ToolRegistry withSkill = tools.withSkills(agent.skillStatus().skills());
        assertEquals(tools.specifications(), withSkill.specifications());
        assertTrue(withSkill.isSkillTool(CodingGraph.START_TOOL)); // routed to skill-coding, never run
    }

    @Test
    void agentStartsAJobAndAnswersWithTheReport() {
        model.answers.add(AiMessage.from(START));
        answers(FINDINGS, PLAN, PLAN, "Looked around.", FINAL);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));

        List<ChatMessage> produced = run();

        // The conversation sees the call, its result (the report) and the answer, nothing of the job's chatter
        assertEquals(3, produced.size(), produced.toString());
        assertEquals(List.of(START), ((AiMessage) produced.get(0)).toolExecutionRequests());
        ToolExecutionResultMessage report = assertInstanceOf(ToolExecutionResultMessage.class, produced.get(1));
        assertEquals(START.id(), report.id());
        assertTrue(report.text().startsWith("Done"), report.text());
        assertFalse(Boolean.TRUE.equals(report.isError()), report.text());
        assertEquals(FINAL, ((AiMessage) produced.get(2)).text());
        assertEquals(List.of(CodingPlan.parse(PLAN)), approver.plans);
        // The agent was asked again with the report: the last request ends with the job's result
        assertEquals(report, model.requests.getLast().messages().getLast());
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void progressReportsPhasesInOrder() {
        model.answers.add(AiMessage.from(START));
        answers(FINDINGS, PLAN, PLAN, "Looked around.", FINAL);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));
        List<String> phases = new ArrayList<>();

        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from(GOAL)));
        agent.run(history, "thread", new Settings(folder, List.of()), ConversationSummary.EMPTY, List.of(),
                progress -> phases.add(progress.phase()));

        // Each phase once: the job ran every node a single time (no tool calls, no revision)
        assertEquals(List.of("Checking tools", "Investigating", "Planning", "Reviewing plan", "Step 1/1",
                "Verifying (attempt 1/3)", "Writing report"), phases);
    }

    @Test
    void messagesArriveLiveInOrder() {
        model.answers.add(AiMessage.from(START));
        answers(FINDINGS, PLAN, PLAN, "Looked around.", FINAL);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));
        List<String> seen = new ArrayList<>();
        Progress.ProgressListener listener = new Progress.ProgressListener() {
            @Override
            public void onProgress(Progress progress) {
                seen.add("progress " + progress.phase());
            }

            @Override
            public void onMessage(ChatMessage message, boolean codingJob) {
                seen.add((codingJob ? "job " : "main ") + message.type());
            }
        };

        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from(GOAL)));
        Agent.Turn turn = agent.run(history, "thread", new Settings(folder, List.of()), ConversationSummary.EMPTY,
                List.of(), listener);

        // The start call shows before the job works; the job's replies come as they happen (its own
        // prompts are not passed on); the report and the final answer arrive last, once each
        assertEquals("main AI", seen.getFirst(), seen.toString());
        int findings = seen.indexOf("job AI");
        assertTrue(findings > 0 && findings < seen.indexOf("progress Planning"), seen.toString());
        assertFalse(seen.contains("job USER"), seen.toString());
        assertEquals(List.of("main AI", "main TOOL_EXECUTION_RESULT", "main AI"),
                seen.stream().filter(s -> s.startsWith("main")).toList());
        assertEquals("main AI", seen.getLast());
        assertEquals(3, turn.messages().size());
    }

    @Test
    void thePlanGoesToAFileNotIntoTheOutput() throws Exception {
        Path plans = dir.resolve("plans");
        EnvironmentInfo environment = new EnvironmentInfo(dir.resolve("environment.json"),
                command -> "exit code: 0", Clock.systemUTC());
        Agent withPlans = new Agent("test", "scripted", model, Tracing.off(),
                ToolRegistry.of(new FileTools(), new TerminalTools(), new CodingTools()), approver, environment,
                new LiveChannel(), plans);
        model.answers.add(AiMessage.from(START));
        answers(FINDINGS, PLAN, PLAN, "Looked around.", FINAL);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));
        List<String> jobTexts = new ArrayList<>();
        List<String> planFiles = new ArrayList<>();
        Progress.ProgressListener listener = new Progress.ProgressListener() {
            @Override public void onProgress(Progress progress) { }
            @Override public void onPlanFile(String path) { planFiles.add(path); }

            @Override
            public void onMessage(ChatMessage message, boolean codingJob) {
                if (codingJob && message instanceof AiMessage ai && ai.text() != null) jobTexts.add(ai.text());
            }
        };

        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from(GOAL)));
        withPlans.run(history, "thread", new Settings(folder, List.of()), ConversationSummary.EMPTY, List.of(), listener);

        // The findings and step summary are shown; the plan JSON (plan and review answers) is not
        assertTrue(jobTexts.contains(FINDINGS), jobTexts.toString());
        assertTrue(jobTexts.stream().noneMatch(t -> t.contains("\"steps\"")), jobTexts.toString());
        // The dialog was told the file; the listener heard of it after review and after the report
        String file = approver.reviews.getFirst().planFile();
        assertTrue(file.startsWith(plans.toAbsolutePath().toString()), file);
        assertEquals(List.of(file, file), planFiles);
        // It ends as the job's report: goal, outcome and the plan
        String text = Files.readString(Path.of(file));
        assertTrue(text.startsWith("# Coding job: " + GOAL), text);
        assertTrue(text.contains("Done"), text);
    }

    @Test
    void failingProgressListenerDoesNotBreakTheRun() {
        model.answers.add(AiMessage.from(START));
        answers(FINDINGS, PLAN, PLAN, "Looked around.", FINAL);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));

        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from(GOAL)));
        // Agent logs each listener failure as a warning; expected here, so kept out of the test output
        Agent.Turn turn = withoutAgentLog(() -> agent.run(history, "thread", new Settings(folder, List.of()),
                ConversationSummary.EMPTY, List.of(), progress -> {
                    throw new IllegalStateException("listener broke");
                }));

        assertEquals(3, turn.messages().size(), turn.messages().toString());
    }

    /** Runs {@code action} with Agent's logger off, for tests whose failures Agent logs on purpose. */
    private static <T> T withoutAgentLog(java.util.function.Supplier<T> action) {
        java.util.logging.Logger log = java.util.logging.Logger.getLogger(Agent.class.getName());
        java.util.logging.Level level = log.getLevel();
        log.setLevel(java.util.logging.Level.OFF);
        try {
            return action.get();
        } finally {
            log.setLevel(level);
        }
    }

    @Test
    void planApprovalPausesAndUsesTheDecision() {
        model.answers.add(AiMessage.from(START));
        answers(FINDINGS, PLAN, PLAN, PLAN, PLAN, "Looked around.", FINAL);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.REVISE, "use the Kotlin DSL"));
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));

        List<ChatMessage> produced = run();

        assertEquals(2, approver.plans.size()); // the revised plan was asked about again
        assertFalse(approver.reviews.get(0).revision());
        PlanReview revised = approver.reviews.get(1);
        assertTrue(revised.revision());
        assertEquals("use the Kotlin DSL", revised.userComment()); // read before implement clears it
        assertTrue(revised.toMarkdown().startsWith("Revised plan: you asked: use the Kotlin DSL"), revised.toMarkdown());
        ChatRequest replan = model.requests.get(4); // agent, investigate, plan, review, then the revision
        assertTrue(replan.messages().getFirst() instanceof SystemMessage s && s.text().startsWith(codingPrompt("plan")));
        assertTrue(text(replan).contains("use the Kotlin DSL"), text(replan));
        assertEquals(3, produced.size(), produced.toString());
        assertTrue(((ToolExecutionResultMessage) produced.get(1)).text().startsWith("Done"));
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void repeatedMessagesInTheHistoryAreKept() {
        // The same question twice, with the same answer in between: the state must keep every copy,
        // or the turn's new messages can't be split off the history (fromIndex > toIndex)
        answers(FINAL);
        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"),
                UserMessage.from("check the code"), AiMessage.from("It matches."),
                UserMessage.from("check the code"), AiMessage.from("It matches."),
                UserMessage.from("check the code")));

        Agent.Turn turn = agent.run(history, "thread", new Settings(folder, List.of()), ConversationSummary.EMPTY, List.of());

        assertEquals(1, turn.messages().size(), turn.messages().toString());
        assertTrue(turn.messages().getFirst() instanceof AiMessage);
    }

    @Test
    void toolApprovalStillPausesAndRuns() {
        ToolExecutionRequest risky = ToolExecutionRequest.builder()
                .id("r1").name("runCommand").arguments("{\"command\":\"echo approved\"}").build();
        model.answers.add(AiMessage.from(risky));
        answers(FINAL);
        approver.approveCalls = true;

        List<ChatMessage> produced = run();

        assertEquals(List.of(risky), approver.reviewedCalls); // the pause before "approval" asked the user
        ToolExecutionResultMessage ran = (ToolExecutionResultMessage) produced.get(1);
        assertTrue(ran.text().startsWith("exit code: 0") && ran.text().contains("approved"), ran.text());
        assertEquals(FINAL, ((AiMessage) produced.getLast()).text());
        assertTrue(approver.plans.isEmpty());
    }

    @Test
    void repeatedPauseWithoutProgressStops() {
        // The model repeats the very same call (same id) after it was denied: the graph pauses again
        // with the same open call, which the safety check reports instead of asking forever.
        // (The text differs because the messages channel drops a message equal to one it already has.)
        ToolExecutionRequest risky = ToolExecutionRequest.builder()
                .id("r1").name("runCommand").arguments("{\"command\":\"echo hi\"}").build();
        model.answers.add(AiMessage.from(risky));
        model.answers.add(AiMessage.from("Trying again.", List.of(risky)));

        // The denied call and its answer happened before the check stopped the turn: they come back to keep.
        // Agent logs the failed turn with its stack trace; expected here, so kept out of the test output
        Agent.TurnFailedException e = withoutAgentLog(() -> assertThrows(Agent.TurnFailedException.class, this::run));
        assertTrue(e.getMessage().contains("made no progress"), e.getMessage());
        assertTrue(e.getCause() instanceof IllegalStateException, String.valueOf(e.getCause()));
        assertEquals(2, model.requests.size());
    }

    @Test
    void trustedCommandAfterAJobThatReadTheWebAsks() {
        ToolExecutionRequest fetch = ToolExecutionRequest.builder()
                .id("w1").name("fetchPage").arguments("{\"url\":\"https://example.com\"}").build();
        ToolExecutionRequest trusted = ToolExecutionRequest.builder()
                .id("t1").name("runCommand").arguments("{\"command\":\"echo trusted\"}").build();
        model.answers.add(AiMessage.from(START));
        model.answers.add(AiMessage.from(fetch)); // the job's investigation reads a page
        answers(FINDINGS, PLAN, PLAN, "Looked around.");
        model.answers.add(AiMessage.from(trusted)); // back in the main loop: the user's trusted command
        answers(FINAL);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));

        List<ChatMessage> produced = run(List.of("echo trusted"));

        // The page came in inside the job, so for the rest of the turn the trusted command asks
        assertEquals(List.of(trusted), approver.reviewedCalls);
        ToolExecutionResultMessage denied = produced.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage r && r.id().equals("t1"))
                .map(ToolExecutionResultMessage.class::cast).findFirst().orElseThrow();
        assertTrue(denied.isError(), denied.text());
        assertFalse(denied.text().contains("exit code"), denied.text()); // it never ran
        assertEquals(FINAL, ((AiMessage) produced.getLast()).text());
    }

    @Test
    void agentLoopStopsAfterMaxRounds() {
        // A model stuck calling a safe tool: every round runs, until the cap
        for (int i = 0; i <= AgentGraph.MAX_AGENT_ROUNDS; i++) {
            model.answers.add(AiMessage.from(ToolExecutionRequest.builder()
                    .id("r" + i).name("readFile").arguments("{\"path\":\"missing.txt\"}").build()));
        }

        List<ChatMessage> produced = run();

        assertEquals(AgentGraph.MAX_AGENT_ROUNDS + 1, model.requests.size());
        // The last request offers no tools and asks for an answer
        ChatRequest last = model.requests.getLast();
        assertTrue(last.toolSpecifications() == null || last.toolSpecifications().isEmpty(), last.toolSpecifications().toString());
        assertTrue(text(last).contains("rounds of tool calls"), text(last));
        // Its calls were stripped: the turn ends with an answer and nothing open
        AiMessage answer = assertInstanceOf(AiMessage.class, produced.getLast());
        assertFalse(answer.hasToolExecutionRequests());
        assertEquals(AgentGraph.TOO_MANY_ROUNDS, answer.text());
        assertEquals(AgentGraph.MAX_AGENT_ROUNDS, produced.stream().filter(ToolExecutionResultMessage.class::isInstance).count());
    }

    @Test
    void deniedPlanAnswersTheToolCallWithAnError() throws Exception {
        model.answers.add(AiMessage.from(START));
        answers(FINDINGS, PLAN, PLAN, "I didn't change anything, as you denied the plan.");
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.DENY, ""));

        List<ChatMessage> produced = run();

        assertEquals(3, produced.size(), produced.toString());
        ToolExecutionResultMessage report = (ToolExecutionResultMessage) produced.get(1);
        assertTrue(report.isError(), report.text());
        assertTrue(report.text().contains("denied"), report.text());
        assertInstanceOf(AiMessage.class, produced.get(2)); // the agent still answers the user
        assertEquals(5, model.requests.size()); // nothing of the job ran after the deny
        try (var files = Files.list(folder)) {
            assertEquals(0, files.count());
        }
    }
}
