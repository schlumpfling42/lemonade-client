# Task 3 report: CodingGraph part 1

**Status:** DONE_WITH_CONCERNS (minor, listed below). No commits (not a git repo); `.\gradlew.bat build` is BUILD SUCCESSFUL.

## What I implemented

- **`AgentGraph.State`**: all 16 `CODING_*` keys from the brief (exact names/values), typed getters for each
  (`codingGoal()`, `codingCallId()`, `codingEnvironment()`, `codingMessages()` (only this job's part from
  `CODING_START`), `codingStart()`, `codingFindings()`, `Optional<CodingPlan> codingPlan()`, `codingDecision()`,
  `codingComment()`, `codingRevisionReason()`, `codingStep()`, `codingStepTurns()`, `codingAttempts()`,
  `codingVerifyOutput()`, `codingReport()`, `List<String> codingPlanErrors()`), plus package-private
  `allCodingMessages()` and `codingUnansweredToolCalls()`. `unansweredToolCalls()` now delegates to a private
  static `unansweredIn(List)` shared by both. Missing keys read as `""` / `0`.
  - `CODING_MESSAGES` is in `SCHEMA` as **`Channels.appenderWithDuplicate`**, not `appender`: LangGraph4j's plain
    appender silently drops a message equal to one already in the list (checked in the 1.9.5 sources,
    `ReducerDisallowDuplicate`). A reviewer returning the plan unchanged, or a second job with the same goal,
    would otherwise be lost and break `CODING_START` indexes. Covered by `reviseReturnsToPlanApproval` (identical plan and review answers, 6 messages kept).
- **`ToolRegistry.specifications(Predicate<String> toolName)`**: filtered specs in registration order.
- **`CodingPrompts`**: `INVESTIGATE`, `PLAN`, `REVIEW`, `IMPLEMENT`, and a shared `PLAN_FORMAT` (Task 1's JSON
  schema + entry rules) that is part of `PLAN` and `REVIEW`. `REVIEW` lists the spec's checks: missing steps,
  order, OS/shell/tool versions from the environment, non-interactive flags, every path inside the folder, every
  command listed with its working directory, verify commands.
- **`CodingGraph`** (`@Graph(name = "coding", state = AgentGraph.State.class, serializer = LANGCHAIN4J)`),
  constructor as in the brief, `START_TOOL = "startCodingJob"`, also `PLAN_APPROVAL`, `APPROVE`/`REVISE`/`DENY`,
  `MAX_INVESTIGATE_ROUNDS = 6`, `MAX_PLAN_ATTEMPTS = 2`, `PROBE_TIMEOUT_SECONDS = 20`. Spans are `coding/<node>`.
  Generated outline (from `CodingGraphDefinition` Javadoc):
  ```
  START -> environment; environment -> investigate; investigateTools -> investigate; plan -> review
  implementTools -> implement; verify -> report; report -> END
  investigate -> afterInvestigate() {tools: investigateTools, plan: plan}
  review -> afterReview() {approval: planApproval, retry: plan, failed: report}
  planApproval -> afterPlanApproval() {approve: implement, revise: plan, deny: report}
  implement -> afterImplement() {tools: implementTools, verify: verify}
  ```
  - `environment`: finds the `startCodingJob` call in `unansweredToolCalls()` (throws `IllegalStateException` if
    none — only reachable through a router bug); answers every other call of that AI message in main `messages`
    with `"Not run: a coding job started; ask again afterwards."` (error result); sets goal / call id /
    environment summary / `CODING_START = allCodingMessages().size()`; resets all job string keys to `""` and
    step/turns/attempts to 0 (so a second job in the same turn starts clean); appends `UserMessage("Coding job goal: ...")`
    as the job transcript's first message.
  - `investigate`: system = `INVESTIGATE` + context (goal, folder, environment, probe list), then the job's
    transcript; offers `specifications(offeredWhileInvestigating)` = read-only tools + `runCommand`, never
    `startCodingJob`. My reading of "6 rounds": after 6 rounds of tool calls the next request offers no tools
    and adds a "write your findings now" instruction (request only, not stored); if the model still returns
    calls they're stripped so no call is left open. A reply without calls is stored as `CODING_FINDINGS`.
  - `investigateTools`: per call: `runCommand` runs only for `EnvironmentInfo.isProbe` or user-trusted commands
    (probe timeout 20 s, trusted commands the job timeout); other offered (read-only) tools run; everything else
    gets an error result listing all probes. Results go to `CODING_MESSAGES` only; facts are not written back.
  - `plan` / `review`: tool-less model calls (system = phase prompt, user = context + findings + revision notes).
    `plan` adds the previous plan JSON when one exists and the last parse error on a retry; `review` gets the
    plan phase's raw answer (last AI message) plus the plan's parse error if that failed. Both store
    `CodingPlan.parse(text).toJson()` in `CODING_PLAN`, or append `"plan: <error>"` / `"review: <error>"` to
    `CODING_PLAN_ERRORS` (one line each). A review that parses clears the errors.
    Revision notes: done steps (`CODING_STEP`), the user's comment when decision is `revise`, `CODING_REVISION_REASON`.
  - `afterReview`: no errors → `planApproval`; review errors < 2 → `plan` (retry); else `report`.
  - `planApproval`: no-op (the interrupt is before it). `afterPlanApproval`: approve / revise / anything else → deny (fail closed).
  - `implement`, `implementTools`, `verify`: stubs (empty updates); `afterImplement` → `verify` (no calls ever made) → `report`.
  - `report`: minimal; writes `CODING_REPORT`: plan failure ("...without a valid plan after 2 attempts: <error> Nothing was changed."),
    denied ("The user denied the plan. Nothing was changed."), or approved-but-not-implemented. It does not
    answer the `startCodingJob` call yet (Task 5).

## Tests

`src/test/java/net/rka/ai/agent/CodingGraphTest.java` — scripted fake `ChatModel` (answer queue, records
requests, fails on an unexpected request), real `EnvironmentInfo` with a lambda probe runner and temp cache,
real `ToolRegistry.of(new FileTools(), new TerminalTools())`, `CodingGraphDefinition.compile(graph, MemorySaver +
interruptBefore("planApproval"))`, resume via `updateState` + `GraphInput.resume()` like `Agent`:

- `pausesBeforePlanApprovalWithReviewedPlan` — paused before `planApproval`; `codingPlan()` = fenced reviewed plan;
  goal, call id, findings, environment summary; 4 job messages; main messages unchanged (3) and only the start call open;
  investigate offers read tools + runCommand but not writeFile; plan/review prompts carry findings / draft.
- `investigateMayRunOnlyProbes` — real `java -version` runs (not an error); `npm install` gets the error listing probes; the model sees both results.
- `invalidPlanTwiceEndsJob` — 4 non-JSON answers → 5 requests, the retry plan prompt carries the error, report mentions "no JSON object", no plan.
- `denyGoesToReport` — report says denied, no further model request, no tool result anywhere.
- `approveEndsInReportUntilImplementExists` (extra, checks the stub path terminates).
- `reviseReturnsToPlanApproval` — the re-plan request contains the comment and the previous plan; paused again; revised plan stored; duplicates kept.
- `otherCallsInTheSameReplyAreRefused` — `readFile` answered with "Not run: a coding job started..." error.

### TDD evidence

- RED: `.\gradlew.bat :test --tests net.rka.ai.agent.CodingGraphTest` after writing the test →
  `compileTestJava` FAILED, 30 errors (`cannot find symbol ... codingReport()`, `codingMessages()`, `codingPlan()`, `CodingGraphDefinition` ...) — expected, nothing implemented yet.
- GREEN: same command → BUILD SUCCESSFUL; `TEST-net.rka.ai.agent.CodingGraphTest.xml`: tests="6" failures="0" errors="0" (7 after adding the approve test), empty stdout/stderr.
- Full: `.\gradlew.bat build` → BUILD SUCCESSFUL; all suites 0 failures/errors (65 tests); compile with `--rerun-tasks` shows no warnings.

## Files

- Created: `src/main/java/net/rka/ai/agent/CodingGraph.java`, `src/main/java/net/rka/ai/agent/CodingPrompts.java`, `src/test/java/net/rka/ai/agent/CodingGraphTest.java`
- Modified: `src/main/java/net/rka/ai/agent/AgentGraph.java` (State keys, SCHEMA, getters, `unansweredIn` helper), `src/main/java/net/rka/ai/tools/ToolRegistry.java` (`specifications(Predicate)`)

## Concerns / notes for later tasks

1. **Web content during investigate**: user-trusted commands run in `investigateTools` even after a `fetchPage`/`webSearch`
   result in the job, matching the spec and Task 1's `CodingPlan.covers` — unlike the main graph, where web content in the turn disables trust. Worth a controller ruling.
2. `runCommand`'s working directory is not confined for probes/trusted commands during investigate (same as the main graph's trusted commands); probes are harmless anywhere.
3. `CODING_REVISION_REASON` is never cleared here; Task 4 (which sets it) should clear it once a new plan is approved, or it shows up in later revision prompts.
4. `codingDecision` stays `revise` after a revise round until Agent writes the next answer; harmless because the router only reads it after the pause.
5. Docs (`docs/agent-graph.md`, README, `AgentGraph` Javadoc) not updated: the subgraph isn't wired into `AgentGraph` until Task 6, and the rules say not to touch docs unless the brief says so. The outline above is ready for that.

---

## Fix round 1

### Changes

1. **Trusted commands stop after web content** (controller ruling). New shared helpers on `AgentGraph.State`
   (next to `isTrusted`), also meant for Task 4:
   - `codingWebContent(ToolRegistry)`: true if `webContentInTurn(tools)` is true (the main turn already had web content), or if this job's
     `codingMessages()` holds a result of a `@WebContent` tool.
   - `codingTrusted(ToolRegistry, call)`: calls the existing `tools.isTrusted(call, trustedCommands(), codingWebContent(tools))`.
   `CodingGraph.allowedWhileInvestigating` now uses `isProbe(command) || state.codingTrusted(...)`. After web content
   only the fixed probes run. The refusal text then says the user's trusted commands don't run unasked because web
   content was read in this job.
2. **No SSH in a job**: new `CodingGraph.NOT_IN_JOB = {startCodingJob, sshHosts, runRemoteCommand, remember, forget}`
   (package-private, for Task 4). These tools are never offered or allowed in investigate. Class Javadoc updated.
3. **Tests**:
   - `investigateMayRunOnlyProbes`: I dropped the `node_modules` assertion. In its place the test asserts that the refusal text has no
     `exit code`, which shows it is our refusal and not output from a command that ran.
   - `denyGoesToReport`: the model now runs a probe (`java -version`) during investigate. The test checks that exactly one result
     existed before the decision, that the job transcript is unchanged after deny, that no model request happened after deny, that the main messages
     have no results, and that the work folder is empty.
   - `pausesBeforePlanApprovalWithReviewedPlan`: the registry now also has `SshTools` and a fake `@WebContent fetchPage`
     (no network). The test asserts that `fetchPage` is offered and that `writeFile`, `deletePath`, `sshHosts` and `runRemoteCommand` are not.
   - New `trustedCommandsStopRunningAfterWebContentInTheJob`: the trusted `echo trusted` runs, then a `fetchPage`
     result arrives, then `echo trusted` gets the error result (mentions web content) while the `java -version` probe still runs.
   - New `trustedCommandsDontRunAfterWebContentInTheTurn`: a `fetchPage` result in the main turn before
     `startCodingJob` means a trusted command is refused in the very first investigate round.

### Tests run

- `.\gradlew.bat :test --tests net.rka.ai.agent.CodingGraphTest` → BUILD SUCCESSFUL; `TEST-net.rka.ai.agent.CodingGraphTest.xml`:
  `tests="9" skipped="0" failures="0" errors="0"`.
- Check that the new tests catch the bug: I temporarily put back `state.trustedCommands().isTrusted(call)` and ran the same command. Result:
  `trustedCommandsDontRunAfterWebContentInTheTurn() FAILED`, `trustedCommandsStopRunningAfterWebContentInTheJob() FAILED`,
  `9 tests completed, 2 failed`. Then I restored the fix.
- `.\gradlew.bat build` → `BUILD SUCCESSFUL in 5s`; all suites 67 tests, 0 failures/errors.

Docs are deferred to Task 9 (ruling). `CODING_REVISION_REASON` clearing is left to Task 4.
