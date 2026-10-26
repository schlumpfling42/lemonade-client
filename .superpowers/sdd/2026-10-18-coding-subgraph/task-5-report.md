# Task 5 report: CodingGraph part 3 (verify, report, the job's tool result)

Status: DONE. No commits (not a git repo); full build green.

## Implemented (src/main/java/net/rka/ai/agent/CodingGraph.java)

- `MAX_VERIFY_ATTEMPTS = 3`.
- `verify` node (no model): runs each plan verify command as a `runCommand` call (id `verify-<attempt>-<n>`)
  through `ToolCallRunner.run(..., timeoutSeconds)`. Each call first goes through `plan.covers(...)`
  with no user-trusted commands, so a verify command whose working directory is outside the trusted
  folder never runs (it counts as a failure). A command passes when its result starts with
  `exit code: 0`. `EnvironmentInfo.looksLikeMissingTool` is applied only to failed results; any hit
  calls `environment.markStale()` once. `CODING_VERIFY_OUTPUT` = a first line written by verify itself
  (`Verify passed.` / `Verify passed (the plan has no verify commands).` /
  `Verify failed: k of n commands failed.`), then `$ <command> [(in dir)]` + output for each command.
  The router reads only that first line, so command output can't fake a pass. Every run sets
  `CODING_ATTEMPTS = attempts + 1`.
- `afterVerify` router (replaces `@Edge(verify -> report)`): `passed` -> report; `fix` -> implement
  while attempts < 3; `failing` -> report. Generated outline:
  `verify -> afterVerify() {passed: report, fix: implement, failing: report}`.
  The fix instruction joins `codingMessages` through implement's existing first-turn task message
  (fix-mode task text + `CODING_VERIFY_OUTPUT`); verify adds no message of its own.
- `report` node: Markdown in `CODING_REPORT`: outcome line, `## Plan` (`toMarkdown(codingStep)`),
  `## Last verify output` (fenced, cut to 4,000 chars with a "[... N more characters cut ...]" note).
  Outcomes: `Done: ...`, `Denied: the user denied the plan. ...`, `Stopped: no valid plan after 2
  attempts: <error> ...`, `Stopped: Step N / The fix after a failed verify did not finish within 15
  model turns. ...`, `Still failing after 3 verify attempts: ...`. Appends to `MESSAGES` a
  `ToolExecutionResultMessage` (id `CODING_CALL_ID`, tool `startCodingJob`, text = report),
  `isError` unless the outcome is Done.
- "What was changed" wording: "Nothing was changed." only if implement never ran in this job
  (detected by implement's task message in the job transcript, which now ends with the constant
  `DO_IT`); otherwise "No step is done." / "Step 1 is done." / "Steps 1 to k are done." plus
  "Changes made so far stay in the folder." The old text claimed "Nothing was changed" after a
  plan error/deny even when a revision happened mid-implementation.
- Class Javadoc: diagram with the verify routes, verify/report bullets, revised-plan rule; the
  "Not implemented yet" verify item replaced.

Review findings from Task 4:
- a. `withPlan`: a parsed plan with `steps.size() <= codingStep` throws the same
  `IllegalArgumentException` path as invalid JSON ("The plan has N steps, but steps 1 to k are
  already done: keep them first and unchanged, and add the remaining work as new steps after
  them."), so it is counted in `CODING_PLAN_ERRORS` and retried/ends the job like any plan error.
  Note: in fix mode (codingStep == steps) this means a revision must add at least one new step; a
  revision that only adds a command to a done step is rejected. That follows the rule as given and
  the existing "keep done steps unchanged" prompt.
- b. Step-limit wording uses `doneSteps(k)` ("No step is done." at step 0); fix-mode limit says
  "The fix after a failed verify" instead of "Step N+1".
- c. `stepTurnLimitEndsJob` asserts r14 has a result and r15 (turn 16) has none.

## Tests (src/test/java/net/rka/ai/agent/CodingGraphTest.java)

Fixture changes: `environment` is a field and the fake probe runner counts probes (`probes`);
`FILE_PLAN`'s verify is now `java -version` (verify really runs; `gradle build` would run in the temp
folder); `approvePlan(plan, trusted, answers)` generalizes `approveFilePlan`; helpers `checkPlan(cmd)`
and `jobAnswer(state)` (last main message answers `job1`/`startCodingJob`, nothing open).

New: `passingVerifyReportsDone`, `failingVerifyRetriesThreeTimes` (3 attempts, 6 model requests,
both fix requests contain "verifying failed" and the exact verify output, no re-probe for a failing
existing command), `missingToolMarksEnvironmentStale` (next `summary()` runs all 9 probes again),
`reviseThenDenyChangesNothing` (revise twice, each back at planApproval; deny; folder empty; report
"denied" + "Nothing was changed"; result isError), `revisedPlanWithoutTheDoneStepsIsAPlanError`
(4 plan errors, review prompt sees the reason, report "Stopped" + "Step 1 is done", isError).
Updated: `denyGoesToReport` (exactly one result in main messages: the error-flagged report),
`plannedCallsRunWithoutApproval` (report starts with "Done"), `stepTurnLimitEndsJob` (b, c, isError).

### TDD evidence

RED: `.\gradlew.bat :test --tests net.rka.ai.agent.CodingGraphTest` (after adding only the
`MAX_VERIFY_ATTEMPTS` constant so the tests compile):
```
CodingGraphTest > stepTurnLimitEndsJob() FAILED  (AssertionFailedError at :407)
CodingGraphTest > missingToolMarksEnvironmentStale() FAILED
CodingGraphTest > plannedCallsRunWithoutApproval() FAILED
CodingGraphTest > denyGoesToReport() FAILED
CodingGraphTest > passingVerifyReportsDone() FAILED
CodingGraphTest > reviseThenDenyChangesNothing() FAILED
CodingGraphTest > revisedPlanWithoutTheDoneStepsIsAPlanError() FAILED
CodingGraphTest > failingVerifyRetriesThreeTimes() FAILED
19 tests completed, 8 failed
```
Expected: verify was a stub, report added no tool result and used the old wording, revised plans
with too few steps were accepted.

GREEN: same command -> `BUILD SUCCESSFUL`, TEST-net.rka.ai.agent.CodingGraphTest.xml:
tests="19" failures="0" errors="0".
Full: `.\gradlew.bat build` -> `BUILD SUCCESSFUL`; all suites 77 tests, 0 failures/errors.

## Files changed

- src/main/java/net/rka/ai/agent/CodingGraph.java
- src/test/java/net/rka/ai/agent/CodingGraphTest.java

## Self-review / concerns

- Verify's runCommand calls are not added to the job transcript (only the output, via the fix
  task message); listed under "Not implemented yet". Their tool spans are still traced.
- Docs (docs/agent-graph.md, README, AgentGraph Javadoc) are not updated: deferred to Task 9. The
  new route names are `passed` / `fix` / `failing` on `afterVerify`; the `verify -> report` edge is gone.
- `CodingGraph.java` is now ~720 lines; still one responsibility, but large.
- The verify tests run real commands (`java -version`, `java --no-such-option`,
  `no-such-tool-xyz --version`) as the brief specifies; they need `java` on PATH.

---

## Fix round 1

Changes (CodingGraph.java):
1. `withPlan`: a revision is a plan error only when `steps.size() < codingStep`. New message:
   "The plan has N steps, but steps 1 to k are already done: keep all of them, first and in order."
   Exactly `codingStep` steps is valid (new commands/files in done steps or in verify).
2. `afterPlanApproval`: new route `verify` -> `verify`. On approve, when `codingStep >= steps` and
   `codingVerifyOutput` is blank (verify never ran), it goes straight to verify, so implement never
   gets a "verifying failed" prompt with an empty output. After a failed verify it still goes to
   implement (fix mode with the real output). Generated outline:
   `planApproval -> afterPlanApproval() {approve: implement, verify: verify, revise: plan, deny: report}`.
   Router and class Javadoc (diagram + implement bullet) updated. Docs for the new route are still
   left for Task 9.
   Note: when a mid-step revision folds the unfinished step's work into a done step, that work is not
   implemented before verify. If verify then fails, fix mode can use the new entries. This was the
   coordinator's preferred option.
3. The verify outside-folder path is now tested (below).
4. Outcome with no verify commands: "Done: every step is implemented (the plan has no verify
   commands)." Fixed `result = ((` spacing.
5. Confirmed: `environment` already resets `CODING_VERIFY_OUTPUT` (in the "" loop) and
   `CODING_ATTEMPTS` (set to 0) for each job. No change was needed.

Tests (CodingGraphTest.java):
- `revisedPlanWithoutTheDoneStepsIsAPlanError` was rewritten. It uses a 3-step plan with steps 1–2 done,
  and every revision has 1 step. Result: 4 plan errors containing "already done", report "Stopped" +
  "Steps 1 to 2 are done", isError.
- New `fixModeRevisionMayAddACommandToADoneStep`: verify fails and the fix calls an unplanned
  `java -version`. The revision has the same single step plus that command. It is accepted with no
  plan errors and pauses at planApproval. After approval the fix-mode request contains "verifying failed",
  the command runs (`exit code: 0`), and the job ends "Still failing" after 3 attempts.
- New `revisionWithNoStepLeftGoesStraightToVerify`: in step 2 an unplanned write leads to a 1-step
  revision. After approval there are no more model requests (7 total), no prompt contains
  "verifying failed", attempts = 1, and the report says "Done".
- New `verifyOutsideTheFolderDoesNotRun`: the verify command has `workingDirectory ".."`. Output starts
  with "Verify failed: 1 of 1", contains "Not run: the working directory is outside the trusted folder"
  and no "exit code". There are 3 attempts and the report says "Still failing".
- New `noVerifyCommandsIsDoneWithoutClaimingVerified`: the report starts with "Done", does not contain
  "verified", and is not an error.

Runs:
- `.\gradlew.bat :test --tests net.rka.ai.agent.CodingGraphTest` -> BUILD SUCCESSFUL;
  TEST-net.rka.ai.agent.CodingGraphTest.xml: tests="23" failures="0" errors="0".
- `.\gradlew.bat build` -> BUILD SUCCESSFUL; all suites: 81 tests, 0 failures/errors.
