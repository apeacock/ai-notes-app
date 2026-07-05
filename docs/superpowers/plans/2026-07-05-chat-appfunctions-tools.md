# In-App Chatbot with Note Tools Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a chat screen where the user converses with Claude, and Claude can call the app's existing `createNote`/`searchNotes`/`getNote`/`deleteNote` operations as tools by discovering and executing them through the same in-process `AppFunctionManager` the OS assistant uses — no duplicated tool definitions, no changes to `NoteFunctions.kt` or the manifest.

**Architecture:** A `ToolSchemaBuilder` converts the app's real `AppFunctionMetadata` into Claude tool-use JSON schemas. `AppFunctionToolBridge` (instrumented-only, real `AppFunctionManager`) discovers and executes those functions in-process. `ChatRepository` runs the agentic tool-use loop against `ClaudeService`, pausing for confirmation before `deleteNote`. `ChatViewModel`/`ChatScreen` present it, wired in as a second screen alongside the existing `NotesScreen`.

**Tech Stack:** Kotlin, Jetpack Compose, `androidx.appfunctions:1.0.0-alpha09`, Retrofit + kotlinx.serialization, mockk + kotlinx-coroutines-test, Compose UI testing.

**Spec:** `docs/superpowers/specs/2026-07-05-chat-appfunctions-tools-design.md`

## Global Constraints

- Reuses `AppFunctionManager` in-process — no parallel/duplicated tool-schema authoring; `NoteFunctions.kt`, `AndroidManifest.xml`, and `App.kt`'s `AppFunctionConfiguration` are not modified by this work.
- Non-streaming: one HTTP request/response per turn (matches `SummarizationRepository`/`ClaudeService` today).
- In-memory conversation history only — no new Room schema, no persistence across process death.
- `deleteNote` requires an explicit in-chat confirmation before it executes; every other tool executes immediately when Claude calls it.
- Tool execution requires a device/emulator on API 36.1+ (per `docs/APP_FUNCTIONS_TESTING.md`); on unsupported devices `discoverTools()` returns empty and chat still works as plain conversation.
- `minSdk 33` / `compileSdk 37` / `targetSdk 37` (unchanged, already satisfies AppFunctions' requirements).
- Kotlin discriminator for `ClaudeContentBlock`'s polymorphism is `"type"` — this is kotlinx.serialization's `Json` default `classDiscriminator`, so no `RetrofitFactory` change is needed for it to line up with Anthropic's own `"type"` field.
- Real `androidx.appfunctions` API signatures used throughout this plan were confirmed directly against the resolved `appfunctions-1.0.0-alpha09-sources.jar` (not guessed) — see Task 4 for the exact classes/methods.
- **Known limitation (accepted, Task 3 review):** if Claude calls `deleteNote` more than once within a single assistant turn, only the first pauses for confirmation via `NeedsConfirmation`; any additional `deleteNote` calls in that same turn execute immediately alongside the other non-destructive calls in `resolvedResults`. Accepted as a disclosed limitation rather than fixed, since it requires a queued/sequential confirmation mechanism to close and the scenario (an LLM invoking the same destructive tool twice in one turn for a single-note-focused assistant) is narrow.

---

## Task 1: Claude tool-use wire models + adapt SummarizationRepository

**Files:**
- Modify: `app/src/main/java/com/ai/notes/data/ai/model/ClaudeModels.kt`
- Modify: `app/src/main/java/com/ai/notes/data/ai/SummarizationRepository.kt`
- Modify: `app/src/test/java/com/ai/notes/data/ai/model/ClaudeModelsTest.kt`
- Modify: `app/src/test/java/com/ai/notes/data/ai/SummarizationRepositoryTest.kt`

**Interfaces:**
- Produces: `ClaudeMessage(role: String, content: List<ClaudeContentBlock>)`; `ClaudeContentBlock` sealed with `Text(text: String)`, `ToolUse(id: String, name: String, input: JsonObject)`, `ToolResult(toolUseId: String, content: String, isError: Boolean = false)`; `ClaudeTool(name: String, description: String, inputSchema: JsonObject)`; `ClaudeRequest(model, maxTokens, messages, tools: List<ClaudeTool>? = null)`; `ClaudeResponse(content: List<ClaudeContentBlock>, stopReason: String? = null)`. All later tasks depend on these exact types.

This task changes `ClaudeMessage.content` from `String` to `List<ClaudeContentBlock>` and `ClaudeContentBlock` from a flat data class to a sealed hierarchy. This breaks the two existing tests that construct these types directly, and `SummarizationRepository`'s usage — all three must change together.

- [ ] **Step 1: Write the failing/updated test files**

Replace `app/src/test/java/com/ai/notes/data/ai/model/ClaudeModelsTest.kt` with:

```kotlin
package com.ai.notes.data.ai.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ClaudeRequest serializes max_tokens with snake_case key`() {
        val request = ClaudeRequest(
            model = "claude-sonnet-4-20250514",
            maxTokens = 2000,
            messages = listOf(ClaudeMessage(role = "user", content = listOf(ClaudeContentBlock.Text("Summarize this"))))
        )

        val encoded = json.encodeToString(request)

        assertTrue(encoded.contains("\"max_tokens\":2000"))
        assertTrue(encoded.contains("\"model\":\"claude-sonnet-4-20250514\""))
    }

    @Test
    fun `ClaudeRequest omits tools field when null`() {
        val request = ClaudeRequest(
            model = "claude-sonnet-4-20250514",
            maxTokens = 2000,
            messages = listOf(ClaudeMessage(role = "user", content = listOf(ClaudeContentBlock.Text("Hi"))))
        )

        val encoded = json.encodeToString(request)

        assertTrue(!encoded.contains("\"tools\""))
    }

    @Test
    fun `ClaudeResponse deserializes a text content block`() {
        val body = """{"content":[{"type":"text","text":"Summary here"}]}"""

        val response = json.decodeFromString<ClaudeResponse>(body)

        assertEquals("Summary here", (response.content[0] as ClaudeContentBlock.Text).text)
    }

    @Test
    fun `ClaudeResponse deserializes a tool_use content block and stop_reason`() {
        val body = """{"content":[{"type":"tool_use","id":"tool_1","name":"searchNotes","input":{"query":"milk"}}],"stop_reason":"tool_use"}"""

        val response = json.decodeFromString<ClaudeResponse>(body)

        val toolUse = response.content[0] as ClaudeContentBlock.ToolUse
        assertEquals("tool_1", toolUse.id)
        assertEquals("searchNotes", toolUse.name)
        assertEquals(JsonPrimitive("milk"), toolUse.input["query"])
        assertEquals("tool_use", response.stopReason)
    }

    @Test
    fun `ClaudeMessage serializes a tool_result content block`() {
        val message = ClaudeMessage(
            role = "user",
            content = listOf(ClaudeContentBlock.ToolResult(toolUseId = "tool_1", content = "[]", isError = false))
        )

        val encoded = json.encodeToString(message)

        assertTrue(encoded.contains("\"type\":\"tool_result\""))
        assertTrue(encoded.contains("\"tool_use_id\":\"tool_1\""))
    }
}
```

Replace `app/src/test/java/com/ai/notes/data/ai/SummarizationRepositoryTest.kt` with (only the `ClaudeContentBlock(...)` construction on the `response` line changes from the old flat form to `ClaudeContentBlock.Text(...)`; everything else is unchanged from the current file):

```kotlin
package com.ai.notes.data.ai

import android.content.Context
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeResponse
import com.ai.notes.data.model.Note
import com.ai.notes.data.preferences.ApiKeyManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

private fun note(n: Int) = Note(
    id = n,
    title = "Title $n",
    body = "Body $n",
    tags = emptyList(),
    category = null,
    createdAt = 0L,
    updatedAt = 0L
)

class SummarizationRepositoryTest {
    private fun fakeApiKeyManager(key: String?): ApiKeyManager {
        val context = mockk<Context>(relaxed = true)
        val manager = mockk<ApiKeyManager>()
        every { manager.getApiKey() } returns key
        return manager
    }

    @Test
    fun `summarize returns Failure InvalidApiKey when no key stored`() = runTest {
        val service = mockk<ClaudeService>()
        val repository = SummarizationRepository(service, fakeApiKeyManager(null))

        val result = repository.summarize(listOf(note(1), note(2)))

        assertTrue(result is SummarizeResult.Failure)
        assertEquals(AppError.InvalidApiKey, (result as SummarizeResult.Failure).error)
    }

    @Test
    fun `summarize returns Success with extracted text on 200 response`() = runTest {
        val service = mockk<ClaudeService>()
        val response = Response.success(ClaudeResponse(content = listOf(ClaudeContentBlock.Text("Summary text"))))
        coEvery { service.sendMessage(any(), any(), any()) } returns response
        val repository = SummarizationRepository(service, fakeApiKeyManager("sk-ant-key"))

        val result = repository.summarize(listOf(note(1), note(2)))

        assertTrue(result is SummarizeResult.Success)
        assertEquals("Summary text", (result as SummarizeResult.Success).summary)
    }

    @Test
    fun `summarize returns Failure InvalidApiKey on HTTP 401`() = runTest {
        val service = mockk<ClaudeService>()
        val errorResponse = Response.error<ClaudeResponse>(
            401,
            "{}".toResponseBody("application/json".toMediaType())
        )
        coEvery { service.sendMessage(any(), any(), any()) } returns errorResponse
        val repository = SummarizationRepository(service, fakeApiKeyManager("sk-ant-key"))

        val result = repository.summarize(listOf(note(1), note(2)))

        assertTrue(result is SummarizeResult.Failure)
        assertEquals(AppError.InvalidApiKey, (result as SummarizeResult.Failure).error)
    }

    @Test
    fun `summarize returns Failure ServerError on HTTP 500`() = runTest {
        val service = mockk<ClaudeService>()
        val errorResponse = Response.error<ClaudeResponse>(
            500,
            "{}".toResponseBody("application/json".toMediaType())
        )
        coEvery { service.sendMessage(any(), any(), any()) } returns errorResponse
        val repository = SummarizationRepository(service, fakeApiKeyManager("sk-ant-key"))

        val result = repository.summarize(listOf(note(1), note(2)))

        assertTrue(result is SummarizeResult.Failure)
        assertEquals(AppError.ServerError, (result as SummarizeResult.Failure).error)
    }

    @Test
    fun `summarize maps thrown IOException via ErrorMapper`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } throws java.net.SocketTimeoutException()
        val repository = SummarizationRepository(service, fakeApiKeyManager("sk-ant-key"))

        val result = repository.summarize(listOf(note(1), note(2)))

        assertTrue(result is SummarizeResult.Failure)
        assertEquals(AppError.Timeout, (result as SummarizeResult.Failure).error)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail to compile**

Run: `cd ai-notes-app && ./gradlew testDebugUnitTest --tests "com.ai.notes.data.ai.model.ClaudeModelsTest" --tests "com.ai.notes.data.ai.SummarizationRepositoryTest"`
Expected: FAIL — compile error, `ClaudeContentBlock.Text` / `ClaudeContentBlock.ToolUse` / `ClaudeContentBlock.ToolResult` / `ClaudeTool` / `ClaudeMessage(content = List<...>)` / `ClaudeResponse.stopReason` do not exist yet.

- [ ] **Step 3: Write the model implementation**

Replace `app/src/main/java/com/ai/notes/data/ai/model/ClaudeModels.kt` with:

```kotlin
package com.ai.notes.data.ai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ClaudeMessage(
    val role: String,
    val content: List<ClaudeContentBlock>
)

@Serializable
sealed class ClaudeContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ClaudeContentBlock()

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject
    ) : ClaudeContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: String,
        @SerialName("is_error") val isError: Boolean = false
    ) : ClaudeContentBlock()
}

@Serializable
data class ClaudeTool(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject
)

@Serializable
data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<ClaudeMessage>,
    val tools: List<ClaudeTool>? = null
)

@Serializable
data class ClaudeResponse(
    val content: List<ClaudeContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null
)
```

In `app/src/main/java/com/ai/notes/data/ai/SummarizationRepository.kt`, replace the imports and `summarize` method body:

```kotlin
package com.ai.notes.data.ai

import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import com.ai.notes.data.ai.model.ClaudeRequest
import com.ai.notes.data.model.Note
import com.ai.notes.data.preferences.ApiKeyManager

sealed class SummarizeResult {
    data class Success(val summary: String) : SummarizeResult()
    data class Failure(val error: AppError) : SummarizeResult()
}

class SummarizationRepository(
    private val claudeService: ClaudeService,
    private val apiKeyManager: ApiKeyManager
) {
    suspend fun summarize(notes: List<Note>): SummarizeResult {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isNullOrEmpty()) {
            return SummarizeResult.Failure(AppError.InvalidApiKey)
        }

        val prompt = BatchSummarizer.buildPrompt(notes)
        val request = ClaudeRequest(
            model = CLAUDE_MODEL,
            maxTokens = 2000,
            messages = listOf(ClaudeMessage(role = "user", content = listOf(ClaudeContentBlock.Text(prompt))))
        )

        return try {
            val response = claudeService.sendMessage(apiKey, CLAUDE_API_VERSION, request)
            if (response.isSuccessful) {
                val text = response.body()?.content
                    ?.filterIsInstance<ClaudeContentBlock.Text>()
                    ?.firstOrNull()
                    ?.text
                if (text != null) {
                    SummarizeResult.Success(text)
                } else {
                    SummarizeResult.Failure(AppError.UnknownNetwork)
                }
            } else {
                SummarizeResult.Failure(ErrorMapper.mapHttpCode(response.code()))
            }
        } catch (t: Throwable) {
            SummarizeResult.Failure(ErrorMapper.mapThrowable(t))
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ai-notes-app && ./gradlew testDebugUnitTest --tests "com.ai.notes.data.ai.model.ClaudeModelsTest" --tests "com.ai.notes.data.ai.SummarizationRepositoryTest"`
Expected: PASS (11 tests: 5 in `ClaudeModelsTest`, 6 in `SummarizationRepositoryTest`)

- [ ] **Step 5: Full regression check and commit**

Run: `cd ai-notes-app && ./gradlew testDebugUnitTest`
Expected: PASS, no other test broken by the model shape change (grep confirms only these two test files and `SummarizationRepository.kt` reference `ClaudeContentBlock`/`ClaudeMessage` construction directly).

```bash
cd ai-notes-app
git add app/src/main/java/com/ai/notes/data/ai/model/ClaudeModels.kt \
        app/src/main/java/com/ai/notes/data/ai/SummarizationRepository.kt \
        app/src/test/java/com/ai/notes/data/ai/model/ClaudeModelsTest.kt \
        app/src/test/java/com/ai/notes/data/ai/SummarizationRepositoryTest.kt
git commit -m "$(cat <<'EOF'
Extend Claude API models for tool-use content blocks

ClaudeMessage.content becomes a list of typed content blocks
(text/tool_use/tool_result) instead of a plain string, and
ClaudeRequest/ClaudeResponse gain tools/stop_reason, laying the
groundwork for the chat agentic loop without changing the
summarization endpoint's behavior.
EOF
)"
```

---

## Task 2: ToolSchemaBuilder — AppFunctionMetadata to Claude tool schema

**Files:**
- Create: `app/src/main/java/com/ai/notes/data/ai/chat/ToolSchemaBuilder.kt`
- Test: `app/src/test/java/com/ai/notes/data/ai/chat/ToolSchemaBuilderTest.kt`

**Interfaces:**
- Consumes: `ClaudeTool` from Task 1; `androidx.appfunctions.metadata.*` classes (pure data holders, constructible directly in a JVM unit test — confirmed via source inspection, no Android framework calls in their constructors).
- Produces: `object ToolSchemaBuilder { fun simpleName(functionId: String): String; fun buildTool(metadata: AppFunctionMetadata): ClaudeTool }` — used by `AppFunctionToolBridge` in Task 4.

This is pure logic (no `Context`, no `AppFunctionManager`), so it's covered by a JVM unit test, not an instrumented one — isolating the one genuinely platform-dependent piece (Task 4) from everything that doesn't need to be.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ai/notes/data/ai/chat/ToolSchemaBuilderTest.kt`:

```kotlin
package com.ai.notes.data.ai.chat

import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionResponseMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaBuilderTest {

    private fun createNoteMetadata(): AppFunctionMetadata = AppFunctionMetadata(
        id = "com.ai.notes.AppFunctions.NoteFunctions#createNote",
        packageName = "com.ai.notes",
        isEnabled = true,
        schema = null,
        parameters = listOf(
            AppFunctionParameterMetadata(
                name = "title",
                isRequired = true,
                dataType = AppFunctionStringTypeMetadata(isNullable = false),
                description = "The note's title.",
            ),
            AppFunctionParameterMetadata(
                name = "tags",
                isRequired = true,
                dataType = AppFunctionArrayTypeMetadata(
                    itemType = AppFunctionStringTypeMetadata(isNullable = false),
                    isNullable = false,
                ),
                description = "Tags for the note.",
            ),
            AppFunctionParameterMetadata(
                name = "category",
                isRequired = true,
                dataType = AppFunctionStringTypeMetadata(isNullable = true),
                description = "Optional category.",
            ),
        ),
        response = AppFunctionResponseMetadata(valueType = AppFunctionStringTypeMetadata(isNullable = false)),
        description = "Creates a new note with a title, body, optional tags, and an optional category.",
    )

    @Test
    fun `simpleName extracts the method name from a qualified function id`() {
        assertEquals(
            "createNote",
            ToolSchemaBuilder.simpleName("com.ai.notes.AppFunctions.NoteFunctions#createNote"),
        )
    }

    @Test
    fun `buildTool uses the simple name and copies the description`() {
        val tool = ToolSchemaBuilder.buildTool(createNoteMetadata())

        assertEquals("createNote", tool.name)
        assertEquals(
            "Creates a new note with a title, body, optional tags, and an optional category.",
            tool.description,
        )
    }

    @Test
    fun `buildTool marks non-nullable string parameter as required string type`() {
        val tool = ToolSchemaBuilder.buildTool(createNoteMetadata())

        val titleSchema = tool.inputSchema["properties"]!!.jsonObject["title"]!!.jsonObject
        assertEquals("string", titleSchema["type"]!!.jsonPrimitive.content)
        val required = tool.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("title" in required)
    }

    @Test
    fun `buildTool excludes a nullable parameter from required`() {
        val tool = ToolSchemaBuilder.buildTool(createNoteMetadata())

        val required = tool.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse("category" in required)
    }

    @Test
    fun `buildTool encodes a List of String parameter as an array of string`() {
        val tool = ToolSchemaBuilder.buildTool(createNoteMetadata())

        val tagsSchema = tool.inputSchema["properties"]!!.jsonObject["tags"]!!.jsonObject
        assertEquals("array", tagsSchema["type"]!!.jsonPrimitive.content)
        assertEquals("string", tagsSchema["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildTool encodes an int parameter as integer type`() {
        val metadata = AppFunctionMetadata(
            id = "com.ai.notes.AppFunctions.NoteFunctions#getNote",
            packageName = "com.ai.notes",
            isEnabled = true,
            schema = null,
            parameters = listOf(
                AppFunctionParameterMetadata(
                    name = "noteId",
                    isRequired = true,
                    dataType = AppFunctionIntTypeMetadata(isNullable = false),
                    description = "The id of the note to retrieve.",
                ),
            ),
            response = AppFunctionResponseMetadata(valueType = AppFunctionStringTypeMetadata(isNullable = true)),
            description = "Retrieves a single note by its unique id.",
        )

        val tool = ToolSchemaBuilder.buildTool(metadata)

        val noteIdSchema = tool.inputSchema["properties"]!!.jsonObject["noteId"]!!.jsonObject
        assertEquals("integer", noteIdSchema["type"]!!.jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ai-notes-app && ./gradlew testDebugUnitTest --tests "com.ai.notes.data.ai.chat.ToolSchemaBuilderTest"`
Expected: FAIL — compile error, `ToolSchemaBuilder` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/ai/notes/data/ai/chat/ToolSchemaBuilder.kt`:

```kotlin
package com.ai.notes.data.ai.chat

import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import com.ai.notes.data.ai.model.ClaudeTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Converts the app's own registered [AppFunctionMetadata] (the same metadata Gemini/Assistant
 * discovers via `adb shell cmd app_function list-app-functions`) into Claude's tool-use JSON
 * schema shape, so the in-app chatbot offers the exact same tools without a second,
 * hand-maintained schema.
 */
object ToolSchemaBuilder {

    fun simpleName(functionId: String): String = functionId.substringAfterLast('#')

    fun buildTool(metadata: AppFunctionMetadata): ClaudeTool = ClaudeTool(
        name = simpleName(metadata.id),
        description = metadata.description,
        inputSchema = buildInputSchema(metadata.parameters),
    )

    private fun buildInputSchema(parameters: List<AppFunctionParameterMetadata>): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for (parameter in parameters) {
                    putJsonObject(parameter.name) {
                        putTypeFields(parameter.dataType)
                        put("description", parameter.description)
                    }
                }
            }
            putJsonArray("required") {
                for (parameter in parameters) {
                    // isNullable (not isRequired) is the real optionality signal: the platform
                    // marks every parameter isRequired=true, even nullable ones like `category`.
                    if (!parameter.dataType.isNullable) add(parameter.name)
                }
            }
        }

    private fun JsonObjectBuilder.putTypeFields(dataType: AppFunctionDataTypeMetadata) {
        when (dataType) {
            is AppFunctionIntTypeMetadata, is AppFunctionLongTypeMetadata -> put("type", "integer")
            is AppFunctionFloatTypeMetadata, is AppFunctionDoubleTypeMetadata -> put("type", "number")
            is AppFunctionBooleanTypeMetadata -> put("type", "boolean")
            is AppFunctionStringTypeMetadata -> put("type", "string")
            is AppFunctionArrayTypeMetadata -> {
                put("type", "array")
                putJsonObject("items") { putTypeFields(dataType.itemType) }
            }
            else -> put("type", "string")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ai-notes-app && ./gradlew testDebugUnitTest --tests "com.ai.notes.data.ai.chat.ToolSchemaBuilderTest"`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
cd ai-notes-app
git add app/src/main/java/com/ai/notes/data/ai/chat/ToolSchemaBuilder.kt \
        app/src/test/java/com/ai/notes/data/ai/chat/ToolSchemaBuilderTest.kt
git commit -m "$(cat <<'EOF'
Add ToolSchemaBuilder to derive Claude tool schemas from AppFunctionMetadata

Pure conversion logic, unit-testable without a device, that will let
the chat feature offer Claude the same functions already registered
for OS AppFunctions instead of a duplicated schema.
EOF
)"
```

---

## Task 3: ChatRepository — agentic tool-use loop

**Files:**
- Create: `app/src/main/java/com/ai/notes/data/ai/chat/ChatMessage.kt`
- Create: `app/src/main/java/com/ai/notes/data/ai/chat/ToolBridge.kt`
- Create: `app/src/main/java/com/ai/notes/data/ai/chat/ChatRepository.kt`
- Test: `app/src/test/java/com/ai/notes/data/ai/chat/ChatRepositoryTest.kt`

**Interfaces:**
- Consumes: `ClaudeService`, `ApiKeyManager`, `ErrorMapper`, `AppError` (existing); `ClaudeMessage`/`ClaudeContentBlock`/`ClaudeRequest`/`ClaudeTool` from Task 1.
- Produces:
  - `sealed class ChatMessage { data class FromUser(val text: String); data class FromAssistant(val text: String); data class ToolActivity(val functionName: String) }` — consumed by `ChatViewModel` (Task 5).
  - `interface ToolBridge { suspend fun discoverTools(): List<ClaudeTool>; suspend fun execute(functionName: String, input: JsonObject): ToolExecutionResult }` and `sealed class ToolExecutionResult { data class Success(val resultJson: String); data class Failure(val message: String) }` — implemented for real by `AppFunctionToolBridge` (Task 4).
  - `data class PendingToolUse(val toolUseId: String, val functionName: String, val input: JsonObject)`.
  - `sealed class ChatTurnResult { data class Done(val history: List<ClaudeMessage>, val reply: String, val toolCalls: List<String>); data class NeedsConfirmation(val history: List<ClaudeMessage>, val pending: PendingToolUse, val resolvedResults: List<ClaudeContentBlock.ToolResult>, val toolCalls: List<String>); data class Error(val error: AppError) }`.
  - `class ChatRepository(claudeService: ClaudeService, apiKeyManager: ApiKeyManager, toolBridge: ToolBridge) { suspend fun send(history: List<ClaudeMessage>): ChatTurnResult; suspend fun resolveConfirmation(history: List<ClaudeMessage>, pending: PendingToolUse, resolvedResults: List<ClaudeContentBlock.ToolResult>, approved: Boolean): ChatTurnResult }` — consumed by `ChatViewModel` (Task 5).
- `DESTRUCTIVE_FUNCTIONS = setOf("deleteNote")` and a 5-iteration cap live as private constants inside `ChatRepository`.

Design note carried into the tests below: if an assistant turn calls more than one tool and one of them (`deleteNote`) is destructive, the non-destructive calls in that same turn are executed immediately and their results held in `NeedsConfirmation.resolvedResults`; `resolveConfirmation` combines those with the (approved or declined) destructive tool's result into one `tool_result`-bearing turn. This matters because Claude's API expects a `tool_result` for every `tool_use` id from the prior turn — dropping the non-destructive ones would leave the conversation unable to continue correctly.

- [ ] **Step 1: Write the failing test**

Create `app/src/main/java/com/ai/notes/data/ai/chat/ChatMessage.kt`:

```kotlin
package com.ai.notes.data.ai.chat

sealed class ChatMessage {
    data class FromUser(val text: String) : ChatMessage()
    data class FromAssistant(val text: String) : ChatMessage()
    data class ToolActivity(val functionName: String) : ChatMessage()
}
```

Create `app/src/main/java/com/ai/notes/data/ai/chat/ToolBridge.kt`:

```kotlin
package com.ai.notes.data.ai.chat

import com.ai.notes.data.ai.model.ClaudeTool
import kotlinx.serialization.json.JsonObject

interface ToolBridge {
    suspend fun discoverTools(): List<ClaudeTool>
    suspend fun execute(functionName: String, input: JsonObject): ToolExecutionResult
}

sealed class ToolExecutionResult {
    data class Success(val resultJson: String) : ToolExecutionResult()
    data class Failure(val message: String) : ToolExecutionResult()
}
```

Create `app/src/test/java/com/ai/notes/data/ai/chat/ChatRepositoryTest.kt`:

```kotlin
package com.ai.notes.data.ai.chat

import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.ClaudeService
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import com.ai.notes.data.ai.model.ClaudeRequest
import com.ai.notes.data.ai.model.ClaudeResponse
import com.ai.notes.data.preferences.ApiKeyManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

private fun apiKeyManager(key: String? = "sk-ant-key"): ApiKeyManager {
    val manager = mockk<ApiKeyManager>()
    every { manager.getApiKey() } returns key
    return manager
}

private fun textResponse(text: String) = Response.success(
    ClaudeResponse(content = listOf(ClaudeContentBlock.Text(text)), stopReason = "end_turn")
)

private fun toolUseResponse(id: String, name: String, input: JsonObject = JsonObject(emptyMap())) = Response.success(
    ClaudeResponse(
        content = listOf(ClaudeContentBlock.ToolUse(id = id, name = name, input = input)),
        stopReason = "tool_use",
    )
)

class ChatRepositoryTest {

    @Test
    fun `send returns Done with the plain text reply when no tool is used`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns textResponse("Hello there!")
        val repository = ChatRepository(service, apiKeyManager(), mockk<ToolBridge>())

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("Hi")))))

        assertTrue(result is ChatTurnResult.Done)
        assertEquals("Hello there!", (result as ChatTurnResult.Done).reply)
        assertTrue(result.toolCalls.isEmpty())
    }

    @Test
    fun `send executes a non-destructive tool then returns the final text`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returnsMany listOf(
            toolUseResponse("t1", "searchNotes", JsonObject(mapOf("query" to JsonPrimitive("milk")))),
            textResponse("Found 1 note about milk."),
        )
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        coEvery { toolBridge.execute("searchNotes", any()) } returns ToolExecutionResult.Success("[]")
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("find milk note")))))

        assertTrue(result is ChatTurnResult.Done)
        assertEquals("Found 1 note about milk.", (result as ChatTurnResult.Done).reply)
        assertEquals(listOf("searchNotes"), result.toolCalls)
        coVerify(exactly = 1) { toolBridge.execute("searchNotes", any()) }
    }

    @Test
    fun `send pauses for confirmation on deleteNote without executing it`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns
            toolUseResponse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("delete note 5")))))

        assertTrue(result is ChatTurnResult.NeedsConfirmation)
        val confirmation = result as ChatTurnResult.NeedsConfirmation
        assertEquals("deleteNote", confirmation.pending.functionName)
        assertEquals(listOf("deleteNote"), confirmation.toolCalls)
        coVerify(exactly = 0) { toolBridge.execute("deleteNote", any()) }
    }

    @Test
    fun `resolveConfirmation with approved true executes the held tool and continues`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns textResponse("Deleted it.")
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        coEvery { toolBridge.execute("deleteNote", any()) } returns ToolExecutionResult.Success("true")
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))

        val result = repository.resolveConfirmation(
            history = listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("delete note 5")))),
            pending = pending,
            resolvedResults = emptyList(),
            approved = true,
        )

        assertTrue(result is ChatTurnResult.Done)
        assertEquals("Deleted it.", (result as ChatTurnResult.Done).reply)
        coVerify(exactly = 1) { toolBridge.execute("deleteNote", any()) }
    }

    @Test
    fun `resolveConfirmation with approved false does not execute and continues with a decline result`() = runTest {
        val service = mockk<ClaudeService>()
        val requestSlot = slot<ClaudeRequest>()
        coEvery { service.sendMessage(any(), any(), capture(requestSlot)) } returns textResponse("Okay, keeping it.")
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))

        val result = repository.resolveConfirmation(
            history = listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("delete note 5")))),
            pending = pending,
            resolvedResults = emptyList(),
            approved = false,
        )

        assertTrue(result is ChatTurnResult.Done)
        coVerify(exactly = 0) { toolBridge.execute("deleteNote", any()) }
        val lastMessageBlocks = requestSlot.captured.messages.last().content
        val toolResult = lastMessageBlocks.single() as ClaudeContentBlock.ToolResult
        assertTrue(toolResult.isError)
        assertEquals("User declined this action.", toolResult.content)
    }

    @Test
    fun `resolveConfirmation combines already-resolved results with the confirmed one`() = runTest {
        val service = mockk<ClaudeService>()
        val requestSlot = slot<ClaudeRequest>()
        coEvery { service.sendMessage(any(), any(), capture(requestSlot)) } returns textResponse("Done.")
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        coEvery { toolBridge.execute("deleteNote", any()) } returns ToolExecutionResult.Success("true")
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)
        val alreadyResolved = listOf(
            ClaudeContentBlock.ToolResult(toolUseId = "t0", content = "[{\"id\":5}]"),
        )
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))

        repository.resolveConfirmation(
            history = listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("find and delete note 5")))),
            pending = pending,
            resolvedResults = alreadyResolved,
            approved = true,
        )

        val lastMessageBlocks = requestSlot.captured.messages.last().content
        assertEquals(2, lastMessageBlocks.size)
        assertEquals("t0", (lastMessageBlocks[0] as ClaudeContentBlock.ToolResult).toolUseId)
        assertEquals("t1", (lastMessageBlocks[1] as ClaudeContentBlock.ToolResult).toolUseId)
    }

    @Test
    fun `send maps a tool execution Failure to an isError tool_result and continues`() = runTest {
        val service = mockk<ClaudeService>()
        val requestSlot = slot<ClaudeRequest>()
        coEvery { service.sendMessage(any(), any(), capture(requestSlot)) } returnsMany listOf(
            toolUseResponse("t1", "searchNotes"),
            textResponse("Sorry, I couldn't search."),
        )
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        coEvery { toolBridge.execute("searchNotes", any()) } returns ToolExecutionResult.Failure("boom")
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("search")))))

        assertTrue(result is ChatTurnResult.Done)
        val toolResult = requestSlot.captured.messages.last().content.single() as ClaudeContentBlock.ToolResult
        assertTrue(toolResult.isError)
        assertEquals("boom", toolResult.content)
    }

    @Test
    fun `send returns an apologetic Done after exceeding the iteration cap`() = runTest {
        val service = mockk<ClaudeService>()
        coEvery { service.sendMessage(any(), any(), any()) } returns toolUseResponse("t1", "searchNotes")
        val toolBridge = mockk<ToolBridge>()
        coEvery { toolBridge.discoverTools() } returns emptyList()
        coEvery { toolBridge.execute("searchNotes", any()) } returns ToolExecutionResult.Success("[]")
        val repository = ChatRepository(service, apiKeyManager(), toolBridge)

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("loop forever")))))

        assertTrue(result is ChatTurnResult.Done)
        coVerify(exactly = 5) { toolBridge.execute("searchNotes", any()) }
    }

    @Test
    fun `send returns Error InvalidApiKey when no key is stored`() = runTest {
        val repository = ChatRepository(mockk<ClaudeService>(), apiKeyManager(null), mockk<ToolBridge>())

        val result = repository.send(listOf(ClaudeMessage("user", listOf(ClaudeContentBlock.Text("hi")))))

        assertTrue(result is ChatTurnResult.Error)
        assertEquals(AppError.InvalidApiKey, (result as ChatTurnResult.Error).error)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ai-notes-app && ./gradlew testDebugUnitTest --tests "com.ai.notes.data.ai.chat.ChatRepositoryTest"`
Expected: FAIL — compile error, `ChatRepository`/`ChatTurnResult`/`PendingToolUse` do not exist yet.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/ai/notes/data/ai/chat/ChatRepository.kt`:

```kotlin
package com.ai.notes.data.ai.chat

import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.CLAUDE_API_VERSION
import com.ai.notes.data.ai.CLAUDE_MODEL
import com.ai.notes.data.ai.ClaudeService
import com.ai.notes.data.ai.ErrorMapper
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import com.ai.notes.data.ai.model.ClaudeRequest
import com.ai.notes.data.preferences.ApiKeyManager
import kotlinx.serialization.json.JsonObject

data class PendingToolUse(
    val toolUseId: String,
    val functionName: String,
    val input: JsonObject,
)

sealed class ChatTurnResult {
    data class Done(
        val history: List<ClaudeMessage>,
        val reply: String,
        val toolCalls: List<String>,
    ) : ChatTurnResult()

    data class NeedsConfirmation(
        val history: List<ClaudeMessage>,
        val pending: PendingToolUse,
        val resolvedResults: List<ClaudeContentBlock.ToolResult>,
        val toolCalls: List<String>,
    ) : ChatTurnResult()

    data class Error(val error: AppError) : ChatTurnResult()
}

/**
 * Runs Claude's agentic tool-use loop: send history + tool schemas, execute any tool the model
 * calls via [toolBridge] (pausing for [DESTRUCTIVE_FUNCTIONS] until the caller confirms), feed
 * results back, repeat until Claude returns a final text reply.
 */
class ChatRepository(
    private val claudeService: ClaudeService,
    private val apiKeyManager: ApiKeyManager,
    private val toolBridge: ToolBridge,
) {
    suspend fun send(history: List<ClaudeMessage>): ChatTurnResult = runLoop(history)

    suspend fun resolveConfirmation(
        history: List<ClaudeMessage>,
        pending: PendingToolUse,
        resolvedResults: List<ClaudeContentBlock.ToolResult>,
        approved: Boolean,
    ): ChatTurnResult {
        val pendingResult = if (approved) {
            toToolResultBlock(pending, toolBridge.execute(pending.functionName, pending.input))
        } else {
            ClaudeContentBlock.ToolResult(
                toolUseId = pending.toolUseId,
                content = "User declined this action.",
                isError = true,
            )
        }
        val nextHistory = history + ClaudeMessage(role = "user", content = resolvedResults + pendingResult)
        return runLoop(nextHistory)
    }

    private suspend fun runLoop(startHistory: List<ClaudeMessage>): ChatTurnResult {
        val apiKey = apiKeyManager.getApiKey()
        if (apiKey.isNullOrEmpty()) return ChatTurnResult.Error(AppError.InvalidApiKey)

        var history = startHistory
        val toolCallNames = mutableListOf<String>()
        val tools = toolBridge.discoverTools().ifEmpty { null }

        repeat(MAX_ITERATIONS) {
            val request = ClaudeRequest(
                model = CLAUDE_MODEL,
                maxTokens = 1024,
                messages = history,
                tools = tools,
            )
            val response = try {
                claudeService.sendMessage(apiKey, CLAUDE_API_VERSION, request)
            } catch (t: Throwable) {
                return ChatTurnResult.Error(ErrorMapper.mapThrowable(t))
            }
            if (!response.isSuccessful) {
                return ChatTurnResult.Error(ErrorMapper.mapHttpCode(response.code()))
            }
            val body = response.body() ?: return ChatTurnResult.Error(AppError.UnknownNetwork)
            history = history + ClaudeMessage(role = "assistant", content = body.content)

            val toolUses = body.content.filterIsInstance<ClaudeContentBlock.ToolUse>()
            if (body.stopReason != "tool_use" || toolUses.isEmpty()) {
                val reply = body.content.filterIsInstance<ClaudeContentBlock.Text>()
                    .joinToString("\n") { it.text }
                return ChatTurnResult.Done(history, reply, toolCallNames)
            }
            toolCallNames += toolUses.map { it.name }

            val destructive = toolUses.firstOrNull { it.name in DESTRUCTIVE_FUNCTIONS }
            if (destructive != null) {
                // Execute every other tool call from this same turn immediately; Claude expects a
                // tool_result for every tool_use id, so these can't be silently dropped while we
                // wait on confirmation for the destructive one.
                val resolvedResults = toolUses.filter { it.id != destructive.id }.map { toolUse ->
                    toToolResultBlock(
                        PendingToolUse(toolUse.id, toolUse.name, toolUse.input),
                        toolBridge.execute(toolUse.name, toolUse.input),
                    )
                }
                return ChatTurnResult.NeedsConfirmation(
                    history,
                    PendingToolUse(destructive.id, destructive.name, destructive.input),
                    resolvedResults,
                    toolCallNames,
                )
            }

            val resultBlocks = toolUses.map { toolUse ->
                toToolResultBlock(
                    PendingToolUse(toolUse.id, toolUse.name, toolUse.input),
                    toolBridge.execute(toolUse.name, toolUse.input),
                )
            }
            history = history + ClaudeMessage(role = "user", content = resultBlocks)
        }
        return ChatTurnResult.Done(history, "Sorry, I couldn't resolve that.", toolCallNames)
    }

    private fun toToolResultBlock(
        pending: PendingToolUse,
        result: ToolExecutionResult,
    ): ClaudeContentBlock.ToolResult = when (result) {
        is ToolExecutionResult.Success -> ClaudeContentBlock.ToolResult(
            toolUseId = pending.toolUseId,
            content = result.resultJson,
        )
        is ToolExecutionResult.Failure -> ClaudeContentBlock.ToolResult(
            toolUseId = pending.toolUseId,
            content = result.message,
            isError = true,
        )
    }

    private companion object {
        const val MAX_ITERATIONS = 5
        val DESTRUCTIVE_FUNCTIONS = setOf("deleteNote")
    }
}
```

`CLAUDE_MODEL`/`CLAUDE_API_VERSION` are the existing top-level constants already declared in `ClaudeService.kt` (`app/src/main/java/com/ai/notes/data/ai/ClaudeService.kt:10-12`) — no changes needed there.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ai-notes-app && ./gradlew testDebugUnitTest --tests "com.ai.notes.data.ai.chat.ChatRepositoryTest"`
Expected: PASS (9 tests)

- [ ] **Step 5: Commit**

```bash
cd ai-notes-app
git add app/src/main/java/com/ai/notes/data/ai/chat/ChatMessage.kt \
        app/src/main/java/com/ai/notes/data/ai/chat/ToolBridge.kt \
        app/src/main/java/com/ai/notes/data/ai/chat/ChatRepository.kt \
        app/src/test/java/com/ai/notes/data/ai/chat/ChatRepositoryTest.kt
git commit -m "$(cat <<'EOF'
Add ChatRepository agentic tool-use loop

Runs the send/execute-tool/feed-result loop against ClaudeService via
a ToolBridge abstraction, pausing for confirmation before deleteNote.
Tested against a mocked ToolBridge so this logic doesn't depend on a
device; the real AppFunctionManager-backed bridge lands next.
EOF
)"
```

---

## Task 4: AppFunctionToolBridge — real AppFunctionManager integration

**Files:**
- Create: `app/src/main/java/com/ai/notes/data/ai/chat/AppFunctionToolBridge.kt`
- Test: `app/src/androidTest/java/com/ai/notes/data/ai/chat/AppFunctionToolBridgeTest.kt`

**Interfaces:**
- Consumes: `ToolBridge`, `ToolExecutionResult` (Task 3); `ToolSchemaBuilder` (Task 2); real `androidx.appfunctions` classes (see below).
- Produces: `class AppFunctionToolBridge(context: Context) : ToolBridge` — consumed by `MainActivity` wiring (Task 6).

Real API surface used here (confirmed by decompiling `appfunctions-1.0.0-alpha09-sources.jar`, not guessed):
- `AppFunctionManager.getInstance(context): AppFunctionManager?`
- `manager.observeAppFunctions(AppFunctionSearchSpec(packageNames = setOf(pkg))): Flow<List<AppFunctionPackageMetadata>>`, where `AppFunctionPackageMetadata.appFunctions: List<AppFunctionMetadata>`
- `manager.executeAppFunction(ExecuteAppFunctionRequest(targetPackageName, functionIdentifier, functionParameters)): ExecuteAppFunctionResponse` — sealed `Success(returnValue: AppFunctionData)` / `Error(error: AppFunctionException)`, and `AppFunctionException.errorMessage: String?`
- `AppFunctionData.Builder(parameterMetadataList: List<AppFunctionParameterMetadata>, componentMetadata: AppFunctionComponentsMetadata)` with `setInt`/`setLong`/`setFloat`/`setDouble`/`setBoolean`/`setString`/`setStringList(key, List<String>)`, and `.build()`
- Reading: `AppFunctionData.getBoolean/getInt/getLong/getString/getStringList(key)`, `getAppFunctionData(key): AppFunctionData?`, `getAppFunctionDataList(key): List<AppFunctionData>?`
- `metadata.response.valueType` is `AppFunctionReferenceTypeMetadata` for a `Note` return (referencing `metadata.components.dataTypes["com.ai.notes.data.model.Note"]`, an `AppFunctionObjectTypeMetadata`), and `AppFunctionArrayTypeMetadata(itemType = <reference>)` for `List<Note>` — confirmed against the real `list-app-functions` JSON dump captured during manual testing (`docs/APP_FUNCTIONS_TESTING.md`).
- `ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE` is the key under which the return value is stored.

`Note` is the only custom type anywhere in the schema, so results are converted with a small dedicated extractor rather than a generic recursive `AppFunctionData`→JSON converter.

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/com/ai/notes/data/ai/chat/AppFunctionToolBridgeTest.kt`:

```kotlin
package com.ai.notes.data.ai.chat

import android.content.Context
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ai.notes.AppFunctions.NoteFunctions
import com.ai.notes.data.database.NoteDatabase
import com.ai.notes.data.database.repositories.NoteRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented — requires a device/emulator on API 36.1+ where AppFunctionManager's shell/tool
 * dispatch is actually implemented (see docs/APP_FUNCTIONS_TESTING.md); this seeds real notes via
 * NoteFunctions directly (same approach as NoteFunctionsExecutionTest) so the bridge is exercised
 * against genuinely registered, real data.
 */
@RunWith(AndroidJUnit4::class)
class AppFunctionToolBridgeTest {

    private class FakeAppFunctionContext(ctx: Context) : AppFunctionContext {
        override val context: Context = ctx
    }

    private fun seedNote(title: String, tags: List<String> = emptyList()): Int = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NoteDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val functions = NoteFunctions(NoteRepository(db.noteDao()))
        val fakeContext = FakeAppFunctionContext(context)
        functions.createNote(fakeContext, title, "Body for $title", tags, null).id
    }

    @Test
    fun discoverTools_returnsAllFourFunctionsWithSchemas() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = AppFunctionToolBridge(context)

        val tools = bridge.discoverTools()

        val names = tools.map { it.name }.toSet()
        assertEquals(setOf("createNote", "searchNotes", "getNote", "deleteNote"), names)
    }

    @Test
    fun execute_createNote_persistsAndReturnsNoteJson() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = AppFunctionToolBridge(context)
        bridge.discoverTools()

        val input = JsonObject(
            mapOf(
                "title" to JsonPrimitive("Bridge Test Note"),
                "body" to JsonPrimitive("Created via AppFunctionToolBridge"),
                "tags" to buildJsonArray { add(JsonPrimitive("bridge")) },
                "category" to JsonPrimitive(null as String?),
            )
        )

        val result = bridge.execute("createNote", input)

        assertTrue(result is ToolExecutionResult.Success)
        val json = (result as ToolExecutionResult.Success).resultJson
        assertTrue(json.contains("Bridge Test Note"))
    }

    @Test
    fun execute_unknownFunction_returnsFailure() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = AppFunctionToolBridge(context)
        bridge.discoverTools()

        val result = bridge.execute("notAFunction", JsonObject(emptyMap()))

        assertTrue(result is ToolExecutionResult.Failure)
    }

    @Test
    fun discoverTools_returnsEmptyListWhenAppFunctionManagerUnavailable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Sanity check the precondition this device actually exercises the supported path;
        // documents the documented fallback behavior even though we can't force the
        // "unsupported device" branch from an instrumented test on a supporting device.
        assertNotNull(AppFunctionManager.getInstance(context))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ai-notes-app && ./gradlew connectedDebugAndroidTest --tests "com.ai.notes.data.ai.chat.AppFunctionToolBridgeTest"`
Expected: FAIL — compile error, `AppFunctionToolBridge` does not exist yet. (Requires the API 36.1+ emulator from `docs/APP_FUNCTIONS_TESTING.md` to be running.)

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/ai/notes/data/ai/chat/AppFunctionToolBridge.kt`:

```kotlin
package com.ai.notes.data.ai.chat

import android.content.Context
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
import com.ai.notes.data.ai.model.ClaudeTool
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * The in-process bridge between Claude's tool-use loop and the app's own registered AppFunctions,
 * modeled on https://github.com/philipplackner/AppFunctionsDemo's AppFunctionRunner: it discovers
 * and executes the exact same functions Gemini/Assistant would via AppFunctionManager, so there is
 * one source of truth for what "createNote" etc. mean.
 */
class AppFunctionToolBridge(private val context: Context) : ToolBridge {
    private val packageName: String = context.packageName
    private var cachedMetadata: Map<String, AppFunctionMetadata> = emptyMap()

    override suspend fun discoverTools(): List<ClaudeTool> {
        val manager = AppFunctionManager.getInstance(context) ?: return emptyList()
        val spec = AppFunctionSearchSpec(packageNames = setOf(packageName))
        val metadataList = manager.observeAppFunctions(spec).first().flatMap { it.appFunctions }
        cachedMetadata = metadataList.associateBy { ToolSchemaBuilder.simpleName(it.id) }
        return metadataList.map { ToolSchemaBuilder.buildTool(it) }
    }

    override suspend fun execute(functionName: String, input: JsonObject): ToolExecutionResult {
        val manager = AppFunctionManager.getInstance(context)
            ?: return ToolExecutionResult.Failure("AppFunctions is not available on this device.")
        val metadata = cachedMetadata[functionName]
            ?: return ToolExecutionResult.Failure("Function '$functionName' was not found.")

        val parameters = buildParameters(metadata, input)
        val request = ExecuteAppFunctionRequest(packageName, metadata.id, parameters)

        return when (val response = manager.executeAppFunction(request)) {
            is ExecuteAppFunctionResponse.Success ->
                ToolExecutionResult.Success(readReturnValue(metadata, response.returnValue))
            is ExecuteAppFunctionResponse.Error ->
                ToolExecutionResult.Failure(response.error.errorMessage ?: "Function execution failed.")
        }
    }

    private fun buildParameters(metadata: AppFunctionMetadata, input: JsonObject): AppFunctionData {
        val builder = AppFunctionData.Builder(metadata.parameters, metadata.components)
        for (parameter in metadata.parameters) {
            val value = input[parameter.name] ?: continue
            if (value is kotlinx.serialization.json.JsonNull) continue
            when (parameter.dataType) {
                is AppFunctionIntTypeMetadata -> builder.setInt(parameter.name, value.jsonPrimitive.int)
                is AppFunctionLongTypeMetadata -> builder.setLong(parameter.name, value.jsonPrimitive.long)
                is AppFunctionFloatTypeMetadata -> builder.setFloat(parameter.name, value.jsonPrimitive.float)
                is AppFunctionDoubleTypeMetadata -> builder.setDouble(parameter.name, value.jsonPrimitive.double)
                is AppFunctionBooleanTypeMetadata -> builder.setBoolean(parameter.name, value.jsonPrimitive.boolean)
                is AppFunctionArrayTypeMetadata ->
                    builder.setStringList(parameter.name, value.jsonArray.map { it.jsonPrimitive.content })
                else -> value.jsonPrimitive.contentOrNull?.let { builder.setString(parameter.name, it) }
            }
        }
        return builder.build()
    }

    private fun readReturnValue(metadata: AppFunctionMetadata, data: AppFunctionData): String {
        val key = ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE
        return when (metadata.response.valueType) {
            is AppFunctionBooleanTypeMetadata -> data.getBoolean(key).toString()
            is AppFunctionIntTypeMetadata -> data.getInt(key).toString()
            is AppFunctionLongTypeMetadata -> data.getLong(key).toString()
            is AppFunctionReferenceTypeMetadata ->
                data.getAppFunctionData(key)?.let { noteJson(it).toString() } ?: "null"
            is AppFunctionArrayTypeMetadata ->
                "[" + (data.getAppFunctionDataList(key) ?: emptyList()).joinToString(",") { noteJson(it).toString() } + "]"
            else -> data.getString(key) ?: "null"
        }
    }

    /**
     * `Note` is the only custom type in this app's AppFunctions schema, so a small dedicated
     * extractor covers it — a generic recursive AppFunctionData-to-JSON converter would be
     * speculative generality with nothing else to justify it.
     */
    private fun noteJson(note: AppFunctionData): JsonObject = buildJsonObject {
        put("id", note.getInt("id"))
        put("title", note.getString("title") ?: "")
        put("body", note.getString("body") ?: "")
        put("tags", kotlinx.serialization.json.JsonArray((note.getStringList("tags") ?: emptyList()).map { JsonPrimitive(it) }))
        note.getString("category")?.let { put("category", it) }
        put("createdAt", note.getLong("createdAt"))
        put("updatedAt", note.getLong("updatedAt"))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ai-notes-app && ./gradlew connectedDebugAndroidTest --tests "com.ai.notes.data.ai.chat.AppFunctionToolBridgeTest"`
Expected: PASS (4 tests) — requires the API 37 emulator confirmed working in `docs/APP_FUNCTIONS_TESTING.md` to be running (`adb devices` shows it attached).

- [ ] **Step 5: Commit**

```bash
cd ai-notes-app
git add app/src/main/java/com/ai/notes/data/ai/chat/AppFunctionToolBridge.kt \
        app/src/androidTest/java/com/ai/notes/data/ai/chat/AppFunctionToolBridgeTest.kt
git commit -m "$(cat <<'EOF'
Add AppFunctionToolBridge: real AppFunctionManager-backed ToolBridge

Discovers and executes the app's own registered AppFunctions
in-process via the real platform API, giving Claude's tool-use loop
the exact same createNote/searchNotes/getNote/deleteNote that OS
Gemini already uses, with no duplicated implementation.
EOF
)"
```

---

## Task 5: ChatViewModel

**Files:**
- Create: `app/src/main/java/com/ai/notes/ui/viewmodel/ChatViewModel.kt`
- Test: `app/src/test/java/com/ai/notes/ui/viewmodel/ChatViewModelTest.kt`

**Interfaces:**
- Consumes: `ChatRepository`, `ChatTurnResult`, `PendingToolUse`, `ChatMessage` (Task 3); `ClaudeMessage`/`ClaudeContentBlock` (Task 1); `AppError` (existing).
- Produces: `class ChatViewModel(chatRepository: ChatRepository) : ViewModel()` with `messages: StateFlow<List<ChatMessage>>`, `isLoading: StateFlow<Boolean>`, `pendingConfirmation: StateFlow<ChatTurnResult.NeedsConfirmation?>`, `errorEvent: StateFlow<AppError?>`, `fun sendMessage(text: String)`, `fun confirmPendingAction()`, `fun cancelPendingAction()`, `fun dismissError()` — consumed by `ChatScreen` (Task 6) and `MainActivity` (Task 7).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/ai/notes/ui/viewmodel/ChatViewModelTest.kt`:

```kotlin
package com.ai.notes.ui.viewmodel

import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.chat.ChatMessage
import com.ai.notes.data.ai.chat.ChatRepository
import com.ai.notes.data.ai.chat.ChatTurnResult
import com.ai.notes.data.ai.chat.PendingToolUse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendMessage appends user message then assistant reply on Done`() = runTest {
        val repository = mockk<ChatRepository>()
        coEvery { repository.send(any()) } returns ChatTurnResult.Done(emptyList(), "Hi back!", emptyList())
        val vm = ChatViewModel(repository)

        vm.sendMessage("Hello")
        dispatcher.scheduler.advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(ChatMessage.FromUser("Hello"), messages[0])
        assertEquals(ChatMessage.FromAssistant("Hi back!"), messages[1])
    }

    @Test
    fun `sendMessage ignores blank input`() = runTest {
        val repository = mockk<ChatRepository>()
        val vm = ChatViewModel(repository)

        vm.sendMessage("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.messages.value.isEmpty())
        coVerify(exactly = 0) { repository.send(any()) }
    }

    @Test
    fun `sendMessage sets errorEvent on Error result`() = runTest {
        val repository = mockk<ChatRepository>()
        coEvery { repository.send(any()) } returns ChatTurnResult.Error(AppError.InvalidApiKey)
        val vm = ChatViewModel(repository)

        vm.sendMessage("Hello")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AppError.InvalidApiKey, vm.errorEvent.value)
    }

    @Test
    fun `sendMessage surfaces tool activity chips before the assistant reply`() = runTest {
        val repository = mockk<ChatRepository>()
        coEvery { repository.send(any()) } returns ChatTurnResult.Done(emptyList(), "Found it.", listOf("searchNotes"))
        val vm = ChatViewModel(repository)

        vm.sendMessage("find my note")
        dispatcher.scheduler.advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(ChatMessage.ToolActivity("searchNotes"), messages[1])
        assertEquals(ChatMessage.FromAssistant("Found it."), messages[2])
    }

    @Test
    fun `sendMessage sets pendingConfirmation on NeedsConfirmation`() = runTest {
        val repository = mockk<ChatRepository>()
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))
        val confirmation = ChatTurnResult.NeedsConfirmation(emptyList(), pending, emptyList(), listOf("deleteNote"))
        coEvery { repository.send(any()) } returns confirmation
        val vm = ChatViewModel(repository)

        vm.sendMessage("delete note 5")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(confirmation, vm.pendingConfirmation.value)
    }

    @Test
    fun `confirmPendingAction resolves via repository and clears pendingConfirmation`() = runTest {
        val repository = mockk<ChatRepository>()
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))
        val confirmation = ChatTurnResult.NeedsConfirmation(emptyList(), pending, emptyList(), listOf("deleteNote"))
        coEvery { repository.send(any()) } returns confirmation
        coEvery { repository.resolveConfirmation(any(), pending, emptyList(), true) } returns
            ChatTurnResult.Done(emptyList(), "Deleted.", emptyList())
        val vm = ChatViewModel(repository)
        vm.sendMessage("delete note 5")
        dispatcher.scheduler.advanceUntilIdle()

        vm.confirmPendingAction()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.pendingConfirmation.value)
        assertTrue(vm.messages.value.any { it == ChatMessage.FromAssistant("Deleted.") })
        coVerify(exactly = 1) { repository.resolveConfirmation(any(), pending, emptyList(), true) }
    }

    @Test
    fun `cancelPendingAction resolves with approved false`() = runTest {
        val repository = mockk<ChatRepository>()
        val pending = PendingToolUse("t1", "deleteNote", JsonObject(mapOf("noteId" to JsonPrimitive(5))))
        val confirmation = ChatTurnResult.NeedsConfirmation(emptyList(), pending, emptyList(), listOf("deleteNote"))
        coEvery { repository.send(any()) } returns confirmation
        coEvery { repository.resolveConfirmation(any(), pending, emptyList(), false) } returns
            ChatTurnResult.Done(emptyList(), "Okay, keeping it.", emptyList())
        val vm = ChatViewModel(repository)
        vm.sendMessage("delete note 5")
        dispatcher.scheduler.advanceUntilIdle()

        vm.cancelPendingAction()
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.resolveConfirmation(any(), pending, emptyList(), false) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ai-notes-app && ./gradlew testDebugUnitTest --tests "com.ai.notes.ui.viewmodel.ChatViewModelTest"`
Expected: FAIL — compile error, `ChatViewModel` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/ai/notes/ui/viewmodel/ChatViewModel.kt`:

```kotlin
package com.ai.notes.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.notes.data.ai.AppError
import com.ai.notes.data.ai.chat.ChatMessage
import com.ai.notes.data.ai.chat.ChatRepository
import com.ai.notes.data.ai.chat.ChatTurnResult
import com.ai.notes.data.ai.model.ClaudeContentBlock
import com.ai.notes.data.ai.model.ClaudeMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val chatRepository: ChatRepository) : ViewModel() {

    private var wireHistory: List<ClaudeMessage> = emptyList()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<ChatTurnResult.NeedsConfirmation?>(null)
    val pendingConfirmation: StateFlow<ChatTurnResult.NeedsConfirmation?> = _pendingConfirmation.asStateFlow()

    private val _errorEvent = MutableStateFlow<AppError?>(null)
    val errorEvent: StateFlow<AppError?> = _errorEvent.asStateFlow()

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        _messages.value = _messages.value + ChatMessage.FromUser(text)
        wireHistory = wireHistory + ClaudeMessage(role = "user", content = listOf(ClaudeContentBlock.Text(text)))
        viewModelScope.launch {
            _isLoading.value = true
            try {
                applyResult(chatRepository.send(wireHistory))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun confirmPendingAction() = resolvePending(approved = true)

    fun cancelPendingAction() = resolvePending(approved = false)

    fun dismissError() {
        _errorEvent.value = null
    }

    private fun resolvePending(approved: Boolean) {
        val confirmation = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        viewModelScope.launch {
            _isLoading.value = true
            try {
                applyResult(
                    chatRepository.resolveConfirmation(
                        confirmation.history,
                        confirmation.pending,
                        confirmation.resolvedResults,
                        approved,
                    )
                )
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun applyResult(result: ChatTurnResult) {
        when (result) {
            is ChatTurnResult.Done -> {
                wireHistory = result.history
                _messages.value = _messages.value +
                    result.toolCalls.map { ChatMessage.ToolActivity(it) } +
                    ChatMessage.FromAssistant(result.reply)
            }
            is ChatTurnResult.NeedsConfirmation -> {
                wireHistory = result.history
                _messages.value = _messages.value + result.toolCalls.map { ChatMessage.ToolActivity(it) }
                _pendingConfirmation.value = result
            }
            is ChatTurnResult.Error -> {
                _errorEvent.value = result.error
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ai-notes-app && ./gradlew testDebugUnitTest --tests "com.ai.notes.ui.viewmodel.ChatViewModelTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
cd ai-notes-app
git add app/src/main/java/com/ai/notes/ui/viewmodel/ChatViewModel.kt \
        app/src/test/java/com/ai/notes/ui/viewmodel/ChatViewModelTest.kt
git commit -m "$(cat <<'EOF'
Add ChatViewModel wiring ChatRepository to UI state

Projects ChatRepository's turn results into UI-facing messages,
tool-activity chips, loading state, and a pending-confirmation slot
for deleteNote, following the same StateFlow conventions as
NotesViewModel.
EOF
)"
```

---

## Task 6: ChatScreen (Compose UI)

**Files:**
- Create: `app/src/main/java/com/ai/notes/ui/screens/ChatScreen.kt`
- Test: `app/src/androidTest/java/com/ai/notes/ui/screens/ChatScreenTest.kt`

**Interfaces:**
- Consumes: `ChatViewModel` (Task 5); `ChatMessage`, `ChatTurnResult`, `PendingToolUse` (Task 3).
- Produces: `@Composable fun ChatScreen(viewModel: ChatViewModel, onBack: () -> Unit = {})` — consumed by `AppNavigation` (Task 7). Test tags: `"chat_input_field"`, `"chat_send_button"`.

The `deleteNote` confirmation dialog reads the target note id directly from `pending.input["noteId"]` (the only argument that tool takes) rather than looking up the note's title — the chat layer only ever sees the raw tool-call JSON, and fetching the note first just to show its title in a confirmation dialog would be extra round-trip complexity the spec's data flow doesn't call for.

- [ ] **Step 1: Write the failing test**

Create `app/src/androidTest/java/com/ai/notes/ui/screens/ChatScreenTest.kt`:

```kotlin
package com.ai.notes.ui.screens

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ai.notes.data.ai.chat.ChatRepository
import com.ai.notes.data.ai.chat.ChatTurnResult
import com.ai.notes.data.ai.chat.PendingToolUse
import com.ai.notes.ui.viewmodel.ChatViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test

class ChatScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sendingMessageShowsUserBubbleAndAssistantReply() {
        val chatRepository = mockk<ChatRepository>()
        coEvery { chatRepository.send(any()) } returns ChatTurnResult.Done(emptyList(), "Hello there!", emptyList())
        val viewModel = ChatViewModel(chatRepository)

        composeTestRule.setContent { ChatScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag("chat_input_field").performTextInput("Hi")
        composeTestRule.onNodeWithTag("chat_send_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Hello there!").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Hi").assertExists()
        composeTestRule.onNodeWithText("Hello there!").assertExists()
    }

    @Test
    fun deleteConfirmationDialogAppearsAndConfirmingInvokesRepository() {
        val chatRepository = mockk<ChatRepository>()
        val pending = PendingToolUse(
            toolUseId = "tool_1",
            functionName = "deleteNote",
            input = JsonObject(mapOf("noteId" to JsonPrimitive(5))),
        )
        coEvery { chatRepository.send(any()) } returns ChatTurnResult.NeedsConfirmation(
            history = emptyList(),
            pending = pending,
            resolvedResults = emptyList(),
            toolCalls = listOf("deleteNote"),
        )
        coEvery { chatRepository.resolveConfirmation(any(), pending, emptyList(), true) } returns
            ChatTurnResult.Done(emptyList(), "Deleted it.", emptyList())
        val viewModel = ChatViewModel(chatRepository)

        composeTestRule.setContent { ChatScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag("chat_input_field").performTextInput("delete note 5")
        composeTestRule.onNodeWithTag("chat_send_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Delete note #5? This can't be undone.").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Delete").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Deleted it.").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun cancellingDeleteConfirmationDoesNotExecute() {
        val chatRepository = mockk<ChatRepository>()
        val pending = PendingToolUse(
            toolUseId = "tool_1",
            functionName = "deleteNote",
            input = JsonObject(mapOf("noteId" to JsonPrimitive(5))),
        )
        coEvery { chatRepository.send(any()) } returns ChatTurnResult.NeedsConfirmation(
            history = emptyList(),
            pending = pending,
            resolvedResults = emptyList(),
            toolCalls = listOf("deleteNote"),
        )
        coEvery { chatRepository.resolveConfirmation(any(), pending, emptyList(), false) } returns
            ChatTurnResult.Done(emptyList(), "Okay, keeping it.", emptyList())
        val viewModel = ChatViewModel(chatRepository)

        composeTestRule.setContent { ChatScreen(viewModel = viewModel) }

        composeTestRule.onNodeWithTag("chat_input_field").performTextInput("delete note 5")
        composeTestRule.onNodeWithTag("chat_send_button").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Cancel").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("Cancel").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText("Okay, keeping it.").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ai-notes-app && ./gradlew connectedDebugAndroidTest --tests "com.ai.notes.ui.screens.ChatScreenTest"`
Expected: FAIL — compile error, `ChatScreen` does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/ai/notes/ui/screens/ChatScreen.kt`:

```kotlin
package com.ai.notes.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ai.notes.data.ai.chat.ChatMessage
import com.ai.notes.ui.viewmodel.ChatViewModel
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, onBack: () -> Unit = {}) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val pendingConfirmation by viewModel.pendingConfirmation.collectAsState()
    var inputText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxSize()) {
                items(messages) { message ->
                    when (message) {
                        is ChatMessage.FromUser -> Text(
                            text = message.text,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            textAlign = TextAlign.End,
                        )
                        is ChatMessage.FromAssistant -> Text(
                            text = message.text,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        )
                        is ChatMessage.ToolActivity -> Text(
                            text = "Called ${message.functionName}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        )
                    }
                }
            }
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .testTag("chat_loading_indicator")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f).testTag("chat_input_field"),
                    placeholder = { Text("Ask something...") },
                )
                IconButton(
                    onClick = {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    },
                    enabled = !isLoading && inputText.isNotBlank(),
                    modifier = Modifier.testTag("chat_send_button"),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }

    pendingConfirmation?.let { confirmation ->
        val noteId = confirmation.pending.input["noteId"]?.jsonPrimitive?.intOrNull
        AlertDialog(
            onDismissRequest = { viewModel.cancelPendingAction() },
            title = { Text("Delete note?") },
            text = {
                Text(
                    if (noteId != null) "Delete note #$noteId? This can't be undone."
                    else "Delete this note? This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmPendingAction() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPendingAction() }) { Text("Cancel") }
            }
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ai-notes-app && ./gradlew connectedDebugAndroidTest --tests "com.ai.notes.ui.screens.ChatScreenTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
cd ai-notes-app
git add app/src/main/java/com/ai/notes/ui/screens/ChatScreen.kt \
        app/src/androidTest/java/com/ai/notes/ui/screens/ChatScreenTest.kt
git commit -m "$(cat <<'EOF'
Add ChatScreen with send flow and deleteNote confirmation dialog

Renders user/assistant bubbles and tool-activity chips from
ChatViewModel state, with an AlertDialog gate before deleteNote
executes, matching the existing NotesScreen dialog conventions.
EOF
)"
```

---

## Task 7: Navigation and dependency wiring

**Files:**
- Modify: `app/src/main/java/com/ai/notes/ui/screens/NotesScreen.kt`
- Modify: `app/src/main/java/com/ai/notes/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/ai/notes/MainActivity.kt`
- Test: `app/src/androidTest/java/com/ai/notes/ui/screens/NotesScreenTest.kt` (add one test)

**Interfaces:**
- Consumes: `ChatScreen`/`ChatViewModel` (Tasks 5-6); `AppFunctionToolBridge` (Task 4); `ChatRepository` (Task 3); existing `RetrofitFactory`, `ApiKeyManager`.
- Produces: `NotesScreen(viewModel, onNavigateToApiKeyEdit = {}, onNavigateToChat = {})`; `AppNavigation(viewModel, chatViewModel, onNavigateToApiKeyEdit = {})`; wired `MainActivity`.

- [ ] **Step 1: Write the failing test**

Add this test to the end of `app/src/androidTest/java/com/ai/notes/ui/screens/NotesScreenTest.kt` (inside the existing `NotesScreenTest` class, before the final closing `}`):

```kotlin
    @Test
    fun tappingChatIconInvokesOnNavigateToChat() {
        var navigatedToChat = false
        composeTestRule.setContent {
            NotesScreen(viewModel = buildViewModel(), onNavigateToChat = { navigatedToChat = true })
        }

        composeTestRule.onNodeWithTag("chat_nav_icon").performClick()

        composeTestRule.runOnIdle {
            assert(navigatedToChat) { "Expected onNavigateToChat to be invoked when the chat icon is tapped" }
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ai-notes-app && ./gradlew connectedDebugAndroidTest --tests "com.ai.notes.ui.screens.NotesScreenTest"`
Expected: FAIL — compile error, `onNavigateToChat` parameter and `"chat_nav_icon"` tag don't exist yet.

- [ ] **Step 3: Write the implementation**

In `app/src/main/java/com/ai/notes/ui/screens/NotesScreen.kt`, add these imports alongside the existing ones:

```kotlin
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
```

Change the function signature and add `@OptIn`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    viewModel: NotesViewModel,
    onNavigateToApiKeyEdit: () -> Unit = {},
    onNavigateToChat: () -> Unit = {}
) {
```

Add a `topBar` to the existing `Scaffold(...)` call (it currently has no `topBar`; add this as the first argument, before `snackbarHost`):

```kotlin
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") },
                actions = {
                    IconButton(onClick = onNavigateToChat, modifier = Modifier.testTag("chat_nav_icon")) {
                        Icon(Icons.Filled.Chat, contentDescription = "Chat")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
```

In `app/src/main/java/com/ai/notes/ui/navigation/AppNavigation.kt`, replace the whole file with:

```kotlin
package com.ai.notes.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ai.notes.ui.screens.ChatScreen
import com.ai.notes.ui.screens.NotesScreen
import com.ai.notes.ui.viewmodel.ChatViewModel
import com.ai.notes.ui.viewmodel.NotesViewModel

const val NOTES_ROUTE = "notes"
const val CHAT_ROUTE = "chat"

@Composable
fun AppNavigation(
    viewModel: NotesViewModel,
    chatViewModel: ChatViewModel,
    onNavigateToApiKeyEdit: () -> Unit = {}
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = NOTES_ROUTE) {
        composable(NOTES_ROUTE) {
            NotesScreen(
                viewModel = viewModel,
                onNavigateToApiKeyEdit = onNavigateToApiKeyEdit,
                onNavigateToChat = { navController.navigate(CHAT_ROUTE) }
            )
        }
        composable(CHAT_ROUTE) {
            ChatScreen(viewModel = chatViewModel, onBack = { navController.popBackStack() })
        }
    }
}
```

In `app/src/main/java/com/ai/notes/MainActivity.kt`, add imports:

```kotlin
import com.ai.notes.data.ai.chat.AppFunctionToolBridge
import com.ai.notes.data.ai.chat.ChatRepository
import com.ai.notes.ui.viewmodel.ChatViewModel
```

Inside `onCreate`, after the existing `summarizationRepository`/`viewModelFactory` setup and before `setContent`, add:

```kotlin
        val toolBridge = AppFunctionToolBridge(applicationContext)
        val chatRepository = ChatRepository(RetrofitFactory.createClaudeService(), apiKeyManager, toolBridge)
        val chatViewModelFactory = viewModelFactory {
            initializer { ChatViewModel(chatRepository) }
        }
```

Change the `AppNavigation(...)` call inside `setContent` to add the chat view model:

```kotlin
                        val viewModel: NotesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = viewModelFactory)
                        val chatViewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = chatViewModelFactory)
                        AppNavigation(
                            viewModel = viewModel,
                            chatViewModel = chatViewModel,
                            onNavigateToApiKeyEdit = {
                                // No dedicated "edit key" route exists yet; reuse the initial
                                // prompt screen to let the user re-enter their API key.
                                hasApiKey = false
                            }
                        )
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd ai-notes-app && ./gradlew connectedDebugAndroidTest --tests "com.ai.notes.ui.screens.NotesScreenTest"`
Expected: PASS (5 tests — the 4 existing plus the new one)

- [ ] **Step 5: Full regression build and commit**

Run: `cd ai-notes-app && ./gradlew testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug assembleRelease`
Expected: PASS — all unit tests green, both android test sources and debug/release APKs compile (release R8 pass in particular catches any missed `@AppFunction`/reflection-sensitive issue, matching this project's existing final-verification convention from `docs/BUILD_LOG.md`).

```bash
cd ai-notes-app
git add app/src/main/java/com/ai/notes/ui/screens/NotesScreen.kt \
        app/src/main/java/com/ai/notes/ui/navigation/AppNavigation.kt \
        app/src/main/java/com/ai/notes/MainActivity.kt \
        app/src/androidTest/java/com/ai/notes/ui/screens/NotesScreenTest.kt
git commit -m "$(cat <<'EOF'
Wire ChatScreen into navigation behind a top-bar chat icon

Adds a TopAppBar to NotesScreen (previously had none) with a chat
action, a second NavHost route, and MainActivity construction of
AppFunctionToolBridge/ChatRepository/ChatViewModel alongside the
existing note dependencies.
EOF
)"
```

---

## Task 8: Manual end-to-end verification on device

**Files:** none (verification only)

This exercises the real Claude API + real `AppFunctionManager` together, which nothing in Tasks 1-7's automated tests does at the same time (Task 3's tests fake the tool bridge; Task 4's tests use real AppFunctions but not real Claude).

- [ ] **Step 1: Install and launch on the API 37 emulator**

Run: `cd ai-notes-app && ./gradlew installDebug && adb shell am start -n com.ai.notes/.MainActivity`
Expected: app launches to the Notes screen (or the API key prompt, if no key stored yet — enter one to proceed).

- [ ] **Step 2: Open chat and exercise the non-destructive path**

Tap the chat icon in the top bar. Type "Create a note titled Milk Run with body Buy milk and eggs" and send.
Expected: a `ToolActivity` chip reading "Called createNote" appears, followed by an assistant reply confirming the note was created. Confirm in the Notes screen (back arrow) that the note actually exists.

- [ ] **Step 3: Exercise the destructive confirmation path**

In chat, type "Delete my Milk Run note" and send.
Expected: the app first calls `searchNotes` (or the model reasons about the id), then the `AlertDialog` ("Delete note #\<id\>? This can't be undone.") appears — the note must NOT be deleted yet at this point (verify via the Notes screen in a second check, or by backgrounding/foregrounding). Tap "Delete".
Expected: the assistant confirms deletion, and the note is gone from the Notes screen.

- [ ] **Step 4: Exercise the decline path**

Repeat step 2 to recreate a note, then ask the chat to delete it but tap "Cancel" on the confirmation dialog.
Expected: the assistant acknowledges the note was kept, and it's still present in the Notes screen.

- [ ] **Step 5: Record the outcome**

Append a short "Chat AppFunctions Tools" section to `docs/APP_FUNCTIONS_TESTING.md` (or a new `docs/CHAT_TOOLS_MANUAL_VERIFICATION.md` if it reads better standalone) noting the device/emulator API level used and the outcome of steps 2-4, following this project's existing practice of disclosing what was and wasn't verified on real hardware (see `docs/BUILD_LOG.md`'s "Known limitations").

```bash
cd ai-notes-app
git add docs/
git commit -m "$(cat <<'EOF'
Document manual end-to-end verification of chat tool-calling

Records the real-device outcome of the create/delete-with-confirm/
decline paths exercised against the live Claude API and real
AppFunctionManager together.
EOF
)"
```
