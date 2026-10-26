# Skills Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Skills — JSON files that define subgraphs from fixed node kinds — are loaded from `~/chat/skills/` (plus built-ins), added to the agent graph and started by the model through a tool; the coding job becomes the built-in skill `coding`; a skill builder drafts, validates and saves new skills after the user's review.

**Architecture:** A small `graph-processor` extension (`@Subgraphs`) lets `AgentGraph` add a map of runtime-built subgraphs and their routes to the generated graph. `SkillLoader` parses and validates skill files into `SkillFile` records; `SkillGraphBuilder` turns one into a `StateGraph<AgentGraph.State>` from **node kinds** (generic: `model`, `askUser`, `branch`, `set`, `finish`; coding: `coding.*`, implemented by the former `CodingGraph` node methods). Approval and trusted stay annotated `@Subgraph`s. The builder is a Java subgraph `skillBuilder`.

**Tech Stack:** Java 27, LangChain4j 1.22, LangGraph4j 1.9.5, project annotations + `graph-processor`, Jackson, JavaFX 27, JUnit 6.

**Spec:** `docs/superpowers/specs/2026-10-25-skills-design.md`

## Global Constraints

- Use LangGraph4j mechanisms (state keys, nodes, routers, subgraphs, interrupts, streaming); no side channels (CLAUDE.md, memory "use the graph"). Skills are real subgraphs in the compiled graph.
- No code in skill files: only the node kinds the app provides. A file can only narrow what a skill may do, never widen it.
- Every tool call from a skill follows the main graph's rules (`ToolRegistry.needsApproval` / `isTrusted`, web content, trusted folder); guarded calls pause at the skill's `toolApproval` node and use the existing `Approver.review`.
- Never available in skills: `startCodingJob`, `skill_*`, `buildSkill`, `sshHosts`, `runRemoteCommand`. Memory tools only if listed.
- One bad skill file never stops the app or other skills; invalid files are listed with their errors.
- Skill folder: `Settings.globalFile().resolveSibling("skills")` (`~/chat/skills`); built-ins in resources `net/rka/ai/agent/skills/<name>.json`; a user file with a built-in's name replaces it (invalid → built-in used, error listed).
- File name must equal `name` + `.json`; `name` matches `[a-z][a-z0-9-]*`; tool `skill_<name with - → _>` unless a built-in sets `"tool"`; node ids `skill-<name>-<node id>`; tracing names `skill/<name>/<node id>`.
- Limits: `maxSteps` default 40 (cap 200), `maxToolRounds` default 8; coding limits move into `coding.json`: `maxInvestigateRounds` 6, `maxStepTurns` 15, `maxVerifyAttempts` 3, `maxDesignRounds` 2, `maxPlanAttempts` 2.
- `askUser` dismissed / failing approver / default `Approver` → the skill finishes with an error result ("cancelled by the user").
- File tools refuse paths inside `~/chat/skills`; skill files are written only by the builder's Save.
- Keep `docs/agent-graph.md`, the README diagram and the `AgentGraph` Javadoc in sync.
- Not a git repository: tasks end with `.\gradlew.bat build` → `BUILD SUCCESSFUL` (focused tests: `.\gradlew.bat :test --tests <class>`; processor tests: `.\gradlew.bat :graph-processor:test`).

## Review Focus

1. A skill file lists a tool that needs approval (`writeFile`, `runCommand`) and the call isn't trusted — it must pause for approval, never run (Task 4 `guardedSkillCallPausesForApproval`).
2. A skill's model calls a tool the node didn't list (or a forbidden one like `startCodingJob`) — refused with an error result, never run (Task 3 `unlistedToolIsRefused`).
3. A skill loops (branch back forever) — stops at `maxSteps` with an error result and the conversation keeps the call answered (Task 3 `maxStepsStopsALoop`).
4. A user `coding.json` that skips plan approval (wires `coding.implement` straight after `coding.review`) — rejected by the validator; the built-in is used (Task 5 `codingWithoutPlanApprovalIsInvalid`).
5. A skill file with a typo (dangling `on` target, unknown kind, undefined `{{var}}`) next to good files — the good ones load, the bad one is listed with a readable error (Task 2 `oneBadFileDoesNotStopTheOthers`).

---

### Task 3: Generic node kinds and `SkillGraphBuilder`

**Files:**
- Create: `src/main/java/net/rka/ai/agent/skills/SkillGraphBuilder.java`, `GenericKinds.java` (model, askUser, branch, set, finish + built-in `toolStep`, `toolApproval`)
- Modify: `src/main/java/net/rka/ai/agent/AgentGraph.java` (State keys + getters only: `SKILL_NAME = "skillName"`, `SKILL_CALL_ID = "skillCallId"`, `SKILL_MESSAGES = "skillMessages"` (appenderWithDuplicate, in `SCHEMA`), `SKILL_START = "skillStart"`, `SKILL_VARS = "skillVars"` (JSON object string), `SKILL_NODE = "skillNode"`, `SKILL_STEPS = "skillSteps"`, `SKILL_TOOL_ROUNDS = "skillToolRounds"`, `SKILL_ANSWER = "skillAnswer"`, `SKILL_RESULT = "skillResult"`, `SKILL_STOP_REASON = "skillStopReason"`)
- Test: `src/test/java/net/rka/ai/agent/skills/SkillGraphTest.java`

**Interfaces:**
- Consumes: Task 2's `SkillFile`, `NodeKind`, `Templates`; `ToolCallRunner`, `ToolRegistry`, `NodeTracing`, `State.unansweredToolCalls()` pattern.
- Produces:
  - `NodeKind` gains `NodeActionWithConfig<State> action(NodeSpec node, SkillRuntime runtime)` and `String route(NodeSpec node, State state)` (returns an outcome).
  - `record SkillRuntime(SkillFile skill, ChatModel model, ToolCallRunner toolCalls, NodeTracing tracing, Map<String, NodeKind> kinds)`.
  - `final class SkillGraphBuilder { static StateGraph<State> build(SkillRuntime runtime) throws GraphStateException; static Set<String> pauseNodes(SkillFile skill); }` — node ids inside the subgraph are the file's ids plus `start` (resets the `skill*` keys, stores goal/call id/inputs from the `startCodingJob`-style call found by `toolName()`, answers other calls of the same reply with "Not run: a skill started; ask again afterwards"), `toolStep`, `toolApproval` (pauses happen *before* it and before each `askUser` node; `pauseNodes` lists them), and an automatic `stopped` finish. Every finish answers the start call in `MESSAGES` with a `ToolExecutionResultMessage` (`isError` from the node's `error`).
  - `model`: system prompt = skill description + fixed preamble; user prompt = rendered `prompt`; offers only listed tools (minus forbidden ones); calls → `toolStep` → back to the node, up to `maxToolRounds`, then one call without tools; text → `output` var (`format: "json"` parses, one retry). `toolStep` runs calls the node listed when allowed by `ToolRegistry` rules (needs `trustedCommands`, web content of turn + `skillMessages`), routes to `toolApproval` when any needs approval (the approval node then runs approved ones, like `ApprovalGraph.execute`), refuses unlisted ones with an error result.
  - `askUser`: pause before; on resume reads `SKILL_ANSWER` (`{choice, comment}`); missing/blank → `stopped` with "cancelled by the user".
  - Every node increments `SKILL_STEPS`; over `maxSteps` → `stopped`.

- [ ] **Step 1: Failing tests** in `SkillGraphTest` (compile `SkillGraphBuilder.build` result directly with `MemorySaver`, `interruptBefore(pauseNodes)`, scripted fake `ChatModel` as in `CodingGraphTest`): `modelBranchFinishRuns` (output var drives `branch`, finish answers the call with the rendered result), `toolsRunAndComeBack` (`readFile` listed → runs, model sees the result), `unlistedToolIsRefused`, `forbiddenToolIsNeverOffered`, `askUserPausesAndResumes` (choice routes via `on`), `dismissedAskUserFinishesWithError`, `maxStepsStopsALoop`, `modelErrorFinishesWithError`, `otherCallsInTheStartingReplyAreRefused`.
- [ ] **Step 2: Run** `.\gradlew.bat :test --tests net.rka.ai.agent.skills.SkillGraphTest` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the test class and `.\gradlew.bat build` — PASS.

---

