# Final-review fix wave — coding subgraph

Project: C:\Data\Projects\langchain (Java agent, LangChain4j/LangGraph4j). Plan docs/superpowers/plans/2026-10-18-coding-subgraph.md, spec docs/superpowers/specs/2026-10-18-coding-subgraph-design.md (spec anti-goal #1: "not following the plan… silently skipping steps"). All 9 tasks are done; a whole-branch review found the items below. Fix ALL of them in one pass. Tests: add the named tests (CodingGraphTest / CodingJobTest style, scripted fake ChatModel). Finish with `.\gradlew.bat build` → BUILD SUCCESSFUL. Keep docs/agent-graph.md, README diagram and AgentGraph Javadoc in sync with any graph/route/state-key change (CLAUDE.md), checked against the generated *Definition outlines.

## Important

1. Steps can be skipped silently and the report claims success (CodingGraph.java ~401-407 text answer = step done; ~555-559 outcome; CodingPlan.toMarkdown only ✓).
   - Keep a per-job record, in state as strings, of which plan items actually ran successfully: covered calls matched to that step's commands and files.
   - When a step that lists commands or files ends with a text answer and none of its items ran, re-prompt once, naming the missing items. If it ends the same way again, mark the step skipped and move on.
   - toMarkdown shows skipped steps (e.g. "✗ skipped"). The outcome line never claims "every step is implemented" when a step was skipped; say "Done with skipped steps: …" and flag the result as an error.
   - Tests: textOnlyStepIsRepromptedThenSkipped, skippedStepIsNotReportedAsDone.
2. Web content read inside a job doesn't count as web content for the rest of the main turn (AgentGraph.java ~327-336 webContentInTurn scans only messages; codingWebContent only the current job).
   - Make webContentInTurn also count @WebContent results in all of this turn's coding messages (allCodingMessages or the equivalent), and make codingWebContent cover every job in the turn.
   - Test (Agent-level, CodingJobTest): trustedCommandAfterAJobThatReadTheWebAsks. The job reads fetchPage, then the agent calls a user-trusted command, and it goes to approval.
3. RECURSION_LIMIT = 1000 removed the old ~12-round cap on the plain agent↔tools loop (AgentGraph.java ~118, ~435).
   - Add a per-turn cap in AgentGraph.route: count AI messages with tool calls since the last UserMessage, ignoring coding-job internals since they live in codingMessages. Past MAX_AGENT_ROUNDS = 12, route to "done" with the calls answered or stripped so nothing is left open. Choose the cleanest graph-native way; e.g. a final error result "Stopped: too many tool rounds in one turn" for the open calls, then agent answers once without tools, or done.
   - Keep 1000 for jobs.
   - Test: agentLoopStopsAfterMaxRounds.
4. A model or tool exception mid-job loses all record of the job (no try/catch around model.chat in investigate/plan/review/implement; Agent.run then throws and the conversation keeps nothing).
   - Catch RuntimeException around the model calls (and tool runs) in coding nodes. Store the reason in a stop-reason key (new CODING_STOP_REASON = "codingStopReason", or reuse an existing one) and route to report via one shared "stopped" route per router. Report: "Stopped: model error: … Steps 1 to k are done."
   - Fold the existing step-limit and plan-error endings into the same shared path if it simplifies things.
   - Test: modelErrorMidImplementEndsWithReport.
5. The plan dialog and CLI show revised plans without done marks or the revision reason (PlanDialog.java:41, ConsoleApprover.java:56 use toMarkdown(0); Approver.reviewPlan(CodingPlan)).
   - Change the Approver callback to take a small record, e.g. PlanReview(CodingPlan plan, int stepsDone, String revisionReason, String userComment, boolean revision). Render with toMarkdown(stepsDone) and show a short "Revised plan: <reason>" line when it's a revision. Note the revision reason is currently cleared when implement starts; read it before that.
   - Update ChatWindow/PlanDialog/ConsoleApprover/Agent and the tests.
   - Also treat a revision with exactly codingStep steps when NOT in fix mode (codingVerifyOutput blank) as a plan error: a pending step vanished. Before verify ran this is always a plan error. Update the planApproval→verify route accordingly (it may become unreachable; remove it if so and update docs).
   - Test: revisionThatDropsThePendingStepIsAPlanError.

## Minor (do these too; small)
6. CodingPlan.covers: an IllegalArgumentException from readTree(null) escapes. Fail closed: null or bad arguments → false. Test with null arguments.
7. CodingPrompts PLAN/REVIEW: add a rule forbidding long-running or never-ending commands (dev servers, watch modes, e.g. `npm run dev`, `gradle bootRun`, `vite`). Commands must terminate.
8. Plan command matching: a newline is a statement separator. Normalize only horizontal whitespace (spaces/tabs) for plan matching, so "a\nb" never matches "a b". Leave TrustedCommands.isTrusted behaviour unchanged unless it's trivially shared and safe. Test.
9. Report "## Plan" shows the last APPROVED plan (with done/skipped marks). If the job ended on a denied or failed revision, show the approved plan, plus the rejected draft labelled as such (or just the label). Keep it simple.
10. Docs:
    - Use `~/chat/environment.json` consistently.
    - Add the copyFile rule: the source must be inside the folder.
    - Fix the planApproval verify-route condition, or remove the route per #5.
    - Fix the ASCII diagram so the END line isn't grouped with coding (README + AgentGraph Javadoc).
    - Note that the "Verifying" progress event fires after the verify commands finish.

## Binding rules
- Use LangGraph4j mechanisms (state keys, routers, nodes); no side channels. Match the project's extensive explanatory comments.
- Not a git repo: no commits. Never launch the app or take screenshots.
- Focused tests use a leading colon: `.\gradlew.bat :test --tests net.rka.ai.agent.CodingGraphTest`.
- Do not dispatch subagents.
