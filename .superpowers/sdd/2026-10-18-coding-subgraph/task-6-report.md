# Task 6 report: wire `coding` into AgentGraph and Agent

**Status:** DONE_WITH_CONCERNS (one deviation from the brief, explained under Concerns 1)
**Commits:** none (not a git repo). `.\gradlew.bat build --rerun-tasks` → BUILD SUCCESSFUL.

## What I implemented

- **`CodingTools.startCodingJob(goal)`** (new, `net.rka.ai.tools`): `@Tool` description says to use it for
  multi-step coding work (new projects, adding frameworks or libraries, larger refactorings, design work)
  and to use the normal file/command tools for small edits. `@P` text is verbatim from the brief. The body
  throws `IllegalStateException("handled by the coding subgraph")`. It is registered in
  `ToolRegistry.defaultTools()`.
- **`PlanDecision`** (new): `record PlanDecision(Kind kind, String comment)`, `enum Kind { APPROVE, REVISE, DENY }`.
  Null comment becomes "". `PlanDecision.DENIED` constant. Package-private `stateValue()` maps the kind to
  `CodingGraph.APPROVE/REVISE/DENY`.
- **`Approver.reviewPlan(CodingPlan)`**: a `default` method that returns `PlanDecision.DENIED`. Approver
  is still a functional interface.
- **`AgentGraph`**:
  - `@Edge(from = "coding", to = "agent")` and `@Subgraph(CODING) coding()`, which builds
    `CodingGraphDefinition.stateGraph(new CodingGraph(model, toolCalls, tracing, environment, codingTimeoutSeconds))`.
  - `route`: a `START_TOOL` call among the unanswered calls → `"coding"`. This is checked first, after
    "done". There is a new `@Route(on = "coding", to = CODING)`.
  - `compile()`: `.interruptBefore(APPROVAL, PLAN_APPROVAL)` and `.recursionLimit(RECURSION_LIMIT)` with
    the value 1000 (controller ruling).
  - Constants: `CODING`, `PLAN_APPROVAL = "coding-planApproval"`, `APPROVAL_PAUSE = "approval-review"`
    (see Concerns 1), `RECURSION_LIMIT`.
  - New constructor that takes `EnvironmentInfo` and the coding timeout. The existing 3-arg and 5-arg
    constructors (used by SummaryMemoryTest) delegate with `EnvironmentInfo.fromSettings()` and 600.
    `fromSettings()` only builds a path; it probes only when a job actually runs.
- **`Agent`**:
  - The constructor is split so tests can run a whole turn. A package-private constructor takes
    `(baseUrl, modelName, ChatModel, Tracing, ToolRegistry, Approver, EnvironmentInfo)`. `fromEnv`
    builds the OpenAI/Lemonade model with the tracing listener, `ToolRegistry.defaultTools()` and
    `EnvironmentInfo.fromSettings()`.
  - `CODING_COMMAND_TIMEOUT_SECONDS` (default "600") is read with `Env.get` in the constructor, next to
    `MEMORY_SUMMARY_TOKENS` / `MEMORY_RECENT_TOKENS`.
  - Pause loop. After each invoke or resume it reads `graph.lastStateOf(thread)`:
    - the next node is `APPROVAL_PAUSE` → the existing tool approval (`askApproval`);
    - it is `PLAN_APPROVAL` → `askPlanApproval`, which calls `approver.reviewPlan(state.codingPlan())` in a
      "human plan approval" trace span; a null answer or an exception counts as DENY;
    - in both cases `updateState(thread, {...})` then resume;
    - anything else, or no snapshot (releaseThread drops a finished run's checkpoints), means done.
    - Still `invoke`, per ruling.
  - "No progress" safety check. It compares a `Pause(node, openCalls, allCodingMessages().size())` with
    the previous one. The old check (same open calls twice) would wrongly fire on every plan revision,
    because `startCodingJob` stays open across revisions. Each revision appends plan and review messages
    to `codingMessages`, so a revision counts as progress. A repeated tool-approval pause with the same
    open calls still throws, as before.
- **`Tracing.off()`** (new public factory): the existing "off" tracing without the stderr line, used by
  the test.
- **`ApprovalGraph`**: new `static final String REVIEW = "review"`, used in its `@Edge`/`@Node`, so that
  `APPROVAL_PAUSE` is derived from it. The generated outline is unchanged.
- Agent class and `run` Javadoc updated for the second pause. docs/agent-graph.md, README and the
  AgentGraph class Javadoc are not touched (deferred to Task 9).

## Tests: `src/test/java/net/rka/ai/agent/CodingJobTest.java`

The tests run a real `Agent` with a scripted fake `ChatModel` (same style as CodingGraphTest),
`ToolRegistry.of(FileTools, TerminalTools, CodingTools)`, `Tracing.off()`, a fake-probe `EnvironmentInfo`
with a temporary cache, and a `PlanApprover` (a queue of `PlanDecision`s that records the plans it saw).
- `agentStartsAJobAndAnswersWithTheReport`: on APPROVE, the turn produces exactly [call, report result
  (Done, not an error), final answer]; the approver saw the plan; the last model request ends with the report.
- `planApprovalPausesAndUsesTheDecision`: REVISE("use the Kotlin DSL") then APPROVE → `reviewPlan` is
  called twice; the second plan request (requests[4], PLAN phase) contains the comment.
- `deniedPlanAnswersTheToolCallWithAnError`: on DENY, the result has isError, contains "denied", the agent
  still answers, no model call happens for the job after the deny, and the folder is empty.
- Extra: `startCodingJobIsADefaultTool`.
- Extra: `toolApprovalStillPausesAndRuns`, a regression test for the existing approval pause through
  Agent (it caught the bug in Concerns 1).
- Extra: `repeatedPauseWithoutProgressStops`. It checks that the "no progress" check still fires (ruling:
  verify the check). The model repeats the same call id after a deny. The text differs because the
  MESSAGES appender drops an identical message.

### TDD evidence
- RED: `.\gradlew.bat :test --tests net.rka.ai.agent.CodingJobTest` → `compileTestJava FAILED`
  (`cannot find symbol` CodingTools / PlanDecision / Tracing.off, `reviewPlan ... does not override`).
  This was expected: none of the types existed.
- First GREEN run: 4/4. I then added the two Agent-loop tests. `repeatedPauseWithoutProgressStops` failed:
  the run ended after one model request with the call still open. Debugging showed the paused state's
  next node is `approval-review`, not `approval` (Concerns 1). After the fix:
  `.\gradlew.bat :test --tests net.rka.ai.agent.CodingJobTest` → BUILD SUCCESSFUL, `tests="6" failures="0"
  errors="0"`, system-out and system-err empty.
- Full build: `.\gradlew.bat build --rerun-tasks` → BUILD SUCCESSFUL. 87 tests across 14 suites, all
  passing (CodingGraphTest 23, CodingJobTest 6, ...).
- Generated `AgentGraphDefinition` outline contains `coding -> agent`,
  `agent -> route() {coding: coding, approval: approval, trusted: trusted, tools: tools, done: END}` and
  `coding = subgraph coding()`.

## Files changed
- New: `src/main/java/net/rka/ai/tools/CodingTools.java`, `src/main/java/net/rka/ai/agent/PlanDecision.java`,
  `src/test/java/net/rka/ai/agent/CodingJobTest.java`
- Modified: `src/main/java/net/rka/ai/agent/AgentGraph.java`, `Agent.java`, `Approver.java`, `ApprovalGraph.java`
  (constant only), `src/main/java/net/rka/ai/tools/ToolRegistry.java`, `src/main/java/net/rka/ai/tracing/Tracing.java`
  (`off()`)

## Concerns
1. **Deviation from the brief: the approval pause's node id.** The brief says to branch on
   `nextNodeId() == "approval"`. In practice LangGraph4j inlines the subgraph, and the paused checkpoint's
   next node is `approval-review`. `interruptBefore("approval")` itself still works. Branching on
   "approval" made Agent treat the approval pause as "done" and return with the call still open. I
   added `AgentGraph.APPROVAL_PAUSE = APPROVAL + "-" + ApprovalGraph.REVIEW`, and
   `toolApprovalStillPausesAndRuns` guards it. Task 7 (stream) and Task 9 docs should use the same id.
2. Until the CLI (`ConsoleApprover`) and the window (`this::approve` method reference) override
   `reviewPlan`, every coding job is denied by the default. This is safe, but jobs can't be approved yet
   in the real app.
3. With the recursion limit at 1000 (ruling), a model stuck in an ordinary `agent ↔ tools` loop now runs
   up to about 500 model calls per run before LangGraph4j stops it, instead of about 12. The AgentGraph
   Javadoc "step limit" note (Task 9 docs) may want to mention this.
4. Minor: `lastStateOf` on a released thread goes through `MemorySaver.loadCheckpoints`, which uses
   `computeIfAbsent`. That leaves one empty list per finished turn in the in-memory saver. It's
   negligible, but it is a small growth over a long-running window session.
5. The startCodingJob `@Tool` description wording is mine. The brief gave its intent, not exact text.
