# Graph redesign (proposal, nothing built)

Rules this design follows (all from the traces and from our discussion):
- Understand first: one call turns the conversation into `goal`, `questions`, `next`. Everything after works from `goal`.
- The program decides from fields it can parse. Free text is for the model, not for routing.
- Messages to the model are type notation, top-down, every term defined where it is used (see memory `feedback_prompt_style`).
- Mechanical work is code, not a model call (find files, build, validate).
- A refusal must be cheap: validate as early as possible, never after minutes of writing.
- Constant text first, changing text last, so the server can reuse what it read (about 8 s per 1,000 tokens saved).
- Stop cancels the graph, the model request and any write.

## 1. Main graph

```
START -> memory -> analyze -> route
route: questions != []   -> ask_user -> analyze        (the answers are added to the conversation; analyze runs again)
route: next == "agent"   -> agent loop (tools, <=12 rounds, last answer without tools) -> END
route: next == "job_*"   -> job -> END                 (the job's report is the answer: no second agent call)
```

| node | model call | does |
|---|---|---|
| memory | no | today's summary of older messages when the conversation is too long |
| analyze | yes | message below; reads JSON; invalid JSON: ask once more, then `next = "agent"` |
| ask_user | no | pause; shows each question with its buttons and a text box; the answer goes into the conversation |
| agent loop | yes | as today, with `tool_choice: none` for the last answer |

### Message: analyze
```
# Reply with JSON with all three fields, nothing else.
goal:      str    # what the user wants: one sentence that stands alone, references written out
questions: [{ question: str, options: [str] }]   # asked first; [] if nothing is unclear; the user can always answer freely
next:      "agent"       # one model call, all tools, answers in text: question, explanation, example, file handling
         | "job_plan"    # coding job: choose files, plan, approval, edit, build: the request names the class or method and what to change
         | "job_study"   # coding job: read code first, then plan, approval, edit, build: the change touches several places or hooks into existing code
         | "job_free"    # coding job without a plan, works with tools, then build: cause unknown, something fails, commands must be run

conversation = [ ... ]   # oldest first, every question and every answer verbatim
latest_user_message = """..."""
```

## 2. Job graph

```
job_plan :  locate -> plan -> approve -> setup -> edit -> verify -> report
job_study:  research -> locate -> plan -> approve -> setup -> edit -> verify -> report
job_free :  work -> check -> report
verify fails -> fix -> verify   (at most 3 rounds, then report with the failure)
plan refused by the validator -> plan again (at most 2)
```

| node | model call | does |
|---|---|---|
| research | yes (tools, read-only, <=8 rounds) | reads the code; writes notes (what exists, where the change belongs) |
| locate | no | resolves the class, file and method names in `goal` and the notes to files; builds outlines and code of the places (today's `gather`). Finds nothing: `pick` below |
| pick | yes, only when locate found nothing | compact message, returns the files |
| plan | yes | message below; the validator runs on the reply at once |
| approve | no | pause; the user approves, comments or denies |
| setup | no | the plan's setup commands |
| edit | yes, one per file | message below |
| verify | no | the build; failures mapped to sections |
| fix | yes, one per failing section | message below |
| work | yes (tools, <=12 rounds) | free route: works with tools; the last answer without tools |
| check | no | the build of what changed |
| report | no | the report (goal, changes, build result) is the job's answer |

### Message: pick (only when locate finds nothing)
```
# Reply with JSON, nothing else.
files: [str]    # at most 6 paths from project_files that the work needs, most important first; [] for new files only
goal: str = """..."""
project_files = """..."""   # path: line count
```

### Message: plan
```
# Plan the change. Reply with JSON of type Plan, nothing else. No code in it.
class Change(TypedDict):
    file: str          # relative path; for create: the new file
    section: str       # id from outlines; "" for create and for delete of a whole file
    action: "create"   # a new file
          | "replace"  # the section's new text takes its place
          | "add"      # a new member in the class named by section
          | "before" | "after"   # new text next to the section
          | "start" | "end"      # statements at the start / end of a method body
          | "delete"   # the section, or the whole file when section is ""
          | "patch"    # up to 8 changed lines, written as hunks in `patch`
    intent: str        # at most 25 words: what is different afterwards
    patch: str         # only for action "patch"
class Plan(TypedDict):
    summary: str
    changes: [Change]  # at most 12; every new class must be used by another change or by existing code
    setup: [str]       # commands before the changes; never one that changes the build tool
    verify: [str]      # [] = the project's own build; one command each, no && or ||
    read: [{ file: str, section: str }]   # only instead of changes: code you need to see first (at most 2 times)

goal: str = """..."""
environment = """..."""      # os, shell, tool versions
notes = """..."""            # research notes, if any
outlines_and_code = """..."""
```

### Message: edit
```
# Write the new text of every change below, in order. One code block per change, nothing else.
# create: whole file | replace: whole section | add/before/after: only the new part | start/end: only the statements
style: str = """..."""       # indentation and conventions of the file
changes = [ { action, section, intent, current_text } ]
design_guide = """..."""     # only where the file shows no style of its own
```

### Message: fix
```
# Reply with one code block: the complete new text of this section. Reply SKIP if the cause is not in it.
build_error: str = """..."""
section = """..."""          # id and current text
callee_sources = """..."""   # only when the error is "cannot find symbol"
```

## 3. What changes compared with today

| today | redesign | why (measured) |
|---|---|---|
| triage: 1 word from a 1,900-character rulebook | analyze: `goal`, `questions`, `next` from the whole conversation | 4 of 10 turns routed wrongly, each costing 6 to 26 minutes |
| `pickFiles` asks the model every time, and chooses plan / study / free | `analyze` chooses the route; `locate` finds files without a model; `pick` only as a fallback | pickFiles cost 9 s (small project) to 34 s (yours) |
| plan may contain code, refused after minutes | plan is a list; validator runs at once | one plan took 485 s and was refused |
| second model call rewrites the job's report | the report is the answer | that call took 43 to 145 s |
| short follow-up gets the old task | the goal comes from the conversation | "plan that" went back to the first request |
| steps differ in their opening | constant text first in every message | cold reads of 20 to 95 s per step |

## 4. Implementation order (each stage measured with the evaluation framework)

0. Routing cases from real messages (target 25), with the old triage's results as the baseline. Data first.
1. `analyze` replaces triage: JSON parse and validation, `ask_user` pause, route on `next`.
2. `locate` before `plan`; `pick` as fallback; plan and edit messages in type notation.
3. The job's report is the answer (no second agent call).
4. Every message: constant text first. Measure cache reuse in the log.

## 5. Not known yet
- Does the model return valid JSON every time? Does the server force a JSON shape?
- How accurate is `analyze` with the whole conversation, and what does reading it cost on your longer conversations (80 to 240 s cold)?
- Can `locate` find the files from names alone often enough to skip `pick`?
