# lemonade-client

A minimal Java agent built with **LangChain4j** (LLM client + tools) and **LangGraph4j** (graph orchestration).
It has a **JavaFX** chat window and a command-line mode, and targets **Java 27** through a Gradle toolchain (Gradle finds JDKs in `~/.jdks`).
It calls a **Lemonade** server through Lemonade's OpenAI-compatible API and sends traces to **LangSmith** over OpenTelemetry.

```
START -> memory -> analyze -> agent                            memory = summarize old messages if too long
                             analyze = the model returns the goal and what happens next: an edge (agent, or a
                                       job: job_plan, job_study, job_free), a read-only tool call, or a question
                                       for you (ask: the graph pauses, your answer joins the conversation, analyze runs again)
                  agent --(safe tool calls)-----> tools ----> agent
                        --(only trusted risky)--> trusted --> agent    trusted  = subgraph: verify -> execute
                        --(needs approval)------> approval -> agent    approval = subgraph: review -> execute
                        --(startCodingJob)------> skill-coding -> agent   the built-in skill coding, see below; after a success the turn ends (no model call)
                                                                          (also when the reply edits existing code by hand)
                        --(buildSkill)----------> skillBuilder -> agent   subgraph, see Skills
                        --(skill_<name> call)---> skill-<name> -> agent   one subgraph per skill file, see Skills
                        --(no tool calls)-------> END                  (also after 12 tool rounds in one turn)

skill-coding = start -> environment -> pickFiles -> gather -> plan 
         -> (pause: you approve the plan; a comment goes to revise: a question is answered, a change makes a new plan)
         -> setup -> edit (per file) -> verify (-> fix) -> report
         -> (no code changed: explain why and what to do) -> answer
         (needs the code understood: pickFiles -> study (read-only model) -> gather -> plan ...;
          open-ended work: pickFiles -> work (a model with tools) -> check (the build, no model) -> [repair -> recheck] -> finish, no plan)
skillBuilder = tools -> draft -> validate -> (pause: you review the draft) -> save -> finish
skill-<name> = start -> the nodes of the file (model, askUser, branch, set, finish) -> finish
         + toolStep and (pause: you approve guarded tool calls) toolApproval; askUser nodes pause too
```

The full diagram, with what each node does, is in [docs/agent-graph.md](docs/agent-graph.md).
Keep it in sync when you change the graph (the steps are at the end of that file).

Risky tools (terminal and SSH commands) need your approval: the graph pauses before the
`approval` subgraph, the app asks you (a dialog in the chat window, `y/N` in the CLI), saves your
decision in the graph state and resumes. Denied commands don't run; the model is told they were denied.

Trusted commands (see [Settings](#settings)) skip the question: they go through the `trusted`
subgraph, which checks the list again and runs them. Each entry is a whole command, and `*`
matches any arguments, e.g. `Get-Date`, `hostname`, `uname -r`, `git status*`.
A command with shell syntax (`; & | < > `` ` `` $ ( ) { }` or a newline) is never trusted, so
`git status; rm -rf ~` still asks. Matching ignores case and extra spaces.

**Nothing that changes files runs without your approval or trust either.** Writing, creating
folders, moving, copying and deleting files (and `remember` / `forget`, which change the
conversation's memory) ask every time, unless the whole tool is trusted with an entry like
`tool:writeFile`. Reading inside the trusted folder, listing, web search, date and math never ask.

The approval dialog can add an entry to the list for you: **Always** runs the call now and trusts
it from the next message on in this conversation; the button's arrow offers **Conversation** or
**Everywhere** (all conversations, stored in the global settings). For commands, edit the **Trust as** field first to widen
it (`git status` → `git status*`); commands with shell syntax can only be run once. For file
tools, the entry is `tool:<name>`. In the CLI, answer `g` to trust it globally.

## Coding jobs

For coding work in the trusted folder (a feature such as a button or dialog in an app, a new
project, a framework, a refactoring, design work across several files: anything that needs existing
code read first or must still build afterwards) the model calls the tool `startCodingJob(goal)`.
That starts the built-in skill `coding` (see [Skills](#skills)); only tiny edits (a typo, one value,
a file already read in the conversation) use the file and command tools directly.

**You don't have to ask for a job.** Every turn starts with a `triage` step, before the agent does anything: the
model is asked one question, "is this coding work?" (a feature, a button, a bug, a new project, tests, the build,
or carrying on with such work: "implement it"), and answers one word. The question is a short request of its own (the latest
message, the one before it and the start of the last answer, no tools), so it costs a few seconds even when the model
server's cache holds a coding job's prompt instead of the conversation (the agent log
shows `⇢ triage: coding work, starting the coding skill` or `⇢ triage: not coding work`). If it is coding work the
coding skill starts right away, with your message as the goal (and your earlier messages, for a short follow-up).
Questions about code, reading files, notes and general chat go to the agent as before. `CODING_TRIAGE=false` turns
the question off. The system prompt also tells the model to start a job for such requests, and two safety nets
catch what slips past: when the model's reply changes an *existing* source or
build file (`.java`, `.py`, `.html`, `.ts`, `package.json`, …) with `writeFile` or `editSection`
by more than a tiny change (over 200 characters of new text), or makes the turn's second such change, the
reply's calls are replaced by a `startCodingJob` call with your message as the goal, and the job runs as if
the model had asked for it (the agent log shows `↪ the reply changes existing code by hand`). New files,
text and Markdown files, tiny edits and reading code (answering questions about it) stay with the direct tools.
A short follow-up like "implement it" works too: the job's goal then includes your earlier messages.

A local model sometimes ends a reply with an announcement ("Let me make these changes:") and stops before the
tool call, and a conversation full of such replies makes it worse. A plain answer that ends on a colon is
handled by the graph: if it names a source file ("Let me make these changes to ChatWindow.java:") the job it
promised is started right away (`↪ the reply announces a change to a source file`); otherwise the model is
asked once more (`↺ the reply announces an action without making the call`) and makes the call or answers
again. For a conversation the model got stuck in, a new conversation is the cleanest way out. The graph starts a
job from the model's replies (an announcement, a hand edit) only once per turn: a job that ended without
finishing explains why in its report, and starting it again from the next reply would loop.

A job is a **pipeline, not a conversation**: the model never calls a tool in it (open-ended work, below, is the exception). The graph reads, writes
and runs commands; the model answers one question per call (which files, the plan, the new text of one
section, what to repair), and the graph checks every answer and limits every loop. That makes a job short
(a two-method change took 17 seconds on a local model, most of it model output) and repeatable: the same steps in
the same order for every request, nothing read or written twice.

1. **Find the code**: at the start the graph walks the trusted folder once (build output, dependencies and
   hidden folders left out); every phase's opening message lists the files with their line counts. One
   model call picks the existing files the work belongs in (`{"approach": "plan | study | free", "files": [...]}`, at most 6), and the graph
   builds their outlines (see [File outlines](#file-outlines)). The tools it finds are cached in
   `~/chat/environment.json` (stale after 7 days, refreshed when a verify run shows a missing tool). A change that spans several places or builds on existing behavior (`study`) gets a research step first: the model reads the code with read-only tools, starting where the feature starts, and writes notes with the exact names, signatures and call paths; every later step of the job (plan, edits, repairs) sees them, so the plan uses names that exist. **Open-ended work** needs no plan: when the model judges that it has to be found out as it goes (why a build or test fails, an investigation: no list of changes can be written first) it answers `free`, and the job skips the plan and its approval. A generic model step then works with the tools (`outline`, `readSection`, `editSection`, `readFile`, `writeFile`, `runCommand`, …) up to 40 rounds, a risky command still asks you as always, and its final report is the job's result.
2. **Plan**: (the study step also looks for what the project already has for the request; a plan that creates a Java class nothing uses is sent back.) One model call writes the plan (`{"changes": [...], "setup": [...], "verify": [...]}`): each change
   names a file, a section id from the outline, an action (`replace`, `add`, `before`, `after`, `start`, `end`,
   `delete`, `create`; `start` / `end` put statements at the beginning / end of a method or constructor body, so a
   check inside existing code needs no rewrite of the method; `before` / `after` with an `anchor`, a line of the method copied as it is
   written, put statements before that statement or after the whole statement, so a button added in the middle of a 150-line
   method is written as the few new lines, indented like the anchor) and a sentence on what it achieves. If the model can't plan without seeing code it asks to `read`
   sections, which the graph reads (two rounds at most). The graph then checks the plan against the folder:
   every file and section exists (a shortened id is replaced by the full one), at most 12 changes, commands stay
   inside the folder and none writes a file (that is a change), every command finishes on its own (no dev
   servers or watch modes). An unusable plan is told back to the model once, then the job reports. If the build is not shown to pass (it still fails after the last repair, a repair can change nothing, or the job stops with edits made), the job puts the plan's files back as they were (and removes the files it created) and says so in the report: the folder never stays in a state that does not build.
   New code is written in the style of the file it goes into: the model is given what the file shows
   (indentation, brace placement, doc comments) and an existing method as an example, and the design guide only
   applies where the file has no convention. A revised plan keeps the changes already
   made (they stay ✓) and drops repeats of them: only what is still to do is done. A plan
   without a verify command gets the project's build command (Gradle, Maven, npm, Cargo, Go).
3. **You approve the plan**: the window shows a plan dialog with **Approve**, **Revise** (with a
   comment, which gives a new plan) and **Deny**. The CLI accepts `y`, `r <comment>` or N. A
   revised plan starts with "Revised plan: ..." (why it changed) and marks the changes already made.
   The plan is also saved as a Markdown file in `~/chat/plans/` (named by date and goal) for you to
   review: the dialog has **Open plan file**, and the chat shows a "📄 Plan" link (side panel) with
   **Open file** (your editor). The file is rewritten with each revised plan and finally with the
   job's report. The plan itself is not shown in the chat.
4. **Make the changes**: the setup commands run once, exactly as planned. Then, for each change, one model
   call: the section's current text and the intent in, the section's new text out as the argument of a write_code call, which the
   graph applies. A change that can't be made (an answer with no code, the section gone, a text that
   declares a part the file already has) is told back once; then it is marked **failed**, the others go on,
   and the report says "Done with failed changes" (flagged as an error), never "done".
5. **Verify**: the plan's verify commands run, at most 3 attempts per job. When one fails, the graph maps the
   `file:line` positions in its output to the sections that hold them, and the model repairs each failing
   section once per round (all its problems together), then verify runs again. A problem in a file the plan
   doesn't change is never edited silently: it comes back to you as a revised plan (at most twice). When the
   output doesn't say where, the job reports instead of guessing.
6. **Report**: what was changed, what failed, and whether the build passes. The checks that used to follow the build (a model check of the
   plan, of the diff against the request, a design review) are not part of the built-in line; they are node kinds
   (`coding.planCheck`, `coding.intent`, `coding.designReview`) that your own skill can use.

**How a job keeps the model server fast**: every phase's request opens with the same job context (goal,
environment, project map, design guide), so the server reuses what it read for the previous phase; a request
carries only the section it works on; the model writes only the sections that change, never a whole file; and
a diff, not the files, goes to the design review. Ask for a coding job ("use a coding job to …") or just ask
for the change: a first question at the start of every turn recognizes coding work.

**Design guides** steer this and are also given to the edit and fix steps. The built-in
Java guide ships with the app; your own rules go in `~/chat/guides/java.md` (created with a short
note the first time) and win where they conflict. Any `~/chat/guides/<language>.md` you add is a
guide too, e.g. `svelte.md`: it applies when the job's goal names the language or the picked or planned
files have that extension. Edits apply to the next job.

A model error, no valid plan, a failed setup command or a fix that can change nothing stops the job early:
the report says why ("Stopped: no valid plan after 2 attempts: ...  Nothing was changed.") and still answers the
`startCodingJob` call, so the conversation keeps a record, with a note that the job is over (the agent doesn't
carry on changing code by itself). The report shows the plan you approved last; a revision you denied is shown
below it, labelled as not approved.

**What your approval covers**: only the changes and the commands in the plan, only inside the trusted
folder, and only until the job ends. The model has no path to a file or a command at all: the graph applies
the planned changes by section and runs the planned commands exactly as written (a command's working directory
must be inside the folder, and a command may not write files: that is a change). A fix touches only sections
of the files the plan changes; a problem anywhere else comes back to you as a revised plan. Your trusted
commands are not used in a job, and a job reads no web page. SSH, `remember`, `forget`, `buildSkill` and the
skills' tools are never available in a job.

The job's progress shows live: a card in the window ("Coding · Change n/m · `file`") and lines in
the CLI. The model's answers (the plan, the code) are not chat: while they are written you see only
"Writing the plan… n characters" or "Writing code… n characters". Another skill's work shows as muted notes with the
skill's name and its progress as `<skill> · <node> · step n/max`. The job's report is the result of the
`startCodingJob` call; it is saved with the conversation and flagged as an error unless the job is Done. Job
commands stop after `CODING_COMMAND_TIMEOUT_SECONDS` (default 600).

## Skills

A **skill** is a fixed procedure you describe in a JSON file: a small graph of steps (ask the model,
ask you a question, branch, set a value, finish) that the agent can start as a tool, like
`startCodingJob`. A skill is a real subgraph of the agent graph (`skill-<name>`, see
[docs/agent-graph.md](docs/agent-graph.md)), not code: a file can only use the step kinds the app
provides, and can only narrow what a skill may do, never widen it.

- **Folder**: `~\chat\skills` (next to `settings.json`). One file per skill, named `<name>.json`.
  The built-in skill `coding` ships with the app. The model starts a skill with the tool
  `skill_<name>` (`-` becomes `_`), whose description is the skill's `description` and whose
  parameters are its `inputs`.
- **Settings** (⚙ Settings in the window header) lists the loaded skills with their tool and origin
  (built-in, your file, your file replacing a built-in), and every file that could not be used with
  its errors. **Reload skills** reads the folder again, between turns. The builder reloads by itself
  after you save a skill. One bad file never stops the app or the other skills: not even a valid file
  whose subgraph can't be built (it is left out and listed, and a replacement of a built-in such as
  `coding.json` gives way to the built-in one; should the agent graph still fail, only the built-in
  skills are used, and the reason is listed under the files left out). An invalid replacement
  of a built-in reads "Your coding.json can't be used: ... The built-in coding skill is used."
- **Generated pages**: for each loaded skill file the app writes `<name>.md` next to it: a Mermaid
  diagram of the steps, every step's prompt and the tools it may use, with those that ask for your
  approval marked. It is rewritten on every load and never read back; edit the `.json`. A `.md` you
  wrote yourself (one that doesn't start with the generated marker) is left alone.

### The file

```json
{
  "name": "release-notes",
  "description": "Draft release notes from the recent commits of the trusted folder. Use when the user asks for release notes or a changelog.",
  "inputs": [ { "name": "since", "description": "tag or date to start from", "required": false } ],
  "limits": { "maxSteps": 30, "maxToolRounds": 4 },
  "start": "collect",
  "nodes": [
    { "id": "collect", "kind": "model", "prompt": "List the commits since {{input.since}} with git log in {{folder}}.",
      "tools": ["runCommand"], "output": "commits", "next": "draft", "on": { "stopped": "cancelled" } },
    { "id": "draft", "kind": "model", "prompt": "Write release notes from these commits:\n{{commits}}",
      "output": "notes", "next": "check" },
    { "id": "check", "kind": "askUser", "question": "Release notes draft:\n\n{{notes}}",
      "choices": ["accept", "revise", "cancel"], "output": "answer",
      "on": { "accept": "done", "revise": "count", "cancel": "cancelled" } },
    { "id": "count", "kind": "set", "values": { "rounds": { "increment": 1 } }, "next": "tooMany" },
    { "id": "tooMany", "kind": "branch",
      "cases": [ { "when": { "value": "{{rounds}}", "atLeast": 3 }, "then": "giveUp" } ],
      "on": { "giveUp": "cancelled", "otherwise": "redraft" } },
    { "id": "redraft", "kind": "model",
      "prompt": "Revise these release notes:\n{{notes}}\n\nThe user asked: {{answer.comment}}",
      "output": "notes", "next": "check" },
    { "id": "done", "kind": "finish", "result": "{{notes}}" },
    { "id": "cancelled", "kind": "finish", "result": "No release notes: cancelled.", "error": true }
  ]
}
```

Top-level fields (anything else is an error): `name` (lower-case letters, digits and `-`, starting
with a letter; the file must be `<name>.json`), `description` (at least 20 characters: the model reads
it to decide when to start the skill), `inputs` (optional: `name`, `description`, `required`; the
model fills them in as text, and one it leaves out is `""`), `limits` (optional), `start` (the id of
the first node) and `nodes`. `tool` is for built-in skills only.

A node has an `id` (letters, digits and `_`, starting with a letter, unique; not `start`, `toolStep`, `toolApproval` or
`stopped`, which every skill has itself), a `kind`, the kind's fields and its wiring.

| Kind | Fields (\* required) | Outcomes | What it does |
|------|----------------------|----------|--------------|
| `model` | `prompt`\*, `tools`, `output`, `format` (`text` / `json`), `thinking` (`shown` / `progress`), `history` (true: the conversation before the skill goes in front of the prompt as messages, clipped), `maxTokens` (50 to 32000: the longest reply of this step; the built-in research step uses 2500) | `done` | Asks the model with `prompt`. With `tools` (names of tools) it may call them, round after round (`maxToolRounds`), before it answers; the final text goes to the variable `output`. `format: json` parses the answer, so later nodes can use `{{<output>.<field>}}`. `thinking: progress` shows progress instead of the streamed text. The model can't ask you anything here: use `askUser` for that. |
| `askUser` | `question`\*, `choices`\*, `output` | one per choice | Pauses and shows `question` with a button per choice (the CLI numbers them: `2`, `2 a comment`, `c <comment>` for the first choice with a comment). The answer goes to `output` as `{choice, comment}`. Closing the question, a failing approver or no input ends the skill with an error ("Stopped: cancelled by the user."), always through the automatic stopped finish, even when the node maps `stopped` in `on`. |
| `branch` | `cases`\* (list of `{when, then}`) | each `then`, and `otherwise` | The first case whose `when` holds gives the outcome; none gives `otherwise`. |
| `set` | `values`\* (variable → value) | `done` | Each value is a template, `{"increment": 1}` (adds to a number, a missing one counts as 0) or `{"append": "<template>"}` (adds to a list). Values are computed from the variables as they were before the node. |
| `finish` | `result`\*, `error`, `say` | none | Ends the skill: `result` (a template) is the tool's answer in the conversation; `"error": true` flags it as failed; `say` (a template) is also the reply to the user, so the agent makes no call to word it again. |

Every kind also has the outcome `stopped` (a model or tool error, a closed question, an invalid JSON
answer, the step limit). Mapped in `on`, it leads to a node that can recover or finish; unmapped, the
skill ends with an error result that says why.

**Wiring**: every outcome of a node must lead to a node id. A node with one outcome (`model`, `set`)
can use `"next": "<id>"`; a node with several (`askUser`, `branch`) maps each in
`"on": { "<outcome>": "<id>" }`. A `finish` has neither. `branch` conditions (every text in them is
a template):

```
{"value": "{{answer.choice}}", "equals": "accept"}    also notEquals, contains, matches (a regex found anywhere)
{"value": "{{notes}}", "isEmpty": true}               true: blank; false: not blank
{"value": "{{rounds}}", "atLeast": 3}                 number comparison, also atMost
{"count": "{{items}}", "atLeast": 1}                  size of a list, also atMost, equals
{"and": [ ... ]}, {"or": [ ... ]}                     combine conditions
```

**Templates** (`prompt`, `question`, `result`, the texts in `branch` and `set`): `{{var}}`,
`{{var.field}}` and `{{input.<name>}}`, plus the built-ins `{{goal}}` (the call's arguments as
text), `{{folder}}` (the trusted folder) and `{{environment}}` (OS, shell and tool versions). Values
are inserted as text; there are no expressions. A variable must be an input, a built-in, or set by an
earlier node's `output` or `set` on **every** path to the node that uses it.

**The validator** checks a file before it becomes a graph and lists each problem as a sentence naming
the node and field: unknown fields, kinds or tools, a dangling `next` / `on` target, an outcome that
leads nowhere, a node `start` can't reach, a node from which no `finish` can be reached (a loop that
can never end), a variable that isn't set on every path, duplicate ids, a file name that isn't
`<name>.json`, and node ids that another skill already has in the agent graph (`skill-a-b` could be
skill `a-b` or node `b` of skill `a`).

### Safety rules

- **Every tool call follows the main rules.** A node only runs the tools it lists. A tool that asks
  for approval (`runCommand`, `writeFile`, ... ) pauses the skill at its `toolApproval` step, before
  anything of that reply runs, and asks you with the usual dialog (or `y/N`), unless you trusted it.
  Trusted commands, `tool:<name>` entries and the "web content in the turn" rule work as in the
  main chat. A web page read in a skill counts for the rest of the turn.
- **Never available in a skill**, even if listed (the file is refused): `startCodingJob`,
  `buildSkill`, `sshHosts`, `runRemoteCommand` and every `skill_*` tool, so a skill can't start
  another skill or a coding job or reach other machines. The memory tools (`remember`, `forget`,
  `listFacts`) only if the node lists them (`remember` / `forget` ask like anywhere else).
- **Skill files change only through the builder's Save.** The file tools refuse paths inside
  `~\chat\skills`.
- **Limits**: `maxSteps` (node visits per run; default 40, 1 to 200, up to 1000 for a skill with
  coding steps) and `maxToolRounds` (rounds of tool calls per visit of a `model` node; default 8; a round whose calls all repeat earlier ones ends the visit's tool use early,
  1 to 50). A loop stops at `maxSteps` with an error result; the conversation always keeps the call
  answered. Other limits are only the ones a kind declares (the coding steps: `maxReadRounds`,
  `maxPlanAttempts`, `maxEditAttempts`, `maxVerifyAttempts`, `maxDesignRounds`, 1 to 100).

### The skill builder

Ask the agent to make a skill ("make a skill that drafts release notes from the git log"). It calls
the tool `buildSkill(description)`, which starts the **skill builder**, a subgraph of the app (it
can't be changed by any skill file):

1. The model drafts the skill from the file format, the tools a skill may list, an example and the
   names already taken.
2. The app checks the draft with the same validator and loader rules as for a file in the folder. If
   there are problems, they go back to the model; three invalid drafts end the builder with the
   problems listed, and nothing is saved.
3. **You review the draft**: the window shows its limits, the steps, every prompt, the tools (with those
   that ask for approval) and, if it changes a skill, that it replaces it; **Show JSON** shows the file.
   No button is the default (Enter does nothing): **Save** has to be clicked. **Save** writes `~\chat\skills\<name>.json` (the skills reload after that turn; the tool works from your
   next message), **Revise** (with a comment) starts a new round of three attempts, **Cancel** (or
   closing the window) saves nothing. The CLI prints the same and accepts `s`, `r <comment>` or N.

A name that is taken needs `"replaces": true` in the draft, which the builder asks for when you
ask to change an existing skill by name ("change the release-notes skill to also ...", "edit
release-notes"; only naming it, as in "a skill that reviews my coding style", isn't a change); it then
starts from that skill's current file and shows in the review that it replaces it. Names and node ids
are checked against the skills folder as it is when the draft is checked. Each draft request carries
the format, the latest draft and its problems or your comment, not every earlier draft, so it doesn't
grow from round to round. The builder can't yet try a draft
before saving it or delete a skill: to delete one, remove its file (and its `.md`) and use **Reload
skills**.

### The built-in `coding` skill

The coding job above is the built-in skill `coding`
([coding.json](src/main/resources/net/rka/ai/agent/skills/coding.json)): the file wires the coding
step kinds (`coding.environment`, `pickFiles`, `gather`, `plan`, `planApproval`, `setup`, `edit`, `verify`,
`fix`, `designReview`, `report`) and, for open-ended work, a generic `model` step with tools and a `finish`, holds their prompts and limits, and sets `"tool": "startCodingJob"`. Its
`maxSteps` is 1000 at most (the coding cap); the file uses 300.

You can change it with the builder ("change the coding skill so that ..."), which writes
`~\chat\skills\coding.json` with `"replaces": true` (its draft request also teaches the model the coding
kinds, their outcomes and limits, the cap of 1000 and the pinned tool). Such a file replaces the built-in, but it is
validated as a user file: its `tool` stays `startCodingJob` (leave it out), the coding kinds take only
a `prompt`, and the validator rejects any wiring that reaches `setup`, `edit`, `fix` or `verify`
without going through `planApproval`'s `approve` (from the start and from every plan step),
or that reaches another coding step before `environment`. The plan approval can't be wired around, and
the trusted-folder confinement and, on the planned route, the rule that the model has no tools are enforced by the steps
whatever the file says. A file that uses a kind from before the pipeline (`investigate`, `review`, `implement`,
...) is invalid: the built-in is used and the errors are listed in Settings; remove the file and reload to go
back to the built-in.

## Evaluations

`./gradlew build` tests the code with a fake model. Evaluations test the agent with the **real model**: small projects (`evals/fixtures`) are
copied to a fresh folder, a prompt is sent to the agent as the user's message, plans are approved automatically, and checks decide whether it
worked (files, a compile or test command, the answer). Nothing is judged by a model. They take minutes per case, so they are not part of
`build`.

```
.\gradlew.bat runEval                                       every case in evals/cases
.\gradlew.bat runEval --args="--only add-method,fix-bug"    some cases
.\gradlew.bat runEval --args="--run after-change --baseline before-change"
.\gradlew.bat runEval --args="--list"
```

Results are in `evals/results/<run>/` (not committed): `report.md` (a table per case with time, model calls, tokens in, changes
applied/refused, checks passed, and the failed checks), `summary.json`, and per case the agent log (`agent-*.log`, with the `ctx` lines) and
`project/`, the folder as the agent left it. A run is compared with the newest earlier run (`--baseline` names another): a case that
passed before and fails now is marked `REGRESSION`, and time, calls and tokens show their change when it is 5% or more. The exit code is 1 when
a case failed.

**A case** (`evals/cases/<id>.json`): `id`, `description`, `fixture` (a folder in `evals/fixtures`), `prompt`, `timeoutMinutes`
(default 15), `expectCodingJob` (true: the coding skill must start; false: it must not) and `checks`:

| check | passes when |
|---|---|
| `fileExists` `path` | the file is there |
| `fileContains` / `fileNotContains` `path` + `text` or `regex` | the file does / does not hold it |
| `fileUnchanged` `path` | the file is equal to the fixture's |
| `command` `command` [`exit`, `outputContains`] | the command, run in the project folder, exits with `exit` (default 0) and prints what is asked |
| `answerContains` `text` / `answerMatches` `regex` | the agent's final answer says it |

**The cases** (21; fixtures `calc`, `stats`, `broken`, `shop` with packages, a record, an enum and a lambda, `strings` with text that looks like
code, `config` with a properties file):

| group | cases |
|---|---|
| change one place | `add-method`, `replace-partial` (one branch of a longer method), `loop-statement` (start of a for body), `lambda-insert` (after a statement in a lambda), `comment-only`, `stream-refactor` (the first half of a method), `tricky-strings` (strings, a char and a text block that look like code), `properties-edit` (not code) |
| change several places | `add-parameter-callers`, `rename-across-files`, `add-field-ctor`, `enum-case`, `import-needed` |
| create / delete | `new-class`, `new-interface-impls` (three files in a package), `delete-unused` |
| repair | `fix-bug`, `fix-compile` |
| answer, don't change | `question`, `question-code`, `question-missing` |

A test makes sure a case that expects changes does not pass on the untouched fixture, so no check passes by doing nothing.

The auto-approver approves plans and every command that asks, except SSH; commands start in the case's own copy. A test
(`EvalFrameworkTest`) checks that every case file is readable, its fixture exists and its checks are known types. To add a case: put a
fixture folder in `evals/fixtures` (or reuse one) and a JSON file in `evals/cases`.

## Settings

Trusted folders and trusted commands are set at two levels:

| Level | Where | Edit it with | Holds |
|-------|-------|--------------|-------|
| **Global** | `~\chat\settings.json` in your user folder (`SETTINGS_FILE`) | **⚙ Settings** in the window header | The default trusted folder for new conversations, and commands trusted in every conversation |
| **Conversation** | The conversation's file in `~\chat\conversations\` | **⚙ Conversation** under the input line (the 📁 button changes just the folder) | This conversation's trusted folder, and extra commands trusted only here |

A run uses the conversation's folder and the global commands **plus** the conversation's. New
conversations start in the global default folder with no extra commands. Changes apply from the
next message you send. Until `~\chat\settings.json` exists, the global defaults come from `WORK_FOLDER`
and `TRUSTED_COMMANDS` below. The CLI uses the global settings.

The global settings dialog also has a **Skills** section: the loaded skills, the skill files that
could not be used (with their errors) and a **Reload skills** button (see [Skills](#skills)).

## Configuration

Set these as environment variables or in a `.env` file in the project folder (environment variables win).

| Variable              | Default                              | Purpose                              |
|-----------------------|--------------------------------------|--------------------------------------|
| `LEMONADE_BASE_URL`   | `http://127.0.0.1:13305/api/v1`      | Lemonade server (use the remote host) |
| `LEMONADE_MODEL`      | `user.gpt-oss-20b`                   | Any model id from `/api/v1/models` (needs tool calling) |
| `LEMONADE_API_KEY`    | `lemonade`                           | Only if your server requires a key   |
| `LANGSMITH_API_KEY`   | *(unset = LangSmith off)*            | LangSmith API key                    |
| `LANGSMITH_PROJECT`   | `lemonade-client`                  | LangSmith project name               |
| `LOCAL_TRACES`        | `true`                               | Write traces to local JSONL files    |
| `TRACE_DIR`           | `traces`                             | Folder for local trace files         |
| `SSH_HOSTS`           | *(none)*                             | Comma-separated hosts the SSH tool may use, e.g. `rka@192.168.68.59` |
| `SETTINGS_FILE`       | `~\chat\settings.json`               | Global settings file (trusted folder and commands) |
| `TRUSTED_COMMANDS`    | *(none)*                             | Initial global trusted commands, comma-separated, until `~\chat\settings.json` exists |
| `WORK_FOLDER`         | *(your user folder)*                 | Default trusted folder when the global settings don't set one |
| `CONVERSATIONS_DIR`   | `~\chat\conversations`               | Where the chat window saves conversations (one JSON file each) |
| `MEMORY_SUMMARY_TOKENS` | a quarter of `MODEL_CONTEXT_TOKENS`, at least `6000` | Summarize older messages once the unsummarized part is larger than this (estimated tokens) |
| `MEMORY_RECENT_TOKENS` | a sixteenth of `MODEL_CONTEXT_TOKENS`, at least `2000` | How much of the newest messages stays verbatim when summarizing |
| `CODING_TRIAGE` | `true`                                 | Start every turn by asking the model "is this coding work?" and run coding work through the coding skill right away |
| `AGENT_LOG` | `summary`                                  | The readable agent log: `summary` (one line per model request, reply and tool call), `full` (also every message, reply and result), `off` |
| `AGENT_LOG_DIR` | `TRACE_DIR` (`traces`)                     | Folder of the daily `agent-YYYY-MM-DD.log` |
| `AGENT_LOG_CONSOLE` | `true`                                 | Also print the log lines to the console |
| `MODEL_REASONING_EFFORT` | `low`                            | How hard a reasoning model thinks (`low`, `medium`, `high`; empty sends nothing), sent as `chat_template_kwargs.reasoning_effort`; low keeps the thinking short |
| `MODEL_MAX_OUTPUT_TOKENS` | `8192`                           | Longest reply the model may write; a runaway rewrite stops after a few minutes instead of running into the timeout |
| `MODEL_TIMEOUT_MINUTES` | `15`                               | How long one model reply may take (a long file written on a local model needs minutes) |
| `MODEL_CONTEXT_TOKENS` | `16384`                             | The model server's context size; each request is shortened to fit it |
| `MODEL_STREAMING` | `true`                                   | Stream replies token by token (thinking, text, tool calls) to the window and CLI |
| `COMMAND_TIMEOUT_SECONDS` | `60`                             | Terminal/SSH commands are stopped after this |
| `CODING_COMMAND_TIMEOUT_SECONDS` | `600`                      | Commands of a coding job are stopped after this |
| `COMMAND_MAX_OUTPUT_CHARS` | `8000`                          | Longer command output keeps only the end |
| `LANGSMITH_ENDPOINT`  | `https://api.smith.langchain.com`    | Use `https://eu.api.smith.langchain.com` for EU |

## Run

```fish
set -x LEMONADE_BASE_URL http://my-lemonade-host:13305/api/v1
set -x LANGSMITH_PROJECT lemonade-client
./gradlew run                                      # JavaFX chat window
./gradlew runCli --args="What is 1234.5 times 42?" # one question on the command line
```

The window needs a desktop session (it won't open over plain SSH). Each chat is one LangSmith thread, and each message you send is one trace. **New chat** starts a new thread.

A restart does not lose your place: a conversation is saved when you send the message (not only after the answer) and again with every message the turn adds, and `~/chat/ui-state.json` keeps the open conversation, the text you typed and did not send, the window's place and size and whether the sidebar is open, so the app opens where you were. A conversation that was cut off by a closed app opens with a note, and a call that had no result gets an error result so the conversation stays valid.

Conversations are saved after every answer to `CONVERSATIONS_DIR` (`~\chat\conversations\` by
default, next to `~\chat\settings.json`), one JSON file per conversation with the full message
history, including tool calls and results, and the conversation's memory (below).
The left sidebar groups them by trusted folder (the folder with the newest conversation first, conversations newest first
inside it; click a folder's arrow to close it). A folder's **⋯** menu, or right-click, starts a **new conversation in that
folder**. Click a conversation to reopen and continue it (it keeps its
LangSmith thread), right-click to rename or delete it, and use **☰** to hide the sidebar. Tool calls
that follow each other share one pill ("⚙ 7 tool calls · listFiles {…}", naming the latest, with
one running / done / failed badge); click it to open the right-hand panel, which lists all of the
pill's calls with the latest selected and shows the selected call's arguments and full output.
Everything appears in real time. While the model writes a reply, its thinking (for reasoning
models), its words and the tool calls it is writing ("⚙ writeFile  writing… 1,240 characters") grow
in a box at the bottom; the finished reply then replaces it with the normal bubble, dashed
"Thinking" note and tool chips. While a command runs, its chip shows the latest output line
("running · Compiling…") and the side panel shows the whole output as it comes. The CLI streams the
same to the console. Replies stream token by token (`MODEL_STREAMING=true`, the default); set it to
`false` for a server that can't stream tool calls, and output then arrives once per reply.
If a turn fails after it already ran tools (the model server drops out mid-turn, say), what it did
is kept: the tool calls and results stay in the conversation and are saved, calls left open are
marked failed, and the error shows below them, so the next turn knows which files were touched.
While a turn runs, the Send button is the **Stop** button: it interrupts the turn's thread, cancels the model's stream and
stops a running command; what the turn did until then is kept like in a failed turn, with a "Stopped" note instead of an error.
A model that falls into a loop and writes the same lines over and over (a local model can do that until its output limit) is
stopped by itself after a few repeats (`RepeatGuard`: the same line or block of lines six times, or the same piece of text four times in a row, also inside one long paragraph): the repeats are cut, and a loop in the thinking makes the reply empty,
which is asked again. **The task is kept.** A coding request that says what to do by itself (60 characters or more) becomes the conversation's task, stored as the fact
"Current task: …" in the user's own words; it shows in the Memory window, where you can read, correct or delete it, and stays until the next such
request. A short message that points back ("plan that", "implement the changes") starts a job whose goal is that message plus what was said or done just before:
the assistant's last answer (a recommendation, say) or the last job's goal and report; the kept task is only added when that is too little, so an old task does not
pull a new recommendation back to the start of the conversation. The question that starts every turn ("coding, a continuation, or other?") is read
from the last of its three words, because a reasoning model writes its reasoning before the word.

**Conversation health.** The chip "● Context" under the input shows how fit the conversation is to work from (green, amber "watch", red
"drifting") from what went wrong in its last 40 messages: turns that ended without an answer, answers that named classes that are not in the
project (each such answer gets a note "Not found in the project: …" under it), coding jobs that stopped, failed tool calls, and how full the
model's context is. Click it for the reasons and **Reset context**: the model's context is rebuilt from your own messages, the task and how each
coding job ended (taken from the jobs' reports, not from what the assistant said about them); the transcript on screen stays. The model is also
told, in a note at the end of each request, how the earlier coding jobs ended.

**Small changes.** The job finds the code the goal is about before it plans (the members whose names and code use the words of the goal, shown with
their code), and a plan can carry a change as a **patch**: the few lines that change with the lines around them, found by those lines. Files that are not Java (style sheets, build files, JSON, Markdown) are searched the same way, and small ones are shown whole. The graph
applies patches itself, without asking the model to write the section again, and a patch that fits nowhere is told back while the plan is made.
`CodingEval` (a test, off by default: set `CODING_EVAL=true` and run `.\gradlew.bat :test --tests "*CodingEval*"`) runs simple tasks against the
real model and writes `build/eval/coding-eval.md`.

File text is kept out of the conversation where it can: reads are small (12,000 characters, a bigger Java file gives its outline), the
research step has no raw file reads, a read that was done again or that an edit made out of date is replaced by a one-line note, and a
file that changed outside the conversation (in the editor, by a job) is named in one note at the end of the next request, with the
members that changed, so the model reads again only what it needs. The coding job builds the outlines of its files again when one
changed while the plan waited. Any step of the graph that gives the same result four times in a row (a repair that changes nothing, a plan that comes back
unchanged) stops the run with a message that says which step went round in circles (`LoopWatch`, kept in the graph state; the plain
agent's tool loop ends by itself after twelve rounds and is not counted). The CLI doesn't save conversations.

## Manual control of the model

```fish
./gradlew runManual   # the chat window, plus a window with every call of the model held for you
```

Everything works as in the normal window (tools, approvals, coding jobs, memory), but no call to the model goes out or comes back without
you. The second window, **Model traffic**, shows

- **the request before it is sent**: the whole thing the server will receive (system message, every message, earlier tool calls and their
  results) as text with a line `=== user ===` (or `system`, `assistant`, `tool calls`, `tool result id=â€¦ tool=â€¦`) before each message. Change
  anything, then **Send**; **Back to the original** undoes your changes; **Cancel the call** fails the call (the job or the turn that made it
  stops with "cancelled by the user"). The line above the text names the model, the numbers and the number of tools; **Toolsâ€¦** lists them
  (they are sent as they are);
- **the answer before the application uses it**: its thinking, its text and its tool calls (`=== thinking ===`, `=== text ===`,
  `=== tool calls ===` with a JSON list), as the model wrote them. While the model writes, the text appears here as it arrives, not in the chat.
  Change it, then **Use this answer**: tool calls you add are run, text you change is what the graph and the chat get.

The two boxes at the bottom switch holding off for requests or for answers (then that side passes at once, and is only shown). The gate sits
directly in front of the model client, below the fitting of a request to the context window (`GatedChatModel`, `WireCodec`, `WireGate`,
`WireWindow`), so what you see is what the server receives; the agent log and the traces record what was really sent. **Stop** in the chat
cancels a held call. Start the main class `net.rka.ai.ManualLauncher` from an IDE if you do not use Gradle.

## Attaching files

**+ Attach** under the input (or dropping files onto the input) adds text files to your next
message; they show as chips above the input, ✕ removes one. When you send, each file's text is
included in your message between `--- Attached file: name ---` / `--- End of name ---` lines,
so the model reads it, it's saved with the conversation, and summary memory can compress it later.
Your bubble shows what you typed plus a chip per file; click a chip to see the file in the side panel.

Only UTF-8 text files are accepted (code, Markdown, CSV, JSON, logs, …), up to 100,000
characters each (longer files are cut). Images and PDFs aren't supported yet: the current model
reads text only.

## Memory

Each conversation has two kinds of memory, both saved in its file:

- **Summary memory** keeps requests small. At the start of each turn, the `memory` step checks
  how big the not-yet-summarized part of the conversation is. Past `MEMORY_SUMMARY_TOKENS`
  (estimated as characters / 4), the model folds the older messages into a running summary,
  keeping about `MEMORY_RECENT_TOKENS` of the newest messages word for word. The cut is always
  at one of your messages, so a tool call never loses its result. The model then sees: system
  prompt + summary + recent messages. The window and the file keep the full history.
  The defaults follow `MODEL_CONTEXT_TOKENS` so summaries are rare: a new summary changes the start
  of every request, and the model server then has to read the whole conversation again instead of
  reusing its cache (that re-read is most of the wait on a long turn).
- **Earlier turns are compacted** when sent again: a tool result from an earlier turn keeps its
  first 400 characters plus a note, and long text in an earlier tool call (the content of a
  `writeFile`, the new text of an `editSection`) becomes "[N characters, sent in an earlier turn]". The
  current turn, every question and every answer are sent in full, and the saved conversation keeps
  everything; the model reads a file again when it needs it. Without this, old file contents and web
  pages were re-sent on every request (one session reached 124,000 characters, about 31,000 tokens),
  which kept summary memory over its limit, so a new summary changed the start of the prompt every
  turn and the model server had to read it all again (minutes). The summary limit is measured on the
  compacted conversation.
- **Context fitting** protects a single turn: if one request (for example after reading big
  files) would not fit `MODEL_CONTEXT_TOKENS`, the oldest tool results in that request are cut
  in the middle to a fixed size (about 2,000 characters, then 400 if needed), marked as cut, before
  it is sent; the conversation keeps them in full. The size is fixed rather than "just enough" so
  every later request of the turn cuts them the same way and the server cache stays valid. If the
  server still rejects it, the request is fitted again using the server's own token count.
  `readFile` returns at most about 24,000 characters per call and reads big files in parts
  (`startLine` / `endLine`).
- **Facts** are long-term memory for this conversation only: details to keep for good (names,
  preferences, decisions). The model saves them with its `remember` tool (removes with `forget`,
  lists with `listFacts`), or you add and delete them in **🧠 Memory** under the input line,
  which also shows the current summary. Facts are listed in the system prompt on every turn and
  are never summarized away. Other conversations don't see them.

`remember` and `forget` count as file changes: they ask for approval unless trusted
(`tool:remember`), and always after web content entered a turn, so a web page can't plant facts.
The CLI starts with no summary or facts each time.

## Tracing

Each question is one trace: a `LangGraph Agent` root span with a child span per graph step, LLM call and tool call.
Traces go to any combination of:

- **Local files** (on by default): `traces/traces-YYYY-MM-DD.jsonl`, one span per line with
  `trace_id`, `parent_id`, `name`, `kind` (`chain`/`llm`/`tool`), `duration_ms`, and attributes
  such as the prompts, completion and token counts.
- **LangSmith**: set `LANGSMITH_API_KEY` to also browse traces at smith.langchain.com.

Example: list the steps of today's traces with `jq -c '{trace_id, name, kind, duration_ms}' traces/traces-*.jsonl`.

### Agent log (readable)

Next to the traces, `traces/agent-YYYY-MM-DD.log` (and the console) gets one line per event, meant
to be watched while a turn runs (`Get-Content traces\agent-2026-10-25.log -Wait` on Windows):

```
22:15:51.138  ■ turn: What is the date today and what is 1234.5 multiplied by 42?
22:15:51.138  → LLM #2 [agent] 5 messages, 977 chars, 28 tools; unchanged from the last request: 2 of 5 messages, new: 93 chars
22:16:10.156     first token after 19.0 s [agent] (reading the prompt), then writing
22:16:11.268     wrote 37 tokens in 1.1 s (33.6 tokens/s)
22:16:11.269  ← LLM #2 [agent] 20.1 s in all, tokens in 3,310 / out 37 → says: Today is Sunday, …
22:15:51.106  ⚙ tool currentDateTime [tools] {} → ok, 31 chars, 3 ms
22:16:11.279  ■ turn done in 143.9 s, 4 new messages
```

How to read a wait: the time before the first token is the server reading the prompt; after it, the
model writing. "Unchanged from the last request" says how much the server could take from its cache:
a low number next to a long "first token after" means the start of the prompt changed (a new
summary, a different tool list), so it had to read everything again. A long first token with only a
few new characters and a high unchanged count means the server itself is busy or slow. `[agent]`,
`[memory]`, `[skill/coding/plan]` … name the graph step that called the model or ran the tool.
`AGENT_LOG=full` adds the new messages of every request, every reply and every tool result.

**What each step was sent:** every model call of a coding job logs a `ctx` line with the size of the parts of its opening (`ctx edit: goal 0.1k · folder 0.1k · guide 5.2k · notes 3.0k = 8.7k chars (opening)`). A step gets only the parts it uses: the file list goes to choosing files and planning, the environment to planning, the design guide to edits and the design review. In the tool loops (study, free route) the results of reads from before the last 4 rounds shrink to a note with the first 400 characters; the model calls the read again when it needs the rest. A command you refuse ends the job's tool use: it answers with what it knows. The free route has 12 rounds of tool calls (`maxToolRounds`), then answers without tools. A message that only asks where something stands or how it works goes to the plain agent, not to a coding job, and a glob such as `ui/**/*.java` also matches files directly in `ui/`.

**Which tools ran:** every call a model makes is a `⚙ tool NAME [step] {arguments} → ok|error: …` line, so the Java tools
(`javaAdd`, `javaReplace`, `javaGraph`, …) show up there by name, with a refused change as `→ error: <why>`. A coding job's
model never calls tools; the graph applies each change itself and logs it: `✎ file · section (action): applied in memory,
structure checked, N lines written`, or `✎✗ …: refused, nothing written: <why>`. A Java change that uses a class which
is not imported, not in the project and not in `java.lang` is noted in the log as `⚠ … uses X` (`JavaUnknownTypes`); the build decides. Imports are added by the graph itself (`JavaImports`) when exactly one class has the name, a failed change gets one second try after the others, and every applied change is written to disk at once. Section names with wrong parameter types resolve when the name or the parameter count is unique, and a patch line without a sign counts as a line that stays. Search the log with
`grep "⚙ tool java\|✎"`.

## SearXNG (web search backend)

A SearXNG instance runs on the AI server for the `webSearch` tool: `~/searxng` with
`docker-compose.yml` and `searxng/settings.yml` (JSON output on, rate limiter off), on port 8888 of
the LAN, restarting with Docker. `SEARXNG_URL` in `.env` points at it.

```bash
ssh rka@192.168.68.59 "cd ~/searxng && docker compose logs --tail 50"            # logs
ssh rka@192.168.68.59 "cd ~/searxng && docker compose pull && docker compose up -d"  # update
```

Test it: `http://192.168.68.59:8888/search?q=test&format=json`. Some engines (Brave, DuckDuckGo)
throttle new instances for a while; Google and the others still answer.

## Running on Windows (Lemonade stays on the Linux box)

The window needs a local desktop, so run the app on your Windows machine and point it at Lemonade over the LAN.
Gradle downloads JDK 27 (Amazon Corretto) on first run if it isn't installed, and the build picks the Windows JavaFX libraries automatically.
You only need some JDK 17 or newer to start the Gradle wrapper (e.g. `winget install Amazon.Corretto.21.JDK`).

```powershell
$env:LEMONADE_BASE_URL = "http://192.168.68.59:13305/api/v1"
$env:LANGSMITH_API_KEY = "lsv2_..."   # optional
.\gradlew.bat run
```

In VS Code, put the same values in `.env` and use the **Chat UI (JavaFX)** run configuration.

## Adding a tool

Tools live in `src/main/java/net/rka/ai/tools`. The model sees each tool's name, description and parameters, decides when to call it, and gets the return value back.

1. **Write a class** with one or more `@Tool` methods (or add a method to an existing class):

   ```java
   package net.rka.ai.tools;

   import dev.langchain4j.agent.tool.P;
   import dev.langchain4j.agent.tool.Tool;

   public class WeatherTools {

       @Tool("Returns the current temperature in Celsius for a city")
       public double currentTemperature(@P("city name, e.g. Seattle") String city) {
           return 12.5; // call a real API here
       }
   }
   ```

2. **Register it** in `ToolRegistry.defaultTools()`:

   ```java
   return of(
           new DateTimeTools(),
           new MathTools(),
           new WeatherTools());
   ```

3. **Restart the app** and ask something that needs it. The call shows up in the chat window and in the traces as a `tool` span.

Tips:

- The `@Tool` text and `@P` descriptions are the model's only documentation. Say what the tool does, what each parameter means, and the formats you expect (e.g. ISO dates).
- Method names are the tool names and must be unique across all registered classes (or set `@Tool(name = "...")`). The registry fails at startup on duplicates.
- Parameters can be strings, numbers, booleans, enums, lists or simple records/POJOs; LangChain4j builds the JSON schema for the model.
- Return a `String`, number or simple object (it's serialized to JSON). Keep results short and readable.
- Throw an exception for bad input (see `MathTools.divide`). The message goes back to the model as an error result instead of crashing the run, so it can retry or explain.
- Tools that need configuration (API keys, URLs) can read it with `Env.get("NAME", default)` from `.env`.
- Tools run on the agent's worker thread, so slow calls don't freeze the UI, but the user waits for them.
- Add `@RequiresApproval` to a tool that changes things outside the app (runs commands, writes files,
  costs money). Its calls then go through the approval subgraph and you're asked before each one.

### Trusted folder and file tools

Each conversation has a **trusted folder**, your user folder unless you pick another one with
the small 📁 button under the input line (it's saved with the conversation; `WORK_FOLDER` changes the
default, and the CLI always uses the default). The file tools (`workFolder`, `listFiles`,
`findFiles`, `searchFiles`, `readFile`, `outline`, `readSection`, `writeFile`, `editSection`, `createDirectory`, `movePath`, `copyFile`, `deletePath`)
work **only inside that folder**: every path is resolved, following `..` and symlinks, and
refused if it ends up outside. Paths are relative to the folder. Reading runs without asking;
the tools that change files ask for approval unless you trusted the tool (`tool:writeFile`, …).
`searchFiles` finds lines in files (a regular expression or plain text, answered as `path:line: text`), so the
model can read just those lines with `readFile` `startLine`/`endLine` instead of whole files; it and
`findFiles` skip build output, version control and dependency folders (`build`, `.gradle`, `.git`,
`node_modules`, …) unless the glob names them. An existing file is never rewritten whole: a local model writes about 25
tokens a second, so a whole file takes minutes. It is changed part by part with `editSection` (below). `writeFile` refuses to replace a file that
already exists unless the call sets `overwrite` (the error points to `editSection`), so the
model can't replace code it never read by accident; `append` still adds to the end.

#### File outlines

There is one way to change an existing file: by its parts. (Quoting the old text exactly is what a local model
gets wrong again and again on big files: wrong indentation, text from memory; so there is no such tool.) And reading a big file costs minutes of prompt processing. So code
files are worked on **by their parts**:

- `outline(path)` lists the file's sections with their line numbers and the file's **md5**. For a Java file:
  `package`, `imports`, and per class its `<header>` (doc comment, annotations, the line with `{`), enum
  `constants`, fields, constructors, methods and initializers, and nested classes the same way. Ids read
  `Foo.LIMIT`, `Foo.run(String,int)` (parameter types without generics, so overloads differ),
  `Foo.Inner.method()`; a repeated id gets `#2`. For Markdown the sections are the headings
  (`Setup > Install`); any other text file is split into blocks of lines (`block 1`, …). A file with so many
  sections that the list would cost more than reading shows only its classes with their part counts;
  `outline(path, section)` lists the parts of one.
- `readSection(path, section)` reads one section. The id may be shortened when that is unambiguous (`Foo.run`,
  `run()`); the result starts with the md5.
- `editSection(path, section, newText, md5, mode)` changes it: `replace` (default; empty text deletes the
  section), `before`, `after`, or `add` with the class's id as the section. `add` uses the class's list of
  sections, each with a kind (field, constructor, method, nested class, ...) and a first and last line: the new
  part (its kind is found by reading it) goes **below the last section of its kind**, above the class's closing
  brace: a method below the last method, a field below the last field (one field next to another with no blank
  line), a constructor below the last constructor, or below the fields when there is none yet, a nested class
  below the last part; a class with no parts before it gets it right below its header. `newText` is the whole
  new part, for example a complete method with its doc comment; its indentation is fixed to the file's, the
  file's line breaks and final newline stay. The model never quotes old text.
- **The md5 is the proof the model looked at the current file.** `editSection` refuses an md5 that isn't the
  file's current one (the file changed since: look again), and a missing one. Each edit returns the new md5, so
  edits can follow each other; the result also lists the new section ids and says when ids no longer exist
  (a renamed method), so a changed id is never guessed.
- A `readFile` of a big code file without a line range returns the outline instead of its first 120 lines.

The outline is **made again on every call** (one pass over the text, no parser library: comments, strings and
text blocks are blanked out, then the braces are matched), never stored, so it can't be out of date and the
same file always gives the same outline. A file of 1,400 lines takes about 7 ms. A file that doesn't parse
cleanly still gets an outline of whatever was found. Sections are whole lines, so two members on one line
are one section. A coding job makes its changes this way itself: the plan names sections, and the graph
applies the model's new text for each one with the same section edit. The plain agent's system prompt tells it
to work this way too.

**Repeated calls:** a local model sometimes calls the same tool with the same arguments again and
again (for example `readFile` of the same lines three times in a row). The call still runs, but when
its result is exactly what an identical call already returned in this turn, the model gets a short note
instead ("Same result as your identical readFile call above … use that result and go on"), and the
agent log shows `↺`. A result that changed (the file was edited in between, a command's new output)
is passed on as usual. A call that fails with exactly the error an identical call already got keeps
that error plus "don't repeat it: change the call, or stop and tell the user". This applies to the
agent's tool calls and to skills' tool steps.

Terminal commands start in the trusted folder but aren't confined to it, so they always need
approval unless they're trusted commands.

Tools receive per-run values such as the folder by declaring an `InvocationParameters`
parameter (the model doesn't see it); read the folder with `WorkFolder.of(parameters)` and check
paths with `WorkFolder.resolveInside(folder, path)`.

#### Java projects in memory

In a Java project (the trusted folder has `.java` files) all Java files are read into memory once: each file's text and
outline, its types with their members, comments and annotations, indexes to find a type by name, and the **references
between types** (who extends, implements, declares or calls what). The index follows every change, and
**changes made on disk are taken in within milliseconds** (a file watcher, plus a look before every tool call).

- Every parsed type (`JavaClass`) holds the complete text of its file, and every entity in it (type, field, method,
  constructor, initializer, comment, enum constant, package, import) has the first and last line it covers in that
  text (`JavaClass.lines(member)` returns them). Every edit reads the file again, so the lines always match the text.
- Every change to a Java file is checked against its structure first: the result must still read as Java and hold exactly
  the declarations it held, minus those of the replaced part, plus those the new text declares. Otherwise the change is
  refused (nothing is written) and the model is told what is wrong, so a change can't leave a file that needs repair.
- `listFiles`, `findFiles` and `searchFiles` show the Java files as they are in memory (new files, deleted files and
  text that is not written yet included).
- `javaRead`, `javaAdd`, `javaReplace`, `javaDelete`, `javaImport` and `javaCreate` are the create / read / update / delete tools
  of the Java workspace: they name a member (`add`, `add(int)`, `<init>`, `static{}`, `Inner.name`) instead of quoting text,
  change exactly its lines, indent it like the other members, and refuse a result that is not valid Java or declares
  something twice.
- `javaOutline` shows a type or file as its members with the names the other tools take and their lines. `javaGraph` is the call
  graph: from the `main` methods (or a type or member) it follows calls, or callers, recursively, through declared types of
  variables, fields and parameters, `new`, static calls, chained calls and the subtypes of interfaces; it counts the calls
  it cannot follow (JDK, libraries, untyped values).
- **One parser.** The sections that `outline`, `readSection` and `editSection` show for a Java file (ids such as `Ledger.add(int)`) come
  from the same parsed structure as the Java tools, so a section is always a member and the lines of all tools agree. A file that
  does not read as Java (half-written) still gets an outline from a simpler scan of the text, so it can be repaired.
- **Partial changes are patches.** `patchFile` does the same for any file that is not Java (a build file, a document, a config): hunks
  found by their context, exactly once, indentation of the file kept; refused with nothing changed when a hunk fits nowhere or in several
  places. For a Java file use `javaPatch`.
- **Partial changes are patches (Java).** `javaPatch` changes part of a member with hunks like a patch: lines that stay (context), `-` lines
  that go, `+` lines that are added. A hunk is found by its context and removed lines, which must be in the member exactly once, so it lands
  in the right place whatever was added or moved above it; the indentation of the file is kept. A hunk that fits nowhere or in
  several places changes nothing and says which line does not match or where it fits. The block tools take an optional `expect` (the
  first line of the statement at the address) and refuse the change when the statements have moved. All Java tools (and the
  workspace) work on `.java` files only; other files are for the file tools.
- **Statements inside a method.** `javaBlocks` shows a method as a tree of statements, each with an address (`2`, `2/body/1`,
  `5/then/2`), kind, lines, declared variables and calls; `javaReadBlock` reads one with the variables a statement there can
  use and what it reads, writes and calls. `javaReplaceBlock`, `javaInsertBlock` (before, after, start or end of a part) and
  `javaDeleteBlock` change a few statements of a long method without writing the rest of it, and are refused (nothing changes)
  when the new code would not land where the address says (a statement without braces takes only one), when a variable that only
  the removed code declared is still used, or when a variable would be declared twice. The statements come from the JDK's parser
  (`JavaBlocks`), built when asked for and kept per text; see `docs/superpowers/plans/2026-11-04-code-blocks.md`.
- `javaProject`, `javaFind` and `javaUsages` answer from the indexes ("which types use `Ledger`?", "what may call
  `Ledger.size`?") without searching files.
- `outline`, `readSection`, `editSection`, `readFile` and `writeFile` work on the text in memory for `.java` files: an
  edit is **not written to the file** until a command runs (a build or a test), the turn ends, or the job ends
  (`javaFlush` writes at once, `javaDiscard` throws the changes away). Other files are written at once.
- The coding job does the same: its edits, repairs and style samples use the workspace, the plan sees which types use the
  types of the picked files, and a job that ends with a failing build discards its changes in memory and restores the files.
- If a file changed on disk (an editor, git) while the job had changes in memory for it, nothing is overwritten: the file
  is named in the report or the error, and `javaFlush` with `force` decides for memory.

Details and limits: [docs/agent-graph.md](docs/agent-graph.md#the-java-workspace).

### Web search

- `webSearch(query, maxResults)` asks the SearXNG instance in `SEARXNG_URL` (see
  [SearXNG](#searxng-web-search-backend)) and returns numbered results: title, URL, snippet.
- `fetchPage(url)` downloads a public page and returns its title and main text (jsoup removes
  scripts, menus and footers; at most 20,000 characters). It refuses local and private network
  addresses (localhost, 192.168.x.x, 10.x, …), also when a redirect points there, so a web page
  can't steer the agent into your LAN.
- Both are marked `@WebContent`: their text is untrusted. **Once a web result is in the current
  turn, file changes (`@WritesFiles`: write, create directory, move, copy, delete) and trusted
  commands need your approval** until you send your next message. Reading files stays free.

### Terminal and SSH tools

- `runCommand(command, workingDirectory)` runs a command on the machine the app runs on (bash on
  Linux/macOS, PowerShell on Windows), in the trusted folder unless `workingDirectory` says otherwise. `systemInfo()` tells the model which OS and shell it has.
- `runRemoteCommand(host, command)` runs a command with your `ssh` client on a host listed in
  `SSH_HOSTS`; `sshHosts()` lists them. Login must work without a password prompt (SSH key or
  agent), and the host must already be in `known_hosts` (connect once by hand first).
- Commands can't read input, are stopped after `COMMAND_TIMEOUT_SECONDS`, and return the exit
  code, stdout and stderr. Both run tools need approval for every call, except commands in
  trusted commands (global or conversation settings).

## Declaring graphs with annotations

Graphs are declared on a class with annotations from `graph-annotations`. At compile time the
`graph-processor` checks the wiring and generates `<ClassName>Definition` next to the class,
which builds the LangGraph4j `StateGraph`. Mistakes are compile errors, shown in VS Code like any other:

```
AgentGraph.java:43: error: @Edge(from = "tools", to = "agnet"): unknown node 'agnet'. Did you mean 'agent'?
```

The agent graph ([AgentGraph.java](src/main/java/net/rka/ai/agent/AgentGraph.java)):

```java
@Graph(name = "agent", state = AgentGraph.State.class, serializer = Graph.Serializer.LANGCHAIN4J)
@Edge(from = Graph.START, to = "agent")
@Edge(from = "tools", to = "agent")
public class AgentGraph {

    @Node("agent")
    Map<String, Object> callModel(State state) { ... }

    @Node("tools")
    Map<String, Object> executeTools(State state) { ... }

    @Router(from = "agent", routes = {
            @Route(on = "tools", to = "tools"),
            @Route(on = "done", to = Graph.END)})
    String route(State state) { ... }

    @NodeWrapper
    NodeActionWithConfig<State> traced(String name, NodeActionWithConfig<State> action) { ... }

    public CompiledGraph<State> compile() throws GraphStateException {
        return AgentGraphDefinition.compile(this);
    }
}
```

| Annotation | On | Meaning |
|------------|----|---------|
| `@Graph(name, state, schema = "SCHEMA", serializer)` | class | Declares a graph. `state` extends `AgentState`, has a `Map` constructor and a static schema field. Use `serializer = LANGCHAIN4J` when the state holds LangChain4j messages. |
| `@Edge(from, to)` | class, repeatable | Fixed edge. `Graph.START` / `Graph.END` for entry and exit. |
| `@Node("name")` | method | A step: `Map<String, Object> m(S state)` or `m(S state, RunnableConfig config)`, returning state updates. |
| `@Router(from, routes = @Route(on, to))` | method | Conditional edge: `String m(S state)`; the returned value picks the route. |
| `@Subgraph("name")` | method | A nested graph as one node: no parameters, returns `StateGraph<S>` or `CompiledGraph<S>` (e.g. `OtherGraphDefinition.stateGraph(other)`). |
| `@NodeWrapper` | method | Wraps every node, e.g. for tracing: `NodeActionWithConfig<S> m(String name, NodeActionWithConfig<S> action)`. |

The processor reports: unknown or misspelled node names (with suggestions), a missing entry
edge, nodes with no way out or with both an `@Edge` and a `@Router`, nodes unreachable from
START, duplicate names, wrong method signatures, private methods, and annotations outside a
`@Graph` class. It warns when no path reaches END. The generated class is in
`build/generated/sources/annotationProcessor`, with a text outline of the graph in its Javadoc.

Graph methods must not be private, because the generated class (same package) calls them.

## Reading the code

Every class starts with a Javadoc that explains **how it works** and lists **Not implemented yet**
ideas for improving it. Good order to read: `Agent` → `AgentGraph` (and `docs/agent-graph.md`) →
`ToolRegistry` → the tool classes → `ChatWindow`. To collect all improvement ideas:

```powershell
Get-ChildItem -Recurse -Filter *.java -Exclude *Test.java | Select-String -Pattern "Not implemented yet" -Context 0,12
```

## Layout

Subprojects: `graph-annotations` (the annotations) and `graph-processor` (the compile-time processor and its tests, `./gradlew :graph-processor:test`). The app is the root project, in `src/main/java/net/rka/ai`:

| Package    | Contents |
|------------|----------|
| `agent`    | `Agent` (runs a turn: pauses for approval, plan, skill questions and skill drafts and resumes, reports `Progress`), `AgentGraph`, `ApprovalGraph`, `TrustedGraph` and `SkillBuilderGraph` (annotated graphs), `CodingGraph` (the steps of the coding skill), `CodingPlan` (the plan and its check), `Approver`, `SkillDraft` |
| `agent.skills` | Skills: `SkillFile` (the file), `SkillLoader` (built-ins + `~\chat\skills`), `SkillValidator`, `SkillGraphBuilder` (file → subgraph), `GenericKinds` and `CodingKinds` (the node kinds), `SkillDiagram` (the generated pages); the built-in `coding.json` is in `src/main/resources/net/rka/ai/agent/skills` |
| `tools.code.java` | `JavaWorkspace` / `JavaWorkspaces` (all Java files of a project in memory, with indexes, cross-class `Reference`s, changes in memory, a file watcher, and `flush` back to the files; see [Java projects in memory](#java-projects-in-memory)), `ProjectFiles` (Java files from memory, other files from disk). An immutable model of a Java file (`JavaClass`, `JavaField`, `JavaMethod`, `JavaConstructor`, `JavaInitializer`, `JavaEnumConstant`, with comments, annotations and modifiers), `JavaSourceReader` (source → model, no parser library) and `JavaSourceWriter` (model → source). `JavaRoundTrip` reads Java files and writes them again as `Name_output.java`: `.gradlew.bat roundTripJava --args="src/main/java/some/folder --out build/roundtrip"` (without `--out` the file lands next to the input, where it declares the same classes and breaks the build until deleted). Members keep the order of the source; bodies, field initializers and enum arguments stay source text; all comments are kept except those inside the head of a declaration (between modifiers, in a parameter list) and after the last type. Two tests check it by meaning (the JDK compiler's syntax trees, members as a set): `JavaSourceSemanticsTest` writes all of `src/main/java` into `build/roundtrip-test` (and checks that every comment is still there, in order), `JavaEveryConstructTest` does the same for `src/test/resources/.../EveryConstruct.java`, a class with every construct, and writes `EveryConstruct_output.java` beside it (git ignores `*_output.java`) |
| `tools`    | `DateTimeTools`, `MathTools`, `FileTools`, `WebTools`, `TerminalTools`, `SshTools` (`@Tool` methods), `WorkFolder` (the trusted folder and path confinement), `ToolRegistry` (which tools are enabled), `@RequiresApproval`, `TrustedCommands` (matches commands against the trusted list) |
| `tracing`  | `Tracing` (OpenTelemetry setup: LangSmith and/or local files), `JsonlFileSpanExporter`, `TracingChatModelListener` (one `llm` span per model call) |
| `config`   | `Env`: reads settings from the environment, then `.env`; `Settings`: global and conversation settings (trusted folder and commands) |
| `eval` | The evaluation framework (`EvalMain`, `EvalRunner`, `EvalCase`, `EvalChecks`, `EvalMetrics`, `EvalReport`, `AutoApprover`): see [Evaluations](#evaluations) |
| `conversations` | `ConversationStore`: saves and loads chat-window conversations as JSON files |
| `ui`       | `ChatWindow` (with the conversations sidebar), `ToolOutputPanel`, `ApprovalDialog` and `Launcher` (JavaFX), styles in `src/main/resources/chat.css` |
| `cli`      | `Main`: command-line entry point (`./gradlew runCli`), `ConsoleApprover` (y/N prompt) |
