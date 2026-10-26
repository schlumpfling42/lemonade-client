# Final-review fix wave — skills

Project C:\Data\Projects\langchain (not a git repo). Plan docs/superpowers/plans/2026-10-25-skills.md, spec docs/superpowers/specs/2026-10-25-skills-design.md. Fix ALL items; add the named tests; keep docs (docs/agent-graph.md, README, CLAUDE.md) in sync with any behavior change; finish with `.\gradlew.bat build` → BUILD SUCCESSFUL.

## Important
1. A valid skill can stop the app from starting: node ids matching `[A-Za-z_][A-Za-z0-9_]*` allow `__END__` / `__START__`, which LangGraph4j's addNode rejects; the exception escapes AgentGraph.skills()/compile() → Agent constructor throws → window/CLI don't start (and the builder can save such a file).
   - Validator: node ids must start with a letter (`[A-Za-z][A-Za-z0-9_]*`) and reserve `__START__`/`__END__`-style names; test.
   - Fault isolation: build each skill's subgraph in a try; a skill that fails to build is dropped and its error listed under its file name (status shows it); if the whole compile still fails, compile with the built-ins only (and if even that fails, with no skills), never failing Agent construction because of a skill file. Test: a file that validates but fails to build (e.g. inject a kind whose action throws at build time, or the reserved id case before the validator fix) → app starts, error listed.
2. Builder prompt (SkillBuilderGraph):
   - Include an existing skill's file only when the description asks to change/edit/update/fix that skill by name (e.g. "change the coding skill", "edit release-notes"), not on any mention of the word; test ("a skill that reviews my coding style" does not pull coding.json).
   - When a coding-kind skill source is included, the format text must include the coding kinds, their outcomes and limits (maxStepTurns etc.), the coding maxSteps cap (1000) and that the built-in's tool is pinned (keep "tool" as in the file or omit it); never tell the model to lower coding's maxSteps. Test: a coding draft request contains the coding kinds and "1000".
   - Keep the draft request bounded across Revise rounds: the request carries the format reference + the latest draft + its problems/comment, not every earlier draft (so ContextFittingChatModel never has to cut the reference). Test: after 2 revise rounds the 3rd draft request contains the format reference once and only the latest draft.
3. A dismissed askUser (cancelled) must always end the skill through the automatic `stopped` finish with an error (spec), never follow the node's `on.stopped` back into the skill. Real errors keep using `on.stopped`. Test: askUser with `on.stopped` → itself; dismiss → skill finishes with "cancelled by the user", one model/question round only.

## Minor (do these too)
4. ChatWindow labels skill transcript replies as "Coding job" and uses the `job:` chip id prefix for both: label notes with the running skill's name (coding stays "Coding job"), and key chips per transcript (e.g. `coding:` / `skill:` prefixes) so ids can't collide.
5. SkillReviewDialog: no default button (controller ruling: Enter does nothing; Save must be clicked); show the skill's limits in the review markdown.
6. Skill progress shows "step n/max" for generic skills (Agent.progressOf).
7. SettingsDialog: an invalid override of a built-in reads "your coding.json can't be used: …; the built-in coding skill is used" instead of "is not used".
8. The builder's collision/name checks use the skills folder as it is at validate time (re-run SkillLoader.check against the current files), not only the skills loaded at turn start.
9. ConsoleApprover: `"\s+"` → `"\s+"` (whitespace class); test with a TAB.
10. AgentGraph Javadoc: escape `<->` as `&lt;-&gt;`; docs: CLI wording "Always (`g` in the CLI)".
11. Test verify's run-time approval check alone (CodingGraph.notApproved via verify, built without the validator).

## Binding rules
- LangGraph4j mechanisms only; match the project's explanatory comments; no UI launch/screenshots; no subagents.
- Focused tests use a leading colon: `.\gradlew.bat :test --tests "net.rka.ai.agent.*"`.
