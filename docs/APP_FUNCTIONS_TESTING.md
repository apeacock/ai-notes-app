# Testing AppFunctions via adb

Commands used to verify the app's exposed AppFunctions
(`app/src/main/java/com/ai/notes/AppFunctions/NoteFunctions.kt`) against a
running emulator, and what was learned along the way.

## Requirements

- Emulator or device on **API 36.1+**. Earlier system images register the
  `app_function` shell service but its shell-command dispatcher is a stub,
  so `adb` reports `No shell command implementation` regardless of app
  configuration. Verified working here on:
  ```
  $ adb shell getprop ro.build.version.release
  17
  $ adb shell getprop ro.build.version.sdk
  37
  ```
- The app installed on the device/emulator (`com.ai.notes`).

## List registered functions

```
adb shell cmd app_function list-app-functions
```

Confirms all four functions are registered:

```
com.ai.notes.AppFunctions.NoteFunctions#createNote
com.ai.notes.AppFunctions.NoteFunctions#searchNotes
com.ai.notes.AppFunctions.NoteFunctions#getNote
com.ai.notes.AppFunctions.NoteFunctions#deleteNote
```

## Execute a function

`execute-app-function` needs `--parameters` as flat JSON matching the
function's parameter names (e.g. `{"title":"...","body":"...","tags":[...]}`).

**Quoting gotcha:** passing `--function` and `--parameters` as separate
arguments to `adb shell` (each individually quoted) causes the remote shell
to reassemble the quoting incorrectly, producing a misleading
`org.json.JSONException: Value title of type java.lang.String cannot be
converted to JSONObject` error — this is a client-side quoting bug, not an
app or platform bug. Wrap the entire `cmd app_function ...` invocation in a
single string passed to `adb shell` instead.

### Create a note

```
adb shell "cmd app_function execute-app-function --package com.ai.notes --function \"com.ai.notes.AppFunctions.NoteFunctions#createNote\" --parameters '{\"title\":\"Test Note\",\"body\":\"This is a test note created via adb shell cmd app_function.\",\"tags\":[\"test\",\"adb\"],\"category\":null}'"
```

Result:

```json
{
  "androidAppfunctionsReturnValue": [
    {
      "id": [1],
      "title": ["Test Note"],
      "body": ["This is a test note created via adb shell cmd app_function."],
      "tags": ["test", "adb"],
      "createdAt": [1783278365381],
      "updatedAt": [1783278365381]
    }
  ]
}
```

### Verify it persisted (search for it)

```
adb shell "cmd app_function execute-app-function --package com.ai.notes --function \"com.ai.notes.AppFunctions.NoteFunctions#searchNotes\" --parameters '{\"query\":\"Test\"}'"
```

Returned the same note (id 1), confirming `createNote` persisted to the
database via the exposed AppFunction, not just returned a response.

### Get a note by id

```
adb shell "cmd app_function execute-app-function --package com.ai.notes --function \"com.ai.notes.AppFunctions.NoteFunctions#getNote\" --parameters '{\"noteId\":1}'"
```

Result:

```json
{
  "androidAppfunctionsReturnValue": [
    {
      "id": [1],
      "title": ["Test Note"],
      "body": ["This is a test note created via adb shell cmd app_function."],
      "tags": ["test", "adb"],
      "createdAt": [1783278365381],
      "updatedAt": [1783278365381]
    }
  ]
}
```

### Delete a note

```
adb shell "cmd app_function execute-app-function --package com.ai.notes --function \"com.ai.notes.AppFunctions.NoteFunctions#deleteNote\" --parameters '{\"noteId\":1}'"
```

Result:

```json
{
  "androidAppfunctionsReturnValue": [
    true
  ]
}
```

### Verify it was actually deleted

```
adb shell "cmd app_function execute-app-function --package com.ai.notes --function \"com.ai.notes.AppFunctions.NoteFunctions#getNote\" --parameters '{\"noteId\":1}'"
```

Result: `{}` — an empty response, confirming `deleteNote` removed the note
from the database rather than just returning `true` unconditionally.

## Chat AppFunctions Tools — manual end-to-end verification

Verifies the chat feature (spec:
`docs/superpowers/specs/2026-07-05-chat-appfunctions-tools-design.md`)
against the **real** Claude API and the **real** `AppFunctionManager`
together — the one combination no automated test exercises (unit tests fake
the tool bridge; `AppFunctionToolBridgeTest` calls the bridge directly, not
through the chat loop).

**Device:** same emulator as above, `sdk_gphone64_x86_64`, Android 16 (API
36), confirmed supported via `adb shell cmd app_function list-app-functions`
succeeding.

### Two real bugs found and fixed during this pass

1. **Deprecated model id.** `ClaudeService.kt`'s `CLAUDE_MODEL` constant was
   `"claude-sonnet-4-20250514"`, which now 404s (`not_found_error`) against
   the real API — confirmed via a direct `curl` to
   `https://api.anthropic.com/v1/messages`. Updated to `"claude-sonnet-5"`.
   This affects `SummarizationRepository` too, not just chat.
2. **Silent chat errors.** `ChatScreen` never collected
   `ChatViewModel.errorEvent`, so a failed request left the UI stuck at
   "loading finished" with zero feedback — no Snackbar, no message, nothing.
   Added a `SnackbarHost` + `LaunchedEffect` on `errorEvent`, matching
   `NotesScreen`'s existing pattern (`ChatScreen.kt`).
3. **Extended-thinking deserialization gap.** `claude-sonnet-5` emits
   `{"type":"thinking","thinking":"...","signature":"..."}` content blocks
   alongside `tool_use`/`text` blocks. `ClaudeContentBlock` had no case for
   `"thinking"`, so the whole response failed to deserialize
   (`JsonDecodingException`). Added `ClaudeContentBlock.Thinking(thinking,
   signature)`.
4. **Dropped thinking signature on round-trip.** The first fix for (3)
   captured `thinking` but not `signature`; re-serializing that block into
   the next request's history (required once a `thinking` block has been
   part of a tool-use turn) silently omitted `signature`, and the API
   rejected the follow-up request with `400 Invalid request` — reproduced
   consistently, not a transient failure. Fixed by adding
   `signature: String? = null` to `Thinking` and round-trip-tested in
   `ClaudeModelsTest`.

Only bug (2) was chat-specific; (1), (3), and (4) are gaps in the shared
Claude wire model that no prior task's testing surface could have caught
without a real model that emits extended thinking.

### Step 2: non-destructive path (create)

Sent "Create a note titled Milk Run with body Buy milk and eggs" in chat.
Result: a "Called createNote" tool-activity chip, followed by the
assistant's confirmation reply. Verified via
`getNote`/the Notes screen that a real note (id 1) was persisted.

### Step 3: destructive confirmation path (delete)

Sent "Delete my Milk Run note". The assistant called `searchNotes` then
`deleteNote`, and the app paused with `AlertDialog` "Delete note #1? This
can't be undone." **Verified via `getNote` while the dialog was still open
that the note was NOT yet deleted** — confirmation genuinely gates
execution. Tapped "Delete"; assistant replied "Done! Your 'Milk Run' note
has been deleted." and `getNote` returned `{}`, confirming real deletion.

### Step 4: decline path

Recreated the note (id 2), asked chat to delete it again, tapped "Cancel"
on the confirmation dialog. Assistant replied "No problem — I've left your
'Milk Run' note as is." and `getNote` confirmed the note still existed
afterward.

All four steps passed after the fixes above. No known gaps remain in this
feature beyond the disclosed limitation already recorded in the plan's
Global Constraints (multiple `deleteNote` calls in a single assistant turn
only guard the first).
