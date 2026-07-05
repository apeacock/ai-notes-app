# In-App Chatbot with Note Tools — Design Specification

## Overview

Add a chat screen where the user converses with Claude, and Claude can call
the app's existing note operations (`createNote`, `searchNotes`, `getNote`,
`deleteNote`) as tools mid-conversation. The tool definitions and the code
that executes them are **not duplicated** from the existing OS-facing
AppFunctions integration (`AppFunctions/NoteFunctions.kt`) — the chatbot
discovers and invokes the exact same registered functions in-process via
`AppFunctionManager`, the same mechanism Gemini/Assistant uses externally.
`NoteFunctions.kt` and the manifest/build configuration are unchanged by this
feature.

**Key constraints:**
- Reuses `AppFunctionManager` in-process (no parallel tool-schema
  authoring); single source of truth for function names, descriptions, and
  parameter shapes.
- Requires the same platform support already documented in
  `docs/APP_FUNCTIONS_TESTING.md` (API 36.1+ device/emulator for tool
  execution) — on unsupported devices the chat degrades to plain
  conversation with no note tools, rather than failing.
- Non-streaming (one HTTP request/response per turn), matching the existing
  `SummarizationRepository`/`ClaudeService` pattern.
- In-memory conversation history only — cleared on process death/app
  restart. No new Room schema.
- `deleteNote` requires an explicit in-chat confirmation step before it
  executes; the other three tools execute immediately when Claude calls
  them.

**Explicitly out of scope:** streaming responses, persisted chat history,
multiple/named conversations, tools beyond the existing four, voice input,
editing prior messages.

---

## Architecture

### High-level structure (new/changed files)

```
app/src/main/java/com/ai/notes/
├── data/
│   └── ai/
│       ├── ClaudeModels.kt              # CHANGED: content blocks, tools, stop_reason
│       ├── ClaudeService.kt             # unchanged (same endpoint/signature)
│       ├── SummarizationRepository.kt   # CHANGED: wrap prompt in a Text content block
│       └── chat/
│           ├── ToolBridge.kt            # NEW: interface (discoverTools/execute)
│           ├── AppFunctionToolBridge.kt # NEW: ToolBridge impl over AppFunctionManager
│           ├── ChatRepository.kt        # NEW: agentic tool-use loop
│           └── ChatMessage.kt           # NEW: sealed UI-facing message/event types
├── ui/
│   ├── screens/
│   │   ├── NotesScreen.kt               # CHANGED: adds TopAppBar + chat icon action
│   │   └── ChatScreen.kt                # NEW
│   ├── viewmodel/
│   │   └── ChatViewModel.kt             # NEW
│   └── navigation/
│       └── AppNavigation.kt             # CHANGED: adds "chat" route
├── App.kt                               # unchanged (AppFunctionConfiguration untouched)
└── MainActivity.kt                      # CHANGED: wires ChatRepository/ChatViewModel
```

---

## Component Details

### 1. `ToolBridge` / `AppFunctionToolBridge`

```kotlin
interface ToolBridge {
    suspend fun discoverTools(): List<ClaudeTool>
    suspend fun execute(functionId: String, input: JsonObject): ToolExecutionResult
}

sealed class ToolExecutionResult {
    data class Success(val resultJson: String) : ToolExecutionResult()
    data class Failure(val message: String) : ToolExecutionResult()
}
```

`AppFunctionToolBridge(context: Context) : ToolBridge` — modeled directly on
the reference `AppFunctionRunner`
(https://github.com/philipplackner/AppFunctionsDemo/blob/master/app/src/main/java/com/plcoding/appfunctionsdemo/ai/AppFunctionRunner.kt):

- `discoverTools()`: `AppFunctionManager.getInstance(context)` → if `null`
  (device doesn't support AppFunctions), return `emptyList()`. Otherwise
  `manager.observeAppFunctions(AppFunctionSearchSpec(packageNames =
  setOf(context.packageName))).first()`, flatten to `AppFunctionMetadata`,
  and map each to a `ClaudeTool(name, description, inputSchema)`:
  - `name` = the simple function name (`id.substringAfterLast('#')`, e.g.
    `createNote`) — Claude tool names must be simple identifiers, not the
    fully-qualified `AppFunctionMetadata.id`.
  - `description` = `metadata.description` (already sourced from KDoc via
    `isDescribedByKDoc = true`).
  - `inputSchema` = a JSON Schema object built from `metadata.parameters`:
    each parameter's Android type metadata
    (`AppFunctionIntTypeMetadata`/`StringTypeMetadata`/list-of-string/etc.)
    maps to the corresponding JSON Schema type (`integer`, `string`,
    `boolean`, `array` of `string`), with `required` populated from
    parameters where `isNullable == false` (not from `isRequired`, which is
    `true` even for the nullable `category` parameter per the platform's
    metadata — nullability is the real optionality signal here).
  - The bridge retains a `functionId` (fully-qualified) ↔ `name` (simple)
    mapping internally so `execute()` can be called with the simple name
    Claude returns.

- `execute(functionId, input)`: look up the cached `AppFunctionMetadata` by
  simple name, build `AppFunctionData` via `AppFunctionData.Builder` from
  `input`'s JSON values (mirroring the reference's per-type `when`, extended
  to handle the `tags: List<String>` parameter — verify the exact
  `AppFunctionData.Builder` list-setter method name against the real
  `androidx.appfunctions` API during implementation; this is the one part
  of the reference pattern that doesn't already cover our parameter shapes),
  call `manager.executeAppFunction(ExecuteAppFunctionRequest(packageName,
  metadata.id, data))`, and convert the result:
  - `ExecuteAppFunctionResponse.Error` → `ToolExecutionResult.Failure(error.errorMessage)`.
  - `ExecuteAppFunctionResponse.Success` → convert `AppFunctionData` to a
    JSON string. Primitives (`Boolean` from `deleteNote`) convert directly.
    `Note`/`Note?`/`List<Note>` (from `createNote`/`getNote`/`searchNotes`)
    convert via a **small dedicated `Note` field extractor** (`id`, `title`,
    `body`, `tags`, `category`, `createdAt`, `updatedAt`) rather than a
    generic recursive `AppFunctionData`→JSON converter — `Note` is the only
    custom type anywhere in the schema, so a generic converter would be
    speculative generality with nothing else to justify it.
  - Any thrown exception during execution → caught, mapped to
    `ToolExecutionResult.Failure(...)` — never propagates to crash the chat
    loop.

`ToolBridge` is instrumented-test-only for real coverage (like
`NoteFunctionsExecutionTest.kt` today), since `AppFunctionManager` is a
platform class not available off-device. `ChatRepository`'s loop logic is
tested separately against a fake `ToolBridge`.

### 2. Claude API model changes

`ClaudeModels.kt`:

```kotlin
@Serializable
data class ClaudeMessage(val role: String, val content: List<ClaudeContentBlock>)

@Serializable
sealed class ClaudeContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ClaudeContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ClaudeContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false,
    ) : ClaudeContentBlock()
}

@Serializable
data class ClaudeTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
)

@Serializable
data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<ClaudeMessage>,
    val tools: List<ClaudeTool>? = null,
)

@Serializable
data class ClaudeResponse(
    val content: List<ClaudeContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null,
)
```

Discriminator field is `"type"` (Anthropic's own field name) — configure the
shared `Json` instance's `classDiscriminator = "type"` in `RetrofitFactory`.

`ClaudeService.kt` is unchanged (same endpoint, same method signature — only
the request/response body shapes change).

`SummarizationRepository.kt`: one-line change — wraps its prompt as
`listOf(ClaudeContentBlock.Text(prompt))` instead of a raw string, and reads
`response.body()?.content?.filterIsInstance<ClaudeContentBlock.Text>()?.firstOrNull()?.text`
instead of the old flat access. No behavior change.

### 3. `ChatRepository` — agentic loop

```kotlin
class ChatRepository(
    private val claudeService: ClaudeService,
    private val apiKeyManager: ApiKeyManager,
    private val toolBridge: ToolBridge,
) {
    suspend fun send(history: List<ClaudeMessage>): ChatTurnResult
    suspend fun resolveConfirmation(
        history: List<ClaudeMessage>,
        pending: PendingToolUse,
        approved: Boolean,
    ): ChatTurnResult
}
```

- `DESTRUCTIVE_FUNCTIONS = setOf("deleteNote")` — a small hardcoded
  allowlist; there's no metadata attribute for "destructive" on
  `AppFunctionMetadata`, and one entry doesn't justify inventing one.
- Loop, capped at 5 round-trips per user turn (guards against a runaway
  tool-use cycle):
  1. Call `claudeService.sendMessage(...)` with full history + tools from
     `toolBridge.discoverTools()` (tools omitted/`null` if discovery
     returned empty — Claude just won't offer to call anything).
  2. Append the assistant's returned content blocks to history.
  3. If `stopReason != "tool_use"` → return `ChatTurnResult.Done(history)`.
  4. For each `ToolUse` block in the response:
     - If `block.name in DESTRUCTIVE_FUNCTIONS` → stop and return
       `ChatTurnResult.NeedsConfirmation(history, PendingToolUse(block))`
       (does not execute; caller/UI must call `resolveConfirmation` next).
     - Else → `toolBridge.execute(block.name, block.input)`, convert to a
       `ToolResult` content block (`isError = true` on `Failure`, message as
       `content`).
  5. Append a new `user`-role message containing all the turn's
     `ToolResult` blocks, go to step 1.
  6. If the iteration cap is hit → return
     `ChatTurnResult.Done(history + assistant text: "Sorry, I couldn't
     resolve that.")` rather than looping forever.
- `resolveConfirmation`: if `approved`, execute the held tool_use via the
  bridge and append its real result; if not approved, append a
  `ToolResult(isError = true, content = "User declined this action.")` —
  either way, re-enter the same loop from step 5 so Claude can react
  (acknowledge success, apologize, ask a follow-up, etc.) instead of the
  conversation silently dead-ending.
- Network/auth failures (missing API key, HTTP errors) reuse the existing
  `AppError`/`ErrorMapper` sealed hierarchy, surfaced as
  `ChatTurnResult.Error(AppError)`.

### 4. `ChatViewModel` + `ChatScreen`

`ChatMessage` (UI-facing, distinct from the wire-format `ClaudeMessage`):

```kotlin
sealed class ChatMessage {
    data class FromUser(val text: String) : ChatMessage()
    data class FromAssistant(val text: String) : ChatMessage()
    data class ToolActivity(val functionName: String) : ChatMessage() // transparency chip
}
```

`ChatViewModel`:
- `messages: StateFlow<List<ChatMessage>>`, `isLoading: StateFlow<Boolean>`,
  `pendingConfirmation: StateFlow<PendingToolUse?>`, `errorEvent:
  StateFlow<AppError?>` — same shape/conventions as `NotesViewModel`.
- Keeps the full `List<ClaudeMessage>` wire history privately (not exposed
  to the UI) alongside the UI-facing `messages` projection.
- `sendMessage(text)`: appends a `FromUser` message + wire user message,
  calls `chatRepository.send(...)`, projects the result into UI messages
  (`ToolUse` blocks become `ToolActivity` chips, final `Text` becomes
  `FromAssistant`), or sets `pendingConfirmation` /`errorEvent` as
  appropriate.
- `confirmPendingAction()` / `cancelPendingAction()`: call
  `chatRepository.resolveConfirmation(..., approved = true/false)`, same
  result projection.

`ChatScreen`: `Scaffold` with a `TopAppBar` (title "Chat", back button),
`LazyColumn` of message bubbles (user right-aligned, assistant
left-aligned, `ToolActivity` rendered as a small muted line like "Searched
notes"), bottom `TextField` + send `IconButton` (disabled while
`isLoading`), `CircularProgressIndicator` while waiting, and an
`AlertDialog` bound to `pendingConfirmation` ("Delete note \"<title>\"?" /
Delete / Cancel) — same visual language as the existing `InvalidApiKey`/
`DatabaseError` dialogs in `NotesScreen.kt`.

### 5. Navigation & wiring

- `NotesScreen.kt`: currently has no `topBar` in its `Scaffold`. Add one —
  `TopAppBar(title = { Text("Notes") }, actions = { IconButton(onClick =
  onNavigateToChat) { Icon(Icons.Filled.Chat, "Chat") } })` — via a new
  `onNavigateToChat: () -> Unit = {}` parameter, following the existing
  `onNavigateToApiKeyEdit` convention.
- `AppNavigation.kt`: add `const val CHAT_ROUTE = "chat"` and a
  `composable(CHAT_ROUTE) { ChatScreen(viewModel = chatViewModel, onBack =
  { navController.popBackStack() }) }`; `NotesScreen`'s
  `onNavigateToChat` becomes `{ navController.navigate(CHAT_ROUTE) }`.
- `MainActivity.kt`: construct `AppFunctionToolBridge(applicationContext)`,
  `ChatRepository(RetrofitFactory.createClaudeService(), apiKeyManager,
  toolBridge)`, and a `ChatViewModel` factory entry, mirroring how
  `SummarizationRepository`/`NotesViewModel` are already built.

---

## Error Handling

| Condition | Behavior |
|---|---|
| No/invalid API key | `ChatTurnResult.Error(AppError.InvalidApiKey)` → shown inline in chat (not a blocking dialog — chat screen is self-contained) |
| Network/HTTP failure | `ErrorMapper`-mapped `AppError`, same as above |
| `AppFunctionManager` unavailable (device < API 36.1) | `discoverTools()` returns empty; chat works as plain conversation; a one-time inline notice explains note-related requests won't work on this device |
| Single tool execution throws/fails | Caught in `AppFunctionToolBridge.execute`, becomes an `isError` `ToolResult` fed back to Claude — visible to the model and (via its reply) the user; does not crash the loop |
| Runaway tool-use cycling | 5-iteration cap in `ChatRepository`, falls back to an apologetic final message |
| User declines a destructive confirmation | Loop continues with an `isError` tool_result explaining the decline, rather than dead-ending |

---

## Testing

- **Unit (JVM, mockk)** — `ChatRepository` against a fake `ToolBridge` and
  a mocked `ClaudeService`: single-turn text reply; one tool_use round then
  final text; multi-round tool_use chaining; `deleteNote` triggers
  `NeedsConfirmation` and does not call `toolBridge.execute` until
  `resolveConfirmation(approved = true)`; decline path continues the
  conversation; iteration cap terminates a pathological loop; a tool
  execution `Failure` becomes an `isError` result without throwing.
- **Instrumented (`androidTest`)** — `AppFunctionToolBridge` against the
  real `AppFunctionManager`, verifying `discoverTools()` returns the four
  real functions with correctly-shaped schemas and that `execute()`
  round-trips a real `createNote`/`getNote` call, matching the existing
  `NoteFunctionsExecutionTest.kt` pattern.
- **Compose UI test** — `ChatScreen`: sending a message renders both
  bubbles; a mocked `deleteNote` tool_use response surfaces the
  confirmation `AlertDialog`; confirming/cancelling dispatches the right
  `ChatViewModel` call.

---

## Known Risks / Open Items for Implementation

- The exact `AppFunctionData.Builder` method for setting a `List<String>`
  parameter (`tags`) isn't confirmed against the real `androidx.appfunctions`
  API surface yet — the reference implementation this design follows only
  handles scalar parameter types. Needs verifying (or a fallback
  encoding) during implementation.
- No device below API 36.1 has been used to verify the "graceful
  degradation with empty tools" path in practice — same disclosed-gap
  pattern as the rest of this project's AppFunctions work
  (`docs/BUILD_LOG.md` "Known limitations").
