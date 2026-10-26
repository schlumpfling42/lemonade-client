# Task 6 report: the skill builder

Status: DONE_WITH_CONCERNS (concerns at the end). No commits: this is not a git repo. The full build is green.

## What I implemented

- **`SkillBuilderGraph`** (new). An annotated `@Graph(name = "skillBuilder")` with the nodes `tools`, `draft`, `validate`, `review` (the pause), `save` and `finish`. Generated outline:
  ```
  START -> tools;  tools -> draft;  save -> finish;  finish -> END
  draft    -> afterDraft()    {validate: validate, stopped: finish}
  validate -> afterValidate() {review: review, retry: draft, failed: finish}
  review   -> afterReview()   {save: save, revise: draft, cancel: finish}
  ```
  - **tools** (no model). Takes `description` from the `buildSkill` call and answers the reply's other calls with "Not run". It resets the builder keys and starts the builder's transcript (`skillMessages` from `skillStart`) with one request message. The request holds:
    - the file format: top-level fields, never `tool`, `replaces`;
    - the generic node-kind table;
    - the outcome wiring (`next`/`on`, `stopped` implicit, branch `cases [{when, then}]` + `otherwise`, set `{increment: n}`/`{append}`);
    - the templates and the data-flow rule;
    - the list of tools a skill may list, generated from the validator's `ToolRegistry` without the forbidden tools and skill tools, each marked "asks" or "web content";
    - one compact example, `SkillBuilderGraph.EXAMPLE`;
    - the existing skill names, with built-ins marked;
    - the description.

    When the description names an existing skill as a whole word, that skill's current JSON (`SkillLoader.source`) is included as the starting point.
  - **draft**. One model call with no tools: `INSTRUCTIONS` as the system prompt, then the transcript. The answer goes to `skillDraft`. A model error goes to `skillStopReason` and routes to finish.
  - **validate** (no model). `Draft.parse` accepts a code fence, requires one JSON object, and takes a boolean `replaces` out. Then come `SkillFile.parse` and `SkillLoader.check`, which applies the real validator as a user file. A replaced built-in keeps its pinned tool, and the node ids must not collide with other loaded skills. Two name rules are added:
    - a taken name needs `replaces`;
    - `replaces` needs an existing skill to replace.

    If the draft is valid, `skillDraft` is stored normalized and `skillDraftErrors` is cleared. If not, the problems go to `skillDraftErrors` as `<attempt>\t<problem>` lines and are sent back to the model while attempts remain. The limit is 3 attempts per round.
  - **review**. Runs after `Agent` writes `skillDecision`/`skillComment`. On revise it clears the round's errors, which starts a new round of 3, and adds the comment to the transcript. The router goes save → save, revise → draft, and anything else → finish (fail closed).
  - **save**. Writes `<folder>/<name>.json` itself, never through FileTools: it writes `<name>.json.tmp` and then does an atomic move. It refuses to overwrite unless the draft says `replaces`. If there is no folder or IO fails, it sets a stop reason and nothing is saved.
  - **finish**. Answers the `buildSkill` call with one of four outcomes: stopped, saved/replaced (with the tool the agent will offer), still invalid after 3 attempts (with the last problems), or cancelled. Every outcome except a save is `isError`.
- **`AgentGraph`**:
  - `@Subgraph("skillBuilder")` plus `@Edge(skillBuilder → agent)`.
  - A route `skillBuilder`: a `buildSkill` call is checked after `startCodingJob` and before skill tools.
  - `SKILL_BUILDER` and `SKILL_REVIEW_PAUSE = "skillBuilder-review"` constants. The interrupt is added in `compile()`.
  - State keys `SKILL_DRAFT = "skillDraft"`, `SKILL_DRAFT_ERRORS = "skillDraftErrors"`, `SKILL_DECISION = "skillDecision"`, `SKILL_COMMENT = "skillComment"`, with getters.
  - The full constructor now takes the `SkillLoader` instead of the kinds map; the kinds come from `loader.validator().kinds()`.
  - Class Javadoc diagram updated.
- **`Agent`**:
  - In the pause loop, `next == AgentGraph.SKILL_REVIEW_PAUSE` calls `askSkillReview`. That builds a `SkillDraft` with an outline and `toolsNeedingApproval` from the turn's registry, calls `approver.reviewSkill` inside a "human skill review" span, and writes `SKILL_DECISION`/`SKILL_COMMENT`. Null or an exception counts as cancel.
  - On Save, a local `reloadAfterTurn` flag makes the `finally` call `reloadSkills()` after the turn. That also happens after a failed turn, and a reload failure is logged. The turn itself keeps its `Compiled` snapshot, so nothing changes mid-turn (tested).
- **`Approver.reviewSkill(SkillDraft)`**: the default returns `SkillDecision.CANCELLED`.
- **`SkillDecision`**: `(Kind kind, String comment)` with `Kind {SAVE, REVISE, CANCEL}`. **`SkillDraft`**: `(SkillFile skill, String json, String outline, List<String> toolsNeedingApproval, boolean replaces)`. `toMarkdown()` shows name and tool, the replacement line, description, outline, inputs, the steps with their prompts, and the tools with those needing approval. `replacement()` gives "Replaces the built-in coding skill." when the name is in `Agent.BUILT_IN_SKILLS`, otherwise "Replaces your skill `x` (...)".
- **`SkillTools.buildSkill(description)`**: never runs, the graph routes it. It is registered in `ToolRegistry.defaultTools()` and added to `CodingGraph.NOT_IN_JOB`; it was already in `SkillValidator.FORBIDDEN_TOOLS`.
- **`SkillDiagram`** (new, in the skills package):
  - `outline(SkillFile)`: one line per node.
  - `markdown(SkillFile, ToolRegistry)`: a Mermaid flowchart, with `n_` id prefixes because `end` is reserved in Mermaid, followed by `details`.
  - `details(skill, needApproval)`: inputs, steps with prompts, and tools.
  - `tools(skill)` and `toolsNeedingApproval(skill, registry)`.
- **`SkillLoader`**:
  - `load()` writes `<name>.md` (`SkillDiagram.markdown`) next to each loaded **user** skill, only when the text changed; a failure is logged.
  - New methods: `builtInNames()`, `existingNames()` (built-ins plus every `*.json` in the folder, valid or not), `source(name)` (the user file, else the built-in) and `check(skill, loaded)`.
- **`SkillValidator.tools()`**: new accessor. **`LiveChannel.hiddenWork`**: `skillBuilder/draft` shows "Drafting the skill" instead of streaming the JSON.

**.md for built-ins: I chose to skip them.** Pages are written only for skills loaded from the user's folder, and a user `coding.json` that replaces the built-in gets one.

## Tests

`src/test/java/net/rka/ai/agent/SkillBuilderTest.java` runs whole turns through `Agent` with a scripted model, the real loader and validator, and a temp skill folder:
- `invalidDraftGetsErrorsThenValidDraftIsReviewed`: the fenced invalid draft gets the validator's error sent back. The request has the description, the kinds and the tool list, with no `startCodingJob` or `buildSkill` and no tools offered. Only the valid draft is reviewed.
- `saveWritesTheFileAndReloads`: the file is written, the result is not an error and names `skill_greet`, and the turn's last request did *not* offer the new tool. After the turn the skill is loaded, `greet.md` with Mermaid exists, and the next turn offers `skill_greet`.
- `cancelWritesNothing`: the folder stays empty and the result is an error. `Approver.DENY_ALL.reviewSkill` returns cancel.
- `threeInvalidDraftsFinishWithErrors`: three invalid drafts, the last one not JSON, give an error result with "3 attempts" and the last problem. Nothing is reviewed or written, and there are exactly 5 model requests.
- `reviseStartsANewRound`: invalid, invalid, valid, then Revise("make it louder"), then invalid, invalid, valid, then Save. Both rounds get their full 3 attempts, the comment reaches the model, and the second version is saved.
- `changingAnExistingSkillStartsFromItsFile`: the request includes the existing `greet.json`. A draft without `replaces` gets "The name `greet` is taken". With `replaces` the review shows `replaces=true` and "Replaces your skill", `toolsNeedingApproval == [writeFile]`, and the saved JSON has no `replaces`. The file is replaced and reloaded.
- `replacingABuiltInIsShownInTheReview`: the built-in `coding.json` is the starting point, and the review says "Replaces the built-in coding skill".
- `theExampleInThePromptIsValid`: `EXAMPLE` passes the real validator with `ToolRegistry.defaultTools()`.

### TDD evidence
- RED: `.\gradlew.bat :test --tests net.rka.ai.agent.SkillBuilderTest` failed with `BUILD FAILED`, 22 compile errors such as `package SkillDecision does not exist`. That was expected: the test was written before `SkillDecision`, `SkillDraft`, `SkillTools`, `SkillBuilderGraph` and `Approver.reviewSkill` existed.
- GREEN: the same command gave `BUILD SUCCESSFUL`, with `TEST-net.rka.ai.agent.SkillBuilderTest.xml` showing tests="8" failures="0" errors="0" and empty stdout/stderr.
- Full suite: `.\gradlew.bat build` gave `BUILD SUCCESSFUL`, 222 tests, 0 failures, 0 errors, 0 skipped. The only stderr is FailedTurnTest's deliberate "Connection reset" log, which was there before this task.

## Files changed

- New: `src/main/java/net/rka/ai/agent/SkillBuilderGraph.java`, `SkillDecision.java`, `SkillDraft.java`, `src/main/java/net/rka/ai/agent/skills/SkillDiagram.java`, `src/main/java/net/rka/ai/tools/SkillTools.java`, `src/test/java/net/rka/ai/agent/SkillBuilderTest.java`
- Modified: `AgentGraph.java`, `Agent.java`, `Approver.java`, `CodingGraph.java` (NOT_IN_JOB), `LiveChannel.java`, `skills/SkillLoader.java`, `skills/SkillValidator.java` (`tools()`), `tools/ToolRegistry.java` (registers `SkillTools`)

## Decisions and deviations

- **How the draft says "replaces".** It is a top-level `"replaces": true` in the model's JSON. The builder strips it before parsing, validating and saving, so the file never contains it (SkillFile.parse rejects unknown fields). The validate rules make `SkillDraft.replaces == true` mean that a skill of that name really exists.
- **Signature change.** `SkillDiagram.markdown` takes `(SkillFile, ToolRegistry)` rather than `(SkillFile)`, because "which need approval" requires the registry. The loader passes its validator's registry.
- **Attempts are counted in state.** `skillDraftErrors` holds lines numbered by attempt, so no extra state key was needed. The builder also reuses the existing skill keys `skillCallId`, `skillMessages`/`skillStart` (its transcript, which keeps the Agent's "no progress" check working across Revise rounds), `skillStopReason` and `skillResult`.
- **How a change to an existing skill is detected.** The existing skill's name must appear as a whole word, case-insensitive, in the description. The prompt tells the model to start from that file only if the description asks for a change; otherwise it should pick another name.

## Concerns

1. **Docs not updated.** `docs/agent-graph.md` and the README diagram are untouched, as the brief and rules say (Docs are Task 8). CLAUDE.md normally requires them in the same change, and only the `AgentGraph` and `Agent` Javadoc diagrams are updated here. Task 8 must add the `skillBuilder` subgraph, its routes, the `skillBuilder-review` pause and the four state keys.
2. **Save is unreachable in production for now.** `ConsoleApprover` and `ChatWindow` don't override `reviewSkill` yet (Task 7), so every draft is cancelled through the default.
3. **Stale `.md` pages.** A skill that is deleted or becomes invalid keeps its old `<name>.md`; I chose not to delete files in the user's folder.
4. **A loose name match can pull in a large file.** A description that merely mentions a built-in name (e.g. "coding") includes `coding.json`, about 9 KB, as a possible starting point. It is harmless but costs context.
5. **No progress card.** `Agent.progressOf` reports nothing for builder nodes. Only the hidden-work label "Drafting the skill" streams.

## Fix round 1

1. **The fence regex rejected valid skills** (Important). `SkillBuilderGraph.Draft.parse` now parses the stripped answer as JSON first. Only when that fails does it fall back to a fenced block. The new `FENCE` regex matches from the first line that opens a fence to the last line that closes one (greedy, MULTILINE, each fence line on its own line). A ``` inside a JSON string can never start a line, because JSON strings hold no raw newlines, so it no longer counts as a fence. Test: `aPromptWithACodeFenceIsValidRawAndFenced` uses a prompt containing "```json block```". Both the raw answer and the "Here it is:\n```json ... ```" answer are valid on the first attempt and reach the review.
2. **Effective tool in the review.** `SkillDraft` gained the components `tool` and `replacesBuiltIn`. The new `SkillLoader.toolOf(skill)` returns the skill's own `tool`, else the pinned tool of the built-in it replaces, else `skill_<name>`. Agent passes it in, the review header shows it, and the builder's finish uses it too. `replacingABuiltInIsShownInTheReview` now drafts `coding.json` without `"tool"` and checks that `draft.tool()` and the header say `startCodingJob`.
3. **Built-in check.** `replacesBuiltIn` is computed in Agent from `skillLoader.builtInNames()`, and `replacement()` uses that flag instead of `Agent.BUILT_IN_SKILLS`.
4. **Pages the user wrote are protected.** Every generated page now starts with `SkillDiagram.GENERATED_MARKER`. `writeDiagrams` overwrites an existing `<name>.md` only when it starts with the marker, and logs the skip at DEBUG. Test: `aPageTheUserWroteIsNotReplaced` checks that a user's own `greet.md` is kept, and that a generated page is rewritten when the skill changes.
5. **Temporary file cleanup.** `save` deletes `<name>.json.tmp` when the write or the move fails.
6. **New tests:**
   - `aFileThatAppearedDuringTheReviewIsNotReplaced`: the approver writes `greet.json` during the review, then picks Save. The result is an error containing "appeared while the draft was reviewed", the file keeps the approver's content, and no `.tmp` is left.
   - `aModelErrorWhileDraftingFinishes`: the draft call throws. The result is an error containing "model error: Connection reset", and nothing is reviewed.
   - `aNameWithAPathIsRejected`: a draft named `../x` gets the validator's name error, the fixed draft is reviewed, and no `x.json` exists outside the folder.

Commands run after the fixes:
- `.\gradlew.bat :test --tests net.rka.ai.agent.SkillBuilderTest` → BUILD SUCCESSFUL, 13 tests, 0 failures, 0 errors, empty stdout and stderr.
- `.\gradlew.bat build` → BUILD SUCCESSFUL, 227 tests, 0 failures, 0 errors.
