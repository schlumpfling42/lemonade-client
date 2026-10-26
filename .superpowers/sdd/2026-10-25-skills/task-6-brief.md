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

### Task 6: The skill builder

**Files:**
- Create: `src/main/java/net/rka/ai/agent/SkillBuilderGraph.java` (annotated `@Graph(name = "skillBuilder")`, nodes `tools`, `draft`, `validate`, `review` (pause), `save`, `finish`), `src/main/java/net/rka/ai/tools/SkillTools.java` (`buildSkill(description)`, never runs — routed), `src/main/java/net/rka/ai/agent/skills/SkillDiagram.java` (`static String outline(SkillFile)` text outline; `static String markdown(SkillFile)` with Mermaid, prompts, tools and which need approval)
- Modify: `AgentGraph.java` (`@Subgraph("skillBuilder")`, route for `buildSkill`, interrupt before `skillBuilder-review`; State keys `SKILL_DRAFT = "skillDraft"` (JSON text), `SKILL_DRAFT_ERRORS = "skillDraftErrors"`, `SKILL_DECISION = "skillDecision"` (`save`/`revise`/`cancel`), `SKILL_COMMENT = "skillComment"`), `Agent.java` (pause → `approver.reviewSkill(SkillDraft)` → `SKILL_DECISION`/`SKILL_COMMENT`), `Approver.java` (`default SkillDecision reviewSkill(SkillDraft d)` → cancel), `SkillLoader` (writes `<name>.md` with `SkillDiagram.markdown` for each loaded skill)
- Test: `src/test/java/net/rka/ai/agent/SkillBuilderTest.java`

**Interfaces:**
- Produces: `record SkillDraft(SkillFile skill, String json, String outline, List<String> toolsNeedingApproval, boolean replaces)`; `record SkillDecision(Kind kind, String comment)` with `Kind { SAVE, REVISE, CANCEL }`. Attempts: 3 invalid drafts per review round; Revise starts a new round. Save writes `~/chat/skills/<name>.json` (replacing only when the draft says `replaces` and the user saved), then `Agent` reloads skills after the turn.

- [ ] **Step 1: Failing tests:** `invalidDraftGetsErrorsThenValidDraftIsReviewed`, `saveWritesTheFileAndReloads`, `cancelWritesNothing`, `threeInvalidDraftsFinishWithErrors`, `reviseStartsANewRound`, `changingAnExistingSkillStartsFromItsFile`.
- [ ] **Step 2–4:** run (FAIL) → implement → `.\gradlew.bat build` (PASS).

---

