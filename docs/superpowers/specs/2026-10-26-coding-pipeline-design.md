# Coding skill: a pipeline, not a conversation

Design for rebuilding the built-in `coding` skill (`coding.json`, `CodingGraph`, `CodingKinds`). Status: **built**
(approved with all five recommendations on 2026-10-26; section 11 lists what the real runs changed).

## 1. Why: the effort the current job repeats

The job today is a chain of free model conversations (investigate, plan, review, implement), each one a loop in which the
model calls tools and decides what to do next. A local model (about 30 tokens/s writing, a few hundred reading) pays for
every repeat, and the logs show where it pays:

| Repeated or wasted effort today | Seen in the log | Cost |
|---|---|---|
| The plan is written by `plan`, then written again by `review` (the model rewrites the whole JSON) | 1,301 + 1,348 output tokens | about 90 s per plan, twice with every revision |
| The investigation lists folders one level per call; the implement step reads the same `outline` / `readSection` again | 6 rounds of `listFiles`; `outline` of one file three times | the step-turn cap (15) is spent reading; no edit is made |
| A call whose text isn't in the plan sends the job back to planning | `gradle` vs `gradlew` | a new plan, a new review, a new approval, again and again |
| The model must quote old text or format a tool call correctly | `editFile` "oldText was not found" | whole turns lost, the edit never lands |
| Whole files or whole plans are generated although a few lines change | 2,181-token `editFile`, 84 s | minutes of generation |
| Each fix is patched around the loop (second chances, caps, repeat notes, redirects) | the last ten changes | more code, still not repeatable |

The cause is one design choice: **the model drives the tools.** Everything above is the model making a decision the
graph could make, or the model producing text the graph could produce.

## 2. Goals, non-goals, anti-goals

**Goals**
- A flow with a fixed shape: the same nodes in the same order for every request, each doing one thing once.
- No work twice: nothing is read, written, planned or checked again unless something changed.
- Fast on a local model: output tokens are only the text that has to be new; the prompt start stays the same so the model
  server's cache is reused.
- Robust: every model answer is validated by the graph; every loop has a limit; a failure ends in a report that says why,
  never in a loop.
- Safe as before: nothing changes before the user approved a plan, and only what the plan says.

**Non-goals**: a general agent for any task inside a job (the plain agent stays that); languages other than Java and
Markdown getting structure-aware outlines now (they work at block level, see 8).

**Anti-goals** (would make it a failure even if it works): another layer of patches around a model-driven loop; a plan
dialog the user can't read; a job that edits files outside what was approved.

## 3. The principle

> **The model never calls a tool inside a coding job.** The graph reads, searches, writes, builds and applies; the model
> answers questions with text of a known shape: a list of files, a plan, the new text of one section, a list of issues.

What this removes by construction: tool-call formatting errors, wandering reads, repeated reads, uncovered calls and the
replans they cause, quoting old text, loops of every kind inside a step. What it needs: every step's input assembled by the
graph, every answer parsed and validated by the graph.

## 4. The flow

```
environment -> pickFiles -> gather -> plan <-(read more code, at most 2 rounds)-- plan
                                        |
                                    [validate]  invalid: back to plan once, with the errors
                                        |
                                  planApproval  (pause: the user approves / revises / denies)
                                        |
                       setup commands (graph runs the approved ones) 
                                        |
                          edit  (one model call per change, applied by the graph)  <-- repeats per change
                                        |
                                     verify  (graph runs the build)
                                 fails |        | passes
                                      fix        designReview (once, on the diff)
                       (one call per failing        | issues
                        section, max 3 rounds)     fix (same node)
                                      \_____________|
                                                 report
```

| Node | Who works | Input | Output | Bound |
|---|---|---|---|---|
| `environment` | graph | goal, trusted folder | environment summary, **project map** (exists), baseline copies of files it may change (exists) | once |
| `pickFiles` | model, one call | goal + project map | `{"files": [...]}`, at most 6 | 1 retry on an invalid answer; skipped for an empty folder |
| `gather` | graph | the picked files | their **outlines** (section ids, kinds, lines, md5), capped at 20,000 characters | once |
| `plan` | model, one call | goal, map, outlines, code read so far, the user's comment on a revision | the plan JSON (below) | at most 2 extra calls when the plan asks to `read` more; 1 retry when invalid |
| *validate* | graph | the plan | the plan with every id resolved to its full form, or a list of errors | deterministic |
| `planApproval` | user | the plan as a table | approve / revise (comment) / deny | at most 2 revisions caused by the job itself (see `fix`) |
| `setup` | graph | the plan's `setup` commands | command output | runs exactly the approved text |
| `edit` | model, one call per change | goal, the change (file, section, action, intent), the section's current text (or the class outline for an add), a one-line list of edits already made, the design guide | the new text of that section in one code block | 1 retry when the block is empty or the section moved |
| *apply* | graph | the block | the file changed by `SectionEdit` with the md5 kept by the graph; the outline made again | deterministic |
| `verify` | graph | the plan's verify commands | pass, or the failures **mapped to sections** | runs after the last change, and after each fix round |
| `fix` | model, one call per failing section | the section's text, the compiler / test messages for it | the section's new text | at most 4 sections per round, 3 rounds |
| `designReview` | model, one call | the diff of what the job changed, the design guide | issues, each with a file and section (or none) | 1 round (limit `maxDesignRounds`) |
| `report` | graph | the ledger | the report and the plan file | once |

Fixes use the same mechanism as edits (a section, new text, apply), so there is one way to change code in the whole job.

### The plan

```json
{ "summary": "what the job does, one or two sentences",
  "read":    [ {"file": "src/.../ChatWindow.java", "section": "ChatWindow.start(Stage)"} ],
  "setup":   [ {"command": "npm install", "workingDirectory": ""} ],
  "changes": [ {"file": "src/.../ChatWindow.java", "section": "ChatWindow", "action": "add",
                "intent": "a Stop button field and the method that cancels the running turn"} ],
  "verify":  [ {"command": ".\\gradlew.bat build", "workingDirectory": ""} ] }
```

- `read` is only used while the model needs to see more code before it can plan; the graph reads those sections (capped),
  and asks again. A plan with changes ends the reading.
- `action`: `replace`, `add` (into the class named by `section`, placed by kind), `before`, `after`, `delete`, `create`
  (a new file: `section` is empty). `intent` is one sentence the edit call is told, and the user reads.
- `verify` defaults to the build command the graph detects from the project map (`build.gradle` → Gradle wrapper,
  `pom.xml` → Maven, `package.json` → `npm test`, ...) when the plan has none.

### Validation (graph, no model)

Every `file` exists (or is a `create` path inside the folder and doesn't exist); every `section` resolves in the file's
outline (lenient names are normalized to the full id); an `add` names a class; at most 12 changes; commands have working
directories inside the folder and contain no shell file writes the plan could express as changes (`>`, `echo`, `sed -i`);
read sections fit the budget. Errors go back to the model once, as a list. A second failure stops the job with the errors
in the report.

### Edit and apply

The edit call is always: one change, the current text of that section, the intent, what earlier changes in this job did
(`added field stopButton to ChatWindow`), and the rule *"answer with one code block: the complete new section."* The graph
extracts the block, applies it (`SectionEdit`, md5 handled by the graph: the model never sees or passes one), makes the
outline again, and goes on. If a section can't be found because an earlier change moved or renamed it, the id is resolved
again from the fresh outline; if it still doesn't resolve the model is told once, then the change is marked `failed` in the
ledger and the job goes on with the others (the report lists it).

### Verify and fix

`verify` runs the commands (existing code). Output is parsed for `path:line` positions (javac, Gradle, tsc, Python
tracebacks, test frameworks); each position is mapped to the **section** that contains the line, and `fix` gets that
section and only its messages. Messages that can't be mapped go with the outline of the plan's files to one fix call that
names the section. Errors in a file the plan doesn't change need the user: the job goes back to `planApproval` with the
extra change added and the reason shown (at most 2 times), never edits it silently.

### Ledger and state

The job's record is data, not conversation: `codingPlan`, `codingPicks`, `codingRead` (file, section, md5 read),
`codingLedger` (per change: pending / applied / failed, the md5 after), `codingBaseline` (exists), the verify output and
attempt counters. The report, the plan file and the design review's diff are all made from it. Nothing is reconstructed
from a transcript, so a revision or a resumed job doesn't redo anything.

## 5. Cost of the stop button (estimate)

pickFiles: 2.5k in / 60 out. plan: 9k in / 350 out. Four edits: 3k in / 300 out each. verify: the build, about 25 s.
designReview: 4k in / 150 out. About 1.9k output tokens in total (about 65 s of writing) against about 3k tokens for plan
plus review alone today, plus the loops. Because every request starts with the same opening message, only the changing
tail is read again.

## 6. Failure matrix

| Failure | What happens |
|---|---|
| Model names no usable file | one retry; then the job stops: "tell me which file" |
| Invalid plan | one retry with the errors; then the report lists them |
| Plan with more than 12 changes | validation error: "split the work"; the retry shortens it |
| Edit block empty / section gone | one retry; then that change is `failed`, the others continue |
| Build still failing after 3 fix rounds | report "still failing" with the last output and the ledger |
| Fix needs a file outside the plan | back to approval (max 2), never silent |
| Server error / timeout | the job stops with the existing explanation; the ledger says what was already applied |
| Any loop | impossible without a counter; every counter has a limit in `limits`, and the report names the one that ended it |

## 7. What changes in the code

**Kept**: triage and the other entry points, `ProjectMap`, `Outline` / `SectionEdit` / `OutlineTools` (the plain agent
still uses them), `Baseline` snapshot and diff-based design review, the plan file, `verify`'s command running,
`report`, the shared job opening message (prompt cache), `CodingRedirect` as a safety net for the plain agent.

**New node kinds** (`CodingKinds`): `coding.pickFiles`, `coding.gather`, `coding.plan` (new meaning), `coding.edit`,
`coding.fix`. **Changed**: `coding.planApproval` (shows changes, not steps), `coding.verify` (maps failures to sections),
`coding.designReview` (issues name sections), `coding.report`. **Removed**: `coding.investigate`,
`coding.investigateTools`, `coding.review`, `coding.implement`, `coding.implementTools`, and with them the
tool-call plan check (`CodingPlan.covers`, `whyNotCovered`, refusals, revisions-by-call), `ToolCallRunner.withoutRepeats`
use inside jobs, and the job's offered-tool list.

**Validator** (`SkillValidator`): "edit, fix, setup and verify are only reached through `planApproval`'s `approve`"
replaces the rule for implement. A user's `coding.json` that uses a removed kind fails validation and the built-in is
used (existing fallback), with the error listed in Settings → Skills.

**UI**: `PlanDialog` shows a table (file, section, action, intent) plus setup and verify commands; the Markdown plan file
and the report use the same data.

**Docs** per `CLAUDE.md`: `docs/agent-graph.md` (diagram, node tables, state keys, "Skills"), README (Coding jobs,
Skills), `AgentGraph` Javadoc.

## 8. Limits of this design (stated, not hidden)

- Structure-aware sections exist for Java and Markdown. Other files are split into blocks of lines: an edit replaces a
  block (about 20 lines), coarser but still exact. More languages are a new outliner each, same interface.
- The model can't explore beyond the project map and the sections it asks for. For tasks that need web pages or ad-hoc
  commands to find out how to proceed, the plain agent remains the tool; a job can't do research.
- Quality now depends on three single-shot answers (files, plan, section text) instead of on a long conversation. They are
  small, validated and cheap to retry, which is the point, but a plan that names the wrong section is a wrong plan: the
  user sees every file and section before anything changes.

## 9. How it will be built and checked

1. Pure classes with unit tests first: plan model and validator, code-block extraction, ledger, error-to-section mapper,
   default verify command detection.
2. The five new kinds and the changed ones, wired in `coding.json`; graph tests with a scripted model for the whole flow
   (happy path, revision, failed edit, fix round, errors outside the plan, every limit).
3. Dialog, plan file, report; docs and diagrams checked against the generated outlines as `CLAUDE.md` requires.
4. Removal of the old kinds and their tests in the same change, so there is one flow, not two.
5. A real run through the CLI against a scratch project in a temporary folder (not this repository) on the Lemonade
   server, with the agent log, to measure model time per node.

## 10. Decisions for the user

1. **No tools for the model inside a job** (it can't fetch pages or run exploratory commands there). *Recommended: yes.*
2. **The model's plan review pass is dropped** and replaced by graph validation plus the user's approval. *Recommended: yes.*
3. **Design review stays, one round, on the diff, fixing through the same section mechanism.** *Recommended: keep.*
4. **A fix outside the approved files goes back to the user (max 2) instead of being forbidden.** *Recommended: re-approval.*
5. **Build it inline in this session** (tightly coupled changes, fewer tokens) rather than with one subagent per task.
   *Recommended: inline, then one fresh review of the whole change.*

## 11. What changed while building it (from real runs on the local model)

The job was run end to end on scratch projects with the Lemonade model (a two-method change took 17 seconds).
What those runs showed, and what the design now does about it:

- **The model names the wrong kind of section.** For "add a method" it picked the class's `<header>` section with
  `add` (twice, so the plan failed). The validator now normalizes: `add` on a header means the class, `add` on
  another part means `after` it.
- **An edit that rewrites a neighbour.** A `before` edit also rewrote `run()`, leaving it twice. An edit whose
  result declares a part the file already had is refused and told back once.
- **Prose taken for code.** An answer without a code fence is no longer code (`CodeBlocks`); a model's "I'm not
  sure what to write" must never be written into a file.
- **The design review hurt small changes.** On a two-method change it produced 8 invented issues and four fix
  calls that overwrote each other. Now: skipped under 40 changed lines, at most 5 issues, only added lines count,
  one fix call per section (all its problems together), and **a design fix that breaks a passing build is undone**
  (the files go back to the copies taken when the build passed).
- **The agent carried on after the job.** The plain agent read the report's issues and started editing by itself.
  The answer to the call now ends with "This is the job's final report ... don't change the code any further unless
  the user asks".
- **Section 4's "fix" without a place.** Build output without a `file:line` ends the job instead of calling the model
  with nothing to fix.
- **Decision 3 (design review stays) held, with the threshold above.** Decisions 1, 2, 4 and 5 were built as approved.
