# Final-review fix wave: report

Status: DONE_WITH_CONCERNS (all 10 items fixed; the concerns are design choices listed at the end)
Commits: none (not a git repo)

## Items

### 1. Silent step skipping (Important)
What changed:
- New state keys (`AgentGraph.State`): `codingDoneItems` (one `step<TAB>item` line per plan item that ran
  successfully), `codingSkippedSteps` (comma-separated 1-based numbers), `codingStepReminded` (int 0/1). All are
  reset by `environment`.
- `CodingPlan.items(step)` names a step's items ("`npm install` in `frontend`", "create `app/`"), and
  `CodingPlan.itemsDoneBy(step, call, folder)` says which of that step's items a call carries out (same rules
  as `covers`, but only that step's entries count). `covers` and `itemsDoneBy` share the refactored
  `matchingCommands` / `matchingEntries` helpers.
- `implementTools` records, after running covered calls, the current step's items whose result is not an error
  (and, for `runCommand`, has exit code 0). Nothing is recorded in fix mode.
- `implement`: a text answer on a step that lists commands or files and has none of its items done -> once, a
  reminder UserMessage naming the missing items (no step advance, task message not repeated; route `next`);
  the second text answer -> the step is added to `codingSkippedSteps` and the job moves on. Steps without
  items and fixes are done by the answer alone. A revision (unplanned call) resets the reminder.
- `CodingPlan.toMarkdown(stepsDone, skipped)` marks skipped steps "✗ skipped"; `toMarkdown(int)` delegates.
  The implement prompt shows the marks too, and the IMPLEMENT prompt says a step only counts when its items ran.
- Report: with skipped steps the outcome is "Done with skipped steps: 1 (Write the page). The other steps are
  implemented and the verify commands pass." and the result is flagged as an error; "Still failing" no longer
  claims every step is implemented when one was skipped; the "Steps 1 to k are done" sentence lists skipped ones.
Tests: `CodingGraphTest.textOnlyStepIsRepromptedThenSkipped`, `skippedStepIsNotReportedAsDone`,
`repromptedStepThatRunsItsItemIsDone`; `CodingPlanTest.itemsDoneByMatchesOnlyTheStepsOwnItems`,
`markdownMarksSkippedSteps`. Existing `userTrustedCommandRunsWithoutBeingPlanned` now also writes the step's
file (a trusted command alone doesn't carry out a step's item, which is the point of #1).

### 2. Web content read inside a job (Important)
What changed: `State.webContentInTurn` now also returns true for any `@WebContent` result in
`allCodingMessages()` (all jobs of the run; a run starts with no coding messages, so it is exactly this turn).
`codingWebContent` now delegates to it, so it covers every job in the turn. The refusal texts say "read in
this turn".
Test: `CodingJobTest.trustedCommandAfterAJobThatReadTheWebAsks` (the job's investigation calls fetchPage, the
job finishes, the agent then calls the user-trusted `echo trusted`: the Approver is asked, the call is denied
and never runs). `CodingJobTest` registers `CodingGraphTest.FakeWebTools` for it.

### 3. Per-turn cap on the agent loop (Important)
What changed: `AgentGraph.MAX_AGENT_ROUNDS = 12` and `State.agentToolRounds()` (AI messages with tool calls since
the last UserMessage in `messages`; job internals live in `codingMessages` and don't count). When the count has
reached 12, `callModel` sends no tool specifications plus a request-only instruction to answer, and strips any
tool calls the model returns anyway (text kept, or `TOO_MANY_ROUNDS` = "Stopped: too many tool rounds in one
turn."). `route` then takes its existing `done` route; nothing is left open. RECURSION_LIMIT stays 1000.
Design choice: a LangGraph4j router can't change state, so "answer or strip the calls" has to happen in a node;
the agent node was the cleanest place (no new node or route). `route` Javadoc says so.
Test: `CodingJobTest.agentLoopStopsAfterMaxRounds` (12 readFile rounds run, request 13 has no tools and the
instruction, its call is stripped, the turn ends with the TOO_MANY_ROUNDS answer, 12 results).

### 4. Model or tool exception mid-job (Important)
What changed: `CODING_STOP_REASON = "codingStopReason"`. `investigate`, `plan`, `review`, `implement` catch
RuntimeException around the model call; `investigateTools`, `implementTools`, `verify` around the tool runs
(the registry already turns tool exceptions into results, so this covers anything else). The reason is
"model error: <msg>" / "tool error: <msg>". Every router except `afterPlanApproval` has one `stopped` route to
`report`. The fixed edges `investigateTools -> investigate` and `plan -> review` became routers
(`afterInvestigateTools`, `afterPlan`) so they can stop too. Folded into the same path: the step-turn limit
(`implement` writes "Step k did not finish within 15 model turns.") and the second invalid plan (`review`
writes "no valid plan after 2 attempts: ..."); routes `failed` and `unfinished` are gone. Report: "Stopped:
<reason>. Steps 1 to k are done. Changes made so far stay in the folder." (or "Nothing was changed.").
Tests: `CodingGraphTest.modelErrorMidImplementEndsWithReport`, `modelErrorWhileInvestigatingEndsWithReport`;
existing `stepTurnLimitEndsJob`, `invalidPlanTwiceEndsJob`, `revisedPlanWithoutTheDoneStepsIsAPlanError` cover
the folded endings.

### 5. Plan review shows done marks and the revision reason; vanished pending step (Important)
What changed:
- New record `PlanReview(plan, stepsDone, skippedSteps, revisionReason, userComment, revision)` with
  `toMarkdown()` ("Revised plan: <reason>" line, the reason's lines joined with "; ", else "you asked:
  <comment>"; then `plan.toMarkdown(stepsDone, skipped)`) and `PlanReview.first(plan)`. I added `skippedSteps`
  to the suggested record so skipped marks show in the dialog too.
- `Approver.reviewPlan(PlanReview)`; `Agent.askPlanApproval` builds it from the paused state (before resuming,
  so before implement clears the reason/comment). `revision` = an approved plan exists or the last decision was
  revise. ChatWindow, PlanDialog (header "Revised coding plan") and ConsoleApprover render `review.toMarkdown()`.
- `withPlan`: a revision with exactly `codingStep` steps while `codingVerifyOutput` is blank is a plan error
  ("The plan ends after step k, but step k+1 is not done yet ..."). That made the `planApproval -> verify` route
  unreachable, so it is removed; `afterPlanApproval` is approve/revise/deny.
Tests: `CodingGraphTest.revisionThatDropsThePendingStepIsAPlanError` (replaces
`revisionWithNoStepLeftGoesStraightToVerify`), `CodingPlanTest.planReviewShowsTheRevisionReasonAndDoneSteps`,
`ConsoleApproverTest.revisedPlanShowsTheReasonAndTheDoneSteps`, the revision assertions added to
`CodingJobTest.planApprovalPausesAndUsesTheDecision`; `fixModeRevisionMayAddACommandToADoneStep` still passes
(exact count after a failed verify is allowed).

### 6. covers with null arguments
What changed: one `arguments(call)` helper returns null for null text, JsonProcessingException and
IllegalArgumentException; `covers` and `itemsDoneBy` fail closed on it.
Test: `CodingPlanTest.nullArgumentsAreNotCovered`.

### 7. Never-ending commands
What changed: `PLAN_FORMAT` (in both PLAN and REVIEW) has a rule that every command must finish on its own
(no `npm run dev`, `gradle bootRun`, `vite`, `npm start`, `--watch`); REVIEW's checklist has a "commands that
end" item. No test (prompt text only).

### 8. Newline in plan command matching
What changed: `CodingPlan.normalizeCommand` collapses only horizontal whitespace (`\h+`) and trims the ends;
plan matching uses it instead of `TrustedCommands.normalize`, which is unchanged.
Test: `CodingPlanTest.newlineIsNotNormalizedAway` (`git \t add  .` and ` git add . ` match `git add .`;
`git add\n.` and `git\r\nadd .` don't).

### 9. Report shows the last approved plan
What changed: new key `codingApprovedPlan`, written by the `planApproval` node when the decision is approve
(the node no longer does nothing). The report's "## Plan" is the approved plan with ✓/✗ marks; if the current
plan differs (denied revision, or a revision that never became valid), it adds "## Revised plan (not
approved)". With no approval at all it shows "## Plan (not approved)".
Test: `CodingGraphTest.reportShowsTheApprovedPlanAfterADeniedRevision`.

### 10. Docs
- `~/chat/environment.json` everywhere (README was `~\chat\...`).
- copyFile rule (source inside the folder) in README and docs/agent-graph.md (CodingPlan Javadoc table too).
- planApproval verify route removed from the Mermaid diagram, node table, CodingGraph Javadoc.
- ASCII diagrams in README and AgentGraph Javadoc: the coding description moved to its own block below, so the
  END line stands alone (and notes the 12-round cap).
- "Verifying" progress fires after the verify node: in docs/agent-graph.md (verify row and Pauses paragraph),
  README step 5 and the verify Javadoc.
- Also updated for this wave: all new routes (`stopped` x7, `investigate`, `review`), new state keys, the skip
  rule, the stop path, web content across jobs, the newline rule, null arguments, terminating commands.

Checked against the generated outline (`.\gradlew.bat compileJava`, CodingGraphDefinition Javadoc):
```
START -> environment
environment -> investigate
report -> END
investigate -> afterInvestigate() {tools: investigateTools, plan: plan, stopped: report}
investigateTools -> afterInvestigateTools() {investigate: investigate, stopped: report}
plan -> afterPlan() {review: review, stopped: report}
review -> afterReview() {approval: planApproval, retry: plan, stopped: report}
planApproval -> afterPlanApproval() {approve: implement, revise: plan, deny: report}
implement -> afterImplement() {tools: implementTools, next: implement, verify: verify, stopped: report}
implementTools -> afterImplementTools() {implement: implement, revise: plan, stopped: report}
verify -> afterVerify() {passed: report, fix: implement, failing: report, stopped: report}
```
Every edge and route appears in the Mermaid diagram under the same name. AgentGraphDefinition is unchanged
(`agent -> route() {coding, approval, trusted, tools, done: END}`).

## Commands run and output

Focused run (PowerShell):
```
.\gradlew.bat :test --tests net.rka.ai.agent.CodingGraphTest --tests net.rka.ai.agent.CodingJobTest --tests net.rka.ai.agent.CodingPlanTest --tests net.rka.ai.cli.ConsoleApproverTest
...
> Task :test
BUILD SUCCESSFUL in 11s
```
Result XML: CodingGraphTest 29 tests, CodingJobTest 10, CodingPlanTest 18, ConsoleApproverTest 4; 0 failures,
0 errors, 0 skipped.

Full build after the last edit:
```
.\gradlew.bat build
...
> Task :test
> Task :check
> Task :build
...
BUILD SUCCESSFUL in 12s
15 actionable tasks: 7 executed, 8 up-to-date
```
All 15 test classes: 106 tests, 0 failures, 0 errors, 0 skipped (graph-processor tests up to date). The only
logged output is the existing "Progress listener failed" warning from
`CodingJobTest.failingProgressListenerDoesNotBreakTheRun`, which deliberately breaks the listener.

TDD: the brief asked for the named tests, not strict TDD. I wrote the tests after the implementation, so there
is no RED run to show; the tests assert the new behaviour (reminder text, skipped marks, stop reasons, the
approval of a trusted command after a job's web read, the stripped 13th round) that the old code didn't have.

## Files changed
- src/main/java/net/rka/ai/agent/CodingGraph.java (skip rule, stop path + 2 new routers, approved plan, report, Javadoc)
- src/main/java/net/rka/ai/agent/AgentGraph.java (new state keys + getters, webContentInTurn, MAX_AGENT_ROUNDS, Javadoc diagram)
- src/main/java/net/rka/ai/agent/CodingPlan.java (items, itemsDoneBy, toMarkdown with skipped, normalizeCommand, null args)
- src/main/java/net/rka/ai/agent/PlanReview.java (new)
- src/main/java/net/rka/ai/agent/Approver.java, Agent.java, CodingPrompts.java
- src/main/java/net/rka/ai/ui/PlanDialog.java, ChatWindow.java; src/main/java/net/rka/ai/cli/ConsoleApprover.java
- src/test/java/net/rka/ai/agent/CodingGraphTest.java, CodingJobTest.java, CodingPlanTest.java;
  src/test/java/net/rka/ai/cli/ConsoleApproverTest.java
- docs/agent-graph.md, README.md

## Design choices / concerns
1. #3 cap is enforced in the agent node, not by the router (a router can't strip calls). The turn ends through
   the existing `done` route, so no new node or route.
2. #1 "ran successfully" means: result not flagged as an error, and for `runCommand` exit code 0. A step whose
   only planned command fails (non-zero) and is never fixed counts as not carried out and is reminded/skipped.
   Items are identified by their rendered text, so a revision that changes a step's item text drops that item's
   "ran" record (conservative: it would be re-prompted, never silently done).
3. #1 the reminder turn isn't counted in `codingStepTurns` (it is at most one extra model call per step).
4. #4 the `stopped` route for `investigateTools`, `implementTools` and `verify` is mostly defensive:
   `ToolRegistry.execute` already turns tool exceptions into error results, so only failures outside it (e.g.
   tracing) reach these catches. No test drives a tool-run exception.
5. #5 I added `skippedSteps` to the suggested `PlanReview` record so the dialog shows ✗ marks as well.
6. CodingGraph.java is now ~980 lines (was ~770); it was already the largest file. The job logic still reads in one
   place, but it is getting large.
