# Task 4 report: skills in the agent graph and `Agent`

**Status:** DONE_WITH_CONCERNS (complete and green; the concerns are small and listed at the end)
No commits, because this is not a git repo. `.\gradlew.bat build` → BUILD SUCCESSFUL. All 26 suites pass with 0 failures and no compiler warnings.

## What I implemented

### New: `skills/SkillQuestion.java`, `skills/SkillAnswer.java`
- `record SkillQuestion(String skill, String node, String question, List<String> choices)`
- `record SkillAnswer(String choice, String comment)`, plus:
  - `static dismissed()`, which gives `(null, "")`
  - `isDismissed()`, true when the choice is null or blank

### `Approver`
- `default SkillAnswer askUser(SkillQuestion)` returns `SkillAnswer.dismissed()`. A lambda approver, or `DENY_ALL`, therefore never picks a choice.

### `SkillLoader`
- `Loaded.nodeIndex()` gives `Map<String inlinedId, NodeRef>`.
  - It covers every file node and the builder's own nodes `start`, `toolStep`, `toolApproval` and `stopped`.
  - For the builder's nodes, `kind` is the node's id.
- `record NodeRef(String skill, String node, String kind)` with `tracingName()`.
- `Loaded.skill(name)`, and `SkillLoader.folder()`, which returns the user folder.
- `SkillGraphBuilder.tracingName(skill, id)` is now the single place that produces `skill/<name>/<id>`. The builder's spans and `NodeRef` both use it.

### `ToolRegistry`
- `withSkills(List<SkillFile>)` returns a new registry view: the base `@Tool`s plus one spec per skill.
  - Each spec has name `toolName()`, the skill's description, and one string parameter per input, with the required inputs marked required.
  - Skill tools have no executor. `execute` refuses them with "Not run: … starts a skill …".
  - A skill tool with the same name as a `@Tool` replaces it. This is for Task 5's `startCodingJob`.
  - Every call starts again from the base registry, so reloading drops the old skill tools.
- `isSkillTool(name)` is the way to exclude skill tools:
  - `CodingGraph.notInJob` = `NOT_IN_JOB` or a skill tool. It is used for what each phase offers and in `whyNotCovered`.
  - `GenericKinds.listedTools` also leaves skill tools out.

### `AgentGraph`
- Generated outline: `skill-* = subgraphs skills()` and `agent -> route() {…, skill-*: skill-*}`.
- New constructor taking `(…, SkillLoader.Loaded skills, Map<String, NodeKind> kinds)`. The old constructor passes no skills and `GenericKinds.all()`.
- `@Subgraphs(prefix = SKILLS_PREFIX = "skill-", returnTo = "agent", router = "route") skills()` builds one subgraph per skill with `SkillGraphBuilder.build(new SkillRuntime(skill, model, toolCalls, tracing, kinds))`.
- `route`: `startCodingJob` is checked first. Then the first call in the reply whose name is a loaded skill's `toolName()` (looked up in a tool → skill map) gives `"skill-<name>"`.
- `compile()`:
  - `interruptBefore` = `approval`, `coding-planApproval`, plus `skillPauses(loaded)`, which is `skill-<name>-<id>` for each id in `SkillGraphBuilder.pauseNodes`.
  - `recursionLimit(loaded)` = max(1000, maxSteps × (2×maxToolRounds + 2) + 100) over the loaded skills.
- I added the skill route and pauses to the class Javadoc text diagram, because CLAUDE.md asks for it. `docs/agent-graph.md` and the README are not touched; they belong to Task 8.

### `Agent`
- New constructor `Agent(…, DesignGuides guides, SkillLoader skills)`. The older constructors pass `new SkillLoader(null, List.of(), new SkillValidator(GenericKinds.all(), tools))`.
- `fromEnv` loads `Settings.skillsFolder()` (`~/chat/skills`) with no built-ins.
- A private `record Compiled(graph, tools view, Loaded, nodeIndex, pause ids, skillWork labels)` is held in a `volatile` field.
  - `run` takes one `Compiled` at the start and uses it for the whole turn.
  - `reloadSkills()` re-runs the loader, builds and compiles a new `Compiled`, then swaps it in and returns the new `Loaded`. If building fails it throws `IllegalStateException` and the old `Compiled` stays in use.
  - `skillStatus()` returns the `Loaded` in use.
- Pause loop: `next` is looked up in the pause ids and in `nodeIndex`, never split as a string.
  - `toolApproval` calls the existing `askApproval(result.skillUnansweredToolCalls(), …)` and writes `APPROVALS`.
  - An askUser node calls `askSkillQuestion`:
    - The question is rendered with `Templates.render(question, GenericKinds.vars(state))`.
    - The choices are the kind's `outcomes(node)`.
    - The call runs inside a "human skill question" span.
    - The approver's result is written to `SKILL_ANSWER` as JSON `{"node","choice","comment"}`. When it is dismissed, null, or the approver throws, `""` is written. This happens on every resume.
- The `Pause` no-progress check now also compares the skill's open calls, the number of skill messages and `skillSteps`.
- `MessageFeed` gets `(live, nodeIndex)`:
  - Skill-run messages (AI replies and tool results, never prompts) are passed on with `codingJob = true`, like a coding job's.
  - Whether text is hidden is decided by `live.work(tracingName)`.
- `progressOf(node, state, nodeIndex)`:
  - Skill nodes report phase `"<skill> · <node>"` and the skill's last `runCommand` as the detail.
  - `toolStep` and `toolApproval` report `skillNode` (the node whose calls they ran). `start` and `stopped` report nothing.

### `LiveChannel`
- `skillWork(Map<tracingName, label>)` is set by `Agent` at the start of each turn and cleared at the end.
- `work(node)` = the static `hiddenWork` or the skill's label. `text()` uses it.
- Labels are `"<skill>: <node>"` for model nodes with `"thinking": "progress"`.

### Skills folder protection (`Settings`, `WorkFolder`, `FileTools`, `ToolCallRunner`)
- `Settings.skillsFolder()` = `globalFile().resolveSibling("skills")`.
- `WorkFolder.resolveInside(InvocationParameters, path)` refuses any path at or inside a denied folder. The denied folders are:
  - always `Settings.skillsFolder()`;
  - plus the `WorkFolder.DENIED_PARAMETER` list.

  Both are compared as real paths.
- `WorkFolder.requireNoDeniedInside(…)`: `deletePath` and `movePath` (source) also refuse a path that *contains* a denied folder, such as deleting the skill folder's parent.
- `ToolCallRunner` passes `DENIED_PARAMETER`. `Agent` sets it to the loader's folder, which is the temp folder in tests.
- All `FileTools` methods use the new overload.

## Tests: `src/test/java/net/rka/ai/agent/SkillJobTest.java` (12 tests)

Each test runs the whole `Agent` with a scripted model, a temporary skill folder inside the trusted folder, and an approver that records calls. The brief's 7 tests:
- `modelStartsASkillAndAnswersWithItsResult`: checks the tool spec (description, input `who` required), the rendered prompt, and that the turn has exactly call → result → answer.
- `guardedSkillCallPausesForApproval`: an untrusted `writeFile` goes to `review`; it is denied, nothing is written, and the model sees "Denied".
- `askUserGoesThroughTheApprover`: checks the exact `SkillQuestion` (rendered question, choices) and that the comment flows into the result.
- `skillToolsAreNotOfferedInsideSkillsOrCodingJobs`:
  - The main request offers the skill tools.
  - A skill node is offered only `readFile`.
  - No request of a whole coding job offers a `skill_*` tool.
  - A file listing `skill_greet` is invalid and appears in the errors.
- `fileToolsRefuseTheSkillsFolder`: a trusted `writeFile`, a trusted `deletePath` on the parent, and a `readFile` are all refused; the files are untouched.
- `reloadPicksUpANewFile`: adding a file then reloading makes the skill usable; deleting it then reloading removes the tool.
- `webContentInASkillMakesLaterCallsAsk`: after a `fetchPage` inside the skill, a later main-graph `writeFile` asks for approval even though the user trusts `tool:writeFile`.

5 more:
- `withoutSkillsNothingChanges`: zero skills. This is the case the Task 1 reviewer said had no test. The offered specs equal the base registry's and the turn is unchanged.
- `progressAndMessagesNameTheSkillNodes`
- `approvedSkillCallRuns`
- `dismissedQuestionCancelsTheSkill`
- `defaultApproverDismissesQuestions`

### TDD evidence
- **RED:** `.\gradlew.bat :test --tests net.rka.ai.agent.SkillJobTest` failed at `:compileTestJava`, as expected because none of the API existed yet:
  - `cannot find symbol` for `SkillAnswer` and `SkillQuestion`
  - `askUser(SkillQuestion) … does not override`
  - `no suitable constructor found for Agent(…, SkillLoader)`
  - `skillStatus` and `reloadSkills` missing
- **GREEN:** the same command gave BUILD SUCCESSFUL. `TEST-net.rka.ai.agent.SkillJobTest.xml` shows `tests="12" failures="0" errors="0"` with empty stdout and stderr.
- **Mutation check:** I disabled the deny check in `WorkFolder.resolveInside`. `fileToolsRefuseTheSkillsFolder` then FAILED (`12 tests completed, 1 failed`), and passed again once I restored it.
- **Full build:** `.\gradlew.bat build` gave BUILD SUCCESSFUL. All suites have 0 failures, including CodingGraphTest 35, CodingJobTest 13, SkillGraphTest 18, SkillLoaderTest 30, FailedTurnTest 3, FileToolsTest 6 and the graph-processor tests.

## Files changed
- New:
  - `src/main/java/net/rka/ai/agent/skills/SkillQuestion.java`
  - `SkillAnswer.java`
  - `src/test/java/net/rka/ai/agent/SkillJobTest.java`
- Modified:
  - `agent/Agent.java`, `agent/AgentGraph.java`, `agent/Approver.java`, `agent/LiveChannel.java`, `agent/ToolCallRunner.java`, `agent/CodingGraph.java` (only `notInJob`)
  - `agent/skills/SkillLoader.java`, `agent/skills/SkillGraphBuilder.java` (`tracingName`), `agent/skills/GenericKinds.java` (`listedTools` excludes skill tools)
  - `tools/ToolRegistry.java`, `tools/WorkFolder.java`, `tools/FileTools.java`
  - `config/Settings.java`

## Concerns / notes for the controller
1. **Docs are behind.** `webContentInTurn` (from Task 3) and the new skill routes and pauses are not yet in `docs/agent-graph.md` or the README. That is Task 8, as the brief says. Only the `AgentGraph` and `Agent` Javadoc diagrams were updated.
2. **`findFiles` still lists files inside the skill folder.** Reading, writing, copying, moving and deleting them is refused. I did not filter the listing.
3. **The deny error message mentions `buildSkill`**, which only arrives in Task 6.
4. **Name collisions:** a skill tool with the same name as a `@Tool` replaces that tool in the registry view. Only built-ins can name their tool, so this is meant for Task 5's `startCodingJob`. In this task `startCodingJob` still routes to `coding` first.
5. **Some files are large and getting larger:** `Agent.java` is now about 875 lines and `AgentGraph.java` about 860.
