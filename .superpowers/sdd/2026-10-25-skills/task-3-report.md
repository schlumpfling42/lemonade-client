# Task 3 report: generic node kinds and SkillGraphBuilder

**Status:** DONE_WITH_CONCERNS (the work is complete and green; the concerns are listed at the end)
No commits (this is not a git repo). `.\gradlew.bat build` → BUILD SUCCESSFUL.

## What I implemented

### `AgentGraph.State` (keys + getters, plus a few visibility changes)
- The keys, exactly as the brief names them: `SKILL_NAME`, `SKILL_CALL_ID`, `SKILL_MESSAGES` (`appenderWithDuplicate`, in `SCHEMA`), `SKILL_START`, `SKILL_VARS` (a JSON object string), `SKILL_NODE`, `SKILL_STEPS`, `SKILL_TOOL_ROUNDS`, `SKILL_ANSWER`, `SKILL_RESULT`, `SKILL_STOP_REASON`. All are `public`.
- Getters: `skillName/CallId/Start/Vars/Node/Steps/ToolRounds/Answer/Result/StopReason`, `skillMessages()` (the current run, from `skillStart`), `allSkillMessages()`, `skillUnansweredToolCalls()`.
- Web content: I extended `webContentInTurn` itself. It now also scans `allSkillMessages()`. So `needsApproval`, `isTrusted`, `codingWebContent` and `codingTrusted` all count skill transcripts: one rule, not a second helper.
- Made public, because the `skills` package needs them: `MESSAGES`, `APPROVALS`, `WORK_FOLDER`, `TRUSTED_COMMANDS`, `SCHEMA` and `needsApproval(ToolRegistry, call)`.
- `SKILL_ANSWER` format (Task 4 must write it this way): a JSON object string `{"choice": "...", "comment": "..."}`. Use `""` when the question was dismissed. Agent must write it on **every** resume from an askUser pause, the same pattern as `APPROVALS`. The askUser node does not clear it, because its router reads it.

### `ToolCallRunner` / `NodeTracing`
- Both classes are now `public`, along with `ToolCallRunner(Tracer, ToolRegistry)`, `tools()`, `NodeTracing(Tracer)` and `wrap(...)`.
- New `ToolCallRunner.runInSkill(State, calls)`. It works like `runUnanswered`, but puts the results in `SKILL_MESSAGES` and returns `FACTS`.

### `NodeKind`, `SkillRuntime`
- `NodeKind` gained `NodeActionWithConfig<State> action(NodeSpec, SkillRuntime)` and `String route(NodeSpec, State)`.
- `record SkillRuntime(SkillFile skill, ChatModel model, ToolCallRunner toolCalls, NodeTracing tracing, Map<String, NodeKind> kinds)`, as the brief specifies.

### `GenericKinds` (extended, as ruled)
- **model**
  - System prompt: description + `PREAMBLE`. User message: the rendered `prompt`.
  - Offers only the listed tools that exist and aren't `isForbidden`.
  - A reply with calls goes to `toolStep` and back. The node's conversation is the transcript from its prompt (the last UserMessage) on.
  - After `maxToolRounds`, one more call without tools. Any calls in that reply are dropped.
  - The text goes to `output`. `format: json` parses it (code fences allowed), with one retry that names the parse error; if the retry fails too, the skill stops.
  - A model exception gives the stop reason `model error: ...`.
- **askUser**
  - Reads `SKILL_ANSWER`. A missing or blank choice gives the stop reason `cancelled by the user`. An unknown choice also stops the skill (fail closed).
  - A valid answer sets `output` to `{choice, comment}`. The route returns the choice.
- **branch:** evaluates the cases as the Javadoc documents (`equals`, `notEquals`, `contains`, `matches` = regex find, `isEmpty` = blank, `atLeast`/`atMost`, `count` on a JSON list where `""` counts as 0, `and`/`or`). The first case that holds wins; otherwise the outcome is `otherwise`.
- **set:** template, `{increment: n}` (a non-number counts as 0) or `{append: template}` (a non-list value becomes the first entry). Every value is computed from the variables as they were before the node.
- **finish:** answers the start call in `MESSAGES` with a `ToolExecutionResultMessage`: id = `skillCallId`, name = `toolName()`, `isError` = the node's `error`. Also sets `SKILL_RESULT`.
- **Built-in node actions**
  - `start`: finds the `toolName()` call and answers the other calls with `Not run: a skill started; ask again afterwards.` It resets the `skill*` keys and sets the variables `input` (`""` for missing inputs), `goal` (the arguments as `name: value` lines), `folder`, and `environment` (only when a node reads it, via `EnvironmentInfo.fromSettings()`).
  - `toolStep`:
    - Refuses calls the node didn't list, with an error result.
    - If any remaining call `needsApproval`, it leaves them open; the router then goes to `toolApproval`.
    - Otherwise it runs them.
    - Each visit counts one tool round.
  - `toolApproval`: reads `APPROVALS`, the same as `ApprovalGraph`. Unapproved guarded calls get "Denied"; approved, safe and trusted calls run. Unlisted calls are refused again.
  - `stopped`: an error answer, `Stopped: <reason>.`. The reason is `too many steps (...)` when the step limit sent the skill here.
- `public static Map<String,Object> vars(State)`, which Task 4 can use to render the askUser question.

### `SkillGraphBuilder`
- `build(runtime)` uses `new StateGraph<>(State.SCHEMA, new LC4jStateSerializer<>(State::new))` and adds the nodes and edges by hand:
  - `START → start → <file start>`.
  - Each file node has conditional edges whose routes are the target node ids.
  - A finish goes to `END`. `toolStep`, `toolApproval` and `stopped` are added to every skill.
- Every node is wrapped as `NodeTracing.wrap("skill/<name>/<id>", ...)`.
- Every file node runs inside a `visit` wrapper:
  - A new visit sets `skillNode`, adds one to `skillSteps` and clears the previous stop reason.
  - A node that goes on after its tool step does not count again.
  - An exception becomes the stop reason. The call is always answered and the turn doesn't fail.
- The router checks, in this order:
  1. A stop reason: go to the node's `on.stopped` target, else the automatic `stopped`.
  2. Open skill calls: go to `toolStep`.
  3. `skillSteps >= maxSteps`: go to the automatic `stopped`.
  4. Otherwise: the kind's route, mapped through `next`/`on`.
- The step limit always ends at the automatic `stopped`, never at a file's `stopped` target. Otherwise an error handler that loops back could run forever.
- `pauseNodes(skill)` returns `toolApproval` plus every `askUser` node id.

## Tests

`src/test/java/net/rka/ai/agent/skills/SkillGraphTest.java` has 13 tests. It compiles `build()` directly with `MemorySaver` and `interruptBefore(pauseNodes)`, and uses a scripted fake ChatModel:
- The 9 the brief names: `modelBranchFinishRuns`, `toolsRunAndComeBack`, `unlistedToolIsRefused`, `forbiddenToolIsNeverOffered`, `askUserPausesAndResumes`, `dismissedAskUserFinishesWithError`, `maxStepsStopsALoop`, `modelErrorFinishesWithError`, `otherCallsInTheStartingReplyAreRefused`.
- 4 extras, because this task implements that code:
  - `guardedCallPausesAtToolApprovalAndRunsOnlyWhenApproved` (approve writes the file; deny writes nothing).
  - `trustedGuardedCallRunsWithoutPause`.
  - `webContentInTheSkillMakesTrustedCallsAsk` (a `fetchPage` in the skill makes a trusted `writeFile` pause).
  - `jsonOutputIsParsedWithOneRetry`.

`SkillLoaderTest`'s anonymous `NodeKind` now delegates the two new methods. Without that it no longer compiles.

### TDD evidence
- **RED:** `.\gradlew.bat :test --tests net.rka.ai.agent.skills.SkillGraphTest` gave `27 errors` (`cannot find symbol` for `State.SKILL_ANSWER`, `skillMessages()`, `skillSteps()`, `GenericKinds.vars`, `SkillGraphBuilder`, `SkillRuntime`, ...). This was expected: none of the API existed yet.
- First green run: 11/12. `unlistedToolIsRefused` failed with `IllegalStateException: Missing Checkpoint!`. That came from my test calling `getState` after a run that never paused. I removed that assertion: the call being answered within the same run already shows there was no pause.
- **GREEN:** the same command gave `BUILD SUCCESSFUL`, 13 tests, 0 failures, empty stdout/stderr.
- `.\gradlew.bat build` gave `BUILD SUCCESSFUL`. Every suite passes (CodingGraphTest 35, CodingJobTest 13, SkillLoaderTest 30, ...) with no compiler warnings.

## Files changed
- New: `src/main/java/net/rka/ai/agent/skills/SkillGraphBuilder.java`, `SkillRuntime.java`, `src/test/java/net/rka/ai/agent/skills/SkillGraphTest.java`
- Modified: `skills/GenericKinds.java`, `skills/NodeKind.java`, `agent/AgentGraph.java` (State only), `agent/ToolCallRunner.java`, `agent/NodeTracing.java`, `test/.../skills/SkillLoaderTest.java`

## Concerns / decisions for the controller
1. **GenericKinds.java is now about 1050 lines** (it was 419). The plan puts all the kinds plus toolStep/toolApproval in that file, so I didn't split it. A later split could be `GenericKinds` (specs + kinds) and a `BuiltInNodes` class (start/toolStep/toolApproval/stopped).
2. **`{{environment}}`:** `SkillRuntime` has no `EnvironmentInfo`, because the record is fixed by the brief. So `start` calls `EnvironmentInfo.fromSettings().summary()`, and only when some node reads `environment`. Task 5's `coding.environment` kind may want an injectable `EnvironmentInfo` (for tests). That would be a new `SkillRuntime` component.
3. **`pauseNodes(SkillFile)` takes no kinds map** (the brief's signature), so it recognises `askUser` by kind name. Task 5 must add `coding.planApproval` there too, or change the signature.
4. **Visibility:** `ToolCallRunner`, `NodeTracing`, several State keys and `State.needsApproval` are now public, because the skills live in another package. The brief only said "State keys + getters".
5. **`webContentInTurn` now covers skill transcripts.** That changes which calls count as trusted. CLAUDE.md asks for the graph docs to be updated in the same change, but the implementer rules forbid touching `docs/agent-graph.md`/README here. This is left for Task 4/8. The graph topology itself doesn't change until Task 4.
6. The refusal text has a trailing period (`... ask again afterwards.`), matching the coding job's message. The brief quotes it without one.
7. `branch` `matches` uses regex *find* (anywhere in the value), not a full match. This is documented in the GenericKinds Javadoc.

---

## Fix round 1

### Changes
1. **A stop reason always starts a new visit** (`GenericKinds.continuesVisit`).
   - A node that comes back with a non-blank `skillStopReason` (its `stopped` route, including a self-wired `on.stopped`) now counts a step and clears the reason. Before, it could pass as "going on after its tool step" and loop past `maxSteps`.
   - The automatic `stopped` finish now says `too many steps (...)` whenever the limit is reached, adding `; the last node stopped with <reason>` when a reason is set. Before, it showed only the error.
2. **Terminal nodes no longer go straight to END** (`SkillGraphBuilder`).
   - Their conditional edge goes to `END` only once the call is answered (a result with `skillCallId` is in `messages`); otherwise it goes to the automatic `stopped`, which answers it.
   - It checks whether the call was answered, not the stop reason, so a node that answered and then set a reason isn't answered twice. Every run answers its call exactly once.
3. **askUser answers are tied to the node and used once.**
   - `SKILL_ANSWER` is now `{"node": <id inside the skill>, "choice", "comment"}`. **Task 4's Agent must write `node`.**
   - An answer for another node, or one already used, counts as dismissed (`cancelled by the user`).
   - Using an answer marks it `"used": true` instead of blanking it, because the router after the node still reads the choice; once marked, it can't satisfy any later question.
   - The `State.SKILL_ANSWER` Javadoc documents this.
4. **New test for a mixed reply through `toolApproval`.**

### Tests added (`SkillGraphTest`, now 18)
- `selfWiredStoppedLoopEndsAtMaxSteps`: the scripted model fails on every request that follows a tool result. The run must end in the "too many steps" error finish after 3 steps and 6 model requests.
- `terminalKindThatFailsIsAnsweredByStopped`: a test-only terminal kind that throws. The call must get exactly one error answer containing "kaboom".
- `answerForAnotherNodeCountsAsDismissed`, `usedAnswerCountsAsDismissed`. The second resumes without writing a new answer; the old `revise` must not answer the second question.
- `mixedReplyGoesThroughToolApprovalTogether`. One reply has a safe `readFile`, two guarded `writeFile`s and an unlisted `deletePath`.
  - At the pause: `deletePath` is already refused, and `readFile` and both writes are still waiting.
  - After resuming with w1 approved and w2 denied: `readFile` ran, w1 wrote its file, w2 was denied and wrote nothing, and `a.txt` still exists.
- The existing askUser tests now pass `"node":"check"`.

### Evidence
- **RED:** I temporarily reverted fixes 1 and 2 and ran `.\gradlew.bat :test --tests net.rka.ai.agent.skills.SkillGraphTest`: `18 tests completed, 2 failed`.
  - `selfWiredStoppedLoopEndsAtMaxSteps` failed with `CompletionException` caused by `IllegalStateException at CompiledGraph.java:865`, LangGraph4j's recursion limit (the loop).
  - `terminalKindThatFailsIsAnsweredByStopped` failed with `AssertionFailedError` (the call was never answered).
  - My first version of the loop test passed even without the fix, because its fixed script happened to break the loop. I changed it to a rule-based model so it reproduces the reviewer's case. I then restored both fixes.
- **GREEN:** `.\gradlew.bat :test --tests net.rka.ai.agent.skills.SkillGraphTest` gave BUILD SUCCESSFUL, `tests="18"`, 0 failures, empty stdout/stderr.
- `.\gradlew.bat build` gave BUILD SUCCESSFUL, every suite with 0 failures and 0 errors, and no compiler warnings.

### Remaining concern
If a node fails on exactly the last allowed step, the stopped message reads "too many steps ...; the last node stopped with <error>". Both parts are true, and the error is still visible.
