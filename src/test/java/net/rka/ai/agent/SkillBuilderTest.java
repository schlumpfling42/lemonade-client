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
import dev.langchain4j.model.chat.response.ChatResponse;
import net.rka.ai.agent.skills.GenericKinds;
import net.rka.ai.agent.skills.SkillFile;
import net.rka.ai.agent.skills.SkillLoader;
import net.rka.ai.agent.skills.SkillValidator;
import net.rka.ai.config.Settings;
import net.rka.ai.tools.CodingTools;
import net.rka.ai.tools.EnvironmentInfo;
import net.rka.ai.tools.FileTools;
import net.rka.ai.tools.SkillTools;
import net.rka.ai.tools.TerminalTools;
import net.rka.ai.tools.ToolRegistry;
import net.rka.ai.tracing.Tracing;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A whole turn through {@link Agent} with the skill builder: the model calls {@code buildSkill}, the
 * main graph routes it to the {@code skillBuilder} subgraph, which drafts a skill with the (scripted)
 * model, checks it with the real validator, pauses for the user's review and saves it on Save.
 */
class SkillBuilderTest {

    private static final String FINAL = "Done.";
    private static final String DESCRIPTION = "Greets someone by name. Use it when the user wants a greeting.";

    /** A valid skill named greet. */
    private static final String VALID = """
            { "name": "greet", "description": "%s",
              "inputs": [ { "name": "who", "description": "the name to greet", "required": true } ],
              "start": "hello",
              "nodes": [
                { "id": "hello", "kind": "model", "prompt": "Greet {{input.who}}.", "output": "greeting", "next": "done" },
                { "id": "done", "kind": "finish", "result": "{{greeting}}" } ] }
            """.formatted(DESCRIPTION);

    /** The same skill, greeting loudly (another prompt, so a test can tell the two apart), with tools. */
    private static final String LOUD = VALID.replace("\"prompt\": \"Greet {{input.who}}.\"",
            "\"prompt\": \"Greet {{input.who}} LOUDLY.\", \"tools\": [\"readFile\", \"writeFile\"]");

    /** Invalid: the hello node's next leads nowhere. */
    private static final String INVALID = VALID.replace("\"next\": \"done\"", "\"next\": \"nowhere\"");

    @TempDir
    Path dir;

    private Path folder;
    private Path skillsDir;
    private ScriptedModel model;
    private BuilderApprover approver;
    private Agent agent;

    /** A scripted answer that makes the model call fail, like a dropped connection. */
    private static final AiMessage FAILS = AiMessage.from("(fails)");

    /** Answers from a queue (failing on an unexpected request) and records every request. */
    private static final class ScriptedModel implements ChatModel {
        final Deque<AiMessage> answers = new ArrayDeque<>();
        final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request);
            AiMessage answer = answers.poll();
            if (answer == null) throw new AssertionError("Unexpected model request: " + request.messages());
            if (answer == FAILS) throw new IllegalStateException("Connection reset");
            return ChatResponse.builder().aiMessage(answer).build();
        }
    }

    /** Records every reviewed draft and answers from a queue of decisions. */
    private static final class BuilderApprover implements Approver {
        final List<SkillDraft> drafts = new ArrayList<>();
        final Deque<SkillDecision> decisions = new ArrayDeque<>();
        Runnable whileReviewing = () -> { };

        @Override
        public Map<String, Boolean> review(List<ToolExecutionRequest> calls) {
            throw new AssertionError("No tool call should need approval: " + calls);
        }

        @Override
        public SkillDecision reviewSkill(SkillDraft draft) {
            drafts.add(draft);
            whileReviewing.run();
            return decisions.remove();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        folder = Files.createDirectory(dir.resolve("work"));
        skillsDir = Files.createDirectories(dir.resolve("chat").resolve("skills"));
        model = new ScriptedModel();
        approver = new BuilderApprover();
        agent = newAgent(List.of());
    }

    private Agent newAgent(List<String> builtIns) throws Exception {
        EnvironmentInfo environment = new EnvironmentInfo(dir.resolve("environment.json"),
                command -> "exit code: 0\n--- stdout ---\nv1 of " + command, Clock.systemUTC());
        ToolRegistry tools = ToolRegistry.of(new FileTools(), new net.rka.ai.tools.OutlineTools(), new net.rka.ai.tools.JavaTools(), new TerminalTools(), new CodingTools(), new SkillTools());
        SkillLoader loader = new SkillLoader(skillsDir, builtIns,
                new SkillValidator(Agent.kinds(new CodingGraph.Setup(environment, 600)), tools));
        return new Agent("test", "scripted", model, Tracing.off(), tools, approver, loader, new LiveChannel());
    }

    private static ToolExecutionRequest buildCall(String description) {
        return ToolExecutionRequest.builder().id("b1").name("buildSkill")
                .arguments("{\"description\":\"" + description + "\"}").build();
    }

    /** The model starts the builder, then answers each draft request with the given texts, then {@link #FINAL}. */
    private void script(String description, String... drafts) {
        model.answers.add(AiMessage.from(List.of(buildCall(description))));
        for (String draft : drafts) model.answers.add(AiMessage.from(draft));
        model.answers.add(AiMessage.from(FINAL));
    }

    private List<ChatMessage> run(String question) {
        List<ChatMessage> history = new ArrayList<>(List.of(SystemMessage.from("system"), UserMessage.from(question)));
        return agent.run(history, "thread", new Settings(folder, List.of()), ConversationSummary.EMPTY, List.of())
                .messages();
    }

    private static ToolExecutionResultMessage result(List<ChatMessage> messages) {
        return messages.stream()
                .filter(m -> m instanceof ToolExecutionResultMessage r && r.id().equals("b1"))
                .map(ToolExecutionResultMessage.class::cast)
                .findFirst().orElseThrow(() -> new AssertionError("buildSkill was not answered: " + messages));
    }

    private static boolean isError(ToolExecutionResultMessage result) {
        return Boolean.TRUE.equals(result.isError());
    }

    private String request(int index) {
        return String.valueOf(model.requests.get(index).messages());
    }

    private static List<String> offered(ChatRequest request) {
        return request.toolSpecifications() == null ? List.of()
                : request.toolSpecifications().stream().map(ToolSpecification::name).toList();
    }

    @Test
    void invalidDraftGetsErrorsThenValidDraftIsReviewed() {
        script("a skill that greets someone", "```json\n" + INVALID + "\n```", VALID);
        approver.decisions.add(SkillDecision.CANCELLED);

        run("make a greeting skill");

        // The first draft request: the description, the schema and the tools a skill may list (no forbidden ones)
        String first = request(1);
        assertTrue(first.contains("a skill that greets someone"), first);
        assertTrue(first.contains("- writeFile"), first);
        assertTrue(first.contains("askUser"), first);
        assertFalse(first.contains("- startCodingJob"), first);
        assertFalse(first.contains("- buildSkill"), first);
        assertEquals(List.of(), offered(model.requests.get(1))); // drafting calls no tools
        // The validator's error went back to the model
        assertTrue(request(2).contains("`next` points to `nowhere`, which doesn't exist"), request(2));
        // Only the valid draft was shown to the user
        assertEquals(1, approver.drafts.size());
        SkillDraft draft = approver.drafts.getFirst();
        assertEquals("greet", draft.skill().name());
        assertFalse(draft.replaces());
        assertEquals(List.of(), draft.toolsNeedingApproval());
        assertTrue(draft.outline().contains("hello"), draft.outline());
        assertEquals("greet", SkillFile.parse(draft.json()).name());
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void saveWritesTheFileAndReloads() throws Exception {
        script("a skill that greets someone", VALID);
        approver.decisions.add(new SkillDecision(SkillDecision.Kind.SAVE, ""));

        ToolExecutionResultMessage answer = result(run("make a greeting skill"));

        assertFalse(isError(answer), answer.text());
        assertTrue(answer.text().contains("skill_greet"), answer.text());
        Path file = skillsDir.resolve("greet.json");
        assertEquals(DESCRIPTION, SkillFile.parse(Files.readString(file)).description());
        // The turn kept its graph: the model's last request in it didn't offer the new skill yet
        assertFalse(offered(model.requests.getLast()).contains("skill_greet"));
        // After the turn the skills were reloaded: the skill is in use, with its generated diagram next to it
        assertEquals(List.of("greet"), agent.skillStatus().skills().stream().map(SkillFile::name).toList());
        assertTrue(Files.readString(skillsDir.resolve("greet.md")).contains("```mermaid"));
        model.answers.add(AiMessage.from("Hello."));
        run("hi");
        assertTrue(offered(model.requests.getLast()).contains("skill_greet"));
    }

    @Test
    void cancelWritesNothing() throws Exception {
        script("a skill that greets someone", VALID);
        approver.decisions.add(SkillDecision.CANCELLED);

        ToolExecutionResultMessage answer = result(run("make a greeting skill"));

        assertTrue(isError(answer));
        assertTrue(answer.text().contains("cancelled"), answer.text());
        try (var files = Files.list(skillsDir)) {
            assertEquals(List.of(), files.toList());
        }
        assertEquals(List.of(), agent.skillStatus().skills());
        // An approver that doesn't know about skills cancels
        assertEquals(SkillDecision.Kind.CANCEL, Approver.DENY_ALL.reviewSkill(approver.drafts.getFirst()).kind());
    }

    @Test
    void threeInvalidDraftsFinishWithErrors() throws Exception {
        script("a skill that greets someone", INVALID, INVALID, "not JSON at all");

        ToolExecutionResultMessage answer = result(run("make a greeting skill"));

        assertTrue(isError(answer));
        assertTrue(answer.text().contains("3 attempts"), answer.text());
        assertTrue(answer.text().contains("JSON"), answer.text()); // the last draft's problem
        assertTrue(approver.drafts.isEmpty()); // nothing to review
        assertEquals(5, model.requests.size()); // the call, three drafts, the final answer
        assertFalse(Files.exists(skillsDir.resolve("greet.json")));
    }

    @Test
    void reviseStartsANewRound() throws Exception {
        // Two invalid drafts per round: with 3 attempts per round, five invalid drafts in all don't end it
        script("a skill that greets someone", INVALID, INVALID, VALID, INVALID, INVALID, LOUD);
        approver.decisions.add(new SkillDecision(SkillDecision.Kind.REVISE, "make it louder"));
        approver.decisions.add(new SkillDecision(SkillDecision.Kind.SAVE, ""));

        ToolExecutionResultMessage answer = result(run("make a greeting skill"));

        assertFalse(isError(answer), answer.text());
        assertEquals(2, approver.drafts.size());
        // The draft after the review got the user's comment
        assertTrue(request(4).contains("make it louder"), request(4));
        assertTrue(Files.readString(skillsDir.resolve("greet.json")).contains("LOUDLY"));
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void changingAnExistingSkillStartsFromItsFile() throws Exception {
        Files.writeString(skillsDir.resolve("greet.json"), VALID);
        agent = newAgent(List.of());
        String replacing = LOUD.replaceFirst("\\{", "{ \"replaces\": true,");
        // The first draft keeps the name without saying "replaces": refused, the file exists
        script("change the greet skill so that it greets loudly", LOUD, replacing);
        approver.decisions.add(new SkillDecision(SkillDecision.Kind.SAVE, ""));

        ToolExecutionResultMessage answer = result(run("make greet louder"));

        // The draft started from the skill's file
        assertTrue(request(1).contains("Greet {{input.who}}."), request(1));
        assertTrue(request(2).contains("The name `greet` is taken"), request(2));
        SkillDraft draft = approver.drafts.getFirst();
        assertTrue(draft.replaces());
        assertTrue(draft.toMarkdown().contains("Replaces your skill"), draft.toMarkdown());
        assertEquals(List.of("writeFile"), draft.toolsNeedingApproval()); // readFile is read-only
        assertFalse(draft.json().contains("replaces")); // the file holds only the skill
        assertFalse(isError(answer), answer.text());
        assertTrue(Files.readString(skillsDir.resolve("greet.json")).contains("LOUDLY"));
        assertTrue(agent.skillStatus().skill("greet").orElseThrow().nodes().getFirst().fields().get("prompt")
                .toString().contains("LOUDLY"));
    }

    @Test
    void replacingABuiltInIsShownInTheReview() throws Exception {
        agent = newAgent(Agent.BUILT_IN_SKILLS);
        String coding;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("net/rka/ai/agent/skills/coding.json")) {
            coding = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // The draft leaves "tool" out: it keeps the built-in's pinned tool
        String draftJson = coding.replace("\"tool\": \"startCodingJob\",", "").replaceFirst("\\{", "{ \"replaces\": true,");
        assertFalse(draftJson.contains("\"tool\""));
        script("change the coding skill so that it verifies twice", draftJson);
        approver.decisions.add(SkillDecision.CANCELLED);

        run("verify coding jobs twice");

        assertTrue(request(1).contains("\"coding.planApproval\""), "the built-in's file is the starting point");
        // ... with the coding kinds, their outcomes and limits, the coding maxSteps cap and the pinned tool
        String first = request(1);
        for (String expected : List.of("## Coding kinds", "coding.verify", "passed, fix, failing", "maxEditAttempts",
                "maxVerifyAttempts", "1 to 1000", "tool is pinned")) {
            assertTrue(first.contains(expected), expected + " in " + first);
        }
        SkillDraft draft = approver.drafts.getFirst();
        assertTrue(draft.toMarkdown().contains("**Limits:** maxSteps 300 · maxToolRounds 12"), draft.toMarkdown());
        assertTrue(draft.toMarkdown().contains("maxVerifyAttempts 3"), draft.toMarkdown());
        assertTrue(draft.replaces());
        assertTrue(draft.replacesBuiltIn());
        assertTrue(draft.toMarkdown().contains("Replaces the built-in coding skill"), draft.toMarkdown());
        assertEquals("startCodingJob", draft.tool());
        assertTrue(draft.toMarkdown().contains("tool `startCodingJob`"), draft.toMarkdown());
        assertFalse(Files.exists(skillsDir.resolve("coding.json")));
    }

    @Test
    void aMentionOfASkillIsNotARequestToChangeIt() throws Exception {
        agent = newAgent(Agent.BUILT_IN_SKILLS);
        script("a skill that reviews my coding style", VALID);
        approver.decisions.add(SkillDecision.CANCELLED);

        run("make a style review skill");

        // "coding" is named, but nothing asks to change the coding skill: its file and kinds stay out
        assertFalse(request(1).contains("\"coding.planApproval\""), request(1));
        assertFalse(request(1).contains("## Coding kinds"), request(1));
        assertTrue(request(1).contains("coding (built-in)"), request(1)); // still listed as a taken name
        assertTrue(SkillBuilderGraph.asksToChange("edit release-notes", "release-notes"));
        assertTrue(SkillBuilderGraph.asksToChange("Please fix my greet skill", "greet"));
        assertFalse(SkillBuilderGraph.asksToChange("edit release-notes-v2", "release-notes"));
    }

    @Test
    void eachDraftRequestCarriesOnlyTheLatestDraft() {
        script("a skill that greets someone", VALID, LOUD, VALID);
        approver.decisions.add(new SkillDecision(SkillDecision.Kind.REVISE, "first comment"));
        approver.decisions.add(new SkillDecision(SkillDecision.Kind.REVISE, "second comment"));
        approver.decisions.add(SkillDecision.CANCELLED);

        run("make a greeting skill");

        // The third draft request: the format reference once, the latest draft (LOUD) and its comment only
        String third = request(3);
        assertEquals(1, third.split("# How a skill file looks", -1).length - 1, third);
        assertTrue(third.contains("LOUDLY") && third.contains("second comment"), third);
        assertFalse(third.contains("Greet {{input.who}}.\""), third); // the first draft's prompt
        assertFalse(third.contains("first comment"), third);
        assertEquals(4, model.requests.get(3).messages().size()); // instructions, request, latest draft, comment
    }

    @Test
    void aSkillSavedDuringTheTurnCountsForTheIdCheck() throws Exception {
        // While the user looks at the first draft, another window saves greet-hello: its id skill-greet-hello
        // is the one the draft's node hello would take
        approver.whileReviewing = () -> {
            try {
                Files.writeString(skillsDir.resolve("greet-hello.json"), """
                        { "name": "greet-hello", "description": "%s", "start": "done",
                          "nodes": [ { "id": "done", "kind": "finish", "result": "hi" } ] }
                        """.formatted(DESCRIPTION));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        String renamed = VALID.replace("\"id\": \"hello\"", "\"id\": \"greeting\"").replace("\"start\": \"hello\"", "\"start\": \"greeting\"");
        script("a skill that greets someone", VALID, VALID, renamed);
        approver.decisions.add(new SkillDecision(SkillDecision.Kind.REVISE, "again"));
        approver.decisions.add(SkillDecision.CANCELLED);

        run("make a greeting skill");

        assertTrue(request(3).contains("already taken by the skill `greet-hello`"), request(3));
        assertEquals(2, approver.drafts.size()); // the colliding draft was never shown
    }

    @Test
    void aPromptWithACodeFenceIsValidRawAndFenced() {
        // ``` inside a JSON string value must not be taken for the fence around the answer
        String fenced = VALID.replace("Greet {{input.who}}.", "Greet {{input.who}}. Answer in a ```json block```.");
        script("a skill that greets someone", fenced, "Here it is:\n```json\n" + fenced + "\n```");
        approver.decisions.add(new SkillDecision(SkillDecision.Kind.REVISE, "again, fenced"));
        approver.decisions.add(SkillDecision.CANCELLED);

        run("make a greeting skill");

        assertEquals(2, approver.drafts.size()); // both answers were valid drafts at the first attempt
        for (SkillDraft draft : approver.drafts) {
            assertTrue(draft.skill().nodes().getFirst().fields().get("prompt").toString().contains("```json block```"));
        }
    }

    @Test
    void aFileThatAppearedDuringTheReviewIsNotReplaced() throws Exception {
        script("a skill that greets someone", VALID);
        // While the user looks at the draft, a greet.json shows up (e.g. from a second window)
        approver.whileReviewing = () -> {
            try {
                Files.writeString(skillsDir.resolve("greet.json"), "{ \"other\": true }");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        approver.decisions.add(new SkillDecision(SkillDecision.Kind.SAVE, ""));

        ToolExecutionResultMessage answer = result(run("make a greeting skill"));

        assertTrue(isError(answer));
        assertTrue(answer.text().contains("appeared while the draft was reviewed"), answer.text());
        assertEquals("{ \"other\": true }", Files.readString(skillsDir.resolve("greet.json")));
        assertFalse(Files.exists(skillsDir.resolve("greet.json.tmp")));
    }

    @Test
    void aModelErrorWhileDraftingFinishes() {
        model.answers.add(AiMessage.from(List.of(buildCall("a skill that greets someone"))));
        model.answers.add(FAILS);
        model.answers.add(AiMessage.from(FINAL));

        ToolExecutionResultMessage answer = result(run("make a greeting skill"));

        assertTrue(isError(answer));
        assertTrue(answer.text().contains("model error: Connection reset"), answer.text());
        assertTrue(approver.drafts.isEmpty());
        assertTrue(model.answers.isEmpty());
    }

    @Test
    void aNameWithAPathIsRejected() throws Exception {
        script("a skill that greets someone", VALID.replace("\"name\": \"greet\"", "\"name\": \"../x\""), VALID);
        approver.decisions.add(SkillDecision.CANCELLED);

        run("make a greeting skill");

        assertTrue(request(2).contains("The name `../x` may only have lower-case letters"), request(2));
        assertEquals("greet", approver.drafts.getFirst().skill().name()); // only the fixed draft was reviewed
        assertFalse(Files.exists(skillsDir.resolveSibling("x.json")));
    }

    @Test
    void aPageTheUserWroteIsNotReplaced() throws Exception {
        Files.writeString(skillsDir.resolve("greet.json"), VALID);
        Files.writeString(skillsDir.resolve("greet.md"), "my own notes");

        agent.reloadSkills();

        assertEquals("my own notes", Files.readString(skillsDir.resolve("greet.md")));
        // A generated page is rewritten when the skill changes
        Files.delete(skillsDir.resolve("greet.md"));
        agent.reloadSkills();
        Files.writeString(skillsDir.resolve("greet.json"), LOUD);
        agent.reloadSkills();
        String page = Files.readString(skillsDir.resolve("greet.md"));
        assertTrue(page.startsWith(net.rka.ai.agent.skills.SkillDiagram.GENERATED_MARKER), page);
        assertTrue(page.contains("LOUDLY"), page);
    }

    @Test
    void theExampleInThePromptIsValid() {
        SkillValidator validator = new SkillValidator(GenericKinds.all(), ToolRegistry.defaultTools());
        assertEquals(List.of(), validator.validate(SkillFile.parse(SkillBuilderGraph.EXAMPLE), false));
    }
}
