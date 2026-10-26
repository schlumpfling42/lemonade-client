package net.rka.ai.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.rka.ai.agent.skills.CodingKinds;
import net.rka.ai.agent.skills.NodeKind;
import net.rka.ai.agent.skills.SkillAnswer;
import net.rka.ai.agent.skills.SkillFile;
import net.rka.ai.agent.skills.SkillLoader;
import net.rka.ai.agent.skills.SkillQuestion;
import net.rka.ai.agent.skills.SkillRuntime;
import net.rka.ai.agent.skills.SkillValidator;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
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
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole turn through {@link Agent} with skills loaded from a (temporary) skill folder: the model
 * starts a skill with its tool, the main graph routes it to the {@code skill-<name>} subgraph, Agent
 * pauses for the skill's tool approvals and questions, and the agent answers with the skill's result.
 */
class SkillJobTest {

    private static final String DESCRIPTION = "Greets someone by name. Use it when the user wants a greeting.";
    private static final String FINAL = "The skill is done.";

    @TempDir
    Path dir;

    private Path folder;
    private Path skillsDir;
    private ToolRegistry tools;
    private ScriptedModel model;
    private SkillApprover approver;
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

    /** Records tool approvals (denied unless {@link #approveCalls}) and questions (answered from a queue). */
    private static final class SkillApprover implements Approver {
        final List<ToolExecutionRequest> reviewedCalls = new ArrayList<>();
        final List<SkillQuestion> questions = new ArrayList<>();
        final Deque<SkillAnswer> answers = new ArrayDeque<>();
        final Deque<PlanDecision> plans = new ArrayDeque<>();
        boolean approveCalls;

        @Override
        public Map<String, Boolean> review(List<ToolExecutionRequest> calls) {
            reviewedCalls.addAll(calls);
            Map<String, Boolean> decisions = new HashMap<>();
            calls.forEach(call -> decisions.put(call.id(), approveCalls));
            return decisions;
        }

        @Override
        public SkillAnswer askUser(SkillQuestion question) {
            questions.add(question);
            return answers.remove();
        }

        @Override
        public PlanDecision reviewPlan(PlanReview review) {
            return plans.remove();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        folder = Files.createDirectory(dir.resolve("work"));
        // Inside the trusted folder on purpose: file tools must refuse it anyway
        skillsDir = Files.createDirectories(folder.resolve("chat").resolve("skills"));
        model = new ScriptedModel();
        approver = new SkillApprover();
        tools = ToolRegistry.of(new FileTools(), new TerminalTools(), new CodingTools(), new CodingGraphTest.FakeWebTools());
        agent = newAgent();
    }

    /** An agent with the skills of the folder, without built-ins (so without the coding job). */
    private Agent newAgent() throws Exception {
        return newAgent(List.of());
    }

    private Agent newAgent(List<String> builtIns) throws Exception {
        return newAgent(builtIns, Map.of());
    }

    /** @param extraKinds node kinds besides the app's, e.g. one that fails while its subgraph is built */
    private Agent newAgent(List<String> builtIns, Map<String, NodeKind> extraKinds) throws Exception {
        EnvironmentInfo environment = new EnvironmentInfo(dir.resolve("environment.json"),
                command -> "exit code: 0\n--- stdout ---\nv1 of " + command, Clock.systemUTC());
        Map<String, NodeKind> kinds = new LinkedHashMap<>(Agent.kinds(new CodingGraph.Setup(environment, 600)));
        kinds.putAll(extraKinds);
        SkillLoader loader = new SkillLoader(skillsDir, builtIns, new SkillValidator(kinds, tools));
        return new Agent("test", "scripted", model, Tracing.off(), tools, approver, loader, new LiveChannel());
    }

    /**
     * A terminal kind that is valid everywhere but throws when its node's action is made (while the skill's
     * subgraph is built), from the {@code failFrom}-th build on (1: every build).
     */
    private static NodeKind failingToBuild(int failFrom) {
        AtomicInteger builds = new AtomicInteger();
        return new NodeKind() {
            public String name() { return "brittle"; }
            public Set<String> outcomes(SkillFile.NodeSpec node) { return Set.of(); }
            public List<String> check(SkillFile.NodeSpec node, SkillContext context) { return List.of(); }
            public Set<String> writes(SkillFile.NodeSpec node) { return Set.of(); }
            public Set<String> reads(SkillFile.NodeSpec node) { return Set.of(); }
            public NodeActionWithConfig<AgentGraph.State> action(SkillFile.NodeSpec node, SkillRuntime runtime) {
                if (builds.incrementAndGet() >= failFrom) throw new IllegalStateException("cannot build " + node.id());
                return (state, config) -> Map.of();
            }
            public String route(SkillFile.NodeSpec node, SkillRuntime runtime, AgentGraph.State state) {
                throw new AssertionError("terminal");
            }
        };
    }

    private void brittleSkill() throws Exception {
        skill("brittle", """
                { "name": "brittle", "description": "%s", "start": "end",
                  "nodes": [ { "id": "end", "kind": "brittle" } ] }
                """.formatted(DESCRIPTION));
    }

    @Test
    void aSkillThatFailsToBuildIsLeftOutAndListed() throws Exception {
        greetSkill();
        brittleSkill();

        agent = newAgent(List.of(), Map.of("brittle", failingToBuild(1))); // starts: the constructor doesn't throw

        assertEquals(List.of("greet"), agent.skillStatus().skills().stream().map(SkillFile::name).toList());
        List<String> errors = agent.skillStatus().errors().get("brittle.json");
        assertTrue(errors != null && errors.stream().anyMatch(e -> e.contains("can't be added to the agent graph")
                && e.contains("cannot build end")), agent.skillStatus().errors().toString());
        // The others work, and the broken skill's tool isn't offered
        answers(AiMessage.from(List.of(call("s1", "skill_greet", "{\"who\":\"Bob\"}"))));
        answers("Hello, Bob!", FINAL);
        assertEquals("Hello, Bob!", result(run("greet Bob"), "s1").orElseThrow().text());
        assertFalse(offered(model.requests.getFirst()).contains("skill_brittle"));
        // A reload lists it again instead of throwing
        assertTrue(agent.reloadSkills().errors().containsKey("brittle.json"));
    }

    @Test
    void aReplacementOfABuiltInThatFailsToBuildGivesWayToTheBuiltIn() throws Exception {
        // The user's coding.json is the built-in one with its report step swapped for a kind that validates
        // but can't be built: the built-in coding skill must stay available, with the error listed
        ObjectMapper json = new ObjectMapper();
        ObjectNode coding;
        try (var in = SkillLoader.class.getResourceAsStream("coding.json")) {
            coding = (ObjectNode) json.readTree(in);
        }
        for (JsonNode node : coding.withArray("nodes")) {
            if ("report".equals(node.path("id").asText())) {
                ObjectNode report = (ObjectNode) node;
                report.removeAll();
                report.put("id", "report");
                report.put("kind", "brittle");
            }
        }
        coding.remove("tool"); // a user file may leave out the pinned tool
        Files.writeString(skillsDir.resolve("coding.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(coding));

        agent = newAgent(Agent.BUILT_IN_SKILLS, Map.of("brittle", failingToBuild(1)));

        assertEquals(List.of("coding"), agent.skillStatus().skills().stream().map(SkillFile::name).toList());
        SkillFile used = agent.skillStatus().skills().getFirst();
        assertTrue(used.nodes().stream().anyMatch(n -> n.id().equals("report") && n.kind().equals(CodingKinds.REPORT)),
                "the built-in coding skill, not the user's file, is used");
        List<String> errors = agent.skillStatus().errors().get("coding.json");
        assertTrue(errors != null && errors.stream().anyMatch(e -> e.contains("can't be added to the agent graph")
                && e.contains("The built-in coding skill is used instead.")), agent.skillStatus().errors().toString());
        assertEquals(SkillLoader.Origin.BUILT_IN, agent.skillOrigin("coding"));
        answers("Hello.");
        run("hi");
        assertTrue(offered(model.requests.getLast()).contains(CodingGraph.START_TOOL));
    }

    @Test
    void aGraphThatDoesNotCompileFallsBackToTheBuiltIns() throws Exception {
        greetSkill();
        brittleSkill();

        // Builds once (the check), then fails when the graph is compiled with it: only the built-ins are used
        agent = newAgent(Agent.BUILT_IN_SKILLS, Map.of("brittle", failingToBuild(2)));

        assertEquals(List.of("coding"), agent.skillStatus().skills().stream().map(SkillFile::name).toList());
        for (String file : List.of("greet.json", "brittle.json")) {
            List<String> errors = agent.skillStatus().errors().get(file);
            assertTrue(errors != null && errors.stream().anyMatch(e -> e.contains("only the built-in skills are used")),
                    agent.skillStatus().errors().toString());
        }
        assertEquals(SkillLoader.Origin.BUILT_IN, agent.skillOrigin("coding"));
        answers("Hello.");
        run("hi");
        assertTrue(offered(model.requests.getLast()).contains(CodingGraph.START_TOOL));
    }

    private void skill(String name, String json) throws Exception {
        Files.writeString(skillsDir.resolve(name + ".json"), json);
    }

    /** The greeting skill: one model node, its answer as the result. */
    private void greetSkill() throws Exception {
        skill("greet", """
                { "name": "greet", "description": "%s",
                  "inputs": [ { "name": "who", "description": "the name to greet", "required": true } ],
                  "start": "hello",
                  "nodes": [
                    { "id": "hello", "kind": "model", "prompt": "Greet {{input.who}}.", "output": "greeting", "next": "done" },
                    { "id": "done", "kind": "finish", "result": "{{greeting}}" } ] }
                """.formatted(DESCRIPTION));
    }

    /** A skill whose one model node may use the given tools. */
    private void toolSkill(String name, String tools) throws Exception {
        skill(name, """
                { "name": "%s", "description": "%s",
                  "start": "work",
                  "nodes": [
                    { "id": "work", "kind": "model", "prompt": "Do the work.", "tools": [%s], "output": "text", "next": "done" },
                    { "id": "done", "kind": "finish", "result": "Result: {{text}}" } ] }
                """.formatted(name, DESCRIPTION, tools));
    }

    private static ToolExecutionRequest call(String id, String tool, String arguments) {
        return ToolExecutionRequest.builder().id(id).name(tool).arguments(arguments).build();
    }

    private void answers(AiMessage... replies) {
        model.answers.addAll(List.of(replies));
    }

    private void answers(String... texts) {
        for (String text : texts) model.answers.add(AiMessage.from(text));
    }

    private List<ChatMessage> run(String question, List<String> trustedCommands) {
        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from(question)));
        return agent.run(history, "thread", new Settings(folder, trustedCommands), ConversationSummary.EMPTY, List.of())
                .messages();
    }

    private List<ChatMessage> run(String question) {
        return run(question, List.of());
    }

    private static List<String> offered(ChatRequest request) {
        return request.toolSpecifications() == null ? List.of()
                : request.toolSpecifications().stream().map(ToolSpecification::name).toList();
    }

    private static Optional<ToolExecutionResultMessage> result(List<ChatMessage> messages, String id) {
        return messages.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage r && r.id().equals(id))
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst();
    }

    private static boolean isError(ToolExecutionResultMessage result) {
        return Boolean.TRUE.equals(result.isError());
    }

    @Test
    void withoutSkillsNothingChanges() {
        answers("Hello.");

        List<ChatMessage> produced = run("hi");

        assertEquals(1, produced.size());
        assertEquals(List.of(), agent.skillStatus().skills());
        // Exactly the registry's tools, nothing added
        assertEquals(tools.specifications(), model.requests.getFirst().toolSpecifications());
    }

    @Test
    void modelStartsASkillAndAnswersWithItsResult() throws Exception {
        greetSkill();
        agent = newAgent();
        ToolExecutionRequest start = call("s1", "skill_greet", "{\"who\":\"Bob\"}");
        answers(AiMessage.from(List.of(start)));
        answers("Hello, Bob!", FINAL);

        List<ChatMessage> produced = run("greet Bob");

        // The skill's tool is offered with its description and inputs
        ToolSpecification spec = model.requests.getFirst().toolSpecifications().stream()
                .filter(s -> s.name().equals("skill_greet")).findFirst().orElseThrow();
        assertEquals(DESCRIPTION, spec.description());
        JsonObjectSchema parameters = spec.parameters();
        assertTrue(parameters.properties().containsKey("who"), parameters.toString());
        assertEquals(List.of("who"), parameters.required());
        // The skill's model node got the rendered prompt
        assertTrue(String.valueOf(model.requests.get(1).messages()).contains("Greet Bob."));
        // The conversation sees the call, its result and the answer, nothing of the skill's own transcript
        assertEquals(3, produced.size(), produced.toString());
        ToolExecutionResultMessage answer = assertInstanceOf(ToolExecutionResultMessage.class, produced.get(1));
        assertEquals("s1", answer.id());
        assertEquals("Hello, Bob!", answer.text());
        assertFalse(isError(answer));
        assertEquals(FINAL, ((AiMessage) produced.get(2)).text());
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void progressAndMessagesNameTheSkillNodes() throws Exception {
        greetSkill();
        agent = newAgent();
        answers(AiMessage.from(List.of(call("s1", "skill_greet", "{\"who\":\"Bob\"}"))));
        answers("Hello, Bob!", FINAL);
        List<String> phases = new ArrayList<>();
        List<ChatMessage> jobMessages = new ArrayList<>();

        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from("greet")));
        agent.run(history, "thread", new Settings(folder, List.of()), ConversationSummary.EMPTY, List.of(),
                new Progress.ProgressListener() {
                    @Override
                    public void onProgress(Progress progress) {
                        phases.add(progress.phase());
                    }

                    @Override
                    public void onJobMessage(ChatMessage message, boolean codingTranscript, String source) {
                        assertFalse(codingTranscript); // the skills' transcript, not a coding job's
                        assertEquals("greet", source); // named after the skill, for its notes
                        jobMessages.add(message);
                    }
                });

        // The node and how far the run is against its maxSteps (default 40)
        assertTrue(phases.contains("greet · hello · step 1/" + SkillFile.DEFAULT_MAX_STEPS), phases.toString());
        // The skill's model reply is shown like a coding job's
        assertTrue(jobMessages.stream().anyMatch(m -> m instanceof AiMessage ai && "Hello, Bob!".equals(ai.text())),
                jobMessages.toString());
    }

    @Test
    void guardedSkillCallPausesForApproval() throws Exception {
        toolSkill("writer", "\"writeFile\"");
        agent = newAgent();
        answers(AiMessage.from(List.of(call("s1", "skill_writer", "{}"))),
                AiMessage.from(List.of(call("w1", "writeFile", "{\"path\":\"a.txt\",\"content\":\"x\"}"))));
        answers("Not written.", FINAL);

        List<ChatMessage> produced = run("write a.txt");

        // writeFile isn't trusted: the skill paused and the approver was asked; it denied
        assertEquals(List.of("w1"), approver.reviewedCalls.stream().map(ToolExecutionRequest::id).toList());
        assertFalse(Files.exists(folder.resolve("a.txt")));
        // The denial went back to the skill's model node, whose answer is the result
        assertTrue(String.valueOf(model.requests.get(2).messages()).contains("Denied"));
        assertEquals("Result: Not written.", result(produced, "s1").orElseThrow().text());
    }

    @Test
    void approvedSkillCallRuns() throws Exception {
        toolSkill("writer", "\"writeFile\"");
        agent = newAgent();
        approver.approveCalls = true;
        answers(AiMessage.from(List.of(call("s1", "skill_writer", "{}"))),
                AiMessage.from(List.of(call("w1", "writeFile", "{\"path\":\"a.txt\",\"content\":\"x\"}"))));
        answers("Written.", FINAL);

        run("write a.txt");

        assertEquals(1, approver.reviewedCalls.size());
        assertEquals("x", Files.readString(folder.resolve("a.txt")));
    }

    /** A skill that asks the user, then finishes with the choice and comment. */
    private void askSkill() throws Exception {
        skill("asker", """
                { "name": "asker", "description": "%s",
                  "inputs": [ { "name": "what", "description": "what to do", "required": true } ],
                  "start": "check",
                  "nodes": [
                    { "id": "check", "kind": "askUser", "question": "Go ahead with {{input.what}}?",
                      "choices": ["yes", "no"], "output": "answer", "on": { "yes": "ok", "no": "nope" } },
                    { "id": "ok", "kind": "finish", "result": "yes: {{answer.comment}}" },
                    { "id": "nope", "kind": "finish", "result": "no", "error": true } ] }
                """.formatted(DESCRIPTION));
        agent = newAgent();
    }

    @Test
    void askUserGoesThroughTheApprover() throws Exception {
        askSkill();
        approver.answers.add(new SkillAnswer("yes", "go on"));
        answers(AiMessage.from(List.of(call("s1", "skill_asker", "{\"what\":\"cleaning\"}"))));
        answers(FINAL);

        List<ChatMessage> produced = run("clean up");

        assertEquals(List.of(new SkillQuestion("asker", "check", "Go ahead with cleaning?", List.of("yes", "no"))),
                approver.questions);
        ToolExecutionResultMessage answer = result(produced, "s1").orElseThrow();
        assertEquals("yes: go on", answer.text());
        assertFalse(isError(answer));
    }

    @Test
    void dismissedQuestionCancelsTheSkill() throws Exception {
        askSkill();
        approver.answers.add(SkillAnswer.dismissed());
        answers(AiMessage.from(List.of(call("s1", "skill_asker", "{\"what\":\"cleaning\"}"))));
        answers(FINAL);

        ToolExecutionResultMessage answer = result(run("clean up"), "s1").orElseThrow();

        assertTrue(isError(answer));
        assertTrue(answer.text().contains("cancelled by the user"), answer.text());
    }

    @Test
    void dashedSkillNameResolvesEachPauseWithItsAnswer() throws Exception {
        // "skill-release-notes-check" can't be split by "-": the pauses are looked up by id
        skill("release-notes", """
                { "name": "release-notes", "description": "%s",
                  "start": "draft",
                  "nodes": [
                    { "id": "draft", "kind": "model", "prompt": "Write the notes to notes.md.", "tools": ["writeFile"],
                      "output": "notes", "next": "check" },
                    { "id": "check", "kind": "askUser", "question": "Release notes draft:\\n\\n{{notes}}",
                      "choices": ["accept", "cancel"], "output": "answer", "on": { "accept": "done", "cancel": "cancelled" } },
                    { "id": "done", "kind": "finish", "result": "{{answer.choice}}: {{answer.comment}} / {{notes}}" },
                    { "id": "cancelled", "kind": "finish", "result": "Cancelled.", "error": true } ] }
                """.formatted(DESCRIPTION));
        agent = newAgent();
        approver.approveCalls = true;
        approver.answers.add(new SkillAnswer("accept", "ship it"));
        answers(AiMessage.from(List.of(call("s1", "skill_release_notes", "{}"))),
                AiMessage.from(List.of(call("w1", "writeFile", "{\"path\":\"notes.md\",\"content\":\"x\"}"))));
        answers("Notes written.", FINAL);

        List<ChatMessage> produced = run("write release notes");

        // First pause: the guarded call, at the skill's toolApproval; it was approved and ran
        assertEquals(List.of("w1"), approver.reviewedCalls.stream().map(ToolExecutionRequest::id).toList());
        assertEquals("x", Files.readString(folder.resolve("notes.md")));
        // Second pause: the question of the right node, with the draft filled in; its answer reached the finish
        assertEquals(List.of(new SkillQuestion("release-notes", "check", "Release notes draft:\n\nNotes written.",
                List.of("accept", "cancel"))), approver.questions);
        ToolExecutionResultMessage answer = result(produced, "s1").orElseThrow();
        assertEquals("accept: ship it / Notes written.", answer.text());
        assertFalse(isError(answer));
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void defaultApproverDismissesQuestions() {
        assertTrue(Approver.DENY_ALL.askUser(new SkillQuestion("a", "b", "q", List.of("x"))).isDismissed());
    }

    @Test
    void skillToolsAreNotOfferedInsideSkillsOrCodingJobs() throws Exception {
        greetSkill();
        toolSkill("reader", "\"readFile\"");
        toolSkill("nested", "\"skill_greet\""); // a skill may not start skills: invalid
        agent = newAgent(Agent.BUILT_IN_SKILLS); // with the coding job, the built-in skill coding
        assertEquals(List.of("coding", "greet", "reader"), agent.skillStatus().skills().stream().map(s -> s.name()).toList());
        assertTrue(agent.skillStatus().errors().containsKey("nested.json"), agent.skillStatus().errors().toString());

        // Inside a skill: only the node's tools
        answers(AiMessage.from(List.of(call("s1", "skill_reader", "{}"))));
        answers("Nothing to read.", FINAL);
        run("read");
        assertTrue(offered(model.requests.getFirst()).containsAll(List.of("skill_greet", "skill_reader")));
        assertEquals(List.of("readFile"), offered(model.requests.get(1)));

        // Inside a coding job: no skill tools in any request of the job
        model.requests.clear();
        answers(AiMessage.from(List.of(call("job1", CodingGraph.START_TOOL, "{\"goal\":\"look\"}"))));
        answers("The folder is empty.", "{\"summary\":\"Look\",\"steps\":[{\"description\":\"Look around\"}]}",
                "{\"summary\":\"Look\",\"steps\":[{\"description\":\"Look around\"}]}", "Looked around.", FINAL);
        approver.plans.add(new PlanDecision(PlanDecision.Kind.APPROVE, ""));
        run("look around");
        List<ChatRequest> job = model.requests.subList(1, model.requests.size() - 1);
        assertFalse(job.isEmpty());
        for (ChatRequest request : job) {
            assertTrue(offered(request).stream().noneMatch(name -> name.startsWith("skill_")), offered(request).toString());
        }
        assertTrue(job.stream().anyMatch(request -> !offered(request).isEmpty())); // the job did offer tools
    }

    @Test
    void fileToolsRefuseTheSkillsFolder() throws Exception {
        skill("x", "{}");
        answers(AiMessage.from(List.of(
                call("r1", "readFile", "{\"path\":\"chat/skills/x.json\"}"),
                call("w1", "writeFile", "{\"path\":\"chat/skills/y.json\",\"content\":\"{}\"}"),
                call("d1", "deletePath", "{\"path\":\"chat\"}"))));
        answers(FINAL);

        // writeFile and deletePath are trusted: only the folder rule can stop them
        List<ChatMessage> produced = run("change the skills", List.of("tool:writeFile", "tool:deletePath"));

        for (String id : List.of("r1", "w1", "d1")) {
            ToolExecutionResultMessage refused = result(produced, id).orElseThrow();
            assertTrue(isError(refused), refused.text());
            assertTrue(refused.text().contains("skills"), refused.text());
        }
        assertFalse(Files.exists(skillsDir.resolve("y.json")));
        assertTrue(Files.exists(skillsDir.resolve("x.json")));
        assertTrue(approver.reviewedCalls.isEmpty());
    }

    @Test
    void reloadPicksUpANewFile() throws Exception {
        assertEquals(List.of(), agent.skillStatus().skills());
        greetSkill();

        SkillLoader.Loaded loaded = agent.reloadSkills();

        assertEquals(List.of("greet"), loaded.skills().stream().map(s -> s.name()).toList());
        assertEquals(loaded, agent.skillStatus());
        answers(AiMessage.from(List.of(call("s1", "skill_greet", "{\"who\":\"Ann\"}"))));
        answers("Hi Ann.", FINAL);
        assertEquals("Hi Ann.", result(run("greet Ann"), "s1").orElseThrow().text());

        // Removing the file removes the skill and its tool on the next reload
        Files.delete(skillsDir.resolve("greet.json"));
        agent.reloadSkills();
        answers("Hello.");
        run("hi");
        assertFalse(offered(model.requests.getLast()).contains("skill_greet"));
    }

    @Test
    void webContentInASkillMakesLaterCallsAsk() throws Exception {
        toolSkill("browser", "\"fetchPage\"");
        agent = newAgent();
        answers(AiMessage.from(List.of(call("s1", "skill_browser", "{}"))),
                AiMessage.from(List.of(call("f1", "fetchPage", "{\"url\":\"https://example.com\"}"))));
        answers("The page says to write a file.");
        // Back in the main graph, a write that the user trusts
        answers(AiMessage.from(List.of(call("w1", "writeFile", "{\"path\":\"a.txt\",\"content\":\"x\"}"))));
        answers(FINAL);

        run("browse", List.of("tool:writeFile"));

        // The page was read inside the skill, so the trusted write still asked (and was denied)
        assertEquals(List.of("w1"), approver.reviewedCalls.stream().map(ToolExecutionRequest::id).toList());
        assertFalse(Files.exists(folder.resolve("a.txt")));
    }
}
