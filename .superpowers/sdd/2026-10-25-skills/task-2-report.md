# Task 2 report: skill files (model, templates, loader, validator)

**Status:** DONE_WITH_CONCERNS (the concerns are small, see the end). No commits (not a git repo).

## What I implemented (package `net.rka.ai.agent.skills`)
- `SkillFile`: records `SkillFile`, `Input`, `Limits(maxSteps, maxToolRounds, extra)`, `NodeSpec(id, kind, fields, next, on)` as in the brief.
  - `parse(String)` uses Jackson's tree API. Unknown top-level fields, unknown input fields, wrong types and invalid JSON throw `IllegalArgumentException` with a readable message. A missing value stays `null`, and the validator reports it.
  - Defaults: `maxSteps` 40, `maxToolRounds` 8. Any other limit goes into `extra`. An input's `required` defaults to false.
  - `NodeSpec.fields` holds plain Java values (String/Number/Boolean/List/Map).
  - `toolName()` is `tool` if set, else `skill_` + the name with `-` → `_`.
  - Added `NodeSpec.targets(outcomes)` (outcome → target, from `on` plus `next` for a single outcome). Task 3 can use it for routing.
- `Templates.render` / `references`: `{{a}}`, `{{a.b}}` (fields of Map values) and `{{input.x}}` (`input` is just a Map variable). Spaces are allowed inside the braces. Non-strings are inserted as JSON; missing values become "". Anything else, like `{{ a + b }}`, stays literal.
- `NodeKind` is an interface with `name/outcomes/check/reads/writes` and the constant `STOPPED`. It has a nested `record SkillContext(SkillFile skill, ToolRegistry tools, boolean builtIn)`, because the brief's signature uses `SkillContext` but lists no file for it.
- `GenericKinds.all()`: the specs of `model`, `askUser`, `branch`, `set` and `finish`, plus the constants `BUILT_IN_VARIABLES`, `INPUT` and `OTHERWISE`. Its Javadoc documents the schema I chose for parts the spec left open:
  - **branch**: `cases: [{when, then}]`. Outcomes are each `then` plus `otherwise`, wired with `on`. Conditions are `{value, equals|notEquals|contains|matches|isEmpty|atLeast|atMost}`, `{count, equals|atLeast|atMost}` and `{and|or: [...]}`. Every text in them is a template.
  - **set**: `values: {var: template | {"increment": n} | {"append": template}}`. Increment and append don't count as reads.
  - Unknown fields on a node are errors (they are usually typos), and so are writing to the built-ins or `input`.
- `SkillValidator(kinds, tools).validate(skill, builtIn)` covers:
  - name pattern, description of at least 20 characters, `tool` only for built-ins
  - inputs: unique and described
  - limits: `maxSteps` 1–200, `maxToolRounds` 1–50, extra limits 1–100
  - node ids: identifiers without `-`, unique, and not the reserved `start`/`toolStep`/`toolApproval`/`stopped`
  - known kind, plus the kind's own `check`
  - tools: every `tools` entry, on any kind, must exist and not be forbidden
  - wiring: `start` exists; `next` only for a single outcome; `on` keys are outcomes or `stopped`; every non-`stopped` outcome is mapped to an existing id; terminal nodes take no wiring
  - graph: every node reachable from `start`, and a terminal node reachable from every node
  - data flow: a must-be-defined forward analysis (see the class Javadoc). A `stopped` edge passes on `in(p)` without the node's writes.
  - When a kind is unknown, the graph checks are skipped to avoid noise.
  - Constants: `FORBIDDEN_TOOLS` (startCodingJob, buildSkill, sshHosts, runRemoteCommand), `SKILL_TOOL_PREFIX` and `isForbidden(tool)`. `graphIds(skill)` lists `skill-<name>` and `skill-<name>-<id>`, built-in ids included.
- `SkillLoader(userDir, builtInNames, validator).load()` returns `Loaded(skills, errors)`:
  - Built-ins come from the classpath resources `net/rka/ai/agent/skills/<name>.json`. A package-private constructor injects their source for tests.
  - User `*.json` files load in file-name order. The file name must be `name` + `.json`.
  - A user file with a built-in's name replaces it and keeps the built-in's tool. It may repeat that tool but not change it, and it is validated as built-in. If it is invalid, the built-in stays and the file's errors are listed.
  - Graph-id collisions between skills: the later skill is skipped with an error. Example: skill `a` with node `b` and skill `a-b` both give `skill-a-b`.
  - Errors are keyed by file name. A null or missing folder means no user skills. Nothing throws: `RuntimeException`s from checks are caught per file.

## Tests
- `TemplatesTest` (3): rendersVariablesFieldsAndInputs, missingIsEmpty, referencesAreListed.
- `SkillLoaderTest` (26):
  - loadsAValidSkill (the spec's release-notes example) and defaultsApplyWhenLeftOut
  - one test per rule: unknownKind, danglingTarget, unmappedOutcome, unreachableNode, pathWithoutFinish, undefinedVariable, unknownTool, forbiddenTool (startCodingJob, runRemoteCommand, skill_*), badName, fileNameMismatch, toolFieldOnlyForBuiltIns
  - variableDefinedOnOnlyOnePath, stoppedEdgeDoesNotDefineTheNodesOutput, limitsInRange, unknownTopLevelFieldIsAnError, unknownNodeFieldIsAnError, branchAndSetKinds
  - loading: oneBadFileDoesNotStopTheOthers, notJsonIsListed, missingFolderLoadsNothing, nodeIdsOfDifferentSkillsMustNotCollide
  - built-ins: builtInsLoadWithTheirTool, userFileReplacesBuiltIn, invalidUserOverrideFallsBackToBuiltIn
- Result: 29/29 passing, empty stdout/stderr. `.\gradlew.bat build` → BUILD SUCCESSFUL.

## TDD evidence
- RED: `.\gradlew.bat :test --tests "net.rka.ai.agent.skills.*"` with only the tests written failed to compile ("53 errors", e.g. `package SkillLoader does not exist`). This was expected because no production classes existed yet.
- First run after implementing: 2 failures.
  - `builtInsLoadWithTheirTool`: I had keyed built-in errors as `x.json (built-in)`. I changed this to plain file names, merging lists.
  - `nodeIdsOfDifferentSkillsMustNotCollide`: `a-b.json` sorts before `a.json`, so the test's expectation was wrong and I fixed it.
- One more red-green round for `branchAndSetKinds`: error paths had misplaced backticks.
- GREEN: same command → BUILD SUCCESSFUL, 29 tests. Then `.\gradlew.bat build` → BUILD SUCCESSFUL.

## Files
- Created: `src/main/java/net/rka/ai/agent/skills/{SkillFile,Templates,NodeKind,GenericKinds,SkillValidator,SkillLoader}.java`
- Created: `src/test/java/net/rka/ai/agent/skills/{TemplatesTest,SkillLoaderTest}.java`
- Nothing existing was modified. Docs were not touched: the graph is unchanged.

## Self-review fixes
- A node without `kind` caused an NPE, because `Map.copyOf` rejects `containsKey(null)`. It is now reported as "needs a `kind`", with a test.
- `on` order is kept, so messages come out in a stable order.
- A null JSON error location is now handled.

## Concerns / notes for later tasks
1. **`GenericKinds.java` was created in this task** to hold the generic kinds' specs (the brief put them in "the kinds map" without naming a file). Task 3's brief says "Create GenericKinds.java", so Task 3 should *extend* this file instead of creating it.
2. **Schema choices** for `branch` conditions and `set` values (above, documented in the `GenericKinds` Javadoc) were mine; Task 3 has to implement them as documented.
3. **Node ids may not contain `-`** (my rule, so `skill-<name>-<id>` stays unambiguous). Coding's ids are camelCase, so Task 5 is fine with it.
4. **A user override of a built-in is validated with `builtIn=true` and the built-in's tool forced.** Otherwise a user `coding.json` would lose `startCodingJob`.
5. **Limit ranges** are my choice: `maxToolRounds` ≤ 50 and kind limits ≤ 100. Unknown `extra` limit names are not rejected.
6. **`stopped` may be mapped on any non-terminal node.** Unmapped, it is the automatic error finish, and it never counts toward "reaches a finish".

---

## Fix round 1

### Changes
1. **An override is now a user file for every rule.**
   - Added `SkillValidator.validate(skill, builtIn, pinnedTool)`. The two-argument form delegates to it with `null`.
   - The `tool` check accepts a user file's `tool` only when it equals `pinnedTool`. A different value gets "This file replaces a built-in skill, so its tool stays `<tool>`; leave `tool` out."
   - The loader calls `validate(skill, false, builtIn.tool())` for an override. It copies the built-in's tool into the file when the file leaves `tool` out. Kinds now receive `SkillContext.builtIn = false` for an override.
   - Removed `keepTool`. `withTool` only copies the pinned tool in.
2. **Unreadable folder entries.** `userFiles` also catches `DirectoryIteratorException`. The files read so far still load, and the folder is listed with "can't be read completely".
3. **Collisions prefer built-ins.**
   - `withoutCollisions` checks an override against the graph ids of earlier accepted skills and of every *other* built-in, including ones that load after it.
   - When an override collides, the error says "The built-in skill is used instead." and the built-in it replaces is restored, provided the built-in itself doesn't collide.
4. **"Reaches a finish" ignores `stopped` edges.** Reachability still follows them.
5. **`required` must be true or false.** Any other value is a parse error, e.g. "The input `since`: `required` must be true or false."
6. **The undefined-variable message** now says "... or declare an input in `inputs` and use `{{input.<name>}}`."

### Tests added or changed
- `overrideIsAUserFileForTheKinds`: a recording `finish` kind sees `builtIn` = [true, false] for the built-in and then its override. The override keeps tool `sayHello`, with no errors.
- `overrideThatCollidesFallsBackToBuiltIn`: built-ins `a` and `a-b`. A user `a.json` adds node `b`, which would take `skill-a-b`. Result: built-in `a` (node `end`) plus `a-b` are loaded, and `a.json`'s error mentions `skill-a-b` and the fallback.
- `stoppedEdgeDoesNotCountAsFinish`: `spin` maps `done` to itself and `stopped` to the finish, and is reported.
- `requiredMustBeTrueOrFalse`.
- `undefinedVariable` also asserts the `{{input.commit}}` hint.
- `invalidUserOverrideFallsBackToBuiltIn` (tool rename) still passes with the validator's new message.
- No test for item 2: an iteration failure can't be provoked reliably on a temp folder. The change is a widened catch.

### Commands and output
- `.\gradlew.bat :test --tests "net.rka.ai.agent.skills.*"` → BUILD SUCCESSFUL. Test results: SkillLoaderTest tests="30" failures="0" errors="0", TemplatesTest tests="3" failures="0" errors="0".
- `.\gradlew.bat build` → BUILD SUCCESSFUL in 16s.
