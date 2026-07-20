# Unify User-Facing Copy on "Notes" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every user-visible "annotation(s)" string with "note(s)" on both platforms, per the approved spec.

**Architecture:** Pure copy change — string literals only. No code identifiers, file names, database tables, or API fields are renamed. The controller pre-ran the authoritative grep sweep; every occurrence is listed below with its exact replacement. No behavior changes, so no new tests; verification is compile + existing suites + a final grep proving no user-facing "annotation" strings remain.

**Tech Stack:** Kotlin/Jetpack Compose, Swift/SwiftUI.

**Spec:** `docs/superpowers/specs/2026-07-20-notes-naming-design.md`

## Global Constraints

- User-facing term is **note/notes** everywhere, including accessibility strings (`contentDescription`, `accessibilityLabel`).
- Do NOT rename: code identifiers, file names (`AddAnnotationSheet.swift`, `AnnotationUi.kt`, `AnnotationsRepository`, …), the `rally_annotations` DB table, the `annotation_count` API field, or the `local_annotations` storage key.
- Already-correct strings stay untouched: "N NOTES" clip row subtitles, "Most notes" sort option, "Note (optional)" placeholder.
- No tests assert on any changed string (verified by controller) — existing suites must simply stay green.
- iOS build environment: prefix `xcodebuild` with `export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, run `xattr -cr iosApp` first, pass `CODE_SIGNING_ALLOWED=NO`. Never commit `DEVELOPER_DIR` into any file.

---

### Task 1: Android copy sweep

**Files:**
- Modify: `androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerScreen.kt:67,202,272,273`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/AnnotationUi.kt:87,120`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt:69,123,141`
- Modify: `androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt:177,200,248`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: nothing — string literals only.

- [ ] **Step 1: Apply the replacements**

Change exactly these string literals (line numbers are from the current tree; match on content if drifted):

`LocalPlayerScreen.kt`
| Line | Old literal | New literal |
|---|---|---|
| 67 | `"Annotations are saved on this phone and are removed if you remove the video from the app."` | `"Notes are saved on this phone and are removed if you remove the video from the app."` |
| 202 | `contentDescription = "Add annotation"` | `contentDescription = "Add note"` |
| 272 | `Text("Delete annotation?")` | `Text("Delete note?")` |
| 273 | `else "This annotation"` | `else "This note"` |

`AnnotationUi.kt`
| Line | Old literal | New literal |
|---|---|---|
| 87 | `contentDescription = "Delete annotation"` | `contentDescription = "Delete note"` |
| 120 | `Text("Add annotation", style = ...)` | `Text("Add note", style = ...)` |

`ClipDetailViewModel.kt`
| Line | Old literal | New literal |
|---|---|---|
| 69 | `"Couldn't load annotations"` | `"Couldn't load notes"` |
| 123 | `"Couldn't add annotation"` | `"Couldn't add note"` |
| 141 | `"Couldn't delete annotation"` | `"Couldn't delete note"` |

`ClipDetailScreen.kt`
| Line | Old literal | New literal |
|---|---|---|
| 177 | `contentDescription = "Add annotation"` | `contentDescription = "Add note"` |
| 200 | `Text("No annotations on this clip.")` | `Text("No notes on this clip.")` |
| 248 | `Text("Delete annotation?")` | `Text("Delete note?")` |

- [ ] **Step 2: Verify no user-facing "annotation" strings remain on Android**

Run:
```bash
grep -rn '"[^"]*[Aa]nnotation[^"]*"' androidApp/src/main --include="*.kt"
```
Expected: no output.

- [ ] **Step 3: Compile and run existing tests**

Run: `./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no test failures

- [ ] **Step 4: Commit**

```bash
git add androidApp/src/main/java/com/badmintontracker/android/localvideo/LocalPlayerScreen.kt \
        androidApp/src/main/java/com/badmintontracker/android/clipdetail/AnnotationUi.kt \
        androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailViewModel.kt \
        androidApp/src/main/java/com/badmintontracker/android/clipdetail/ClipDetailScreen.kt
git commit -m "copy(android): user-facing 'annotation' -> 'note' everywhere"
```

---

### Task 2: iOS copy sweep + changelog

**Files:**
- Modify: `iosApp/Sources/LocalVideo/LocalPlayerView.swift:77,107,119,130,155`
- Modify: `iosApp/Sources/ClipDetail/ClipDetailView.swift:86,121`
- Modify: `iosApp/Sources/ClipDetail/AddAnnotationSheet.swift:14`
- Modify: `iosApp/Sources/ClipDetail/ClipDetailModel.swift:49,140`
- Modify: `CHANGELOG.md` (add one line under `## [Unreleased]`)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: nothing — string literals only.

- [ ] **Step 1: Apply the replacements**

Change exactly these string literals (line numbers are from the current tree; match on content if drifted):

`LocalPlayerView.swift`
| Line | Old literal | New literal |
|---|---|---|
| 77 | `Text("Annotations are saved on this phone and are removed if you remove the video from the app.")` | `Text("Notes are saved on this phone and are removed if you remove the video from the app.")` |
| 107 | `.accessibilityLabel("Add annotation")` | `.accessibilityLabel("Add note")` |
| 119 | `.alert("Delete annotation?", isPresented: ...)` | `.alert("Delete note?", isPresented: ...)` |
| 130 | `Text(target.body.isEmpty ? "This annotation" : ...)` | `Text(target.body.isEmpty ? "This note" : ...)` |
| 155 | `.accessibilityLabel("Delete annotation")` | `.accessibilityLabel("Delete note")` |

`ClipDetailView.swift`
| Line | Old literal | New literal |
|---|---|---|
| 86 | `.alert("Delete annotation?", isPresented: ...)` | `.alert("Delete note?", isPresented: ...)` |
| 121 | `.accessibilityLabel("Delete annotation")` | `.accessibilityLabel("Delete note")` |

`AddAnnotationSheet.swift`
| Line | Old literal | New literal |
|---|---|---|
| 14 | `Text("Add annotation")` | `Text("Add note")` |

`ClipDetailModel.swift`
| Line | Old literal | New literal |
|---|---|---|
| 49 | `actionError = "Couldn't load annotations"` | `actionError = "Couldn't load notes"` |
| 140 | `actionError = "Couldn't add annotation"` | `actionError = "Couldn't add note"` |

- [ ] **Step 2: Verify no user-facing "annotation" strings remain on iOS**

Run:
```bash
grep -rn '"[^"]*[Aa]nnotation[^"]*"' iosApp/Sources --include="*.swift"
```
Expected: no output.

- [ ] **Step 3: Add the changelog entry**

In `CHANGELOG.md`, under `## [Unreleased]`, add a `### Changed` section immediately after the `### Added` section's last bullet (before the next `###` heading), or append to the existing `### Changed` section if one exists:

```markdown
### Changed
- All user-facing copy now says "note(s)" instead of "annotation(s)" —
  one consistent term for the text coaches attach to rally moments.
```

- [ ] **Step 4: Build the iOS app**

```bash
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
xattr -cr iosApp
xcodebuild build -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -destination "platform=iOS Simulator,name=iPhone 17 Pro" \
  -derivedDataPath iosApp/build/DerivedData \
  CODE_SIGNING_ALLOWED=NO
```
Expected: `** BUILD SUCCEEDED **`

- [ ] **Step 5: Commit**

```bash
git add iosApp/Sources/LocalVideo/LocalPlayerView.swift \
        iosApp/Sources/ClipDetail/ClipDetailView.swift \
        iosApp/Sources/ClipDetail/AddAnnotationSheet.swift \
        iosApp/Sources/ClipDetail/ClipDetailModel.swift \
        CHANGELOG.md
git commit -m "copy(ios): user-facing 'annotation' -> 'note' everywhere; changelog"
```

---

### Final verification (manual, per spec)

- Reinstall both apps (emulator + simulator); open a clip's detail: add-button label, sheet title, delete dialog, and empty state all say note/notes; local player banner says "Notes are saved on this phone…".
