package net.rka.ai.agent.skills;

import net.rka.ai.tools.CodingTools;
import net.rka.ai.tools.FileTools;
import net.rka.ai.tools.TerminalTools;
import net.rka.ai.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing skill files, the validator's rules, and loading a folder where some files are broken. */
class SkillLoaderTest {

    private final ToolRegistry tools = ToolRegistry.of(new FileTools(), new TerminalTools(), new CodingTools(), new net.rka.ai.tools.WebTools());
    private final SkillValidator validator = new SkillValidator(GenericKinds.all(), tools);

    @TempDir
    Path folder;

    /** The spec's example: collect commits, draft notes, let the user accept, revise or cancel. */
    private static final String RELEASE_NOTES = """
            {
              "name": "release-notes",
              "description": "Draft release notes from the recent commits of the trusted folder. Use when the user asks for release notes or a changelog.",
              "inputs": [ { "name": "since", "description": "tag or date to start from", "required": false } ],
              "limits": { "maxSteps": 40, "maxToolRounds": 8 },
              "nodes": [
                { "id": "collect", "kind": "model", "prompt": "Collect the commits since {{input.since}} ...",
                  "tools": ["runCommand", "readFile"], "output": "commits", "next": "draft" },
                { "id": "draft", "kind": "model", "prompt": "Write release notes from:\\n{{commits}}", "output": "notes",
                  "next": "check" },
                { "id": "check", "kind": "askUser", "question": "Release notes draft:\\n\\n{{notes}}",
                  "choices": ["accept", "revise", "cancel"], "output": "answer",
                  "on": { "accept": "done", "revise": "draft", "cancel": "cancelled" } },
                { "id": "done", "kind": "finish", "result": "{{notes}}" },
                { "id": "cancelled", "kind": "finish", "result": "Cancelled by the user.", "error": true }
              ],
              "start": "collect"
            }
            """;

    /** The validator's errors for the release-notes example with one text replaced. */
    private List<String> errorsWith(String text, String replacement) {
        assertTrue(RELEASE_NOTES.contains(text), "test setup: example contains " + text);
        return validator.validate(SkillFile.parse(RELEASE_NOTES.replace(text, replacement)), false);
    }

    private static void assertMentions(List<String> errors, String... parts) {
        assertTrue(errors.stream().anyMatch(e -> List.of(parts).stream().allMatch(e::contains)),
                "expected an error mentioning " + List.of(parts) + " but got " + errors);
    }

    /** A minimal valid skill: one model node, then finish. */
    private static String simple(String name) {
        return """
                { "name": "%s", "description": "A small skill used by the loader tests to check loading.",
                  "start": "ask",
                  "nodes": [
                    { "id": "ask", "kind": "model", "prompt": "Say hi to {{goal}}", "output": "text", "next": "end" },
                    { "id": "end", "kind": "finish", "result": "{{text}}" } ] }
                """.formatted(name);
    }

    private void write(String fileName, String json) throws IOException {
        Files.writeString(folder.resolve(fileName), json);
    }

    @Test
    void loadsAValidSkill() throws IOException {
        SkillFile skill = SkillFile.parse(RELEASE_NOTES);
        assertEquals(List.of(), validator.validate(skill, false));
        assertEquals("skill_release_notes", skill.toolName());
        assertEquals(new SkillFile.Input("since", "tag or date to start from", false), skill.inputs().get(0));
        assertEquals(40, skill.limits().maxSteps());
        assertEquals("collect", skill.start());
        SkillFile.NodeSpec check = skill.nodes().get(2);
        assertEquals("askUser", check.kind());
        assertEquals(List.of("accept", "revise", "cancel"), check.fields().get("choices"));
        assertEquals("draft", check.on().get("revise"));

        write("release-notes.json", RELEASE_NOTES);
        SkillLoader.Loaded loaded = new SkillLoader(folder, List.of(), validator).load();
        assertEquals(List.of("release-notes"), loaded.skills().stream().map(SkillFile::name).toList());
        assertEquals(Map.of(), loaded.errors());
    }

    @Test
    void defaultsApplyWhenLeftOut() {
        SkillFile skill = SkillFile.parse(simple("hello"));
        assertEquals(new SkillFile.Limits(40, 8, Map.of()), skill.limits());
        assertEquals(List.of(), skill.inputs());
        assertEquals(List.of(), validator.validate(skill, false));
    }

    @Test
    void unknownKind() {
        assertMentions(errorsWith("\"kind\": \"askUser\"", "\"kind\": \"askUsr\""), "check", "askUsr");
        assertMentions(errorsWith("\"kind\": \"askUser\", ", ""), "check", "needs a `kind`");
    }

    @Test
    void danglingTarget() {
        assertMentions(errorsWith("\"revise\": \"draft\"", "\"revise\": \"drafts\""),
                "node `check`", "`on.revise` points to `drafts`, which doesn't exist");
        assertMentions(errorsWith("\"next\": \"draft\"", "\"next\": \"drift\""), "collect", "`next`", "drift");
    }

    @Test
    void unmappedOutcome() {
        assertMentions(errorsWith(", \"cancel\": \"cancelled\"", ""), "check", "cancel");
        // a mapping for an outcome the kind doesn't have is a typo too
        assertMentions(errorsWith("\"cancel\": \"cancelled\"", "\"cancel\": \"cancelled\", \"later\": \"done\""),
                "check", "later");
        // stopped never needs a mapping, but may have one
        assertEquals(List.of(), errorsWith("\"cancel\": \"cancelled\"", "\"cancel\": \"cancelled\", \"stopped\": \"cancelled\""));
    }

    @Test
    void unreachableNode() {
        assertMentions(errorsWith("{ \"id\": \"done\"",
                        "{ \"id\": \"orphan\", \"kind\": \"finish\", \"result\": \"x\" },\n{ \"id\": \"done\""),
                "orphan", "reach");
    }

    @Test
    void pathWithoutFinish() {
        // "spin" loops on itself forever: nothing after it ends the skill
        assertMentions(errorsWith("\"cancel\": \"cancelled\" } },",
                        "\"cancel\": \"spin\" } },\n{ \"id\": \"spin\", \"kind\": \"model\", \"prompt\": \"again\", \"next\": \"spin\" },"),
                "spin", "finish");
    }

    @Test
    void undefinedVariable() {
        assertMentions(errorsWith("from:\\n{{commits}}", "from:\\n{{commit}}"), "draft", "{{commit}}", "{{input.commit}}");
        assertMentions(errorsWith("{{input.since}}", "{{input.until}}"), "collect", "{{input.until}}");
        // built-ins are always defined
        assertEquals(List.of(), errorsWith("{{input.since}}", "{{input.since}} in {{folder}} for {{goal}} on {{environment}}"));
    }

    @Test
    void variableDefinedOnOnlyOnePath() {
        String skill = """
                { "name": "paths", "description": "Writes a value on one path only, then reads it.",
                  "start": "ask",
                  "nodes": [
                    { "id": "ask", "kind": "askUser", "question": "Write?", "choices": ["yes", "no"],
                      "on": { "yes": "writer", "no": "end" } },
                    { "id": "writer", "kind": "model", "prompt": "Write", "output": "text", "next": "end" },
                    { "id": "end", "kind": "finish", "result": "{{text}}" } ] }
                """;
        assertMentions(validator.validate(SkillFile.parse(skill), false), "end", "{{text}}");
    }

    @Test
    void stoppedEdgeDoesNotDefineTheNodesOutput() {
        // when "draft" stops, its output was never written, so "rescue" can't read it
        List<String> errors = errorsWith("\"next\": \"check\" },",
                "\"on\": { \"done\": \"check\", \"stopped\": \"rescue\" } },\n"
                        + "{ \"id\": \"rescue\", \"kind\": \"finish\", \"result\": \"{{notes}}\", \"error\": true },");
        assertMentions(errors, "rescue", "{{notes}}");
    }

    /** A counter loop: set → model → branch back until three rounds, then finish. */
    private static final String ROUNDS = """
            { "name": "rounds", "description": "Polishes a text three times, counting the rounds.",
              "start": "count",
              "nodes": [
                { "id": "count", "kind": "set", "values": { "rounds": {"increment": 1}, "log": {"append": "{{goal}}"} },
                  "next": "polish" },
                { "id": "polish", "kind": "model", "prompt": "Round {{rounds}}: polish {{goal}}", "output": "text",
                  "format": "json", "next": "enough" },
                { "id": "enough", "kind": "branch",
                  "cases": [ { "when": { "and": [ {"value": "{{rounds}}", "atLeast": 3},
                                                  {"value": "{{text.title}}", "isEmpty": false} ] }, "then": "yes" },
                             { "when": { "count": "{{log}}", "atMost": 0 }, "then": "broken" } ],
                  "on": { "yes": "done", "broken": "done", "otherwise": "count" } },
                { "id": "done", "kind": "finish", "result": "{{text}} after {{rounds}} rounds" } ] }
            """;

    @Test
    void branchAndSetKinds() {
        assertEquals(List.of(), validator.validate(SkillFile.parse(ROUNDS), false));
        List<String> errors = validator.validate(SkillFile.parse(ROUNDS
                .replace("\"atLeast\": 3", "\"over\": 3")
                .replace("{\"increment\": 1}", "{\"increment\": \"one\"}")
                .replace("\"format\": \"json\"", "\"format\": \"yaml\"")), false);
        assertMentions(errors, "enough", "cases[0].when[0]");
        assertMentions(errors, "count", "values.rounds");
        assertMentions(errors, "polish", "format", "yaml");
        // the branch's outcomes must all be wired: dropping one is reported
        assertMentions(validator.validate(SkillFile.parse(ROUNDS.replace("\"broken\": \"done\", ", "")), false),
                "enough", "broken");
    }

    @Test
    void unknownTool() {
        assertMentions(errorsWith("\"readFile\"]", "\"readFiles\"]"), "collect", "readFiles");
    }

    @Test
    void forbiddenTool() {
        assertMentions(errorsWith("\"readFile\"]", "\"startCodingJob\"]"), "collect", "startCodingJob", "never available");
        // forbidden even where the tool isn't registered at all
        assertMentions(errorsWith("\"readFile\"]", "\"runRemoteCommand\"]"), "collect", "runRemoteCommand", "never available");
        assertMentions(errorsWith("\"readFile\"]", "\"skill_other\"]"), "skill_other", "never available");
        assertTrue(SkillValidator.isForbidden("buildSkill"));
        assertTrue(SkillValidator.isForbidden("sshHosts"));
        assertTrue(!SkillValidator.isForbidden("readFile"));
    }

    @Test
    void badName() {
        assertMentions(errorsWith("\"name\": \"release-notes\"", "\"name\": \"Release_Notes\""), "Release_Notes");
        assertMentions(errorsWith("\"name\": \"release-notes\"", "\"name\": \"\""), "name");
        assertMentions(errorsWith("\"id\": \"collect\"", "\"id\": \"col-lect\""), "col-lect");
        assertMentions(errorsWith("\"id\": \"done\"", "\"id\": \"draft\""), "draft", "more than once");
        assertMentions(errorsWith("Draft release notes from the recent commits of the trusted folder. Use when the user asks for release notes or a changelog.", "Notes"),
                "description");
    }

    @Test
    void limitsInRange() {
        assertMentions(errorsWith("\"maxSteps\": 40", "\"maxSteps\": 500"), "maxSteps", "200");
        assertMentions(errorsWith("\"maxToolRounds\": 8", "\"maxToolRounds\": 0"), "maxToolRounds");
    }

    @Test
    void fileNameMismatch() throws IOException {
        write("notes.json", RELEASE_NOTES);
        SkillLoader.Loaded loaded = new SkillLoader(folder, List.of(), validator).load();
        assertEquals(List.of(), loaded.skills());
        assertMentions(loaded.errors().get("notes.json"), "release-notes.json");
    }

    @Test
    void toolFieldOnlyForBuiltIns() {
        String withTool = RELEASE_NOTES.replace("\"start\": \"collect\"", "\"start\": \"collect\", \"tool\": \"releaseNotes\"");
        SkillFile skill = SkillFile.parse(withTool);
        assertEquals("releaseNotes", skill.toolName());
        assertMentions(validator.validate(skill, false), "tool", "built-in");
        assertEquals(List.of(), validator.validate(skill, true));
    }

    @Test
    void unknownTopLevelFieldIsAnError() throws IOException {
        write("release-notes.json", RELEASE_NOTES.replace("\"start\": \"collect\"", "\"start\": \"collect\", \"strat\": 1"));
        assertMentions(new SkillLoader(folder, List.of(), validator).load().errors().get("release-notes.json"), "strat");
    }

    @Test
    void unknownNodeFieldIsAnError() {
        assertMentions(errorsWith("\"output\": \"notes\"", "\"ouput\": \"notes\""), "draft", "ouput");
    }

    @Test
    void oneBadFileDoesNotStopTheOthers() throws IOException {
        write("alpha.json", simple("alpha"));
        write("beta.json", simple("beta"));
        write("broken.json", simple("broken").replace("\"next\": \"end\"", "\"next\": \"ending\""));
        write("readme.txt", "not a skill");
        SkillLoader.Loaded loaded = new SkillLoader(folder, List.of(), validator).load();
        assertEquals(List.of("alpha", "beta"), loaded.skills().stream().map(SkillFile::name).toList());
        assertEquals(Set.of("broken.json"), loaded.errors().keySet());
        assertMentions(loaded.errors().get("broken.json"), "node `ask`", "`next` points to `ending`, which doesn't exist");
    }

    @Test
    void notJsonIsListed() throws IOException {
        write("alpha.json", simple("alpha"));
        write("garbage.json", "{ this is not json");
        SkillLoader.Loaded loaded = new SkillLoader(folder, List.of(), validator).load();
        assertEquals(List.of("alpha"), loaded.skills().stream().map(SkillFile::name).toList());
        assertMentions(loaded.errors().get("garbage.json"), "JSON");
    }

    @Test
    void missingFolderLoadsNothing() {
        SkillLoader.Loaded loaded = new SkillLoader(folder.resolve("absent"), List.of(), validator).load();
        assertEquals(List.of(), loaded.skills());
        assertEquals(Map.of(), loaded.errors());
        assertEquals(List.of(), new SkillLoader(null, List.of(), validator).load().skills());
    }

    @Test
    void nodeIdsOfDifferentSkillsMustNotCollide() throws IOException {
        // skill "a" node "b" and skill "a-b" both become "skill-a-b" in the agent graph
        write("a.json", simple("a").replace("\"id\": \"end\"", "\"id\": \"b\"").replace("\"next\": \"end\"", "\"next\": \"b\""));
        write("a-b.json", simple("a-b"));
        // files load in name order ("a-b.json" sorts before "a.json"), so the later one is skipped
        SkillLoader.Loaded loaded = new SkillLoader(folder, List.of(), validator).load();
        assertEquals(List.of("a-b"), loaded.skills().stream().map(SkillFile::name).toList());
        assertMentions(loaded.errors().get("a.json"), "skill-a-b", "a-b.json");
    }

    /** A built-in served from a map instead of the classpath resources. */
    private SkillLoader withBuiltIn(String json) {
        return new SkillLoader(folder, List.of("hello"), validator, name -> name.equals("hello") ? json : null);
    }

    private static final String BUILT_IN = simple("hello").replace("\"start\": \"ask\"", "\"start\": \"ask\", \"tool\": \"sayHello\"");

    @Test
    void builtInsLoadWithTheirTool() {
        SkillLoader.Loaded loaded = withBuiltIn(BUILT_IN).load();
        assertEquals(List.of("sayHello"), loaded.skills().stream().map(SkillFile::toolName).toList());
        assertEquals(Map.of(), loaded.errors());
        // a built-in that isn't there is listed, not thrown
        assertTrue(new SkillLoader(null, List.of("absent"), validator).load().errors().containsKey("absent.json"));
    }

    @Test
    void userFileReplacesBuiltIn() throws IOException {
        write("hello.json", simple("hello").replace("Say hi", "Say hello"));
        SkillLoader.Loaded loaded = withBuiltIn(BUILT_IN).load();
        assertEquals(1, loaded.skills().size());
        SkillFile skill = loaded.skills().get(0);
        assertEquals("Say hello to {{goal}}", skill.nodes().get(0).fields().get("prompt"));
        assertEquals("sayHello", skill.toolName()); // the override keeps the built-in's tool
        assertEquals(Map.of(), loaded.errors());
    }

    @Test
    void overrideIsAUserFileForTheKinds() throws IOException {
        // a finish kind that remembers what each check was told
        List<Boolean> seen = new java.util.ArrayList<>();
        NodeKind finish = GenericKinds.all().get("finish");
        Map<String, NodeKind> kinds = new java.util.LinkedHashMap<>(GenericKinds.all());
        kinds.put("finish", new NodeKind() {
            public String name() { return "finish"; }
            public Set<String> outcomes(SkillFile.NodeSpec node) { return finish.outcomes(node); }
            public List<String> check(SkillFile.NodeSpec node, SkillContext context) {
                seen.add(context.builtIn());
                return finish.check(node, context);
            }
            public Set<String> writes(SkillFile.NodeSpec node) { return finish.writes(node); }
            public Set<String> reads(SkillFile.NodeSpec node) { return finish.reads(node); }
            public org.bsc.langgraph4j.action.NodeActionWithConfig<net.rka.ai.agent.AgentGraph.State> action(
                    SkillFile.NodeSpec node, SkillRuntime runtime) { return finish.action(node, runtime); }
            public String route(SkillFile.NodeSpec node, SkillRuntime runtime, net.rka.ai.agent.AgentGraph.State state) { return finish.route(node, runtime, state); }
        });
        write("hello.json", simple("hello"));
        SkillLoader.Loaded loaded = new SkillLoader(folder, List.of("hello"), new SkillValidator(kinds, tools),
                name -> BUILT_IN).load();
        assertEquals(List.of(true, false), seen); // the built-in, then its replacement as a user file
        assertEquals("sayHello", loaded.skills().get(0).toolName());
        assertEquals(Map.of(), loaded.errors());
    }

    @Test
    void overrideThatCollidesFallsBackToBuiltIn() throws IOException {
        // the replacement of "a" adds node "b", which would be "skill-a-b": the built-in skill a-b's id
        write("a.json", simple("a").replace("\"id\": \"end\"", "\"id\": \"b\"").replace("\"next\": \"end\"", "\"next\": \"b\""));
        SkillLoader.Loaded loaded = new SkillLoader(folder, List.of("a", "a-b"), validator, SkillLoaderTest::simple).load();
        assertEquals(List.of("a", "a-b"), loaded.skills().stream().map(SkillFile::name).toList());
        assertEquals("end", loaded.skills().get(0).nodes().get(1).id()); // the built-in a, not the file
        assertMentions(loaded.errors().get("a.json"), "skill-a-b", "built-in skill is used instead");
    }

    @Test
    void stoppedEdgeDoesNotCountAsFinish() {
        String skill = """
                { "name": "spin", "description": "Loops on done; only its error path reaches the finish.",
                  "start": "spin",
                  "nodes": [
                    { "id": "spin", "kind": "model", "prompt": "again", "on": { "done": "spin", "stopped": "end" } },
                    { "id": "end", "kind": "finish", "result": "stopped", "error": true } ] }
                """;
        assertMentions(validator.validate(SkillFile.parse(skill), false), "spin", "finish");
    }

    @Test
    void requiredMustBeTrueOrFalse() throws IOException {
        write("release-notes.json", RELEASE_NOTES.replace("\"required\": false", "\"required\": \"yes\""));
        assertMentions(new SkillLoader(folder, List.of(), validator).load().errors().get("release-notes.json"),
                "since", "required", "true or false");
    }

    @Test
    void builtInsOnlyAndCurrentAndFallbacks() throws IOException {
        write("hello.json", simple("hello").replace("Say hi", "Say hello")); // replaces the built-in
        write("other.json", simple("other"));
        SkillLoader loader = withBuiltIn(BUILT_IN);

        // current(): the folder as it is, but no diagram pages written (the builder checks drafts with it)
        assertEquals(List.of("hello", "other"), loader.current().skills().stream().map(SkillFile::name).toList());
        assertTrue(Files.notExists(folder.resolve("other.md")));
        // builtInsOnly(): the built-in hello, not the user's replacement, and nothing from the folder
        SkillLoader.Loaded builtIns = loader.builtInsOnly();
        assertEquals(List.of("Say hi to {{goal}}"),
                builtIns.skills().stream().map(s -> s.nodes().get(0).fields().get("prompt")).toList());

        // A fallback to the built-ins lists the problem under every file it leaves out, the replacement too
        SkillLoader.Loaded loaded = loader.load();
        SkillLoader.Loaded fallback = loaded.fallback(builtIns.skills(), builtIns.errors(), "only the built-ins");
        assertEquals(builtIns.skills(), fallback.skills());
        assertEquals(Map.of("hello.json", List.of("only the built-ins"), "other.json", List.of("only the built-ins")),
                fallback.errors());
        assertEquals(SkillLoader.Origin.BUILT_IN, loader.origin(fallback, "hello"));
        // without(): one skill out, listed under its file name
        SkillLoader.Loaded without = loaded.without(Map.of("other", "can't build"));
        assertEquals(List.of("hello"), without.skills().stream().map(SkillFile::name).toList());
        assertEquals(Map.of("other.json", List.of("can't build")), without.errors());
    }

    @Test
    void invalidUserOverrideFallsBackToBuiltIn() throws IOException {
        write("hello.json", simple("hello").replace("{{text}}", "{{txt}}"));
        SkillLoader.Loaded loaded = withBuiltIn(BUILT_IN).load();
        assertEquals("Say hi to {{goal}}", loaded.skills().get(0).nodes().get(0).fields().get("prompt"));
        assertMentions(loaded.errors().get("hello.json"), "{{txt}}");
        // an override may not rename the built-in's tool either
        write("hello.json", simple("hello").replace("\"start\": \"ask\"", "\"start\": \"ask\", \"tool\": \"writeFile\""));
        loaded = withBuiltIn(BUILT_IN).load();
        assertEquals("sayHello", loaded.skills().get(0).toolName());
        assertMentions(loaded.errors().get("hello.json"), "sayHello");
    }
}
