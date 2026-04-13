# Repository Guidelines

## Project Structure & Module Organization
This repository is a single-module Android app named `TelegramCachePlayer`. Root build configuration lives in `build.gradle.kts`, `settings.gradle.kts`, and `gradle/libs.versions.toml`. App code is under `app/`.

Production Kotlin sources live in `app/src/main/java/com/example/telegramcacheplayer`. Compose UI, activities, view models, and storage/scanning helpers currently share that package. Android resources are in `app/src/main/res`, and the manifest is `app/src/main/AndroidManifest.xml`. Treat `app/build/`, `.gradle/`, and IDE files as generated output and do not edit or commit them.

## Build, Test, and Development Commands
This checkout includes Gradle configuration but not wrapper scripts, so run tasks from Android Studio or a local Gradle install.

- `gradle assembleDebug`: build the debug APK.
- `gradle installDebug`: install the debug build on a connected device or emulator.
- `gradle lint`: run Android lint checks.
- `gradle test`: run JVM unit tests in `app/src/test` when present.
- `gradle connectedAndroidTest`: run device or emulator tests from `app/src/androidTest`.

## Coding Style & Naming Conventions
Use Kotlin with 4-space indentation and keep formatting consistent with existing `.kts` and `.kt` files. Prefer small composables and keep stateful or I/O-heavy logic in `*ViewModel`, scanner, cache, or store classes.

Use `PascalCase` for activities, view models, enums, and data classes (`PlayerActivity`, `VideoListViewModel`). Use `camelCase` for functions and properties. Keep Android resource names in lowercase `snake_case`. When adding files, match the existing feature-oriented naming pattern such as `TelegramFileScanner.kt` or `PlaybackProgressStore.kt`.

## Testing Guidelines
There is no committed test suite yet. Add JVM tests under `app/src/test/kotlin` and instrumentation or Compose UI tests under `app/src/androidTest/kotlin`. Name test files `ThingTest.kt` and prefer descriptive test names such as `scan_returnsVideosSortedByModifiedTime`.

Prioritize coverage for storage scanning, filtering and sorting, permission-gated flows, and playback progress persistence.

## Commit & Pull Request Guidelines
Git history is not available in this workspace, so use short, imperative commit subjects such as `Add sort menu refresh state`. Keep commits focused and explain non-obvious storage or permission changes in the body.

Pull requests should summarize behavior changes, list manual test coverage, link related issues, and include screenshots or screen recordings for UI updates. Call out any change touching `MANAGE_EXTERNAL_STORAGE`, file deletion, or cache path handling.

## Security & Configuration Tips
Do not commit `local.properties` or machine-specific SDK paths. Avoid logging full user file paths unless required for debugging, and remove temporary debug harnesses from `src/main` before merging.
