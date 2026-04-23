# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

- Debug build (no signing required):
  ```bash
  ./gradlew assembleDebug
  ```
- Release build requires signing. Provide via `keystore.properties` (copy from `keystore.properties.example`) or environment variables:
  - `AUTOSKIP_KEYSTORE_PATH`
  - `AUTOSKIP_KEYSTORE_PASSWORD`
  - `AUTOSKIP_KEY_ALIAS`
  - `AUTOSKIP_KEY_PASSWORD`

There are no meaningful unit or instrumentation tests in this project; the existing test files are only Android Studio templates.

## Architecture

This is a single-module Android app with three core components:

1. **`SkipAdService`** (`app/src/main/java/com/xiaojiwei/autoskip/SkipAdService.kt`) — An `AccessibilityService` that does all the ad-skipping work.
2. **`WhitelistManager`** (`app/src/main/java/com/xiaojiwei/autoskip/WhitelistManager.kt`) — Wraps `SharedPreferences` for the whitelist package set, the master auto-skip toggle, and the toast toggle.
3. **`MainActivity`** (`app/src/main/java/com/xiaojiwei/autoskip/MainActivity.kt`) — A single Compose screen with no ViewModel; all UI state lives in `remember` / `mutableStateOf`. It shows the service status, toggles, and a whitelist app picker.

The UI and service do not communicate via bound service or BroadcastReceiver. `MainActivity` checks `SkipAdService.isRunning` (a static field) to show service status, and both sides read/write the same `SharedPreferences` through `WhitelistManager`.

## Skip Detection Logic

This is the most complex part of the codebase and spans keyword parsing, candidate scoring, and click execution inside `SkipAdService`.

### Event Windowing
The service only acts within an 8-second detection window after `TYPE_WINDOW_STATE_CHANGED` for a given package. `TYPE_WINDOW_CONTENT_CHANGED` events are ignored outside this window.

### Keywords
`WhitelistManager.DEFAULT_KEYWORDS` defines the built-in patterns. Keywords support two placeholders:
- `%d` — matches 1-2 digits
- `%ds` — matches 1-2 digits followed by an optional `s`, `S`, or `秒`

Global/custom keywords were removed in a recent refactor; the app only uses the hardcoded default list.

### Candidate Collection & Scoring
When an event fires inside the window:
1. `collectCandidates` searches the accessibility tree for nodes matching any keyword (by `findAccessibilityNodeInfosByText` and direct traversal).
2. For each match, `findClickTarget` walks up the parent chain (max depth 5) looking for a clickable ancestor.
3. `scoreCandidate` assigns an integer score based on:
   - Text similarity to the keyword
   - Parent traversal depth (self-clickable is best)
   - Position (upper-right quadrant is strongly favored)
   - Size relative to the window (very small targets are preferred; large ones are penalized)
   - View class name (`Button` > `ImageView` > `TextView`)
   - Resource name hints containing `skip`, `close`, `dismiss`, `countdown`, `timer`
4. The highest-scoring candidate is clicked. A per-package `clickedElementSignatures` set prevents duplicate clicks on the same element within the same window.

### `isReasonableTarget` Guard
Before a node is considered a valid click target, it must pass size heuristics: it cannot cover more than 45% of the screen area, cannot be a near-full-width tall bar, and its height cannot exceed 45% of the screen.

## Release Process

The project uses GitHub Actions (`.github/workflows/release.yml`) to build and publish signed release APKs:
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit and push a tag starting with `v` (e.g., `git tag v1.2.0 && git push origin --tags`).
3. The workflow validates signing secrets, builds `assembleRelease`, and creates a GitHub Release with the APK attached.
4. Manual runs via `workflow_dispatch` build and upload the APK as an artifact but do **not** create a Release.
