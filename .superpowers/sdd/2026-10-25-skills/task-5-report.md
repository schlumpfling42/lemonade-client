# Task 5 report: the coding job as the built-in skill `coding`

Status: DONE_WITH_CONCERNS (concerns at the end). No commits: this isn't a git repo. Full `.\gradlew.bat build` passes.

## What I implemented

- **`src/main/resources/net/rka/ai/agent/skills/coding.json`** (new). It has nodes `environment`, `investigate`,
  `investigateTools`, `plan`, `review`, `planApproval`, `implement`, `implementTools`, `verify`, `designReview` and
  `report`, wired with today's routes, including `stopped → report` on every node except planApproval.
  - Other fields: `"tool": "startCodingJob"`, the input `goal` (required) and `start: environment`.
  - Limits: `maxSteps` 200 (the cap), `maxToolRounds` 8, and in `limits.extra` `maxInvestigateRounds` 6,
    `maxStepTurns` 15, `maxVerifyAttempts` 3, `maxDesignRounds` 2, `maxPlanAttempts` 2.
  - The prompts are node `prompt` fields. A scratch program generated them from the `CodingPrompts` constants.
    Before deleting `CodingPrompts`, a temporary test checked that every prompt and the description equal the old
    constants and the `@Tool` text. It passed; then I removed the test.
- **`skills/CodingKinds.java`** (new). It holds the 11 kinds with the spec's outcomes (`coding.environment` has
  the single outcome `done`, wired with `next`). `prompt` is required for investigate, plan, review, implement and
  designReview. No kind accepts any other field, and none reads or writes skill variables. Each kind's action builds
  a `CodingGraph` from the `SkillRuntime` (model, tools, limits) plus an app-wide `CodingGraph.Setup` (environment,
  timeout, plans folder, guides). `CodingKinds.all(setup)` returns the kinds.
- **`CodingGraph`**. The annotations and `@NodeWrapper` are gone, and the class is `public final`. The node methods
  are public, and the methods that call the model take the node's prompt as `instructions`. The routers are
  `public static after...(State[, Limits])`.
  - New `Setup` and `Limits` records. `Limits.of(SkillFile.Limits)` falls back to the old constants when a limit
    is absent.
  - The report answers with the skill's tool name. Its stop reason is `codingStopReason`, or else
    `skillStopReason`, so an exception in a step still produces a report.
  - The behavior is otherwise unchanged.
- **Start-node design** (the controller asked me to decide this):
  - The generic `start` node is the only place that looks for the call. It records `skillCallId`, resets the
    `skill*` keys and answers the reply's other calls.
  - `coding.environment` finds the call by `skillCallId`, sets `codingCallId` to it and resets the `coding*` keys.
  - To keep the old refusal text, `NodeKind` got the default `otherCallsAnswer()`. `coding.environment` returns
    "Not run: a coding job started; ask again afterwards.", and the start node uses the first node's answer.
  - Result: the call is found once (by start) and answered once (by report). The builder sends report to END
    because the call is answered.
- **`NodeKind`**:
  - `route(node, runtime, state)` now takes the runtime, because the verify and designReview routers need the
    skill's limits. I updated the 5 generic kinds and the 2 anonymous test kinds.
  - New default `pausesBefore()` (askUser and coding.planApproval return true).
  - New default `otherCallsAnswer()`.
- **`SkillGraphBuilder.pauseNodes(skill, kinds)`** now asks each kind (`pausesBefore`). `AgentGraph.skillPauses`
  takes the kinds too.
- **`SkillValidator.checkPlanApproval`**. In any skill with `coding.implementTools` or `coding.verify`, a walk
  from `start` over every wired edge (stopped included) except a `coding.planApproval` node's `approve` must not
  reach those nodes. A user override that wires around the approval is invalid, and the loader falls back to the
  built-in. Overrides are still validated as user files with only the tool pinned.
- **`AgentGraph`**:
  - Removed `@Subgraph("coding")`, the `coding` edge and route, and the environment, timeout, plans and guides
    fields and constructors.
  - `startCodingJob` (still first priority) routes to `skill-` plus the name of the skill whose tool it is
    (`skill-coding`).
  - `PLAN_APPROVAL = "skill-coding-planApproval"`. The interrupts come from the skill pauses only.
  - The class Javadoc diagram is updated.
- **`Agent`**:
  - The pause loop recognizes a plan approval by kind (`CodingKinds.PLAN_APPROVAL.equals(nodeRef.kind())`) and
    calls the unchanged `askPlanApproval` (`Approver.reviewPlan`, `codingDecision` and `codingComment`).
  - `progressOf` gives coding kinds the old phases, using the skill's limits for "attempt n/3" and "round n/2".
  - `MessageFeed` sends the plan file after `coding.review` or `coding.report` (by kind) and resolves names
    through `nodeIndex`.
  - New static `kinds(setup)`, `codingSetup(...)` and `BUILT_IN_SKILLS = ["coding"]`.
  - The convenience constructors load the built-in coding skill, so every Agent has coding jobs as before.
  - The loader constructor is now `Agent(baseUrl, modelName, model, tracing, tools, approver, SkillLoader, LiveChannel)`.
  - `fromEnv` loads `coding` as a built-in.
- **`LiveChannel.hiddenWork`** now matches `skill/coding/plan`, `skill/coding/review` and `skill/coding/designReview`.
- **Deleted `CodingPrompts.java`.** I also updated the CodingTools and AgentGraph comments.

## Tests

Command: `.\gradlew.bat :test --tests "net.rka.ai.agent.*"` → BUILD SUCCESSFUL. Then `.\gradlew.bat build` →
BUILD SUCCESSFUL. Per suite:

| Suite | Tests | Before |
|---|---|---|
| CodingGraphTest | 38 | 35 + 3 new |
| CodingJobTest | 14 | 13 + 1 new |
| SkillJobTest | 13 | 12 + 1 new |
| SkillGraphTest | 18 | |
| SkillLoaderTest | 30 | |
| LiveChannelTest | 3 | |

0 failures and 0 errors in every suite. The output is clean apart from the existing "Turn failed" log line of
`repeatedPauseWithoutProgressStops`.

- **Migration** (only ids, prompt references and setup changed):
  - CodingGraphTest loads the skill through `SkillLoader` with `Agent.kinds(setup)` and builds it with
    `SkillGraphBuilder`. It compiles it inside a parent graph as node `skill-coding`, so the pause ids are real
    (`AgentGraph.PLAN_APPROVAL`).
  - `CodingPrompts.X` became `prompt("<node>")`, read from the loaded skill.
  - `withGuides` reloads with a `Setup` that has guides.
  - `aDesignFixGetsItsOwnVerifyAttempts` calls the `coding.verify` kind's `route` with the skill's runtime.
  - CodingJobTest reads `codingPrompt("plan")` from `agent.skillStatus()`.
  - LiveChannelTest uses the `skill/coding/...` names.
  - In SkillJobTest, `newAgent()` has no built-ins (as before), and `newAgent(builtIns)` uses the new Agent
    constructor.
  - `skillToolsAreNotOfferedInsideSkillsOrCodingJobs` now needs the built-in coding skill to run a coding job.
    Its expected skill list became `["coding", "greet", "reader"]`.
  - I removed no coverage.
- **New tests**:
  - `codingWithoutPlanApprovalIsInvalid`: the built-in file is valid. review→implement is flagged for
    implementTools and verify. A side path (`investigate.on.stopped → verify`) is also flagged.
  - `userCodingOverrideReplacesBuiltIn`: a user coding.json without `tool` keeps `startCodingJob`. Its prompt and
    `maxVerifyAttempts` 1 take effect in a run.
  - `invalidCodingOverrideFallsBackToBuiltIn`: the error is listed under coding.json, the built-in prompt is used,
    and the run still pauses at `skill-coding-planApproval`.
  - `SkillJobTest.dashedSkillNameResolvesEachPauseWithItsAnswer`: the skill `release-notes` has a guarded
    writeFile, which pauses at toolApproval and is approved and run. Then an askUser `check` pauses with the right
    `SkillQuestion`, and its answer reaches the finish.
  - `CodingJobTest.theCodingSkillOffersStartCodingJobAsBefore`: the specification `withSkills` builds from
    coding.json equals the old `@Tool` specification.

## TDD evidence

- **RED**: I did not run the migrated tests before implementing. I wrote the main code first, and that order is a
  deviation from the brief.
  - What I have instead: I disabled the `checkPlanApproval(...)` call, ran
    `.\gradlew.bat :test --tests "net.rka.ai.agent.CodingGraphTest"`, and got
    `38 tests completed, 2 failed`: `codingWithoutPlanApprovalIsInvalid` and
    `invalidCodingOverrideFallsBackToBuiltIn`, both `AssertionFailedError`. That failure was expected: without the
    rule, the bypass isn't reported in those terms, and the side-path case isn't reported at all. I then restored
    the call.
- **GREEN**: the commands above, all passing.

## Files

- New: `src/main/resources/net/rka/ai/agent/skills/coding.json`, `src/main/java/net/rka/ai/agent/skills/CodingKinds.java`
- Changed:
  - Main code: `CodingGraph.java`, `AgentGraph.java`, `Agent.java`, `LiveChannel.java`, `skills/NodeKind.java`,
    `skills/GenericKinds.java`, `skills/SkillGraphBuilder.java`, `skills/SkillValidator.java`,
    `tools/CodingTools.java` (comment only)
  - Tests: `CodingGraphTest`, `CodingJobTest`, `SkillJobTest`, `LiveChannelTest`, `skills/SkillGraphTest`,
    `skills/SkillLoaderTest` (signature changes only)
- Deleted: `src/main/java/net/rka/ai/agent/CodingPrompts.java`

## Concerns / notes

1. **New cap on job length.** `maxSteps` 200 now caps one coding job at 200 node visits. Before, only the per-step
   limits applied, plus LangGraph4j's 1000 node steps per run. Each implement and implementTools visit counts, so a
   plan with many steps of about 15 tool turns could hit the cap. That would be roughly 7 or more steps at the
   maximum turns. When it does, the generic `stopped` answers the call with "Stopped: too many steps". No
   `codingReport` is written and the plan file isn't finalized. 200 is the validator's cap, so a larger value
   would need the cap raised for built-ins.
2. **`NodeKind.route` signature change.** It now takes the runtime, plus two new default methods. This is an
   interface change beyond the brief. It was needed for the limit-dependent routers and to keep the exact "a coding
   job started" text.
3. **Agent constructor change.** The loader-taking constructor dropped its environment, plansDir and guides
   parameters, because the coding kinds now carry those through `CodingGraph.Setup`.
4. **`LiveChannel.hiddenWork` uses fixed names.** These are `skill/coding/plan` and the other two, as the brief
   says. A user override that renames those nodes would show their JSON as streamed text (display only).
   Progress and plan-file events go by kind, so renaming doesn't affect them.
5. **Docs not updated.** CLAUDE.md asks for `docs/agent-graph.md` and the README diagram to change with the graph.
   Per the brief, docs are Task 8, so I didn't touch them. The `AgentGraph` Javadoc diagram is updated.
   `AgentGraphDefinition`'s outline now lists no `coding` node or route, only `skill-*`.

---

## Fix round 1

All 7 review items are fixed.

1. **maxSteps for coding skills.**
   - `SkillValidator` caps `maxSteps` at `MAX_CODING_STEPS` = 1000 for any skill with a `coding.*` node.
     Generic skills keep the cap of 200.
   - `coding.json` now sets `maxSteps` 600. The recursion limit follows it: 600 × 18 + 100 = 10900, a backstop.
   - Test `codingSkillsMayTakeMoreSteps`: 201 is accepted for the coding skill, 1001 is rejected, and 201 is
     rejected for a generic skill.
2. **Plan approval bypass.**
   - a. Run time: `implementTools` and `verify` call `notApproved(state)` first. If there's no `codingApprovedPlan`,
     or `codingPlan` differs from it, nothing runs. implementTools answers the open calls with "Not run: ...".
     Both set `codingStopReason` ("the current plan is not the one the user approved; a changed plan must be
     approved before it runs."), and the job reports.
   - b. Validator: walking from `start` and from every `coding.plan` and `coding.review` node, skipping
     `coding.planApproval`'s `approve` edges, must not reach `coding.implement`, `coding.implementTools` or
     `coding.verify`. `implement` is now also guarded. The error names the source node.
   - Tests use exactly the reported wiring (`implementTools.revise → plan2 → review2 → implement`):
     `aRevisedPlanAroundTheApprovalIsInvalid` checks the validator ("node `implement` ... from `plan2`").
     `aRevisedPlanThatWasNotApprovedNeverRuns` builds the file without validation and runs it: the call under the
     unapproved draft is refused, the file isn't written, and the report starts "Stopped: the current plan is not
     the one the user approved".
   - RED check: with `notApproved` short-circuited to `return null`, `aRevisedPlanThatWasNotApprovedNeverRuns`
     failed (46 tests, 1 failed). I then restored it.
3. **Call id.**
   - `report` answers `state.skillCallId()`, which the generic start node sets for every run. `codingCallId` is
     still set by environment as a copy, for display and tests.
   - The validator also requires that every path from `start` to a coding node other than environment passes
     through `coding.environment`.
   - Tests: `codingNodesNeedTheEnvironmentFirst` (an override that starts at investigate without environment is
     rejected). `theReportAnswersTheSkillsCall` (the report kind answers `skillCallId` "job2", not a stale
     `codingCallId` "job1").
4. **Exception in a step.** Test `anExceptionInAStepEndsTheJobWithAReport` builds a file (unvalidated) that
   approves before any plan exists. implement throws. The run doesn't fail: it ends through report with
   "Stopped: error in implement: implement without an approved plan." The call is answered exactly once, as an
   error.
5. **Limit names.**
   - New `NodeKind.limitNames()` (default empty). Each coding kind declares the limit it reads: investigate
     `maxInvestigateRounds`, review `maxPlanAttempts`, implement `maxStepTurns`, verify `maxVerifyAttempts`,
     designReview `maxDesignRounds`.
   - The validator rejects any `limits` key that isn't `maxSteps`, `maxToolRounds` or a name declared by the
     skill's kinds, and lists the valid names.
   - Test `unknownLimitsAreErrors`: `maxStepTurn` in coding.json is rejected, and so is `maxStepTurns` in a
     generic skill.
6. **Hidden output by kind.**
   - `LiveChannel.hiddenWork` now only covers `memory`. The new `LiveChannel.hiddenWorkOfKind(kind)` maps
     coding.plan, coding.review and coding.designReview to their labels.
   - `Agent.skillWork(Loaded)` (static, extracted from compile) puts every node of those kinds into the skill-work
     map by its tracing name, found through `nodeIndex()`, alongside the "thinking: progress" nodes.
   - Test `planOutputIsHiddenByKindWhateverTheNodeIsCalled`: an override renames `plan` to `draft`, and
     `skill/coding/draft` is still hidden. LiveChannelTest sets the skill-work map the way Agent does.
7. **Test output.** CodingJobTest has a `withoutAgentLog` helper that turns off Agent's JUL logger.
   `repeatedPauseWithoutProgressStops` uses it ("Turn failed"), and so does `failingProgressListenerDoesNotBreakTheRun`
   (expected "Progress listener failed" warnings). CodingJobTest's stderr is now empty.
   - FailedTurnTest still logs to stderr: langgraph4j's ERROR line and Agent's "Turn failed". It isn't part of
     this task, so I left it alone.

**Files changed in this round:**
- Main code: `skills/SkillValidator.java`, `skills/NodeKind.java`, `skills/CodingKinds.java`, `CodingGraph.java`,
  `Agent.java`, `AgentGraph.java` (doc only), `LiveChannel.java`, `resources/.../skills/coding.json`
- Tests: `CodingGraphTest` (8 new tests and a `compile(SkillFile)` helper), `CodingJobTest`, `LiveChannelTest`

**Commands and results:**
- `.\gradlew.bat :test --tests "net.rka.ai.agent.*"` → BUILD SUCCESSFUL.
- `.\gradlew.bat build` → BUILD SUCCESSFUL.
- Per suite: CodingGraphTest 46, CodingJobTest 14, SkillJobTest 13, SkillGraphTest 18, SkillLoaderTest 30,
  LiveChannelTest 3. All have 0 failures and 0 errors, and empty stderr apart from FailedTurnTest.
