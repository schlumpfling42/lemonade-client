# langchain-example

A minimal Java agent built with **LangChain4j** (LLM client + tools) and **LangGraph4j** (graph orchestration).
It has a **JavaFX** chat window and a command-line mode, and targets **Java 27** through a Gradle toolchain (Gradle finds JDKs in `~/.jdks`).
It calls a **Lemonade** server through Lemonade's OpenAI-compatible API and sends traces to **LangSmith** over OpenTelemetry.

```
START -> memory -> agent                                       memory = summarize old messages if too long
                  agent --(safe tool calls)-----> tools ----> agent
                        --(only trusted risky)--> trusted --> agent    trusted  = subgraph: verify -> execute
                        --(needs approval)------> approval -> agent    approval = subgraph: review -> execute
                        --(startCodingJob)------> skill-coding -> agent   the built-in skill coding, see below
                        --(buildSkill)----------> skillBuilder -> agent   subgraph, see Skills
                        --(skill_<name> call)---> skill-<name> -> agent   one subgraph per skill file, see Skills
                        --(no tool calls)-------> END                  (also after 12 tool rounds in one turn)

skill-coding = start -> environment -> investigate -> plan -> review
         -> (pause: you approve the plan) -> implement -> verify -> designReview -> report
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

For multi-step coding work in the trusted folder (a new project, a framework, a larger
refactoring, design work across several files) the model calls the tool `startCodingJob(goal)`.
That starts the built-in skill `coding` (see [Skills](#skills)); small edits still use the file and command tools directly.

1. **Investigate**: the model looks at the folder with read-only tools and a fixed list of
   version-check commands (probes, 20 s timeout), at most 6 rounds. The tools it finds are cached in
   `~/chat/environment.json` (stale after 7 days, refreshed when a verify run shows a missing tool).
2. **Plan and review**: two model calls write and check a plan: steps with commands and file
   changes, plus verify commands. Every command must finish on its own (no dev servers or watch
   modes). Invalid JSON gets one retry, then the job reports.
3. **You approve the plan**: the window shows a plan dialog with **Approve**, **Revise** (with a
   comment, which gives a new plan) and **Deny**. The CLI accepts `y`, `r <comment>` or N. A
   revised plan starts with "Revised plan: ..." (why it changed) and marks the steps already done.
   The plan is also saved as a Markdown file in `~/chat/plans/` (named by date and goal) for you to
   review: the dialog has **Open plan file**, and the chat shows a "📄 Plan" link (side panel) with
   **Open file** (your editor). The file is rewritten with each revised plan and finally with the
   job's report. The plan itself is not shown in the chat.
4. **Implement**: the model works through the steps (at most 15 turns per step). A step that lists
   commands or file changes only counts as done when at least one of them ran successfully; if
   the model answers without running any, it is reminded once, then the step is marked
   **skipped**, and the report says "Done with skipped steps" (flagged as an error), never "done".
5. **Verify**: the plan's verify commands run, at most 3 attempts per job; a failure sends the job
   back to implement to fix it. (The "Verifying" progress shows once the verify commands have
   finished.)
6. **Design review**: once the build passes, the model reads the code the job wrote and checks it
   against the design guide: is it readable and easy to change (instances over static, small
   classes, immutable data, clear names, ...)? Issues it finds go back to implement once, as a fix
   limited to what the plan covers, then verify runs again; a second review records what is left.
   Remaining issues are listed in the report under "Design review" but don't fail the job. Then the
   job reports.

**Design guides** steer this and are also given to plan, plan review and implement. The built-in
Java guide ships with the app; your own rules go in `~/chat/guides/java.md` (created with a short
note the first time) and win where they conflict. Any `~/chat/guides/<language>.md` you add is a
guide too, e.g. `svelte.md`: it applies when the job's goal or findings name the language or the
plan changes files with that extension. Edits apply to the next job.

A model or tool error, a second invalid plan, or a step over its 15 turns stops the job early:
the report says why ("Stopped: model error: ... Steps 1 to 2 are done.") and still answers the
`startCodingJob` call, so the conversation keeps a record. The report shows the plan you approved
last; a revision you denied is shown below it, labelled as not approved (when a revision never
became a valid plan, the outcome line says so).

**What your approval covers**: only the commands and file changes in the plan, only inside the
trusted folder, and only until the job ends. A command matches by its exact text and working
directory (no wildcards; extra spaces and tabs are ignored, but a newline makes it a different
command) and may run any number of times; a file entry ending in `/` covers everything under that
folder, but never deleting or moving the folder itself. `copyFile` needs a `create` or `modify`
entry for the target, and its source must be inside the trusted folder. If the model calls
anything the plan doesn't cover, nothing from that reply runs and the job goes back to a revised
plan for your approval; steps already done are kept. Your trusted commands still run without
being in the plan, but not once web content came into the turn (before the job, in it, or in an
earlier job or skill); a page read inside a job also makes the rest of the turn ask, like one read outside. SSH, `remember`,
`forget`, `buildSkill` and the skills' tools are never available in a job.

The job's progress shows live: a card in the window ("Coding · Step n/m · `cmd`") and lines in
the CLI. Its own work shows live too, as muted "Coding job" notes (findings, step summaries,
thinking) and tool pills, not saved with the conversation. Another skill's work shows the same way,
its notes labeled with the skill's name and its progress as `<skill> · <node> · step n/max`. While the plan is written you see only
"Writing the plan… n characters"; the plan goes to its file and the plan dialog.
The job's report is the result of the `startCodingJob` call; it is saved with the
conversation and flagged as an error unless the job is Done. Job commands stop after
`CODING_COMMAND_TIMEOUT_SECONDS` (default 600).

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
| `model` | `prompt`\*, `tools`, `output`, `format` (`text` / `json`), `thinking` (`shown` / `progress`) | `done` | Asks the model with `prompt`. With `tools` (names of tools) it may call them, round after round (`maxToolRounds`), before it answers; the final text goes to the variable `output`. `format: json` parses the answer, so later nodes can use `{{<output>.<field>}}`. `thinking: progress` shows progress instead of the streamed text. The model can't ask you anything here: use `askUser` for that. |
| `askUser` | `question`\*, `choices`\*, `output` | one per choice | Pauses and shows `question` with a button per choice (the CLI numbers them: `2`, `2 a comment`, `c <comment>` for the first choice with a comment). The answer goes to `output` as `{choice, comment}`. Closing the question, a failing approver or no input ends the skill with an error ("Stopped: cancelled by the user."), always through the automatic stopped finish, even when the node maps `stopped` in `on`. |
| `branch` | `cases`\* (list of `{when, then}`) | each `then`, and `otherwise` | The first case whose `when` holds gives the outcome; none gives `otherwise`. |
| `set` | `values`\* (variable → value) | `done` | Each value is a template, `{"increment": 1}` (adds to a number, a missing one counts as 0) or `{"append": "<template>"}` (adds to a list). Values are computed from the variables as they were before the node. |
| `finish` | `result`\*, `error` | none | Ends the skill: `result` (a template) is the tool's answer in the conversation; `"error": true` flags it as failed. |

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
  coding steps) and `maxToolRounds` (rounds of tool calls per visit of a `model` node; default 8,
  1 to 50). A loop stops at `maxSteps` with an error result; the conversation always keeps the call
  answered. Other limits are only the ones a kind declares (the coding steps: `maxInvestigateRounds`,
  `maxStepTurns`, `maxVerifyAttempts`, `maxDesignRounds`, `maxPlanAttempts`, 1 to 100).

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
step kinds (`coding.environment`, `investigate`, `investigateTools`, `plan`, `review`,
`planApproval`, `implement`, `implementTools`, `verify`, `designReview`, `report`), holds their
prompts and limits, and sets `"tool": "startCodingJob"`. Its `maxSteps` is 1000 at most (the coding
cap); the file uses 600.

You can change it with the builder ("change the coding skill so that ..."), which writes
`~\chat\skills\coding.json` with `"replaces": true` (its draft request also teaches the model the coding
kinds, their outcomes and limits, the cap of 1000 and the pinned tool). Such a file replaces the built-in, but it is
validated as a user file: its `tool` stays `startCodingJob` (leave it out), the coding kinds take only
a `prompt`, and the validator rejects any wiring that reaches `implement`, `implementTools` or `verify`
without going through `planApproval`'s `approve` (from the start and from every plan and review step),
or that reaches another coding step before `environment`. The plan approval can't be wired around, and
the plan check, the trusted-folder confinement and the web content rule are enforced by the steps
whatever the file says. If the replacement is invalid, the built-in is used and the errors are listed
in Settings; remove the file and reload to go back to the built-in.

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
| `LEMONADE_MODEL`      | `Qwen3-30B-A3B-Instruct-2507-GGUF`   | Any model id from `/api/v1/models` (needs tool calling) |
| `LEMONADE_API_KEY`    | `lemonade`                           | Only if your server requires a key   |
| `LANGSMITH_API_KEY`   | *(unset = LangSmith off)*            | LangSmith API key                    |
| `LANGSMITH_PROJECT`   | `langchain-example`                  | LangSmith project name               |
| `LOCAL_TRACES`        | `true`                               | Write traces to local JSONL files    |
| `TRACE_DIR`           | `traces`                             | Folder for local trace files         |
| `SSH_HOSTS`           | *(none)*                             | Comma-separated hosts the SSH tool may use, e.g. `rka@192.168.68.59` |
| `SETTINGS_FILE`       | `~\chat\settings.json`               | Global settings file (trusted folder and commands) |
| `TRUSTED_COMMANDS`    | *(none)*                             | Initial global trusted commands, comma-separated, until `~\chat\settings.json` exists |
| `WORK_FOLDER`         | *(your user folder)*                 | Default trusted folder when the global settings don't set one |
| `CONVERSATIONS_DIR`   | `~\chat\conversations`               | Where the chat window saves conversations (one JSON file each) |
| `MEMORY_SUMMARY_TOKENS` | `6000`                             | Summarize older messages once the unsummarized part is larger than this (estimated tokens) |
| `MEMORY_RECENT_TOKENS` | `2000`                              | How much of the newest messages stays verbatim when summarizing |
| `MODEL_CONTEXT_TOKENS` | `16384`                             | The model server's context size; each request is shortened to fit it |
| `MODEL_STREAMING` | `true`                                   | Stream replies token by token (thinking, text, tool calls) to the window and CLI |
| `COMMAND_TIMEOUT_SECONDS` | `60`                             | Terminal/SSH commands are stopped after this |
| `CODING_COMMAND_TIMEOUT_SECONDS` | `600`                      | Commands of a coding job are stopped after this |
| `COMMAND_MAX_OUTPUT_CHARS` | `8000`                          | Longer command output keeps only the end |
| `LANGSMITH_ENDPOINT`  | `https://api.smith.langchain.com`    | Use `https://eu.api.smith.langchain.com` for EU |

## Run

```fish
set -x LEMONADE_BASE_URL http://my-lemonade-host:13305/api/v1
set -x LANGSMITH_PROJECT langchain-example
./gradlew run                                      # JavaFX chat window
./gradlew runCli --args="What is 1234.5 times 42?" # one question on the command line
```

The window needs a desktop session (it won't open over plain SSH). Each chat is one LangSmith thread, and each message you send is one trace. **New chat** starts a new thread.

Conversations are saved after every answer to `CONVERSATIONS_DIR` (`~\chat\conversations\` by
default, next to `~\chat\settings.json`), one JSON file per conversation with the full message
history, including tool calls and results, and the conversation's memory (below).
The left sidebar lists them, newest first: click one to reopen and continue it (it keeps its
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
The CLI doesn't save conversations.

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
- **Context fitting** protects a single turn: if one request (for example after reading big
  files) would not fit `MODEL_CONTEXT_TOKENS`, the oldest tool results in that request are cut
  in the middle, marked as cut, before it is sent; the conversation keeps them in full. If the
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
`findFiles`, `readFile`, `writeFile`, `createDirectory`, `movePath`, `copyFile`, `deletePath`)
work **only inside that folder**: every path is resolved, following `..` and symlinks, and
refused if it ends up outside. Paths are relative to the folder. Reading runs without asking;
the tools that change files ask for approval unless you trusted the tool (`tool:writeFile`, …).

Terminal commands start in the trusted folder but aren't confined to it, so they always need
approval unless they're trusted commands.

Tools receive per-run values such as the folder by declaring an `InvocationParameters`
parameter (the model doesn't see it); read the folder with `WorkFolder.of(parameters)` and check
paths with `WorkFolder.resolveInside(folder, path)`.

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
| `tools`    | `DateTimeTools`, `MathTools`, `FileTools`, `WebTools`, `TerminalTools`, `SshTools` (`@Tool` methods), `WorkFolder` (the trusted folder and path confinement), `ToolRegistry` (which tools are enabled), `@RequiresApproval`, `TrustedCommands` (matches commands against the trusted list) |
| `tracing`  | `Tracing` (OpenTelemetry setup: LangSmith and/or local files), `JsonlFileSpanExporter`, `TracingChatModelListener` (one `llm` span per model call) |
| `config`   | `Env`: reads settings from the environment, then `.env`; `Settings`: global and conversation settings (trusted folder and commands) |
| `conversations` | `ConversationStore`: saves and loads chat-window conversations as JSON files |
| `ui`       | `ChatWindow` (with the conversations sidebar), `ToolOutputPanel`, `ApprovalDialog` and `Launcher` (JavaFX), styles in `src/main/resources/chat.css` |
| `cli`      | `Main`: command-line entry point (`./gradlew runCli`), `ConsoleApprover` (y/N prompt) |
