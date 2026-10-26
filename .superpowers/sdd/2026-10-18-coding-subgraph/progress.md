# SDD ledger — plan: docs/superpowers/plans/2026-10-18-coding-subgraph.md
Spec: docs/superpowers/specs/2026-10-18-coding-subgraph-design.md
Not a git repo: BASE/HEAD = snapshots in $W/snap-N (copy of src/ docs/ README.md .env before task N); review packages = diff -ruN snap-N vs working tree.

## Pre-flight scan
| Rows | Produces / consumes | Finding |
|---|---|---|
| T1→T3/T4/T6 | CodingPlan records, parse/toJson/toMarkdown/covers | consistent |
| T2→T3/T4/T5 | EnvironmentInfo, ToolCallRunner.run(...,Integer) | consistent |
| T3→T4/T5 | State keys, node names, stub implement/verify/report | T3 test invalidPlanTwiceEndsJob needs codingReport → report must exist minimally in T3 |
| T3 test investigateMayRunOnlyProbes | "fake runner" for runCommand | ToolCallRunner runs real commands; no fake runner for runCommand |
| T5 vs Review Focus #5 | reviseThenDenyChangesNothing owned by T5 (CodingGraphTest) vs header says T6 | text conflict only |
| T6→T7 | Agent.run loop invoke→stream | consistent; T7 rewrites T6 loop |
| T7→T8 | Progress, ProgressListener, Agent.run 6-arg | consistent |
| T8 | build only, manual UI check by user | ok |
| T9 | docs from generated outlines | ok |
| each task self-consistency | tests vs code | ok apart from above |

Ruling: Task 3 implements a minimal `report` node that writes CODING_REPORT with the outcome reason (e.g. plan error/denied); Task 5 completes it — the T3 test requires it — cost if wrong: small rework in T5.
Ruling: investigateMayRunOnlyProbes uses `java -version` (a real probe present on every build machine) instead of `node --version` — no fake command runner exists in ToolCallRunner — cost: none.
Ruling: reviseThenDenyChangesNothing lives in Task 5 (CodingGraphTest), per the task text — the header line is a cross-reference slip — cost: none.

## Tasks
Task 1: minor (deferred): covers() catch JsonProcessingException|NPE — null arguments may throw IllegalArgumentException out of covers
Task 1: minor (deferred): dead null checks for command/file after List.copyOf
Task 1: minor (deferred): no test for copyFile source outside folder (test file misnamed outside.txt)
Task 1: minor (deferred): user-trusted runCommand covered regardless of workingDirectory (existing trusted-command behavior)
Task 1: minor (deferred): parse takes first '{'..last '}' — trailing prose with '}' breaks parse (costs a retry)
Task 1: minor (deferred): unknown tool names count as read-only in covers (fail at execution)
Task 1: complete (snap-1..tree, review clean) — implementer aca3cb9131336452b; TrustedCommands.normalize(String) added public static
Note: focused tests run with `.\gradlew.bat :test --tests ...` (plain `test --tests` also hits graph-processor and fails)
Task 2: minor (deferred): no test for TIMEOUT_PARAMETER pass-through (Integer-only lookup, Long silently ignored)
Task 2: minor (deferred): looksLikeMissingTool matches bare "not found" broadly — Task 5 should apply it only to failed verify output
Task 2: minor (deferred): probe line shape "node --version: v1" vs "node: not installed" cosmetic
Task 2: complete (snap-2..tree, review clean) — ToolCallRunner.run(calls, workFolder, facts, Integer) is package-private
Ruling: inside a coding job, once a @WebContent result is in this job's messages, user-trusted commands no longer bypass checks (investigate: only probes run; implement: only plan-covered calls run, trusted-only calls trigger a revision); probes and explicitly approved plan items stay allowed — the existing web rule exists to stop injected actions, and trusted commands weren't approved for this job, while plan items were — cost if wrong: an extra revision after reading docs.
Task 3: note: CODING_MESSAGES uses Channels.appenderWithDuplicate (plain appender drops equal messages, breaking CODING_START indexes)
Ruling: docs/agent-graph.md, README diagram, AgentGraph Javadoc are updated in Task 9 (after Task 6 wires coding in), not per task — the plan schedules docs as Task 9 and the subgraph is unreachable until Task 6 — cost if wrong: docs briefly out of sync between tasks.
Task 3: minor (deferred): probe/trusted runCommand workingDirectory during investigate not checked against folder
Task 3: minor (deferred): no test for 6-round investigate limit
Task 3: fix round 1/5 (3 addressed, 0 open — trusted after web content; no SSH in job; weak tests)
Task 3: complete (snap-3..tree, review clean after fix round 1) — helpers State.codingWebContent(tools)/codingTrusted(...), CodingGraph.NOT_IN_JOB set; CODING_REVISION_REASON clearing left to Task 4
Ruling: Task 6 must raise the compiled AgentGraph recursionLimit (LangGraph4j default 25 node steps) to fit a coding job — 15 turns/step × steps × 2 nodes + verify loops; use a generous limit (e.g. 1000) since the per-step turn cap and 3 verify attempts already bound a job — cost if wrong: a very long job hits the cap and errors.
Ruling: user `tool:<name>` trust entries (e.g. tool:writeFile) still allow unplanned file changes inside a job until web content arrives — spec says the user's trusted entries still apply in a job, and FileTools keep writes inside the trusted folder — cost if wrong: an unplanned write the user had globally trusted anyway.
Task 4: minor (carried into Task 5): revised plan with steps <= codingStep silently enters fix mode — reject as plan error
Task 4: minor (deferred): plan prompt uses previous.toJson()+"steps 1..n done" instead of toMarkdown(codingStep) — intent met
Task 4: minor (carried into Task 5): "Steps 1 to 0 are done" wording when first step hits limit
Task 4: minor (carried into Task 5): stepTurnLimitEndsJob should assert turn 16's call never ran
Task 4: complete (snap-4..tree, review clean)
Ruling: correcting my Task 4 carry-over (a): a revision is a plan error only when it has FEWER steps than codingStep (it dropped done steps); equal is valid (fix-mode revisions may only add commands/files to done steps or verify) — rejecting equal would make every fix-mode revision fail — cost if wrong: a plan that silently drops pending steps goes straight to verify, still gated by the plan check.
Task 5: minor (deferred): no-verify-commands outcome says "verified"; formatting `result =((`; CodingGraph.java ~720 lines
Task 5: fix round 1/5 (5 addressed, 0 open — step-count check <, mid-step revision → verify route, verify outside folder test, no-verify wording, reset confirmed)
Task 5: minor (deferred): mid-step revision folding unfinished work into a done step goes straight to verify; with no verify commands it reports Done without that work
Task 5: complete (snap-5..tree, review clean after fix round 1) — routes: afterVerify passed/fix/failing; afterPlanApproval approve/revise/deny/verify
Task 6: minor (deferred, final review to triage): recursion limit 1000 means a plain agent<->tools loop can now run ~500 model calls (was ~12 at default 25); option: per-turn agent round cap in route
Task 6: minor (deferred): askPlanApproval/askApproval duplicate ~10 lines of span setup
Task 6: minor (deferred): no Agent-level revise×2 then deny test
Task 6: complete (snap-6..tree, review clean) — pause ids: AgentGraph.APPROVAL_PAUSE ("approval-review"), PLAN_APPROVAL ("coding-planApproval"); Agent package-private ctor (ChatModel, ToolRegistry, Tracing, EnvironmentInfo); Tracing.off(); reviewPlan denies by default until Task 8
Task 7: minor (deferred): progress tests assert only phase (step/steps/detail untested); import order; CODING_PREFIX placement
Task 7: complete (snap-7..tree, review clean) — Agent.run 6-arg with Progress.ProgressListener; streamToPause; progressOf static
Task 8: fix round 1/5 (4 addressed, 0 open — PlanDialog 5 buttons/Enter denies; diff noise; scroll; backticks)
Task 8: complete (snap-8..tree, review clean after fix round 1) — manual UI check pending (user)
Task 9: minor (deferred): ~\chat vs ~/chat path form; copyFile source-inside rule missing in docs; planApproval verify route condition incomplete in docs; ASCII diagram END line grouping
Task 9: complete (snap-9..tree, review clean)
## Final review (opus): With fixes — Important 1-5; one fix wave dispatched
Ruling: PlanDialog keeps Approve as the default button (final-review minor 1) — the plan brief specifies "Approve (default)" and Enter in the comment field is already consumed — cost if wrong: a stray Enter approves a plan the user is looking at.
Ruling: verify progress fires after verify ends (final-review minor 2) — documented instead of adding a pre-verify node — cost: card shows the last step during a long build.
Ruling: jobs in the default home folder (final-review minor 6) parked — consistent with existing file tools and the user's chosen default ("default is the user folder") — cost: a broad plan entry covers home; the user still approves it.
Final fix wave: all 10 addressed (re-review opus), build --rerun-tasks BUILD SUCCESSFUL, 106 tests
Final: parked — PlanReview.why() prefers stale graph revisionReason over newer user comment — Ruling: real, deferred; done marks/revision flag still correct
Final: parked — docs say a never-valid revision is labelled "not approved", report only labels when codingPlan differs — Ruling: docs slightly overstate; outcome line says it
Final: parked — environment node has no try/catch around summary() — Ruling: real, deferred; probe runner already maps IO errors to exit -1
Ruling: keep progress.md (rulings record) and delete snapshots/diffs — not a git repo, so the ledger is the only record of decisions — cost: a small folder under .superpowers/
