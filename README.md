# AI Notes App

An Android notes app with built-in AI features: note summarization and an
in-app chatbot that can create, search, retrieve, and delete notes on your
behalf via Claude tool use.

## Features

- **Notes CRUD** — create, view, and delete notes, backed by a local Room
  database.
- **AI summarization** — batch-summarize notes via the Claude API.
- **In-app chat assistant** — a chat screen where Claude can call the app's
  own registered [App Functions](https://developer.android.com/reference/kotlin/androidx/appfunctions/package-summary)
  (`createNote`, `searchNotes`, `getNote`, `deleteNote`) directly against the
  real `AppFunctionManager`, so the assistant and any OS-level integration
  (e.g. system Gemini) share one source of truth for tool behavior.
  Destructive actions (`deleteNote`) require explicit user confirmation
  before executing.

## Tech stack

- Kotlin, Jetpack Compose, Material 3
- Room (local persistence)
- Retrofit + kotlinx.serialization (Claude API client)
- AndroidX AppFunctions (tool discovery/execution)
- AndroidX Security Crypto (API key storage)
- JUnit, MockK, Espresso (unit + instrumented tests)

## Requirements

- Android Studio with AGP 9 / built-in Kotlin support
- JDK 17
- minSdk 33, compileSdk/targetSdk 37
- A Claude API key (prompted for on first launch and stored securely on
  device)

## Building

```bash
./gradlew assembleDebug
```

## Testing

```bash
./gradlew test              # unit tests
./gradlew connectedAndroidTest  # instrumented tests (requires a device/emulator)
```

Instrumented tests that exercise App Functions require an emulator/device on
API 36.1+ (the `app_function` shell dispatcher isn't available on older
system images). See `docs/APP_FUNCTIONS_TESTING.md` for details.

## Project structure

```
app/src/main/java/com/ai/notes/
├── AppFunctions/     # Registered App Functions (createNote, searchNotes, getNote, deleteNote)
├── data/
│   ├── ai/           # Claude API client, chat repository/tool bridge, summarization
│   ├── database/     # Room database, DAOs
│   ├── model/        # Note model
│   └── preferences/  # Encrypted API key storage
└── ui/
    ├── components/
    ├── navigation/
    ├── screens/       # NotesScreen, ChatScreen, ApiKeyPromptScreen
    ├── theme/
    └── viewmodel/
```

## Documentation

- `docs/APP_FUNCTIONS_TESTING.md` — App Functions manual testing notes and
  emulator gotchas
