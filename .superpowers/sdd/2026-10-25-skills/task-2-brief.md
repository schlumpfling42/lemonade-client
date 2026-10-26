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

### Task 2: Skill files — model, templates, loader and validator

**Files:**
- Create: `src/main/java/net/rka/ai/agent/skills/SkillFile.java`, `Templates.java`, `SkillValidator.java`, `SkillLoader.java`, `NodeKind.java`
- Test: `src/test/java/net/rka/ai/agent/skills/SkillLoaderTest.java`, `TemplatesTest.java`

**Interfaces:**
- Produces:
  - `record SkillFile(String name, String tool, String description, List<Input> inputs, Limits limits, String start, List<NodeSpec> nodes)`; `record Input(String name, String description, boolean required)`; `record Limits(int maxSteps, int maxToolRounds, Map<String, Integer> extra)` (extra holds kind-specific limits such as `maxStepTurns`); `record NodeSpec(String id, String kind, Map<String, Object> fields, String next, Map<String, String> on)`. `static SkillFile parse(String json)` (Jackson; unknown top-level fields are errors).
  - `String toolName()` (`tool` if set, else `skill_` + name with `-` → `_`).
  - `interface NodeKind { String name(); Set<String> outcomes(NodeSpec node); List<String> check(NodeSpec node, SkillContext context); Set<String> writes(NodeSpec node); Set<String> reads(NodeSpec node); }` — `check` returns field errors; `writes`/`reads` are variable names for the data-flow check.
  - `final class Templates { static String render(String template, Map<String, Object> vars); static Set<String> references(String template); }` — `{{a}}`, `{{a.b}}` (JSON object field), `{{input.x}}`; missing → "".
  - `final class SkillValidator { SkillValidator(Map<String, NodeKind> kinds, ToolRegistry tools); List<String> validate(SkillFile skill, boolean builtIn); }` — rules from the spec "Loading and validation" (names, file name, unique ids, start, every non-`stopped` outcome mapped to an existing id, reachability, every path reaches a node with no outcomes, references defined on every path, tools exist and aren't forbidden, limits in range, `tool` only for built-ins, no id collisions).
  - `final class SkillLoader { SkillLoader(Path userDir, List<String> builtInNames, SkillValidator validator); Loaded load(); }`; `record Loaded(List<SkillFile> skills, Map<String, List<String>> errors)` (errors by file name; a user override that is invalid falls back to the built-in, with its errors listed).
- In this task the kinds map holds the generic kinds' *specs* (`model`, `askUser`, `branch`, `set`, `finish`) with outcomes/reads/writes/check only; Task 3 adds their graph behavior.

- [ ] **Step 1: Failing tests** — `TemplatesTest`: `rendersVariablesFieldsAndInputs`, `missingIsEmpty`, `referencesAreListed`. `SkillLoaderTest` (temp dir, a `ToolRegistry.of(new FileTools(), new TerminalTools(), new CodingTools())`): `loadsAValidSkill` (the spec's release-notes example), one test per rule (`unknownKind`, `danglingTarget`, `unmappedOutcome`, `unreachableNode`, `pathWithoutFinish`, `undefinedVariable`, `unknownTool`, `forbiddenTool` for `startCodingJob`/`runRemoteCommand`, `badName`, `fileNameMismatch`, `toolFieldOnlyForBuiltIns`), and `oneBadFileDoesNotStopTheOthers` (two good + one broken file → 2 skills, 1 error entry naming the file and the problem).
- [ ] **Step 2: Run** `.\gradlew.bat :test --tests "net.rka.ai.agent.skills.*"` — FAIL.
- [ ] **Step 3: Implement.** Validation errors are full sentences naming the node id and field ("node `draft`: `on.revise` points to `drafts`, which doesn't exist").
- [ ] **Step 4: Run** the tests and `.\gradlew.bat build` — PASS.

---

