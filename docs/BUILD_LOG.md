# Build Log — AI-Assisted Notes App

Record of how this app was built: 2026-07-04–2026-07-05, via subagent-driven
development (fresh implementer subagent per task, independent build
verification, fresh reviewer subagent per task, fix loops, final whole-branch
review). 28 planned tasks + one final cross-cutting fix pass, 41 commits.

The original design spec is at
`docs/superpowers/specs/2026-07-04-ai-notes-app-design.md` (outside this
repo, in the parent workspace) and the full task-by-task plan — including
inline corrections made mid-build — is at
`docs/superpowers/plans/2026-07-04-ai-notes-app.md` (same location).

## What was built

- Room-backed local note storage (title, body, tags, category, timestamps)
- Direct Anthropic Claude API integration for batch note summarization
  (endpoint `https://api.anthropic.com/v1/messages`, model
  `claude-sonnet-4-20250514`, min 2 / max 10 notes per batch)
- System-level Android AppFunctions integration
  (`androidx.appfunctions:1.0.0-alpha09`) exposing `createNote`,
  `searchNotes`, `getNote`, `deleteNote` to system AI assistants
- Full Jetpack Compose UI: note list with swipe-to-delete and long-press
  multi-select, create/edit dialog with chip-based tag input and category
  autocomplete, live search, batch-summarization flow with a dedicated
  summary dialog, first-launch API key setup (EncryptedSharedPreferences),
  and end-to-end error handling (Snackbar for recoverable errors, AlertDialog
  for critical ones — invalid API key, DB failure)

## Toolchain (corrected mid-build — see "Major deviations" below)

- AGP `9.2.1`, Kotlin `2.2.10`, KSP `2.2.10-2.0.2`, Gradle wrapper `9.4.1`
- `compileSdk`/`targetSdk` `37`, `minSdk` `33`
- No classic `org.jetbrains.kotlin.android` plugin — AGP 9's built-in Kotlin
  support replaces it
- `gradle.properties` requires `android.disallowKotlinSourceSets=false`
- `app/build.gradle.kts` requires a KSP-generated-assets wiring block
  (`androidComponents { onVariants { ... } }` + a `merge*Assets` → `ksp*Kotlin`
  task dependency) so `app_functions_v2.xml` reaches the APK
- Build with `JAVA_HOME` pointed at a JDK with the full jlink/jmod toolchain
  (this project was built using Android Studio's bundled JBR)

## Major deviations from the original plan, discovered during implementation

**1. AppFunctions toolchain conflict (Task 1, 3 fix rounds).** The original
plan specified `compileSdk 35` / AGP `8.6.1`, but
`androidx.appfunctions:1.0.0-alpha09`'s transitive `androidx.appsearch`
dependency requires `compileSdk 37` and AGP `9.1.0`+. Bumping AGP to 9.x
then required removing the classic Kotlin Android Gradle plugin (AGP 9 has
built-in Kotlin support and the two conflict), which in turn hit an
open, unresolved KSP/AGP-9 incompatibility. The final working combination
(AGP 9.2.1 / Kotlin 2.2.10 / KSP 2.2.10-2.0.2 / Gradle 9.4.1, plus the
KSP-generated-assets wiring block) was confirmed against a real working
reference project: <https://github.com/philipplackner/AppFunctionsDemo>.

**2. Real AppFunctions API differs from the plan's assumptions (Tasks
13–14).** The plan assumed `@AppFunction` came from
`androidx.appfunctions.AppFunction` with no context parameter, and
registration via a nonexistent `AppFunctionConfiguration.getInstance()`.
The real alpha09 API (confirmed against the same reference project and
Google's own docs at <https://developer.android.com/ai/appfunctions>)
requires: `@AppFunction` from `androidx.appfunctions.service.AppFunction`;
every annotated method as `suspend fun` with `AppFunctionContext`
(`androidx.appfunctions.AppFunctionContext`) as the first parameter; any
custom data class used as a parameter/return type annotated with
`@AppFunctionSerializable` (applied directly to the shared `Note` domain
model — an accepted coupling, not a bug); and registration via
`App : Application(), AppFunctionConfiguration.Provider` with
`AppFunctionConfiguration.Builder().addEnclosingClassFactory(...).build()`.
Verified end-to-end: `app_functions_v2.xml` is genuinely generated with all
four function names in both debug and release builds.

**3. Non-toolchain dependency versions bumped to latest-stable** (Task 1),
since the spec only said "latest" — checked against real Maven/Google
maven-metadata.xml at build time, not guessed.

## Real bugs caught and fixed during task review (not exhaustive — see
`git log` for the full commit history)

- **Task 3 → 4**: Timber was widened from `debugImplementation` to
  `implementation` to unblock a release build, silently reversing the
  spec's debug-only logging intent. Fixed with a proper debug/release
  logger facade (`AppLogger` interface + variant-specific implementations)
  instead of widening the dependency scope.
- **Task 16**: `NotesViewModel.summarizeSelected()` read notes from a
  lazily-shared `StateFlow` that only started collecting once the UI
  subscribed to it — an implicit collection-order dependency that a test
  with a loose mock argument matcher failed to catch (it silently
  summarized an empty list in the test). Fixed by having it fetch notes
  directly from the repository.
- **Task 17**: `NoteCard`'s swipe-to-delete could fire its destructive
  callback more than once per continuous gesture (no "already triggered"
  guard). Fixed with an arm/latch/disarm state machine tied to the drag
  gesture's start/stop callbacks.
- **Task 18**: The design spec's loading-indicator requirement during API
  calls was silently missing from `NotesScreen`, despite the ViewModel
  state already existing. Added and covered by a real async test.
- **Task 20**: `NoteEditDialog` was missing Enter-key tag entry (only the
  "+" button worked), and duplicate/blank tags from AI-created notes
  weren't filtered on prefill, risking test-tag collisions.
- **Task 23**: `ApiKeyPromptScreen`'s text field used
  `fillMaxSize(fraction = 1f)` inside a `Column` that already filled the
  screen — a genuine layout bug that would have pushed the Save button
  below the visible viewport on a real device, not just "look tall."
- **Task 25**: The `InvalidApiKey` AlertDialog was missing the spec-required
  "navigate to key edit" button (rendered identically to the generic
  `DatabaseError` dialog), and its test never actually triggered the
  `InvalidApiKey` state, making it pass regardless of correctness.
- **Task 27**: Discovered and fixed a real Compose testing pitfall —
  `performClick()` does **not** respect `enabled = false` on a composable
  (it invokes the semantics `OnClick` action directly, bypassing the
  disabled check). A test asserting a disabled Summarize button's click
  was a no-op would have failed the first time it ran on a real device.
  Fixed by asserting `assertIsNotEnabled()` directly instead.
- **Final whole-branch review** (after all 28 tasks passed individually)
  caught four more cross-cutting issues no single task's narrower review
  could see:
  - The ProGuard keep rule for `@AppFunction` referenced the wrong package
    (`androidx.appfunctions.annotation.AppFunction` instead of the real
    `androidx.appfunctions.service.AppFunction`) — it matched zero members,
    so R8 could have renamed the AppFunctions methods in a release build,
    silently breaking system-AI invocation.
  - `AppError.DatabaseError` was fully wired into the UI (a dedicated
    AlertDialog) but never actually produced anywhere — Room failures
    propagated uncaught and would have crashed the app instead of showing
    the promised dialog.
  - Editing an existing note was unreachable: `NoteEditDialog` fully
    supported edit mode, but `NotesScreen` only ever invoked it in create
    mode, and tapping a note outside multi-select did nothing.
  - The same `performClick()`/`enabled=false` test pitfall from Task 27
    recurred in a different test file (`MultiSelectHeaderTest`) that
    wasn't touched during that task's fix.

All four were fixed and independently re-verified in commit `ea63a51`.

## Known limitations

- **No Android emulator/device was available in the build environment at
  any point.** Every instrumented test (the entire `androidTest` source
  set — Compose UI tests, Room DAO tests, AppFunctions execution tests) has
  only ever been compile-verified (`compileDebugAndroidTestKotlin`), never
  executed. This was disclosed and tracked throughout rather than assumed
  passing. **Run the full instrumented suite on a real device or emulator
  before shipping.**
- No Room migration test exists (schema is still v1, so likely not yet
  applicable).
- No code-coverage tooling (Jacoco/Kover) was run, so the design spec's
  numeric coverage targets (90%/80%/70%/100% by layer) are unverified.

## Final verification (independently reproduced, not just trusted from
subagent reports)

```
JAVA_HOME=<jdk-with-full-jlink/jmod> ./gradlew clean testDebugUnitTest \
  compileDebugAndroidTestKotlin assembleDebug assembleRelease
```

- 54/54 unit tests pass, 0 failures
- Debug and release builds succeed (release with R8 minification)
- `app_functions_v2.xml` generated in both debug and release merged assets,
  containing all four function names
- Zero `Log.`/`Timber.` calls found outside the intended debug-only logger
  facade; no API key or note content ever logged
