# Task 8: Docs - report

Status: DONE. No commits (not a git repo). `.\gradlew.bat build` -> BUILD SUCCESSFUL (28 s, 7 tasks executed).

## What changed
- `docs/agent-graph.md`: rewritten. Mermaid diagram now has the annotated subgraphs `trusted`, `approval`, `skillBuilder` (with its review pause), the generic `skill-*` subgraph (start, the file's nodes, askUser pause, toolStep, toolApproval pause, stopped, finish) and `skill-coding` from coding.json (every node, next/on, planApproval pause, stopped). Node tables for agent/route/tools/trusted/approval/skillBuilder/generic skill nodes/generic kinds/coding skill; "Safe, trusted and guarded calls" (incl. skill transcripts in `webContentInTurn`, rules inside skills, forbidden tools, plan check in coding); state keys (`skill*`, builder keys, coding keys); a "Skills" section (loader, built-ins, overrides, tool, builder, `@Subgraphs`, pauses, limits, recursion limit, reload, hidden output by kind); "Keeping this diagram current" updated (no CodingGraphDefinition, adds SkillBuilderGraphDefinition and the coding.json check).
- `README.md`: top diagram (skill-coding, skillBuilder, skill-<name>); "Skills" section (folder, Settings/reload, generated pages, example file, top-level fields, node kinds table, wiring, branch conditions, templates, validator, safety rules, limits incl. coding cap 1000, builder and review, built-in `coding` and overriding it); Coding jobs intro and "never available" line; Settings note; Layout table (`agent`, new `agent.skills` row).
- `AgentGraph` Javadoc: skill-coding line now shows investigateTools/implementTools and the revise/stopped routes; note on `skill-<name>` inlining and `@Subgraphs`. (The rest of the diagram was already right.)
- `CLAUDE.md`: graph-diagram section mentions `@Subgraphs` and SkillBuilderGraph definition; new "Keep the skills docs current" section (what to update, how to check against coding.json and `SkillGraphBuilder`, how to re-validate the README example).

## Verification
- README example (the `### The file` JSON block, release-notes with `limits` and `on.stopped` added): extracted by a throwaway `ReadmeExampleTmpTest` (parsed with `SkillFile.parse`, `SkillValidator(GenericKinds.all(), ToolRegistry.defaultTools()).validate(skill, false)`) -> 0 problems, 8 nodes. Test deleted afterwards (confirmed no `*Tmp*` file in src/test).
- Final `.\gradlew.bat build` -> BUILD SUCCESSFUL.
- Mermaid not rendered (no renderer run); checked by reading only.

## Checklist: outlines and coding.json -> docs/agent-graph.md (diagram line numbers)
AgentGraphDefinition:
- START -> memory: l.18. memory -> agent: l.19.
- tools -> agent: l.144. trusted -> agent: l.145 (execute), l.146 (verify "done"). approval -> agent: l.147, l.148.
- skillBuilder -> agent: l.142.
- agent route done -> END: l.21. approval: l.27. trusted: l.26. tools: l.25. skillBuilder: l.23. skill-*: l.24 (generic) and l.22 (skill-coding, a skill-* route).
- `approval = subgraph`, `trusted = subgraph`, `skillBuilder = subgraph`, `skill-* = subgraphs skills()`: subgraph blocks + node table rows + "Skills" section (`@Subgraphs`).
- Return edge of every skill subgraph (generated `skill-<name> -> agent`): l.140, l.141.
SkillBuilderGraphDefinition:
- START -> tools: entered by l.23 (agent route skillBuilder -> builder_tools). tools -> draft: l.52.
- draft: validate l.53, stopped l.54. validate: review l.55 (+pause l.56), retry l.57, failed l.58. review: save l.59, revise l.60, cancel l.61.
- save -> finish: l.62. finish -> END: l.142 (subgraph end, then agent).
ApprovalGraphDefinition: START -> review via pause l.41; review: execute l.38, done l.148; execute -> END via l.147.
TrustedGraphDefinition: START -> verify l.26; verify: execute l.32, done l.146; execute -> END via l.145.
coding.json (start = environment; the generic `start` node precedes it, l.103):
- environment next investigate l.104.
- investigate: tools l.105, plan l.106, stopped l.107.
- investigateTools: investigate l.108, stopped l.109.
- plan: review l.110, stopped l.111.
- review: approval l.112 (pause l.113), retry l.114, stopped l.115.
- planApproval: approve l.116, revise l.117, deny l.118 (no stopped mapping in the file; stated in the text).
- implement: tools l.119, next l.120, verify l.121, stopped l.122.
- implementTools: implement l.123, revise l.124, stopped l.125.
- verify: passed l.126, fix l.127, failing l.128, stopped l.129.
- designReview: fix l.130, done l.131, stopped l.132.
- report: terminal l.133. Automatic stopped (maxSteps / failure without report) l.134-135.
Generic skill (SkillGraphBuilder): start l.75, askUser pause l.76-77, toolStep l.78-79, toolApproval pause l.80-82, stopped l.83, finish l.84, ends l.138-140.

## Notes / concerns
- Documented as built, where it differs from the spec: `branch` uses `cases` + `otherwise`; `set` uses `{increment}`/`{append}`; node ids may not contain `-`; coding kinds take only `prompt`; `maxSteps` cap 200 (1000 with coding kinds); extra limits 1-100; the builder can't yet dry-run or delete a skill (documented in README).
- Non-obvious doc choice: coding's `toolStep`/`toolApproval` exist but are unused (stated); `coding_stopped` is drawn with one dotted edge from `investigate` that stands for "any node over maxSteps".
- One claim I took from code reading, not from running: skill model replies/tool results are shown live but not saved (from `Agent.MessageFeed`).
