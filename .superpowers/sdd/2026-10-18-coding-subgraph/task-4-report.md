# Task 4 report: CodingGraph part 2, implement with plan enforcement

Status: DONE_WITH_CONCERNS. There are no commits because this folder is not a git repo. The full `.\gradlew.bat build` passes.

## What I implemented (src/main/java/net/rka/ai/agent/CodingGraph.java)

### `implement` node
- System prompt: `CodingPrompts.IMPLEMENT`, then the context (goal, folder, environment), the findings, `plan.toMarkdown(codingStep)` and "Your task now: ...".
  - The task is either "Step n of m: <description>" or, when all steps are done, the fix task with `codingVerifyOutput`.
  - After the system prompt comes the job's `codingMessages`.
- On a step's first turn (`codingStepTurns == 0`), the node appends the task as a `UserMessage` to the transcript.
  - Without it, the model request would end with the review's AiMessage right after approval.
  - It also marks in the transcript where each step began.
- Tools offered: everything except `NOT_IN_JOB` that is either `runCommand` or not `@RequiresApproval`. That means the read-only tools, `runCommand` and the five FileTools.
- After a tool-call reply, `CODING_STEP_TURNS + 1`. After a text reply, `CODING_STEP = min(step+1, steps)` and `CODING_STEP_TURNS = 0`. While fixing, the step stays at the end of the plan.
- Every implement turn clears `CODING_REVISION_REASON` and `CODING_COMMENT` (controller ruling). implement only runs under an approved plan, so both values are used up by then.
  - A Revise after a graph-triggered revision still sees the reason, which is still relevant because no plan has been approved since.

### `afterImplement` router
- Open calls: if `codingStepTurns > MAX_STEP_TURNS` (15) → `unfinished` (report), otherwise `tools`.
- No open calls: steps left → `next` (implement), otherwise → `verify`.

### `implementTools` node: the plan check, all or nothing
- `whyNotCovered(call)` returns null when the call may run, otherwise "<tool> <args>: why".
  - `NOT_IN_JOB` tools never run. This is checked first, so a read-only `sshHosts` or a trusted-text `runRemoteCommand` is refused.
  - `plan.covers(call, folder, TrustedCommands.of(List.of()), tools)`: the plan alone, so web content doesn't change it.
  - Otherwise `state.codingTrusted(tools, call)`, which already applies the web-content rule.
  - When a call is refused only because of web content, the reason says so.
- When every call is covered, they all run through `toolCalls.run(calls, folder, facts, timeoutSeconds)`, using the constructor's `CODING_COMMAND_TIMEOUT_SECONDS`.
- When any call is not covered, none runs. Each call gets an error result "Not in the approved plan; a revised plan will be proposed". The node sets `CODING_REVISION_REASON` (one line per refused call) and resets `CODING_STEP_TURNS = 0` so the step starts over under the new plan.

### `afterImplementTools` router
- This replaces the fixed edge `implementTools -> implement`.
- A reason is set → `revise` (plan), otherwise → `implement`.
- `plan` and `review` already include `toMarkdown`'s done-steps note and the revision reason through `revisionNotes`. `CODING_STEP` is kept.

### Other changes
- `report` has a new branch: "Step n did not finish within 15 model turns...".
- `report`'s approved branch now reads "The plan was implemented; verifying it is not available yet." Task 5 completes `report`.
- `verify` is still a stub.
- New constant `MAX_STEP_TURNS = 15`.
- The class Javadoc diagram and "How it works" are updated. agent-graph.md and the README are not touched (Task 9).

## Tests (CodingGraphTest)
- The old `approveEndsInReportUntilImplementExists` test is removed, because approve now implements.
- New tests:
  - `plannedCallsRunWithoutApproval`
  - `unplannedCallSendsJobBackToPlanning`: a planned and an unplanned call in one reply, and neither runs. The revision reason reaches plan and review. Re-approving clears the reason and finishes.
  - `stepTurnLimitEndsJob`
  - `userTrustedCommandRunsWithoutBeingPlanned`
  - `afterWebContentOnlyPlannedCallsRun` (controller ruling): after `fetchPage`, a planned writeFile still runs, but a user-trusted `echo trusted` triggers a revision whose reason mentions web content.
  - `toolsNotInJobNeverRun`
- The test compile config now sets `.recursionLimit(100)`, because LangGraph4j stops after 25 node steps by default.

### TDD evidence
- RED: `.\gradlew.bat :test --tests net.rka.ai.agent.CodingGraphTest` gave "14 tests completed, 6 failed". All 6 new tests failed, as expected with stub nodes.
- GREEN: the same command gave BUILD SUCCESSFUL. The XML reports tests="14" failures="0" errors="0" with empty system-out.
- `.\gradlew.bat build`: BUILD SUCCESSFUL. The generated `CodingGraphDefinition` outline lists `implement -> afterImplement() {tools, next, verify, unfinished}` and `implementTools -> afterImplementTools() {implement, revise}`.

## Concerns
1. **Recursion limit (production):** LangGraph4j's default limit is 25 node steps per invoke. A real job (steps × up to 31 implement/implementTools steps) will hit it once the subgraph is wired into AgentGraph.
   - `AgentGraph.compile` sets no `recursionLimit`.
   - I only raised it in the test. The wiring task should set `CompileConfig.recursionLimit(...)`, or the RunnableConfig equivalent, high enough for a job.
2. **`tool:<name>` trust entries count during a job:** `codingTrusted` (ToolRegistry.isTrusted) treats a `tool:writeFile` entry as trusted, so such a user lets file writes outside the plan run inside the folder until web content arrives.
   - This matches the user's trust outside jobs and the ruling "covered iff plan-covered or codingTrusted". It is still worth confirming.
3. Unknown tool names count as "read-only" in `CodingPlan.covers`, so they run and fail with ToolRegistry's error result instead of triggering a revision. This is harmless.
