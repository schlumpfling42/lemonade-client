# Final-review fix wave: report

Status: DONE. No commits (not a git repo). Full `.\gradlew.bat build` is BUILD SUCCESSFUL: 239 tests, 0 failures, 0 errors, 0 skipped.

## Item by item

### 1. A valid skill could stop the app from starting
- **Validator** (`SkillValidator`): node ids now match `[A-Za-z][A-Za-z0-9_]*`, so they must start with a letter. That rules out `__START__`, `__END__` and anything else starting with `_`, which LangGraph4j's `addNode` would reject. Input names keep the old identifier rule (`INPUT_NAME`). The error message says "starting with a letter (names like `__END__` are LangGraph4j's own)". I updated the class Javadoc, and the builder's FORMAT text now says "starting with a letter".
- **Fault isolation**:
  - New `AgentGraph.buildable(Loaded, model, toolCalls, tracing, kinds)` builds each skill's subgraph once inside a try. A skill that throws (`GraphStateException` or `RuntimeException`) is left out through the new `Loaded.without(Map)`. Its error, "The skill can't be added to the agent graph: …", is listed under `<name>.json`.
  - `Agent.compile(loaded)` has three stages:
    1. Compile with `buildable(loaded)`.
    2. On any failure, compile with `buildable(skillLoader.builtInsOnly())`. `Loaded.fallback(...)` lists "…; only the built-in skills are used." under every file it leaves out, including a user file that replaces a built-in, so `origin()` says built-in.
    3. If that fails too, compile with no skills ("…; no skills are used.").
  - Only a graph that fails without any skill throws. Routes, pauses, the offered tools and the node index all come from the filtered `Loaded`, so they always match the subgraphs that exist.
  - `reloadSkills()` now returns the status that is actually in use (`compiled.skills()`).
- **New in SkillLoader**: `builtInsOnly()` and `current()`, both through a private `load(userFiles, writeDiagrams)`. `addErrors` skips empty lists.
- **Tests**:
  - `SkillGraphTest.nodeIdsMustStartWithALetter` covers `__END__`, `__START__` and `_private`.
  - `SkillJobTest.aSkillThatFailsToBuildIsLeftOutAndListed` injects a kind whose `action()` throws at build time. The app starts, the error is listed under brittle.json, the other skill works, the broken skill's tool isn't offered, and a reload lists it again.
  - `SkillJobTest.aGraphThatDoesNotCompileFallsBackToTheBuiltIns` uses a kind that builds once, then throws during the real compile. Only `coding` is used, the error is listed under greet.json and brittle.json, the origin is BUILT_IN, and `startCodingJob` is offered.
  - `SkillLoaderTest.builtInsOnlyAndCurrentAndFallbacks`.

### 2. Builder prompt (SkillBuilderGraph)
- **Change requests only**: the new `asksToChange(text, name)` looks for a change verb (change, edit, update, fix, modify, adjust, improve, extend, revise, rework, rename) followed, within three words, by the skill name as a whole word. Only then is the skill's file included. Plain `mentions` was removed.
- **Coding reference**: when an included source has a `"kind": "coding.…"` node, the request appends the new `CodingKinds.builderReference()`. It contains:
  - a table built from the kinds themselves: kind, `prompt*`, outcomes, and the limit each kind reads;
  - the rule that `maxSteps` may be 1 to 1000 (`SkillValidator.MAX_CODING_STEPS`), keep the current value and never lower it unless asked;
  - kind limits 1 to 100;
  - the pinned tool: keep `"tool"` exactly as in the current file or leave it out;
  - the environment and planApproval wiring rules.
- **Bounded drafts**: `draft` now sends the instructions, the first transcript message (the request with the format reference), and then only the latest AI draft and what follows it (its problems or the Revise comment). The transcript in state is unchanged.
- **Tests** (SkillBuilderTest):
  - `aMentionOfASkillIsNotARequestToChangeIt`: "a skill that reviews my coding style" does not pull in coding.json or the coding kinds. Also checks `asksToChange` on positive and negative cases.
  - `replacingABuiltInIsShownInTheReview` gained assertions: the request contains "## Coding kinds", "coding.verify", "passed, fix, failing", "maxStepTurns", "maxVerifyAttempts", "1 to 1000" and "tool is pinned".
  - `eachDraftRequestCarriesOnlyTheLatestDraft`: after 2 Revise rounds, the third request contains "# How a skill file looks" exactly once, has the latest draft and comment, has neither the first draft nor the first comment, and has 4 messages.

### 3. A dismissed askUser always ends the skill through the automatic `stopped`
- In `SkillGraphBuilder.next`, a stop reason equal to `GenericKinds.CANCELLED` returns `STOPPED` before the node's `on.stopped` is looked at. Real errors, including an answer that isn't one of the choices, still use `on.stopped`. The class Javadoc and text diagram are updated.
- **Test**: `SkillGraphTest.dismissedAskUserEndsTheSkillEvenWithItsOwnStoppedRoute` uses askUser with `on.stopped` pointing to itself. After a dismissal the answer is "Stopped: cancelled by the user." as an error, and there is exactly one model request.
- **RED evidence**: with the new line commented out, the test failed at SkillGraphTest.java:156 with `AssertionError` (no answer to s1: the skill had paused before `check` again). GREEN after restoring it.

### 4. ChatWindow labels and chip ids
- New `ProgressListener.onJobMessage(message, codingTranscript, source)`. By default it calls `onMessage(message, true)`, so the CLI and older tests keep working.
- `Agent.MessageFeed` sends coding-transcript messages with `codingTranscript = true` and skill-transcript messages with `false`. The `source` is the skill of the node that just ran (from the node index), `Progress.SKILL_BUILDER` for builder nodes, or `state.skillName()` otherwise.
- ChatWindow overrides `onJobMessage`:
  - Notes say "Coding job" for the coding transcript and the skill's name for other skills ("<name> · thinking" for thinking).
  - Chip keys use the prefix `coding:` or `skill:` (`CODING_IDS` / `SKILL_IDS`). The conversation's chips stay unprefixed.
  - `showRunningOutput` checks `coding:`, then `skill:`, then the bare id, and uses the first chip that has no result yet.
- **Test**: `SkillJobTest.progressAndMessagesNameTheSkillNodes` asserts `codingTranscript == false` and `source == "greet"`. The UI itself was not launched (per the rules).

### 5. SkillReviewDialog
- `setDefaultButton(false)` on every button after `setAll` (both OK_DONE and YES are default-button types in JavaFX), so Enter does nothing. Javadoc updated.
- `SkillDraft.toMarkdown()` gained a `limits()` line, e.g. "**Limits:** maxSteps 600 · maxToolRounds 8 · maxDesignRounds 2 · …", with the extra limits sorted. The dialog and the CLI both show it.
- **Test**: in `SkillBuilderTest.replacingABuiltInIsShownInTheReview`, the markdown contains "**Limits:** maxSteps 600 · maxToolRounds 8" and "maxVerifyAttempts 3".

### 6. "step n/max" for generic skills
- `Agent.progressOf` for a non-coding skill node gives the phase `<skill> · <node> · step n/max`, with `step = skillSteps` and `steps = maxSteps`.
- **Test**: `SkillJobTest.progressAndMessagesNameTheSkillNodes` expects "greet · hello · step 1/40" (via `SkillFile.DEFAULT_MAX_STEPS`).

### 7. SettingsDialog wording
- New static `SettingsDialog.errorText(file, name, problems, builtInUsed)`. When the file's name is a built-in (new `Agent.isBuiltInSkill`) and that skill is in use, it reads "Your coding.json can't be used:\n- …\nThe built-in coding skill is used." Otherwise it reads "<file> is not used:\n- …".
- No test: this is UI code and the brief names none.

### 8. The builder checks the folder as it is at validate time
- New `SkillLoader.check(SkillFile)` checks against `current()`, which loads without writing diagrams. `SkillBuilderGraph.validate` uses it.
- I removed SkillBuilderGraph's `loaded` field and constructor parameter. `AgentGraph.skillBuilder()` was updated to match.
- **Test**: `SkillBuilderTest.aSkillSavedDuringTheTurnCountsForTheIdCheck`. While the first draft is under review, `greet-hello.json` appears in the folder. After Revise, the same draft is refused with "already taken by the skill `greet-hello`", and only the renamed draft is shown.

### 9. ConsoleApprover
- `"\s+"` became `"\\s+"`, with a comment: Java's `\s` escape is a single space, not the regex whitespace class.
- **Test**: `ConsoleApproverTest.askUserReadsAChoiceAndComment` adds `"3\tnext time"` and `"c\ttabbed"`.

### 10. Javadoc and docs wording
- `AgentGraph` Javadoc: `<->` is now `&lt;-&gt;` (both places).
- docs/agent-graph.md: "**Always** (`g` in the CLI) to trust".

### 11. Verify's run-time approval check on its own
- **Test**: `CodingGraphTest.verifyRefusesAPlanThatWasNotApproved`.
  - It uses `withPlanStepAroundTheApproval()` with review2's `approval` wired to `verify`. The validator rejects that file (asserted), and it is built anyway.
  - After the approved plan, implement makes an unplanned call, so the job goes to plan2 and review2, which write a draft with another verify command. verify then refuses it.
  - Result: `codingAttempts == 0`, `codingVerifyOutput == ""`, the report starts with "Stopped: the current plan is not the one the user approved", the job's answer is an error, and every scripted answer was used.

## Docs updated
- **docs/agent-graph.md**:
  - the Mermaid edge label for `stopped` (an error uses on.stopped; a cancelled question or maxSteps always goes to the automatic stopped);
  - the rows for `skillBuilder/tools`, `/draft`, `/validate` and `/review`, and the router text in the `skill-<name>-<id>` row;
  - the generic kinds intro and the `askUser` row;
  - the Skills section: the Settings wording, a new "A valid file that can't be built" bullet, the id rule, Reload, and "What is shown" (step n/max, `onJobMessage`, labels and chip prefixes);
  - the `g` in the CLI wording.
- **README.md**: the Skills folder and Settings text (fault isolation, wording), the node id rule, the askUser row, the builder review (limits, no default button), the change-by-name rule, the folder-time checks and bounded requests, the coding reference, and skill notes and progress.
- **CLAUDE.md**: the skills-docs trigger list now includes `AgentGraph.buildable` / `Agent.compile`.
- **Graph shape**: no `@Node`, `@Edge`, `@Router` route or `@Subgraph` changed, so the generated outlines and the text diagrams' structure are the same. The routing change (a dismissed askUser always goes to the automatic stopped) is a router decision, documented above.

## Commands and output
- `.\gradlew.bat :test --tests "net.rka.ai.agent.*" --tests "net.rka.ai.cli.*"`, first run: 202 tests, 1 failed (`dismissedAskUserEndsTheSkillEvenWithItsOwnStoppedRoute`). The cause was a `getState` call on a finished run in my own assertion; I removed that assertion.
- **RED for item 3**: `.\gradlew.bat :test --tests "net.rka.ai.agent.skills.SkillGraphTest"` with the fix commented out gave `dismissedAskUserEndsTheSkillEvenWithItsOwnStoppedRoute() FAILED java.lang.AssertionError at SkillGraphTest.java:156`, 20 tests, 1 failed.
- **RED for item 1**: the same command with the old ID pattern gave `nodeIdsMustStartWithALetter() FAILED AssertionFailedError at SkillGraphTest.java:386`, 20 tests, 1 failed.
- **GREEN**: `.\gradlew.bat :test --tests "net.rka.ai.*"` gave BUILD SUCCESSFUL, 239 tests, 0 failures, 0 errors (counted from build/test-results XML).
- **Full build**: `.\gradlew.bat build` gave BUILD SUCCESSFUL in 28s, with no compiler warnings in the output.

## Files changed
- **Main**:
  - `agent/skills/SkillValidator.java`, `SkillGraphBuilder.java`, `SkillLoader.java`, `CodingKinds.java`
  - `agent/AgentGraph.java`, `Agent.java`, `SkillBuilderGraph.java`, `SkillDraft.java`, `Progress.java`
  - `ui/ChatWindow.java`, `ui/SkillReviewDialog.java`, `ui/SettingsDialog.java`
  - `cli/ConsoleApprover.java`
- **Tests**: `SkillGraphTest`, `SkillLoaderTest`, `SkillJobTest`, `SkillBuilderTest`, `CodingGraphTest`, `ConsoleApproverTest`.
- **Docs**: `docs/agent-graph.md`, `README.md`, `CLAUDE.md`.

## Self-review and concerns
- **Broken replacement is dropped, not swapped**: a user replacement of a built-in that validates but fails to *build* is dropped. The built-in is not put in its place; only the whole-compile fallback brings the built-ins back. This follows the brief ("dropped and its error listed"), but one could argue for falling back to the built-in in that case too.
- **Change-verb matching is a heuristic**: "fix coding style issues" would still pull in coding.json, because "fix" is followed within three words by "coding". The included text still tells the model to keep the name only if it is changing that skill.
- **Expected log noise**: the fallback test logs the expected `SEVERE` compile failure with a stack trace to stderr. Existing tests such as FailedTurnTest already log turn failures the same way.
- **New public surface**: `ProgressListener.onJobMessage` is a new default method. The CLI keeps its `[job]` prefix for skills through the default.
- **UI not run**: ChatWindow, SkillReviewDialog and SettingsDialog compile but were not launched (no UI or screenshots, per the rules).
