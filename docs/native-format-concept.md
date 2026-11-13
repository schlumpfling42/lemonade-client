# Concept: talk to the model in its native format

Status: proposal, nothing built. It replaces the message format of `docs/graph-redesign.md` (one big user message in type notation, answer as JSON text) with the format the model and its server are made for. The graph, the validators, the approvals and the goals of that document stay.

## 1. What the model and the server give us (measured on your server, user.qwen3.8-27b)

| native thing | what it does | checked |
|---|---|---|
| roles `system`, `user`, `assistant`, `tool` | the conversation as the model was trained to read it | used today |
| `tools` (name, description, JSON schema per parameter) | the model knows each argument's name, type, meaning, whether it is required | works; lost today when the classes lack `-parameters` |
| `tool_choice` `auto` / `required` / `none` / `{function: name}` | the model must call, must not call, or must call this one | `required` and named: both answered with exactly one call whose arguments follow the schema (enums respected) |
| `tool_calls` in the answer, `tool` message with the same id as the result | a decision, and what came of it | works |
| `response_format: json_schema` | a pure data answer, valid by construction | works: the answer was valid JSON of the schema |
| prefix cache of the server | what was read once is not read again, as long as the start of the prompt is identical | second identical request: 4 of 468 tokens read, 0.9 s instead of 4.9 s; same start, other last message: 16 tokens read; `tool_choice` may change without breaking it |

The prefix cache is the main reason for the layout in section 3: the server reads about 100 to 130 tokens a second cold, so every token read twice costs about 8 ms.

## 2. Five rules

1. **A decision is a function call.** The model does not write JSON for the program to find; it calls `route(...)`, `ask_user(...)`, `submit_plan(...)`. The server checks the arguments against the schema; the program checks what the schema cannot.
2. **Data arrives as tool results.** What the model needs to see (outlines, code, build output, the user's answer to a question) is the result of a call, in the `tool` role, not text pasted into a user message. A model trained on "I called outline and got this" reads it as facts it holds.
3. **One conversation per job, a constant start.** `system` and `tools` are the same for every step of a job and for every job. A step is a short `user` message appended at the end, plus a `tool_choice`. Nothing before it changes, so the server reads each token once.
4. **A mistake goes back as a tool result.** A plan the validator refuses, an edit that breaks the structure, a build error: the result of the call says what is wrong, and the model answers in the same conversation, seeing its own try. That is the "second try" and the "fix" of today, without special code. The graph limits the rounds.
5. **Data without a decision uses `response_format`.** Only where no function fits (for example a list of files).

The compact type notation stays where it helps the model: in the `description` of a function, parameter or enum value ("every term defined where it is used").

## 3. Layout of every request

```
system   constant: who you are and the rules, a few lines
tools    constant: the same list for every step (the cache needs it identical); a step restricts with tool_choice, not by removing tools
messages the conversation as it happened (user / assistant / tool), then this job's steps, appended
  ...
user     the step: "Submit the plan." (one short line, last)
tool_choice  required | { function: submit_plan } | none
```

## 4. The functions (names are proposals)

| function | arguments | handled by | answer to the model |
|---|---|---|---|
| `route` | `goal: str` (stands alone, references written out), `edge: enum` with one described value per edge (generated from `@Offers`) | the graph: ends the step, goes to the edge | none (the step ends) |
| `ask_user` | `question: str`, `options: [str]` | the app: pauses, shows buttons and a text box | the user's answer, as the tool result |
| read tools | as today (`findParts`, `searchFiles`, `outline`, `readSection`, `readFile`), full descriptions | the registry | the result |
| `submit_plan` | `changes: [{file, section, action: enum, intent}]`, `setup: [str]`, `verify: [str]`; no code | the validator, then the approval | `refused: <reason>` (the model corrects), or the user's comment, or `approved` |
| edit tools | `replace_section(file, section, text)`, `add_member(...)`, `patch_file(...)`, `create_file(...)`, `delete(...)` (today's `editSection`, `patchFile`, `writeFile`) | the structure check, then the write (through to disk at once) | `applied` or `refused: <reason>` |
| `run_build` | none; the program makes this call itself (a synthetic exchange: the call, then the output as its result) | the program | the build output |

## 5. The graph, in this format

- **analyze.** `system` (route the message, three lines), the real conversation as real messages, tools = `route`, `ask_user`, read-only tools; `tool_choice: required`. A read tool: run it, append the result, ask again. `ask_user`: pause, the answer is the tool result, ask again. `route`: done. At most 6 rounds; then `route(agent)`.
- **agent.** As today: all tools, `tool_choice: auto`, at most 12 rounds, the last answer with `tool_choice: none` (the tool definitions stay in the request, so the start still matches).
- **job.** One conversation continues from the analysis: the same `system` and `tools`, the goal as the first `user` message.
  - locate: the program makes the exchange for the model: `outline(...)` calls and their results appear as if it had read them.
  - plan: `user`: "Submit the plan." with `tool_choice` named `submit_plan`. Refused plans cost one short round, not a rewrite from nothing.
  - approve: the user's comment is the result of `submit_plan`; the model answers with a changed `submit_plan`.
  - edit: `user`: "Make change 1." `tool_choice: required` over the edit tools. The model calls them; the program applies and answers.
  - verify: `run_build` by the program; a failure appends the output, then "Fix it." `tool_choice: required` (at most 3 rounds).
  - report: the program writes it (no model call), as in the redesign.

## 6. What goes away, and what stays

Goes: the big type-notation user messages with `conversation = [...]`, `Analysis.parse` and the JSON search, the retry text "your last answer could not be used", the one-line tool summaries (`firstSentence`, bare argument names), the code-block answers of the edit step, the second try and the fix prompts as separate code.
Stays: the graph and its edges (`@Offers` generates the `route` function), the validators and structure checks (now answering through tool results), the approvals, write-through, the cap on rounds, Stop, the evaluation framework.

## 7. Risks, and what to measure first

| question | how to find out |
|---|---|
| Does code in a tool argument survive? JSON escapes quotes and newlines; the model may slip on 200 lines. | `create_file` with a 100-line class, 10 times, compare with the code-block answer of today. One try so far: a 44-line class with quotes, a backslash-n and a switch came back as valid arguments (339 tokens, 12 tokens a second) |
| Does the model think a long time before a forced call? Thinking counts as output and costs about 12 tokens a second. | time the calls; for decisions turn thinking off per request (`chat_template_kwargs: {enable_thinking: false}`), if the server takes it |
| Several calls in one answer (parallel tool calls) | `route` must be alone: ignore the rest, tell the model |
| A long job conversation grows | the cache keeps reading cheap, but the context is finite (65,536 tokens here): cut old tool results only when over a limit, never earlier (rewriting an old message breaks the cache) |
| The function list is long (34 tools, about 4,300 tokens) | it is read once per conversation, then cached; an analysis with only the read tools is shorter: its list is constant too |

## 8. Order (each step measured with `runEval` and `runRouting`)

1. (built; routing accuracy with the real model not yet measured) `analyze` with `route`, `ask_user` and the read tools, `tool_choice: required`. Compare with the JSON-text analysis on the routing cases.
2. (built: the plan step calls `submit_plan`, `PlanFunction`; a text reply is still read) `submit_plan` in place of the plan JSON.
3. (built for the edit step: the function `write_code` takes one text per change, `EditFunction`; the fix step too) Edit tools in place of code blocks; the second try disappears.
4. The job as one conversation with a constant start; measure the cache in the log (the server reports `cache_n`).
