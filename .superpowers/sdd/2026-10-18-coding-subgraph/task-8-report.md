# Task 8 report

Status: DONE (no commits, not a git repo). `.\gradlew.bat build` -> BUILD SUCCESSFUL (includes new ConsoleApproverTest, 3 tests).

## Implemented
- `ui/PlanDialog.java` (new): `ask(Window, CodingPlan) -> PlanDecision`. Plan via `MarkdownView.render(plan.toMarkdown(0))` in a ScrollPane, "Comment" field, buttons Approve (OK_DONE, default), Revise (ButtonData.YES, disabled while comment blank), Deny (CANCEL_CLOSE; closing = Deny). Same Alert/stylesheet approach as ApprovalDialog.
- `ChatWindow`: Approver is now an anonymous class (`review` -> `approve`, `reviewPlan` -> new `approvePlan`, Platform.runLater + CompletableFuture). Turn calls the 6-arg `Agent.run` with `showProgress`; the listener creates/updates a `.progress-card` Label ("Coding · <phase> · <detail>") via Platform.runLater; `removeProgressCard()` runs in onSucceeded and onFailed.
- `chat.css`: `.progress-card` (tonal secondary-container chip).
- `ConsoleApprover.reviewPlan`: prints `plan.toMarkdown(0)`, prompt `Approve plan? [y = yes, r <comment> = revise, N = deny]`; y approves, `r <comment>` revises, anything else (incl. bare `r`, EOF) denies. Added package-private constructor taking a BufferedReader (default constructor unchanged).
- `cli/Main`: runs the 6-arg overload with global effective settings (same as the 2-arg one) and prints `Coding · phase · detail` lines.
- `ConsoleApproverTest` (new): y / r comment / deny cases.

## Notes
- UI not launched (per instructions); manual check is the user's.
- Progress card text is not wrapped in backticks (a plain Label); the detail is truncated to 70 chars like tool chips.
- Docs untouched (Task 9).

## Fix round 1
1. PlanDialog: `getButtonTypes().setAll(APPROVE, REVISE, DENY)` (was addAll, leaving the Alert's OK/Cancel, so 5 buttons). Comment field has a consuming `setOnAction`, so Enter there no longer fires the default button.
2. ChatWindow: restored the original CRLF endings on the four import lines and the original `shownEntry` comment alignment (checked with diff against snap-8: only intended changes remain).
3. Progress card: no extra code. The existing `messages.heightProperty()` listener scrolls to the bottom whenever the transcript grows, which is how new messages scroll too; adding the card grows it.
4. `Progress.phase` already carries "Step n/m", so left as is. Detail is now wrapped in backticks: `Coding · Step 3/7 · \`gradle build\``.
Build: `.\gradlew.bat build` -> BUILD SUCCESSFUL.
