# SDD ledger — plan: docs/superpowers/plans/2026-10-25-skills.md
Spec: docs/superpowers/specs/2026-10-25-skills-design.md
Not a git repo: snapshots snap-<task> (src, docs, README, CLAUDE.md, graph-processor/src as gp, graph-annotations/src as ga); review packages via pkg.sh.

## Pre-flight scan
| Rows | Produces / consumes | Finding |
|---|---|---|
| T1→T4 | @Subgraphs annotation → AgentGraph.skills() | consistent |
| T2→T3 | SkillFile/NodeKind/Templates → builder | NodeKind extended in T3 (action/route) — stated |
| T3→T4/T5 | SkillGraphBuilder, SkillRuntime, skill* keys | consistent |
| T4→T5 | Agent pause loop, ToolRegistry skill tools, lookup of node ids | T4 must expose a node-id lookup (skill, node) for LiveChannel/progress; T5 relies on it for coding |
| T5 | removes CodingGraphDefinition; migrates tests | largest task; tests must keep all coding behaviors |
| T6 | builder keys added in T6 | consistent after patch |
| T7 | UI over T4/T6 approver methods | consistent |
| T8 | docs | ok |
Ruling: T4 adds `SkillLoader.Loaded.nodeIndex()` → Map<String inlinedId, record NodeRef(String skill, String node, String kind)> used by pause loop, NodeTracing names, MessageFeed and progress — the spec requires no string splitting — cost if wrong: small API rework in T5.
Ruling: Task 5's coding node kinds may keep reading coding limits via the skill's `limits.extra` map with the defaults from the plan when absent — cost: none.

## Tasks
Task 1: minor (deferred): runtime key equal to a static route `on` silently overwrites it; key = static node name fails only at build
Task 1: minor (carried to Task 4): no test for an empty @Subgraphs map (common case: no skills) and CompiledGraph variant / END returnTo
Task 1: minor (deferred): GraphProcessor/GraphSourceWriter class Javadoc don't mention @Subgraphs
Task 1: complete (review clean) — note: snap-1 app src was corrupted by snap.sh (fixed); Task 1 touched only gp/ga
Task 2: note: GenericKinds.java created in T2 (specs); branch schema cases:[{when,then}] with value/count conditions; set: template | {increment:n} | {append:template}; node ids may not contain '-'; id collisions across skills → later skill skipped; user override of a built-in keeps the built-in's tool; limit caps maxToolRounds 1–50, extra 1–100
Ruling: accept T2 schema choices — `branch` uses `cases` + `otherwise` as an outcome mapped with `on`; `set` uses `"var": {"increment": 1}` — consistent and documented; the spec's sketch was illustrative — cost if wrong: builder prompt/docs follow the implemented schema.
Ruling: Task 3 brief step "Create GenericKinds.java" means extend the existing file.
Task 2: minor (deferred): unknown `extra` limit names accepted silently
Task 2: minor (deferred): built-in and user-override errors merged under one file key
Task 2: fix round 1/5 (6 addressed, 0 open — override as user file w/ pinned tool; DirectoryIteratorException; collision restores built-in; stopped edges don't count as finish; required type; message)
Task 2: complete (review clean after fix round 1) — validate(skill, builtIn, pinnedTool); NodeKind.STOPPED; GenericKinds.all(); SkillValidator.FORBIDDEN_TOOLS/isForbidden/graphIds; SkillLoader(userDir, builtIns, validator) + package-private ctor injecting built-in sources
Task 3: notes for T4/T5: SKILL_ANSWER JSON {"choice","comment"} or "" (dismissed) — Agent writes it on every askUser resume; pauseNodes finds askUser by kind name (T5 adds coding.planApproval); {{environment}} uses EnvironmentInfo.fromSettings() (T5 may inject); webContentInTurn now includes skillMessages (docs in T8); several classes made public across packages
Task 3: minor (deferred): GenericKinds.java ~1050 lines
Ruling (for Task 4): the compiled graph's recursion limit = max(1000, over loaded skills of maxSteps × (2×maxToolRounds + 2) + 100), so a valid skill hits its own maxSteps before LangGraph4j's limit — cost if wrong: a runaway skill runs longer before stopping.
Task 3: minor (deferred): refused unlisted web tool results count as web content (fails closed, spurious approval)
Task 3: minor (deferred): GenericKinds ~1050 lines; extra public visibility
Task 3: fix round 1/5 (4 addressed, 0 open — stopped loop counts steps; terminal answers once; SKILL_ANSWER {node,choice,comment,used}; mixed batch test)
Task 3: complete (review clean after fix round 1) — Agent (T4) must write SKILL_ANSWER as JSON {"node","choice","comment"}
Task 4: minor (carried to Task 5 dispatch): add a dashed skill name test (askUser + guarded call in one run)
Task 4: minor (deferred): no test for reload mid-turn (correct by construction)
Task 4: minor (deferred, resolved by T6): deny message names buildSkill before it exists
Task 4: minor (deferred): findFiles lists paths inside skills folder
Task 4: minor (deferred): skill progress lastCommand may come from an earlier run in the turn
Task 4: note: ChatWindow/ConsoleApprover askUser overrides come in Task 7 (until then askUser cancels)
Task 4: complete (review clean) — Agent holds volatile Compiled(graph, tools, Loaded) swapped by reloadSkills(); Loaded.nodeIndex(); recursion limit per ruling
Ruling: skills that use coding.* kinds may set maxSteps up to 1000 (generic cap stays 200); coding.json uses a value that fits a long job (≥ 600) — the coding kinds carry their own per-step/verify/design limits, and a generic "too many steps" without a coding report would be a regression — cost if wrong: a runaway coding job runs longer before the generic stop.
Ruling: exceptions inside coding steps now end the job through report (via skillStopReason) instead of failing the turn — accepted as an improvement over "keep behavior" (consistent with skills: errors finish with an error result); pinned by a test in fix round 1 — cost if wrong: none (report records it).
Task 5: fix round 1/5 (7 addressed, 0 open — coding maxSteps 1000/600; approved-plan enforced at run time + validator walk; report answers skillCallId + environment-first rule; exception→report test; limit names declared; hidden output by kind; clean test output)
Task 5: minor (deferred): no test of verify's run-time approval check alone
Task 5: minor (deferred): FailedTurnTest stderr noise (langgraph4j ERROR + Turn failed)
Task 5: complete (review clean after fix round 1) — Agent ctor (…, tools, approver, SkillLoader, LiveChannel); CodingGraph.Setup; NodeKind.route(node,state,runtime), pausesBefore(), otherCallsAnswer(), limitNames(); LiveChannel.hiddenWorkOfKind; Agent.skillWork
Task 6: notes: "replaces": true top-level flag stripped before validate/save; .md pages for user skills only; SkillDiagram.markdown(SkillFile, ToolRegistry); builder progress card missing (carry to T7); stale .md for deleted skills; mentioning "coding" pulls coding.json into the draft prompt
Task 6: fix round 1/5 (6 addressed, 0 open — JSON-first parse w/ whole-answer fence; effective tool in review; replacesBuiltIn from loader; .md marker; tmp cleanup; tests)
Task 6: minor (deferred): no forced move-failure test for .tmp cleanup; prose after closing fence not matched
Task 6: complete (review clean after fix round 1) — SkillDraft(skill, json, outline, toolsNeedingApproval, replaces, tool, replacesBuiltIn); SkillDecision; Approver.reviewSkill default cancel; AgentGraph.SKILL_REVIEW_PAUSE; SkillLoader.toolOf/check; SkillDiagram.markdown(SkillFile, ToolRegistry) w/ GENERATED_MARKER
Task 7: minor (final wave): ConsoleApprover "\s+" is the space escape, not whitespace class → use "\s+"
Task 7: minor (deferred): settings-error class re-added on repeated failed reload; empty help label; no test for builder progressOf/Progress.label; stale origin after file changes; import order
Task 7: complete (review clean) — manual UI check pending (user): Enter never answers a skill question; CSS classes exist
Task 8: minor (final wave): raw "<->" in AgentGraph Javadoc → escape; "Always"/`g` wording for CLI in docs
Task 8: complete (review clean)
## Final review (opus): With fixes — Important 1-3; one fix wave
Ruling: the skill review dialog gets no default button (Enter does nothing; Save must be clicked) — saving writes a file that changes the agent's behavior, so it should be a deliberate click; the plan dialog keeps Approve default as before — cost if wrong: one extra click.
Final fix wave: 11/11 addressed (re-review opus); clean rebuild: 239 tests, 0 failures, 0 errors
Final: parked — a user override of a built-in that validates but fails to BUILD is dropped and the built-in isn't restored (coding job missing until the file is fixed) — Ruling: real, contradicts the spec's "invalid → built-in used"; rare after the id fix; surfaced to the user with a recommended small fix.
Final: parked — asksToChange heuristic misses verb-less/quoted requests and catches "fix coding style" — Ruling: acceptable heuristic; cost: the model drafts without the current file.
Final: parked — SettingsDialog Javadoc order; Progress.onMessage codingJob param stale doc — Ruling: cosmetic.
