# Task 7 report: window and CLI for skills

Status: DONE (no commits, not a git repo). `.\gradlew.bat build` -> BUILD SUCCESSFUL. UI not run (checked manually by the user).

## Implemented
- `cli/ConsoleApprover`: `askUser` prints question + numbered choices; reads `N [comment]` or `c <comment>` (= first choice with comment). Empty/EOF/out-of-range/non-number/bare `c` -> dismissed. `reviewSkill` prints `SkillDraft.toMarkdown()`; `s` save, `r <comment>` revise, anything else cancel.
- `ui/SkillQuestionDialog` (new): Markdown question, one button per choice (none is the default button), comment field, Cancel; close/failure = dismissed.
- `ui/SkillReviewDialog` (new): Markdown draft, red "Asks before running: ..." line for tools needing approval, "Show JSON" toggle, comment, Save (default) / Revise (enabled only with comment) / Cancel; close = cancel.
- `ChatWindow`: approver overrides `askUser` / `reviewSkill` via `Platform.runLater` + `CompletableFuture`; failures complete exceptionally (Agent treats as dismissed/cancel). Progress card uses `Progress.label()`. Global settings dialog gets the agent and `busy.isVisible()`.
- `SettingsDialog`: Skills section in the global settings only: each skill as `name · tool · built-in | your file | your file replacing built-in`, errors per file, "Reload skills" button (disabled while a turn runs, with a note). Reload failure shows the message; previous skills stay.
- `cli/Main`: prints `Skills: name (tool), ...` and unusable files with errors at start.
- Supporting API: `SkillLoader.Origin` + `SkillLoader.origin(loaded, name)`; `Agent.skillOrigin(name)`, `Agent.skillTool(skill)`; `Progress.label()`.
- Progress: `Agent.progressOf` already reported skill nodes ("skill · node"); extended to report the skill builder's `draft`/`validate`/`save` nodes ("Skill builder · draft"). `Progress.label()` shows phases already containing " · " as is and prefixes coding phases with "Coding · " (window and CLI).
- Tests: `ConsoleApproverTest` adds `askUserReadsAChoiceAndComment`, `askUserEofIsDismissed`, `reviewSkillAnswers`.

## Decisions
- CLI `c <comment>` = first choice with comment; a bare `c` dismisses.
- Origin is derived: a `<name>.json` in the skill folder with no error listed for it counts as the user's file; an invalid user file leaves the built-in in use.
- Reload is disabled (not queued) when the settings dialog opens during a turn; the dialog is modal so no turn can start while it is open.

## Concerns
- No new test for the builder-progress branch in `progressOf` or `Progress.label()`.
- ChatWindow import order for the new imports is not alphabetical (cosmetic).
- Docs/graph diagram untouched (Task 8); no graph nodes/edges changed.
