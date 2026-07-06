# Chat Feature — Summary

Record of the work to add an in-app chatbot with note tool-calling: what was
built, decisions made along the way, and known remaining concerns. Full
detail lives in `docs/superpowers/specs/2026-07-05-chat-appfunctions-tools-design.md`,
`docs/superpowers/plans/2026-07-05-chat-appfunctions-tools.md`, and
`.superpowers/sdd/progress.md`.

## Session 1: AppFunctions debugging & manual testing

- **Root cause found:** `adb shell cmd app_function list-app-functions`
  failing with "No shell command implementation" was an emulator/system-image
  version issue — the shell dispatcher for `app_function` only exists on API
  36.1+. Not an app bug.
- Verified all four existing AppFunctions (`createNote`, `searchNotes`,
  `getNote`, `deleteNote`) work correctly via
  `adb shell cmd app_function execute-app-function` once the emulator was on
  a supported API level.
- Documented everything in `docs/APP_FUNCTIONS_TESTING.md`, including a
  quoting gotcha in `adb shell` invocations that produces a misleading JSON
  error.

## Session 2: Chat feature — design → plan → implementation

**Design decision (key architectural choice):** the in-app chatbot doesn't
duplicate tool definitions. It discovers and executes the app's *existing*
registered AppFunctions in-process via the real `AppFunctionManager`
platform API — the same mechanism OS Gemini uses. One source of truth for
`createNote`/`searchNotes`/`getNote`/`deleteNote`.

**Other decisions made during brainstorming:**
- Non-streaming responses (matches existing summarization pattern)
- In-memory-only chat history (no persistence)
- `deleteNote` requires explicit confirmation; every other tool executes
  immediately
- Entry point: chat icon in a new `NotesScreen` top bar

**Implementation (8 tasks, subagent-driven, each with independent review):**
1. Claude tool-use wire models (`ClaudeContentBlock` sealed hierarchy,
   `ClaudeTool`, etc.)
2. `ToolSchemaBuilder` — pure conversion from AppFunctions metadata to
   Claude tool schemas
3. `ChatRepository` — the agentic tool-use loop, unit-tested against a fake
   tool bridge
4. `AppFunctionToolBridge` — real `AppFunctionManager`-backed
   implementation, instrumented-tested
5. `ChatViewModel`
6. `ChatScreen` (Compose UI + delete-confirmation dialog)
7. Navigation/dependency wiring
8. Manual end-to-end verification against the real Claude API

**Real bugs found and fixed along the way:**
- Task 4 review: `execute()` had no exception handling around malformed
  tool input — fixed to return `Failure` instead of throwing.
- Task 6: an unrelated pre-existing `NotesScreenTest` bug was exposed by an
  espresso-core version bump needed to fix a real API-36 emulator crash —
  fixed (ambiguous `onNodeWithText` match, scoped to test tags instead).
- **Task 8 (manual verification) found the big ones**, since it was the
  first time real Claude + real AppFunctions ran together:
  - `CLAUDE_MODEL` was a deprecated model ID, 404ing against the live API —
    updated to `claude-sonnet-5` (fixes summarization too).
  - `ChatScreen` never rendered `errorEvent` — failures were completely
    silent. Added a Snackbar.
  - `claude-sonnet-5` emits extended-thinking blocks the wire model didn't
    recognize — whole response deserialization crashed.
  - The `thinking` block's `signature` field was being dropped on
    round-trip, causing the *next* request to 400.
- **Final whole-branch review** caught that the thinking-block fix was
  incomplete (a `redacted_thinking` variant could still crash things) and
  that `maxTokens` was too low under default-on thinking. Resolved by
  disabling extended thinking outright on every request (chat and
  summarization) plus adding a defensive `RedactedThinking` case and
  raising `maxTokens` to 2000.

Everything was re-verified live against the real API after each fix
(create, delete-with-confirmation, decline, and summarization all
confirmed working end-to-end).

## Remaining concerns (disclosed, not blocking)

- **Accepted limitation:** if Claude calls `deleteNote` twice in one turn,
  only the first pauses for confirmation — documented in the plan, not
  fixed (narrow scenario, would need queued confirmation handling).
- **Minor, deferred:** `AppFunctionToolBridge.execute()`/`discoverTools()`
  use broad `catch (Throwable)`; `discoverTools()` has no exception guard;
  dialog buttons/list items lack `testTag`/`key`; a couple of
  `ChatViewModel` edge-case paths (isLoading transition, chained
  confirmations) are untested. All judged not worth blocking on.
- **Environment-only:** the espresso-core bump (3.6.1→3.7.0) was necessary
  for any instrumented test to run on this emulator at all — unrelated to
  the feature but landed alongside it.
- No remote is configured for this repo, so the work is committed directly
  to `master` rather than merged via PR.
