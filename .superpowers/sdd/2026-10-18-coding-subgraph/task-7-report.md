# Task 7 report: Live progress via graph streaming

Status: DONE. No commits (not a git repo).

## Implemented
- `Progress.java`: `record Progress(phase, step, steps, detail)`, nested `ProgressListener`, `NONE`.
- `Agent.java`: new 6-arg `run(..., ProgressListener)`; the 5-arg overload delegates with `NONE`.
  Both the initial run and every resume use `graph.stream(...)` through `streamToPause`, which reports
  each `coding-*` NodeOutput and returns the last output's state. The pause loop, `Pause` no-progress
  check and tool approvals are unchanged.
- `progressOf(node, state)` maps nodes to phases; `planApproval` is not reported. The state in a
  NodeOutput is the state after the node ran, so verify already has the attempt incremented
  ("attempt 1/3", from `CodingGraph.MAX_VERIFY_ATTEMPTS`); implement uses min(codingStep+1, plan steps).
  `detail` = command of the last `runCommand` in the job's messages.
- A listener that throws is logged (System.Logger, WARNING) and ignored.

## Tests (CodingJobTest)
- RED: `.\gradlew.bat :test --tests net.rka.ai.agent.CodingJobTest` -> compile error, no `run` overload with a listener.
- GREEN: same command -> BUILD SUCCESSFUL (all tests incl. new `progressReportsPhasesInOrder`,
  `failingProgressListenerDoesNotBreakTheRun`, existing tool-approval and no-progress tests).
- `.\gradlew.bat build` -> BUILD SUCCESSFUL.

## Notes
- Docs (agent-graph.md, README) untouched as instructed; only the Agent class Javadoc diagram line says `graph.stream`.
- Verify/implement progress also fill step/steps (attempt/max, step/plan size).
