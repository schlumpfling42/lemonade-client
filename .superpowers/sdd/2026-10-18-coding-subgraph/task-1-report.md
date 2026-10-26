# Task 1 report: Plan model, JSON parsing and plan check

Status: DONE. No commits (not a git repo).

## Implemented
- `src/main/java/net/rka/ai/agent/CodingPlan.java`: record with nested `Step`, `Command`, `FileChange`, enum `Change` (lower-case JSON); `parse` (first `{` to last `}`, so fences/prose ignored; lenient about unknown fields; readable IllegalArgumentException), `toJson`, `toMarkdown(int)`, `covers(...)`.
- `TrustedCommands.normalize(String)` added (public static) and used by `isTrusted`, so the whitespace normalization is shared, not duplicated.
- Design choices: missing lists become empty; unknown tools with no markers count as read-only (covered; they just fail at execution); copyFile source must be inside the folder but needs no entry; `deletePath`/`movePath(from)` never cover the trusted folder itself, nor a folder entry's own folder; a plan command whose directory is outside the folder never matches; user-trusted commands are accepted before any path checks.

## Tests (TDD)
- RED: `.\gradlew.bat test --tests net.rka.ai.agent.CodingPlanTest` -> compileTestJava FAILED, "package net.rka.ai.agent.CodingPlan does not exist" (class missing, expected).
- GREEN: same class, 13 tests, 0 failures (10 from the brief plus rejectsInvalidJsonAndBadValues, verifyCommandsAreCovered, malformedArgumentsAreNotCovered). Full `.\gradlew.bat build`: BUILD SUCCESSFUL.

## Files
- created `src/main/java/net/rka/ai/agent/CodingPlan.java`, `src/test/java/net/rka/ai/agent/CodingPlanTest.java`
- modified `src/main/java/net/rka/ai/tools/TrustedCommands.java` (normalize helper)

## Self-review / concerns
- Running only `--tests` at root also triggers `:graph-processor:test`, which fails with "No tests found" for the filter; use `:test --tests ...` instead.
- A user-trusted `runCommand` is covered regardless of its working directory (unchanged existing behavior of trusted commands).
