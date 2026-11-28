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

    private static final String GOAL = "Add a note";
    // The folder is empty, so the job asks no question about files: the plan creates one file, the edit writes it
    private static final String PLAN = "{\"summary\":\"Add a note\",\"changes\":[{\"file\":\"note.txt\",\"action\":\"create\",\"intent\":\"the note\"}]}";
    private static final String PLAN_KOTLIN = "{\"summary\":\"Add a note\",\"changes\":[{\"file\":\"note.txt\",\"action\":\"create\",\"intent\":\"the note, in the Kotlin DSL\"}]}";
    private static final String REVISE_CHANGE = "{\"kind\":\"change\",\"points\":[\"use the Kotlin DSL\"],\"answer\":\"\"}";
    private static final String EDIT = "```\nhello\n```";
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
        /** What the first step of a job answers to "is the goal big?": no steps. */
        String roadmap = "{\"milestones\": []}";

        @Override
        public ChatResponse chat(ChatRequest request) {
            // The intent check after a passing build is answered on the side, so the scripts of the other steps stay as they are
            if (request.messages().toString().contains("checking a finished coding change") || request.messages().toString().contains("checking a plan for a coding job")) {
                return ChatResponse.builder().aiMessage(AiMessage.from("OK")).build();
            }
            // The first step of every job asks whether the goal is big (a list of steps); these tests are about small jobs
            if (request.messages().toString().contains("Split this goal into steps only when it is big")) {
                return ChatResponse.builder().aiMessage(AiMessage.from(roadmap)).build();
            }
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
        final List<net.rka.ai.agent.skills.SkillQuestion> questions = new ArrayList<>();
        final Deque<net.rka.ai.agent.skills.SkillAnswer> answersToQuestions = new ArrayDeque<>();
        boolean approveCalls;

        @Override
        public net.rka.ai.agent.skills.SkillAnswer askUser(net.rka.ai.agent.skills.SkillQuestion question) {
            questions.add(question);
            return answersToQuestions.remove();
        }

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
                ToolRegistry.of(new FileTools(), new net.rka.ai.tools.OutlineTools(), new net.rka.ai.tools.JavaTools(), new TerminalTools(), new CodingTools(),
                        new CodingGraphTest.FakeWebTools()),
                approver, environment);
    }

    private void answers(String... texts) {
        Arrays.stream(texts).map(AiMessage::from).forEach(model.answers::add);
    }

    /** What the analysis answers: a call of a function (the model's native way to decide). */
    private static AiMessage call(String name, String arguments) {
        return AiMessage.from(List.of(ToolExecutionRequest.builder().id("a" + System.nanoTime()).name(name).arguments(arguments).build()));
    }

    private static AiMessage route(String goal, String edge) {
        return call("route", "{\"goal\":\"" + goal + "\",\"edge\":\"" + edge + "\"}");
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
    void aBigGoalIsSplitIntoStepsTheUserApprovesAndEachStepIsWorkedOnInTurnAndReported() throws Exception {
        model.answers.add(AiMessage.from(START));
        model.roadmap = "{\"milestones\": [\"Step A: the sidebar\", \"Step B: the shortcuts\"]}";
        approver.answersToQuestions.add(new net.rka.ai.agent.skills.SkillAnswer("Do these steps", ""));
        answers("Did A.", "Did B."); // the work of each step: text, no tool calls

        List<ChatMessage> produced = run();

        // The user was asked once, with the steps in the question
        assertEquals(1, approver.questions.size(), approver.questions.toString());
        assertTrue(approver.questions.getFirst().question().contains("Step A: the sidebar")
                && approver.questions.getFirst().question().contains("Step B: the shortcuts"), approver.questions.getFirst().question());
        // Each step is worked on alone, in order, as part of the whole goal
        List<String> work = model.requests.stream().map(CodingJobTest::text).filter(t -> t.contains("Do only this step now")).toList();
        assertEquals(2, work.size(), work.toString());
        assertTrue(work.get(0).contains("Do only this step now: Step A: the sidebar") && !work.get(0).contains("Step B: the shortcuts"), work.get(0));
        assertTrue(work.get(1).contains("Do only this step now: Step B: the shortcuts"), work.get(1));
        // One report of the whole job, with every step in it
        ToolExecutionResultMessage report = assertInstanceOf(ToolExecutionResultMessage.class, produced.get(1));
        assertFalse(Boolean.TRUE.equals(report.isError()), report.text());
        assertTrue(report.text().startsWith("Every step is done"), report.text());
        assertTrue(report.text().contains("## Step A: the sidebar\nDid A.") && report.text().contains("## Step B: the shortcuts\nDid B."), report.text());
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void aStuckStepAsksAndStoppingEndsTheBigJobWithTheStepsAlreadyDoneInTheReport() throws Exception {
        model.answers.add(AiMessage.from(START));
        model.roadmap = "{\"milestones\": [\"Step A\", \"Step B\", \"Step C\"]}";
        approver.answersToQuestions.add(new net.rka.ai.agent.skills.SkillAnswer("Do these steps", ""));
        approver.answersToQuestions.add(new net.rka.ai.agent.skills.SkillAnswer("Stop here", ""));
        answers("Did A.\nSTEP: done", "The edit kept failing.\nSTEP: stuck: no md5"); // step C must not start

        List<ChatMessage> produced = run();

        List<String> work = model.requests.stream().map(CodingJobTest::text).filter(t -> t.contains("Do only this step now")).toList();
        assertEquals(2, work.size(), work.toString());
        ToolExecutionResultMessage report = assertInstanceOf(ToolExecutionResultMessage.class, produced.get(1));
        assertFalse(report.text().startsWith("Every step is done"), report.text());
        assertTrue(report.text().contains("## Step A\nDid A.") && report.text().contains("The edit kept failing."), report.text());
        assertFalse(report.text().contains("Step C"), report.text());
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void aStuckStepCanBeTriedAgainWithItsFirstReportOrSkipped() throws Exception {
        model.answers.add(AiMessage.from(START));
        model.roadmap = "{\"milestones\": [\"Step A\", \"Step B\", \"Step C\"]}";
        approver.answersToQuestions.add(new net.rka.ai.agent.skills.SkillAnswer("Do these steps", ""));
        approver.answersToQuestions.add(new net.rka.ai.agent.skills.SkillAnswer("Try this step again", ""));  // A is stuck: again
        approver.answersToQuestions.add(new net.rka.ai.agent.skills.SkillAnswer("Skip it and go on", ""));    // B is stuck: skip
        answers("Read only.\nSTEP: stuck: out of rounds", "Now A is made.\nSTEP: done", "No way.\nSTEP: stuck: no idea", "Made C.\nSTEP: done");

        List<ChatMessage> produced = run();

        List<String> work = model.requests.stream().map(CodingJobTest::text).filter(t -> t.contains("Do only this step now")).toList();
        assertEquals(4, work.size(), work.toString());
        // the second try of A starts from what the first one reported
        assertTrue(work.get(1).contains("Do only this step now: Step A") && work.get(1).contains("Read only."), work.get(1));
        assertTrue(work.get(3).contains("Do only this step now: Step C"), work.get(3));
        assertEquals(2, approver.questions.stream().filter(q -> q.question().startsWith("This step did not finish")).count(), approver.questions.toString());
        ToolExecutionResultMessage report = assertInstanceOf(ToolExecutionResultMessage.class, produced.get(1));
        assertTrue(report.text().startsWith("Every step is done"), report.text());
        assertTrue(report.text().contains("## Step A\nNow A is made.") && report.text().contains("## Step B\nSkipped") && report.text().contains("## Step C\nMade C."),
                report.text());
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void agentStartsAJobAndAnswersWithTheReport() throws Exception {
        model.answers.add(AiMessage.from(START));
        answers(PLAN, EDIT);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));

        List<ChatMessage> produced = run();

        // The conversation sees the call, its result (the report) and the answer, nothing of the job's chatter
        assertEquals(3, produced.size(), produced.toString());
        assertEquals(List.of(START), ((AiMessage) produced.get(0)).toolExecutionRequests());
        ToolExecutionResultMessage report = assertInstanceOf(ToolExecutionResultMessage.class, produced.get(1));
        assertEquals(START.id(), report.id());
        assertTrue(report.text().startsWith("Done: every change is made"), report.text());
        assertFalse(Boolean.TRUE.equals(report.isError()), report.text());
        assertTrue(((AiMessage) produced.get(2)).text().startsWith("Done: every change is made"), produced.get(2).toString());
        assertEquals(List.of(CodingPlan.parse(PLAN)), approver.plans);
        assertEquals("hello\n", Files.readString(folder.resolve("note.txt"))); // the graph applied the edit
        // A job that is done answers the user itself: the agent is not asked again
        assertFalse(model.requests.stream().anyMatch(r -> r.messages().getLast() == report), "no model call after the report");
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void triageStartsTheCodingSkillBeforeTheAgentDoesAnything() {
        agent.triage(true);
        model.answers.add(route(GOAL, "job_plan")); // the analysis; the agent step is never asked
        answers(PLAN, EDIT);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));

        List<ChatMessage> produced = run();

        assertEquals(3, produced.size(), produced.toString());
        AiMessage start = (AiMessage) produced.get(0);
        assertEquals(CodingGraph.START_TOOL, start.toolExecutionRequests().get(0).name());
        assertTrue(start.toolExecutionRequests().get(0).arguments().contains(GOAL));
        assertEquals(start.toolExecutionRequests().get(0).id(), assertInstanceOf(ToolExecutionResultMessage.class, produced.get(1)).id());
        // The very first request was the analysis; the next ones are the job's (the agent only answers with the report)
        ChatRequest analysis = model.requests.get(0);
        assertEquals(GOAL, ((UserMessage) analysis.messages().getLast()).singleText(), "the conversation as it is, the user's message last");
        assertTrue(analysis.toolSpecifications().stream().anyMatch(t -> t.name().equals("route")), "the model is offered the function route");
        assertEquals(dev.langchain4j.model.chat.request.ToolChoice.REQUIRED, analysis.parameters().toolChoice(), "and must call one");
        assertTrue(((AiMessage) produced.get(2)).text().startsWith("Done: every change is made"), produced.get(2).toString());
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void theAnalysisMayAskTwiceInOneTurnAfterReadingSomething() {
        // This was "TurnFailedException: Resuming before ask made no progress on []": the second question looked like the first pause
        agent.triage(true);
        model.answers.add(call("ask_user", "{\"question\":\"Which file?\"}"));
        model.answers.add(call("readFile", "{\"path\":\"note.txt\"}"));
        model.answers.add(call("ask_user", "{\"question\":\"Which line?\"}"));
        model.answers.add(route(GOAL + " in note.txt", "job_plan"));
        answers(PLAN, EDIT);
        approver.answersToQuestions.add(new net.rka.ai.agent.skills.SkillAnswer("note.txt", ""));
        approver.answersToQuestions.add(new net.rka.ai.agent.skills.SkillAnswer("the first", ""));
        approver.approveCalls = true;
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));

        List<ChatMessage> produced = run();

        assertEquals(List.of("Which file?", "Which line?"), approver.questions.stream().map(net.rka.ai.agent.skills.SkillQuestion::question).toList());
        assertTrue(produced.stream().anyMatch(m -> m instanceof AiMessage ai && ai.toolExecutionRequests().stream().anyMatch(c -> c.name().equals(CodingGraph.START_TOOL))),
                produced.toString());
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void aQuestionOfTheAnalysisIsAskedTheAnswerBecomesPartOfTheConversationAndTheAnalysisRunsAgain() {
        agent.triage(true);
        model.answers.add(call("ask_user", "{\"question\":\"Which file?\",\"options\":[\"note.txt\",\"other.txt\"]}"));
        model.answers.add(route(GOAL + " in note.txt", "job_plan"));
        answers(PLAN, EDIT);
        approver.answersToQuestions.add(new net.rka.ai.agent.skills.SkillAnswer("note.txt", ""));
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));

        List<ChatMessage> produced = run();

        assertEquals(1, approver.questions.size());
        assertEquals("Which file?", approver.questions.get(0).question());
        assertEquals(List.of("note.txt", "other.txt"), approver.questions.get(0).choices());
        // The question and the answer are in the conversation, in order, before the job
        assertEquals("Which file?", ((AiMessage) produced.get(0)).text());
        assertEquals("note.txt", ((UserMessage) produced.get(1)).singleText());
        assertEquals(CodingGraph.START_TOOL, ((AiMessage) produced.get(2)).toolExecutionRequests().get(0).name());
        assertTrue(((AiMessage) produced.get(2)).toolExecutionRequests().get(0).arguments().contains("in note.txt"), produced.get(2).toString());
        // The second analysis saw the question and the answer
        List<ChatMessage> seen = model.requests.get(1).messages();
        assertEquals("Which file?", ((AiMessage) seen.get(seen.size() - 2)).text());
        assertEquals("note.txt", ((UserMessage) seen.getLast()).singleText());
        assertEquals("Add a note", GOAL);
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void aToolCallOfTheAnalysisRunsItsResultGoesBackToTheAnalysisAndTheTurnGoesOn() throws Exception {
        Files.writeString(folder.resolve("a.txt"), "hello there");
        agent.triage(true);
        model.answers.add(call("searchFiles", "{\"pattern\":\"hello\"}"));
        model.answers.add(route("g", "agent"));
        answers("It is in a.txt.");

        List<ChatMessage> produced = run();

        // The call and its result are in the conversation; then the answer of the agent
        AiMessage call = (AiMessage) produced.get(0);
        assertEquals("searchFiles", call.toolExecutionRequests().get(0).name());
        assertEquals(call.toolExecutionRequests().get(0).id(), assertInstanceOf(ToolExecutionResultMessage.class, produced.get(1)).id());
        assertTrue(((ToolExecutionResultMessage) produced.get(1)).text().contains("a.txt"), produced.get(1).toString());
        assertEquals("It is in a.txt.", ((AiMessage) produced.get(2)).text());
        // The second analysis saw what the tool found
        List<ChatMessage> seen = model.requests.get(1).messages();
        assertTrue(seen.getLast() instanceof ToolExecutionResultMessage r && r.text().contains("a.txt"), seen.getLast().toString());
        assertTrue(seen.get(seen.size() - 2) instanceof AiMessage ai && ai.hasToolExecutionRequests(), "its call, then the result as the tool message");
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void anAnswerOfTheUserWithoutOptionsIsTheComment() {
        agent.triage(true);
        model.answers.add(call("ask_user", "{\"question\":\"What should it say?\",\"options\":[]}"));
        model.answers.add(route("g", "agent"));
        answers("ok");
        approver.answersToQuestions.add(new net.rka.ai.agent.skills.SkillAnswer("Go on", "say hello"));

        List<ChatMessage> produced = run();

        assertEquals(List.of("Go on"), approver.questions.get(0).choices());
        assertEquals("say hello", ((UserMessage) produced.get(1)).singleText());
        assertEquals("ok", ((AiMessage) produced.get(2)).text());
    }

    @Test
    void triageIsAShortRequestAndLeavesOtherTurnsToTheAgent() {
        agent.triage(true);
        model.answers.add(route("greet the user", "agent"));
        answers("Hello!");

        List<ChatMessage> produced = run();

        assertEquals(1, produced.size(), produced.toString());
        assertEquals("Hello!", ((AiMessage) produced.get(0)).text());
        ChatRequest triage = model.requests.get(0);
        ChatRequest agentStep = model.requests.get(1);
        // The analysis is one request of its own: its short system message and the user's message, the function route, a required call
        assertEquals(2, triage.messages().size());
        assertEquals(dev.langchain4j.model.chat.request.ToolChoice.REQUIRED, triage.parameters().toolChoice());
        assertTrue(agentStep.messages().size() > 1);
    }

    @Test
    void aBigHandEditOfExistingCodeBecomesACodingJob() throws Exception {
        Files.writeString(folder.resolve("A.java"), "class A {}\n");
        // The model skips startCodingJob and starts rewriting a source file: that is coding work
        String edit = "{\"path\":\"A.java\",\"section\":\"A\",\"md5\":\"m\",\"newText\":\"" + "x".repeat(300) + "\"}";
        model.answers.add(AiMessage.from(ToolExecutionRequest.builder().id("e1").name("editSection").arguments(edit).build()));
        answers("{\"files\":[]}", PLAN, EDIT); // the job asks which files first: the folder has one
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));

        List<ChatMessage> produced = run();

        // The conversation sees a startCodingJob call with the user's request, its report and the answer;
        // the edit never ran and isn't in the conversation
        assertEquals(3, produced.size(), produced.toString());
        List<ToolExecutionRequest> calls = ((AiMessage) produced.get(0)).toolExecutionRequests();
        assertEquals(1, calls.size());
        assertEquals(CodingGraph.START_TOOL, calls.get(0).name());
        assertTrue(calls.get(0).arguments().contains(GOAL), calls.get(0).arguments());
        assertEquals(calls.get(0).id(), assertInstanceOf(ToolExecutionResultMessage.class, produced.get(1)).id());
        assertEquals("class A {}\n", Files.readString(folder.resolve("A.java")));
        assertEquals(List.of(), approver.reviewedCalls); // nothing was asked for approval
        assertTrue(((AiMessage) produced.get(2)).text().startsWith("Done: every change is made"), produced.get(2).toString());
    }

    @Test
    void aReplyThatAnnouncesAnActionIsAskedOnceMore() {
        answers("Let me change it:", "Nothing to change.");

        List<ChatMessage> produced = run();

        // The nudge isn't part of the conversation; the second answer replaces the announcement
        assertEquals(1, produced.size(), produced.toString());
        assertEquals("Nothing to change.", ((AiMessage) produced.get(0)).text());
        assertEquals(2, model.requests.size());
        assertEquals(AgentGraph.ANNOUNCED_NUDGE, ((UserMessage) model.requests.get(1).messages().getLast()).singleText());
    }

    @Test
    void anAnnouncementOfAChangeToASourceFileStartsAJobWithoutAskingAgain() {
        answers("Let me make these changes to the ChatWindow.java file:");
        answers(PLAN, EDIT);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));

        List<ChatMessage> produced = run();

        assertEquals(3, produced.size(), produced.toString());
        AiMessage first = (AiMessage) produced.get(0);
        assertEquals("Let me make these changes to the ChatWindow.java file:", first.text()); // what it said stays
        assertEquals(CodingGraph.START_TOOL, first.toolExecutionRequests().get(0).name());
        assertTrue(first.toolExecutionRequests().get(0).arguments().contains(GOAL));
        assertFalse(model.requests.stream().anyMatch(r -> text(r).contains(AgentGraph.ANNOUNCED_NUDGE)), "no nudge first");
        assertTrue(((AiMessage) produced.get(2)).text().startsWith("Done: every change is made"), produced.get(2).toString());
    }

    @Test
    void anAnnouncementKeepsItsTextAndTakesTheCallThatFollows() throws Exception {
        Files.writeString(folder.resolve("a.txt"), "hello");
        model.answers.add(AiMessage.from("Let me read it:"));
        model.answers.add(AiMessage.from(ToolExecutionRequest.builder().id("r1").name("readFile")
                .arguments("{\"path\":\"a.txt\"}").build()));
        answers("It says hello.");

        List<ChatMessage> produced = run();

        assertEquals(3, produced.size(), produced.toString()); // the reply with its call, the result, the answer
        AiMessage first = (AiMessage) produced.get(0);
        assertEquals("Let me read it:", first.text());
        assertEquals("readFile", first.toolExecutionRequests().get(0).name());
        assertEquals("It says hello.", ((AiMessage) produced.get(2)).text());
    }

    @Test
    void noSecondJobStartsInATurnThatAlreadyRanOne() {
        // The job runs; afterwards the agent announces another change to a source file: that must not start the same
        // job again (each would end the same way, and the turn would loop). The announcement is asked about once instead.
        model.answers.add(AiMessage.from(START));
        answers(PLAN);
        answers("Let me change the ChatWindow.java file:", "Nothing more to do.");
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.DENY, ""));

        List<ChatMessage> produced = run();

        long jobs = produced.stream().filter(m -> m instanceof AiMessage ai
                && ai.toolExecutionRequests().stream().anyMatch(c -> CodingGraph.START_TOOL.equals(c.name()))).count();
        assertEquals(1, jobs, produced.toString());
        assertEquals("Nothing more to do.", ((AiMessage) produced.getLast()).text());
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void afterAJobTheAgentChangesNoFilesItself() throws Exception {
        model.answers.add(AiMessage.from(START));
        answers(PLAN);
        // The job is over; the agent now tries to write a file by hand (as a model does when a job stopped)
        model.answers.add(AiMessage.from(ToolExecutionRequest.builder().id("w1").name("writeFile")
                .arguments("{\"path\":\"other.txt\",\"content\":\"x\"}").build()));
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.DENY, ""));

        List<ChatMessage> produced = run();

        assertEquals(3, produced.size(), produced.toString()); // the call, the result of the denied job, the reply
        assertTrue(((AiMessage) produced.getLast()).text().startsWith(AgentGraph.NO_CHANGES_AFTER_JOB), ((AiMessage) produced.getLast()).text());
        assertFalse(Files.exists(folder.resolve("other.txt")));
        assertEquals(List.of(), approver.reviewedCalls);
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void aTinyEditOfCodeStaysADirectEdit() throws Exception {
        Files.writeString(folder.resolve("A.java"), "class A {}\n");
        String edit = "{\"path\":\"A.java\",\"section\":\"A\",\"md5\":\"m\",\"newText\":\"B\"}";
        model.answers.add(AiMessage.from(ToolExecutionRequest.builder().id("e1").name("editSection").arguments(edit).build()));
        answers("Not changed.");

        List<ChatMessage> produced = run();

        assertEquals("editSection", ((AiMessage) produced.get(0)).toolExecutionRequests().get(0).name());
        assertEquals(1, approver.reviewedCalls.size()); // asked as usual (and denied)
    }

    @Test
    void progressReportsPhasesInOrder() {
        model.answers.add(AiMessage.from(START));
        answers(PLAN, EDIT);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));
        List<String> phases = new ArrayList<>();

        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from(GOAL)));
        agent.run(history, "thread", new Settings(folder, List.of()), ConversationSummary.EMPTY, List.of(),
                progress -> phases.add(progress.phase()));

        // Each phase once: the job ran every node a single time (the empty folder has no files to find)
        assertEquals(List.of("Checking tools", "Sizing up the job", "Finding the code", "Finding the code", "Planning", "Setting up", "Change 1/1",
                "Verifying (attempt 1/3)", "Writing report"), phases);
    }

    @Test
    void messagesArriveLiveInOrder() {
        model.answers.add(AiMessage.from(START));
        answers(PLAN, EDIT);
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

        // The start call shows before the job works; the job answers in data, not in messages (the plan and the
        // code are not chat); the report and the final answer arrive last, once each
        assertEquals("main AI", seen.getFirst(), seen.toString());
        assertTrue(seen.indexOf("progress Planning") > 0, seen.toString());
        assertTrue(seen.stream().noneMatch(m -> m.startsWith("job ")), seen.toString());
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
                ToolRegistry.of(new FileTools(), new net.rka.ai.tools.OutlineTools(), new net.rka.ai.tools.JavaTools(), new TerminalTools(), new CodingTools(), new CodingGraphTest.FakeWebTools()), approver, environment,
                new LiveChannel(), plans);
        model.answers.add(AiMessage.from(START));
        answers(PLAN, EDIT);
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

        // Neither the plan JSON nor the written code is chat
        assertEquals(List.of(), jobTexts);
        // The dialog was told the file; the listener heard of it once the plan was valid and after the report
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
        answers(PLAN, EDIT);
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
        answers(PLAN, REVISE_CHANGE, PLAN_KOTLIN, EDIT);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.REVISE, "use the Kotlin DSL"));
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));

        List<ChatMessage> produced = run();

        assertEquals(2, approver.plans.size()); // the revised plan was asked about again
        assertFalse(approver.reviews.get(0).revision());
        PlanReview revised = approver.reviews.get(1);
        assertTrue(revised.revision());
        assertEquals("use the Kotlin DSL", revised.userComment()); // read before the plan approval step clears it
        assertTrue(revised.toMarkdown().startsWith("Revised plan: you asked: use the Kotlin DSL"), revised.toMarkdown());
        ChatRequest replan = model.requests.get(3); // agent, plan, the reading of the comment, then the revision
        assertTrue(replan.messages().getLast() instanceof UserMessage u && u.singleText().startsWith("You are planning a coding job."));
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
    void aTrustedCommandStillRunsAfterAJobBecauseTheJobReadNoWeb() {
        ToolExecutionRequest trusted = ToolExecutionRequest.builder()
                .id("t1").name("runCommand").arguments("{\"command\":\"echo trusted\"}").build();
        model.answers.add(AiMessage.from(START));
        answers(PLAN);
        model.answers.add(AiMessage.from(trusted)); // back in the main loop: the user's trusted command
        answers(FINAL);
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.DENY, ""));

        List<ChatMessage> produced = run(List.of("echo trusted"));

        // A job has no tools, so it can't bring web content into the turn: the trusted command runs without asking
        assertEquals(List.of(), approver.reviewedCalls);
        ToolExecutionResultMessage ran = produced.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage r && r.id().equals("t1"))
                .map(ToolExecutionResultMessage.class::cast).findFirst().orElseThrow();
        assertTrue(ran.text().startsWith("exit code: 0") && ran.text().contains("trusted"), ran.text());
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
        // The last request asks for an answer; it still lists the tools (the same prompt start as the round
        // before, so the model server reuses its cache), but any call it makes is dropped
        ChatRequest last = model.requests.getLast();
        assertEquals(model.requests.get(model.requests.size() - 2).toolSpecifications(), last.toolSpecifications());
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
        answers(PLAN, "I didn't change anything, as you denied the plan.");
        approver.decisions.add(new PlanDecision(PlanDecision.Kind.DENY, ""));

        List<ChatMessage> produced = run();

        assertEquals(3, produced.size(), produced.toString());
        ToolExecutionResultMessage report = (ToolExecutionResultMessage) produced.get(1);
        assertTrue(report.isError(), report.text());
        assertTrue(report.text().contains("denied"), report.text());
        assertInstanceOf(AiMessage.class, produced.get(2)); // the agent still answers the user
        assertEquals(3, model.requests.size()); // agent, plan, agent: nothing of the job ran after the deny
        try (var files = Files.list(folder)) {
            assertEquals(0, files.count());
        }
    }
}
