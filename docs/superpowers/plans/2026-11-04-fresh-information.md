# Fresh information: refresh what the graph knows, cheaply

**Goal:** the model never works from what a file *used to* say. When a file changes (by the job, by a tool, by the user in the editor), exactly the information that depends on it is marked or rebuilt, and nothing else is read again.

**Why now:** the last job explained its failure with classes that do not exist (`FolderCell`, `ChatWindowMenuButton`) and a "refactor" that never happened. It was working from old reads, old notes and its own guesses. The structure changes under a conversation all the time, and today the graph does not notice.

## Revised after "avoid file content in the context" (done)

The plan was reworked: the context holds addresses and digests, not text. Done: smaller reads (12,000 characters, searches 40 lines), no `readFile` in the
research step, `StaleReads` (doubles and outdated reads become a note), the `knowledge` ledger and the note about changed files before each agent
model call, and the job's outlines built again when a picked file changed. Not done: the same note inside the skills' model steps and at the other phases of
the job, derived notes marked outdated, and a check that a name the model puts in a plan exists.

## What exists

- Java workspace: per-file stamp and md5, a directory look before every tool call, a watcher, incremental indexes and references. Java is already refreshed cheaply.
- `editSection` / the block tools refuse an edit when the md5 the model saw is old.
- `SummaryMemory.compactEarlierTurns`: tool results of *earlier turns* are shortened to a placeholder (same text on every request, so the server's cache stays valid).
- The coding job reads files fresh for each edit and finds sections again by id in the current outline.

## The gaps

1. **Within a turn, old reads stay as they are.** A file read at step 2 and changed at step 5 is still in the conversation with its old text; the model answers from it. Only an edit with an old md5 is caught, a plan or a report is not.
2. **Nothing connects a read to the file.** A tool result is only text: the graph cannot tell which results are now wrong.
3. **Derived data is built once.** The project map (job start), the research notes (`brief`), the gathered code and the plan do not know the files they describe changed. A job that is resumed or revised uses them as they were.
4. **Files outside Java** have no change tracking at all (no stamp, no md5 kept).
5. **The same file is read again and again** (the log shows `readFile` of ChatWindow three times in one study step): old copies stay in the prompt and cost tokens without being right.

## Design

**One ledger: what the conversation knows, and at which version.** A state key `knowledge`: a map from a file path to the version the model was last shown (size, modified time, and the md5 once it is known). It is filled by the tools themselves where they already read or write (`readFile`, `readSection`, `outline`, `javaRead`, `javaBlocks`, the edit and patch tools, the job's `gather` / `describe`), so it holds only files that matter, not the project.

**A refresh step that costs one look.** `refresh` runs
- at the start of each turn (inside the `memory` node, which already runs once per turn),
- before each model call in the agent loop (a stat of the tracked files only),
- at the job's phase boundaries (before `plan`, `planApproval`, `setup`, `edit`, `verify`, `fix`).
For Java it asks the workspace (already incremental). For other files it compares size and modified time of the *tracked* files; an md5 is computed only when those differ. A file the user edited in the editor shows up here and nowhere else.

**What a change does, in three tiers by cost:**
1. **Tell, do not rewrite.** The request gets one short note at its *end*: "Changed since you read them: A.java (method `run()` changed, `stop()` added), notes.md (3 lines). Read again what you need." The note is built from the structure (declarations before and after for Java, section ids and line counts for others), so it is small and exact. Nothing earlier in the prompt changes, so the model server keeps its cache.
2. **Supersede, do not accumulate.** When a file (or a section of it) is read again, the earlier result of the same read is replaced by a placeholder at the next turn boundary, together with `compactEarlierTurns` (so the cache is only cut once per turn, where it is cut anyway). A read that is known to be outdated is replaced the same way.
3. **Rebuild only what depends on it.** Each derived piece records the files it came from (project map: per file; research notes and gathered code: the files they name; plan: the sections its changes name). On a change, the project map row is rebuilt for that file; the notes are marked "may be outdated for X" and re-run only when a changed file is one they quote; a plan whose sections are gone goes back to the plan step through `PlanValidator`, which exists.

**Not done by the graph:** reloading the project, rereading unchanged files, asking the model to re-explain. If nothing tracked changed, the refresh does a stat call per tracked file and returns no update.

## Milestones (each ends with a green build and tests)

1. **Ledger and look.** `Knowledge` (map path → version), a `refresh` function returning the changed paths; fed by the file tools and the Java tools. Tests: unchanged files cost no read and give no update; a change by the user, by a tool and by the job are all seen; a file outside Java is tracked by size and time, hashed only when they differ.
2. **The note.** Structure diff (Java: declarations before and after, from `JavaStructure`; other files: section ids and line counts from the outline), added to the end of the request. Tests: the request prefix is byte-identical with and without a change; the note names the member that changed.
3. **Supersede.** Same read twice → the older result is a placeholder after the turn; an outdated read likewise. Tests with the log's case (three reads of one file).
4. **Derived data.** Project map per file; notes and gathered code record their source files; a changed source marks or re-runs them; the plan check before `edit`. Tests: a change to an unrelated file leaves everything alone.
5. **In the graph.** The `refresh` step at the phase boundaries of the coding skill and before each agent model call; update `docs/agent-graph.md` (edges, state keys, node table) and the README.

## Risks and choices

- **Cache versus freshness.** Rewriting earlier messages makes the model server read the whole prompt again (minutes on a long conversation). So a change adds a note at the end and rewrites old results only at the turn boundary, where the prompt is rebuilt anyway.
- **Noise.** A note on every save of a file the model barely used would distract: the ledger lists only files the model was shown, and a change is reported once.
- **Generated files** (build output, traces, the plans folder) are never tracked: the folders the file tools already skip, plus the job's own plan files.
- **The model can still guess.** A fresh note does not stop invented names; it removes one cause. A check that a class the model names exists (`javaFind`) before it is put into a plan is a cheap second step, left out here.

## Not in scope

A file watcher for non-Java files (the look at the start of each step is enough), a cache of file contents, re-embedding or searching the project.
